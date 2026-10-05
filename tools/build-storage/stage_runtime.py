"""Keep each worktree's pinned runtime logic, with outputs in its build bucket."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess


def plain_path(path):
    path = Path(os.path.abspath(path))
    for part in (path, *path.parents):
        if os.path.lexists(part) and getattr(part.lstat(), 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
            raise ValueError(f'Refusing a link or junction: {part}')
    return path


def configure(module, bucket):
    """Redirect staging and extraction, without changing URLs, hashes or patches."""
    runtime = bucket / 'app' / 'generated' / 'runtime'
    cache = bucket / '_runtime'
    required = ('DEFAULT_JNILIBS', 'DEFAULT_X86_JNILIBS', 'DEFAULT_ASSETS',
                'DEFAULT_PACKAGE_DIR', 'DEFAULT_X86_PACKAGE_DIR', 'reset_output')
    if not all(hasattr(module, name) for name in required):
        raise ValueError('Unsupported runtime staging script')
    # Downloads and extracted packages are build inputs too, so keep them in
    # the same bounded storage rather than adding a cache to every worktree.
    for name in ('DEFAULT_PACKAGE', 'DEFAULT_X86_PACKAGE', 'DEFAULT_CA_BUNDLE',
                 'DEFAULT_MUSL_APK', 'DEFAULT_MUSL_LICENSE'):
        if hasattr(module, name):
            setattr(module, name, str(cache / Path(getattr(module, name)).name))
    module.DEFAULT_PACKAGE_DIR = str(cache / 'package')
    module.DEFAULT_X86_PACKAGE_DIR = str(cache / 'package-x86_64')
    module.DEFAULT_JNILIBS = str(runtime / 'jniLibs' / 'arm64-v8a')
    module.DEFAULT_X86_JNILIBS = str(runtime / 'jniLibs' / 'x86_64')
    module.DEFAULT_ASSETS = str(runtime / 'assets' / 'runtime')

    allowed = {cache / 'package', cache / 'package-x86_64',
               runtime / 'jniLibs' / 'arm64-v8a', runtime / 'jniLibs' / 'x86_64',
               runtime / 'assets' / 'runtime'}

    def reset(path):
        target = plain_path(path)
        if target not in allowed:
            raise ValueError(f'Runtime output escapes its dedicated directories: {target}')
        if target.exists():
            # Use one shell end to end, literal arguments and a checked target.
            # Refuse nested junctions before resetting any generated output.
            command = (
                '$ErrorActionPreference="Stop"; $p=$env:HEY_MIKE_RESET_OUTPUT; '
                'function Check($d) { foreach($i in Get-ChildItem -LiteralPath $d -Force) { '
                'if($i.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Runtime link refused" }; '
                'if($i.PSIsContainer) { Check $i.FullName } } }; '
                'Check $p; Remove-Item -LiteralPath $p -Recurse -Force'
            )
            env = dict(os.environ, HEY_MIKE_RESET_OUTPUT=str(target))
            subprocess.run(['powershell.exe', '-NoProfile', '-Command', command], env=env, check=True)
        target.mkdir(parents=True, exist_ok=True)

    module.reset_output = reset
    return runtime


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--worktree', required=True)
    parser.add_argument('--bucket', required=True)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding='utf-8-sig'))
    worktree = plain_path(args.worktree)
    root = plain_path(config['buildRoot'])
    if root != plain_path(Path(config['repository']) / 'build'):
        raise ValueError('Invalid build root')
    expected = hashlib.sha256(str(worktree).lower().encode('utf-8')).hexdigest()[:16]
    if args.bucket != expected:
        raise ValueError('Worktree build ID mismatch')
    bucket = plain_path(root / expected)
    marker = json.loads((bucket / '.hey-mike-build.json').read_text(encoding='utf-8-sig'))
    if marker['schema'] != 1 or Path(marker['worktree']) != worktree or Path(marker['repository']) != Path(config['repository']):
        raise ValueError('Unowned build bucket')
    script = plain_path(worktree / 'tools' / 'prepare_runtime.py')
    spec = importlib.util.spec_from_file_location('worktree_runtime', script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    configure(module, bucket)
    return module.main([])


if __name__ == '__main__':
    raise SystemExit(main())
