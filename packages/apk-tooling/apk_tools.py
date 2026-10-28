"""Shared APK build utilities; no device installation side effects."""
import copy
import hashlib
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SDK = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')))
BUILD_TOOLS = SDK / 'build-tools' / os.environ.get('ANDROID_BUILD_TOOLS', '36.0.0')
PLATFORM = SDK / 'platforms' / 'android-36/android.jar'
NDK = SDK / 'ndk' / os.environ.get('ANDROID_NDK_VERSION', '29.0.14206865')
ORIGINAL = ROOT / 'vendor/tiktok-47.5.3/apk'
KEY = Path(os.environ.get('OTGLIVE_SIGNING_KEY', str(ROOT / 'secrets/test-signing.p12')))

def run(*args):
    result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed\n{result.stdout}{result.stderr}')
    return result.stdout.strip()

def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def repack(source, destination, replacements):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, 'w') as target:
        for info in original.infolist():
            if info.filename.startswith('META-INF/') and re.search(r'\.(SF|RSA|DSA|EC)$|MANIFEST\.MF$', info.filename, re.I):
                continue
            info = copy.copy(info)
            if info.filename in replacements:
                info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, replacements.get(info.filename, original.read(info.filename)))
        for name, data in replacements.items():
            if name not in original.namelist():
                target.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)

def sign(source, destination, scratch):
    aligned = scratch / ('aligned-' + destination.name)
    run(BUILD_TOOLS / 'zipalign.exe', '-P', '16', '-f', '4', source, aligned)
    # Keep compatibility with the existing project's local signing identity.
    env = os.environ.copy()
    env.setdefault('OTGLIVE_KEY_PASSWORD', 'otglive-test')
    args = ['java', '-jar', str(BUILD_TOOLS / 'lib/apksigner.jar'), 'sign',
            '--ks', str(KEY), '--ks-pass', 'env:OTGLIVE_KEY_PASSWORD', '--out', str(destination), str(aligned)]
    result = subprocess.run(args, env=env, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('APK signing failed\n' + result.stdout + result.stderr)
    run('java', '-jar', BUILD_TOOLS / 'lib/apksigner.jar', 'verify', destination)
    aligned.unlink()

def changed_members(original, result):
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(result) as after:
        left, right = set(before.namelist()), set(after.namelist())
        return {name for name in left | right
                if not name.startswith('META-INF/') and name != 'stamp-cert-sha256'
                and (name not in left or name not in right or before.read(name) != after.read(name))}
