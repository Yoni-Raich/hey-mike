"""Check that the combined candidate keeps the exact 1179 source and assets."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

BASE = Path(r'C:\Users\SHIRA\.codex\worktrees\personal-assistant-flow-20261028')
COMBINED = Path(r'C:\Users\SHIRA\.codex\worktrees\assistant-flow-limits-20261028')
OUT = Path(r'C:\Users\SHIRA\AppData\Local\Temp\hey-mike\assistant-flow-limits-20261028')
BASE_APK = OUT.parent / 'personal-assistant-flow-20261028/hey-mike-assistant-flow1179-dev-debug.apk'


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args]).decode('utf-8')


def sha(data):
    return hashlib.sha256(data).hexdigest()


base_manifest = json.loads((OUT / 'base-1179.json').read_text(encoding='utf-8-sig'))
for name, digest in base_manifest['changedFiles'].items():
    assert sha((BASE / name).read_bytes()) == digest, f'1179 source changed: {name}'
assert sha(BASE_APK.read_bytes()) == base_manifest['apkSha256'], '1179 APK changed'
base_files = git(BASE, 'ls-files', '-z').rstrip('\0').split('\0')
feature_files = set(git(COMBINED, 'diff-tree', '--no-commit-id', '--name-only', '-r', '8c899bcc').splitlines())
allowed = feature_files | {'version.properties', 'docs/testing/assistant-flow-limits-20261028.md'}
different = []
for name in base_files:
    original = (BASE / name).read_bytes().replace(b'\r\n', b'\n')
    target = (COMBINED / name).read_bytes().replace(b'\r\n', b'\n')
    if target != original:
        different.append(name)
        assert name in allowed, f'Unexpected change to 1179: {name}'
assert not git(COMBINED, 'diff', '--name-only', '--diff-filter=U').strip(), 'Unresolved merge'
result = {
    'base': base_manifest['baseline'],
    'baseApkSha256': base_manifest['apkSha256'],
    'limitFeature': git(COMBINED, 'rev-parse', '8c899bcc').strip(),
    'baseSourceFilesChecked': len(base_files),
    'filesChangedByIntegration': different,
    'changedFiles': {
        name: sha((COMBINED / name).read_bytes())
        for name in git(COMBINED, 'diff', 'HEAD', '--name-only').splitlines()
    },
}
candidate = OUT / 'hey-mike-assistant-flow-limits1180.apk'
if candidate.is_file():
    with zipfile.ZipFile(BASE_APK) as old, zipfile.ZipFile(candidate) as new:
        selected = {n for n in old.namelist() if n.startswith(('lib/', 'assets/')) and not n.endswith('/')}
        new_selected = {n for n in new.namelist() if n.startswith(('lib/', 'assets/')) and not n.endswith('/')}
        assert selected == new_selected, 'Bundled native or asset entries changed'
        for name in sorted(selected):
            assert sha(old.read(name)) == sha(new.read(name)), f'1179 asset changed: {name}'
        dex = b''.join(new.read(n) for n in new.namelist() if n.endswith('.dex'))
        for cls in (b'Ldev/androidagent/core/UsageLimitRecovery;', b'Ldev/androidagent/core/TaskFollowThrough;', b'Ldev/androidagent/app/TaskBriefingNotifier;'):
            assert cls in dex, f'Missing packaged class: {cls!r}'
    result['bundledNativeAndAssetFilesMatched'] = len(selected)
    result['apkSha256'] = sha(candidate.read_bytes())
    result['apkBytes'] = candidate.stat().st_size
    result['versionCode'] = 1180
    result['versionName'] = '0.14.0-dev.assistant-flow-limits1180'
    result['packagedFeatures'] = ['UsageLimitRecovery', 'TaskFollowThrough', 'TaskBriefingNotifier']
(OUT / 'source-1180.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: v for k, v in result.items() if k != 'changedFiles'}, indent=2))
