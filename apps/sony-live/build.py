"""Build Sony LIVE directly from official inputs, with no historical APK dependency."""
import json
from pathlib import Path
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
APP = Path(__file__).resolve().parent
ROOT = APP.parents[1]
sys.path.insert(0, str(ROOT / 'packages/apk-tooling'))
import apk_tools as apk

def build():
    output = ROOT / 'artifacts/build/sony-live'
    output.mkdir(parents=True, exist_ok=True)
    for required in (apk.PLATFORM, apk.KEY, apk.ORIGINAL / 'base.apk', apk.ORIGINAL / 'split_df_a_dex.apk'):
        if not required.is_file():
            raise FileNotFoundError(required)
    with tempfile.TemporaryDirectory(prefix='compile-', dir=output) as directory:
        task = Path(directory)
        for name in ('classes', 'dex', 'patcher'):
            (task / name).mkdir()
        apk.run('javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', apk.PLATFORM,
                '-d', task / 'classes', *sorted((APP / 'src').rglob('*.java')))
        apk.run('jar', 'cf', task / 'bridge.jar', '-C', task / 'classes', '.')
        apk.run('java', '-cp', apk.BUILD_TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', apk.PLATFORM,
                '--min-api', '26', '--output', task / 'dex', task / 'bridge.jar')
        bridge = (task / 'dex/classes.dex').read_bytes()
        for marker in (b'AudioDiagnosticsBridge', b'AudioMeterSink', b'CameraPipelineTrace', b'ProbeActivity', b'forceCameraType'):
            if marker in bridge:
                raise RuntimeError('Inactive diagnostic or camera-forcing code included')
        cp = str(ROOT / 'tools/dexlib2.jar') + ';' + str(ROOT / 'tools/guava.jar')
        apk.run('javac', '-cp', cp, '-d', task / 'patcher', *sorted((APP / 'patcher').glob('*.java')))
        patch_cp = str(task / 'patcher') + ';' + cp
        replacements = {}
        with zipfile.ZipFile(apk.ORIGINAL / 'split_df_a_dex.apk') as official:
            for name, mode in (('classes4.dex', 'preview'), ('classes25.dex', 'provider'), ('classes5.dex', 'live')):
                source, result = task / ('original-' + name), task / ('patched-' + name)
                source.write_bytes(official.read(name))
                args = ['PatchLiveDex', source, result] if mode == 'live' else ['PatchProductionDex', source, result, mode]
                print(apk.run('java', '-Xmx4g', '-cp', patch_cp, *args), flush=True)
                replacements[name] = result.read_bytes()
        native = task / 'libotglive_image.so'
        toolchain = apk.NDK / 'toolchains/llvm/prebuilt/windows-x86_64'
        apk.run(toolchain / 'bin/clang++.exe', '--target=aarch64-linux-android26',
                '--sysroot=' + str(toolchain / 'sysroot'), '-std=c++17', '-O3', '-DNDEBUG', '-fPIC',
                '-fvisibility=hidden', '-shared', '-Wl,--no-undefined', '-Wl,-z,relro', '-Wl,-z,now',
                APP / 'native/otglive_image.cpp', '-ljnigraphics', '-o', native)
        payloads = {
            'base.apk': {'classes16.dex': bridge},
            'split_df_a_dex.apk': replacements,
            'split_config.arm64_v8a.apk': {'lib/arm64-v8a/libotglive_image.so': native.read_bytes()},
        }
        for name, payload in payloads.items():
            unsigned = task / name
            apk.repack(apk.ORIGINAL / name, unsigned, payload)
            apk.sign(unsigned, output / name, task)
            changed = apk.changed_members(apk.ORIGINAL / name, output / name)
            if changed != set(payload):
                raise RuntimeError(f'Unexpected payload changes in {name}: {changed}')
        # Run the CPU-only concurrency regression against the compiled production class.
        apk.run('javac', '-cp', task / 'classes', '-d', task / 'classes', APP / 'tests/LatestFrameMailboxTest.java')
        print(apk.run('java', '-cp', task / 'classes', 'dev.otglive.bridge.LatestFrameMailboxTest'))
        report = {'profile': 'production-clean', 'derived_from': 'r17', 'installed': False,
                  'files': [{'name': name, 'sha256': apk.sha(output / name)} for name in payloads]}
        (output / 'revision.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('Build verified: ' + str(output))

if __name__ == '__main__':
    build()
