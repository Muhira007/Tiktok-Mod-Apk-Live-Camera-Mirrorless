"""Install changed splits into our signed mod. Does not uninstall or clear data."""
import argparse
import re
import subprocess
import time
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--directory',type=Path,default=Path(__file__).resolve().parents[2]/'artifacts/releases/r17/update')
parser.add_argument('--files',nargs='+',default=['base.apk','split_df_a_dex.apk'])
args=parser.parse_args()
adb=r'C:\Users\WIN\AppData\Local\Android\Sdk\platform-tools\adb.exe'
# shell-v2 on this device can keep its host client alive after commit completed.
# exec-out returns the same package-manager output without that interactive stdin path.
prefix=[adb,'-s',args.serial,'exec-out','cmd','package']

def command(*parts,stdin=None):
    # exec-in keeps binary APK bytes intact on Windows. shell stdin can treat
    # byte 0x1a as end-of-file when stdin is redirected from a disk file.
    transport=[adb,'-s',args.serial,'exec-in','cmd','package'] if stdin is not None else prefix
    p=subprocess.run(transport+list(parts),stdin=stdin,capture_output=True)
    output=p.stdout.decode('utf-8','replace')+p.stderr.decode('utf-8','replace')
    if p.returncode or 'Failure' in output or 'Error:' in output: raise RuntimeError(output)
    return output

output=command('install-create','-r','-p','com.ss.android.ugc.trill','--user','0')
print(output.strip(),flush=True)
session=re.search(r'\[(\d+)\]',output)
if not session: raise RuntimeError('Installer session ID not returned')
session=session.group(1)
try:
    for name in args.files:
        apk=args.directory/name
        print(f'Writing {name}: {apk.stat().st_size:,} bytes',flush=True)
        with apk.open('rb') as f:
            print(command('install-write','-S',str(apk.stat().st_size),session,name,'-',stdin=f).strip(),flush=True)
    time.sleep(2)
    for attempt in range(10):
        try:
            print(command('install-commit',session).strip(),flush=True)
            break
        except RuntimeError as e:
            if 'Files still open' in str(e) and attempt < 9:
                time.sleep(2)
                continue
            raise
except BaseException:
    print(command('install-abandon',session).strip(),flush=True)
    raise
