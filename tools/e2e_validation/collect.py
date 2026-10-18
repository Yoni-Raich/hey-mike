"""Read-only Xiaomi E2E evidence. Python stdlib; never clears logs or app data."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

def redact(text):
    text = re.sub(r'\b(?:sk|sk-ant)-[A-Za-z0-9_-]{8,}', '[REDACTED_KEY]', text)
    text = re.sub(r'(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+', r'\1[REDACTED]', text)
    text = re.sub(r'(?i)(["\']?(?:access_token|refresh_token|id_token|api_key|password|authorization)["\']?\s*[:=]\s*["\']?)[^\s"\',}]+', r'\1[REDACTED]', text)
    text = re.sub(r'\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED_JWT]', text)
    return text

def safe_value(value):
    if isinstance(value, dict):
        return {k: '[REDACTED]' if re.search(r'(?i)^(password|access_token|refresh_token|id_token|api_key|authorization)$', k) else safe_value(v) for k, v in value.items()}
    if isinstance(value, list): return [safe_value(v) for v in value]
    return redact(value) if isinstance(value, str) else value

class Device:
    def __init__(self, args): self.args = args
    def call(self, *command, timeout=30):
        inventory = subprocess.run([self.args.adb, 'devices'], capture_output=True, check=True, timeout=10).stdout.decode().replace('\r', '')
        if not re.search(r'^' + re.escape(self.args.serial) + r'\s+device$', inventory, re.M):
            raise RuntimeError('Selected serial is not in device state; no command dispatched')
        result = subprocess.run([self.args.adb, '-s', self.args.serial, *command], capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(redact(result.stderr.decode('utf-8', 'replace')[:2000]))
        return result.stdout
    def text(self, *command, **kwargs): return self.call(*command, **kwargs).decode('utf-8', 'replace')
    def uid(self):
        output = self.text('shell', 'pm', 'list', 'packages', '-U', self.args.package)
        match = re.search(r'^package:' + re.escape(self.args.package) + r'\s+uid:(\d+)', output, re.M)
        if not match: raise RuntimeError('Package is not installed')
        return match.group(1)

def write_text(path, content): path.write_text(redact(content), encoding='utf-8')
def snapshot(device, args):
    folder = Path(args.out) / args.label
    folder.mkdir(parents=True, exist_ok=True)
    if (folder / 'manifest.json').exists(): raise RuntimeError('Snapshot label exists; choose a new label')
    results = {}
    uid = device.uid()
    commands = {
        'package.txt': ('shell', 'dumpsys', 'package', args.package),
        'exit-info.txt': ('shell', 'dumpsys', 'activity', 'exit-info', args.package),
        'meminfo.txt': ('shell', 'dumpsys', 'meminfo', args.package),
        'app-logcat.txt': ('logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-d', '-t', '2000', '--uid=' + uid, '-v', 'threadtime'),
        'crash-buffer.txt': ('logcat', '-b', 'crash', '-d', '-t', '200', '--uid=' + uid, '-v', 'threadtime'),
        'a11y-enabled.txt': ('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'),
    }
    for name, command in commands.items():
        try:
            write_text(folder / name, device.text(*command))
            results[name] = 'collected'
        except Exception as error: results[name] = {'error': redact(str(error))}
    # ANR/crash events can be emitted by system_server, outside the app UID.
    try:
        events = device.text('logcat', '-b', 'events', '-d', '-t', '2000', '-v', 'threadtime',
                             'am_crash:V', 'am_anr:V', 'am_kill:V', 'am_proc_died:V', '*:S')
        scoped = '\n'.join(line for line in events.splitlines() if args.package in line or re.search(r'\b' + uid + r'\b', line))
        write_text(folder / 'system-events.txt', scoped)
        results['system-events.txt'] = 'collected'
    except Exception as error: results['system-events.txt'] = {'error': redact(str(error))}
    manifest = {'utc': datetime.now(timezone.utc).isoformat(), 'deviceTime': device.text('shell', 'date'),
                'serial': args.serial, 'package': args.package, 'uid': uid, 'sourceSha': args.source_sha,
                'model': device.text('shell', 'getprop', 'ro.product.model').strip(),
                'android': device.text('shell', 'getprop', 'ro.build.version.release').strip(), 'files': results,
                'complete': all(status == 'collected' for status in results.values()),
                'note': 'Recent log buffers may contain older events. Use timestamps and pre/post exit-info to set the test window. Redaction is best effort; retain only local, synthetic QA evidence.'}
    (folder / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'folder': str(folder.resolve()), 'files': results}))
    if not manifest['complete']: raise RuntimeError('Snapshot is incomplete; inspect manifest errors')

def watch(device, args):
    folder = Path(args.out); folder.mkdir(parents=True, exist_ok=True)
    path = folder / 'app-live-logcat.txt'
    if path.exists(): raise RuntimeError('Live log exists; use a new run folder')
    uid = device.uid()  # UID survives app-process restarts; a fixed PID does not.
    inventory = device.text('shell', 'date')
    start = datetime.now(timezone.utc).isoformat()
    status_path = folder / 'log-capture-status.json'
    status_path.write_text(json.dumps({'startedUtc': start, 'deviceTime': inventory,
        'uid': uid, 'complete': False, 'note': 'Capture is running. Missing completion is incomplete evidence.'}, indent=2), encoding='utf-8')
    command = [args.adb, '-s', args.serial, 'logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '--uid=' + uid, '-T', '1', '-v', 'threadtime']
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    system_process = subprocess.Popen([args.adb, '-s', args.serial, 'logcat', '-b', 'events', '-T', '1', '-v', 'threadtime',
                                       'am_crash:V', 'am_anr:V', 'am_kill:V', 'am_proc_died:V', '*:S'],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    # A reader thread prevents readline() blocking the duration timer while logs are quiet.
    import threading
    errors = []
    def reader():
        try:
            with path.open('w', encoding='utf-8') as stream:
                for line in iter(process.stdout.readline, b''):
                    stream.write(redact(line.decode('utf-8', 'replace'))); stream.flush()
        except Exception as error: errors.append(str(error))
    thread = threading.Thread(target=reader, daemon=True); thread.start()
    def system_reader():
        try:
            with (folder / 'system-events-live.txt').open('w', encoding='utf-8') as stream:
                for raw in iter(system_process.stdout.readline, b''):
                    line = raw.decode('utf-8', 'replace')
                    if args.package in line or re.search(r'\b' + uid + r'\b', line):
                        stream.write(redact(line)); stream.flush()
        except Exception as error: errors.append(str(error))
    system_thread = threading.Thread(target=system_reader, daemon=True); system_thread.start()
    deadline = time.monotonic() + args.seconds
    try:
        while time.monotonic() < deadline and process.poll() is None and system_process.poll() is None:
            if args.stop_file and Path(args.stop_file).exists(): break
            time.sleep(.25)
        ended_early = process.poll() is not None or system_process.poll() is not None
    finally:
        if process.poll() is None: process.terminate()
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        thread.join(timeout=5)
        if system_process.poll() is None: system_process.terminate()
        try: system_process.wait(timeout=5)
        except subprocess.TimeoutExpired: system_process.kill(); system_process.wait(timeout=5)
        system_thread.join(timeout=5)
    stderr = process.stderr.read().decode('utf-8', 'replace')
    status = {'startedUtc': start, 'deviceTime': inventory, 'endedUtc': datetime.now(timezone.utc).isoformat(),
              'uid': uid, 'endedEarly': ended_early, 'errors': errors, 'stderr': redact(stderr),
              'systemStderr': redact(system_process.stderr.read().decode('utf-8', 'replace')),
              'bytes': path.stat().st_size if path.exists() else 0}
    status['complete'] = not (ended_early or errors or thread.is_alive() or system_thread.is_alive())
    status_path.write_text(json.dumps(status, indent=2), encoding='utf-8')
    print(json.dumps(status))
    if ended_early or errors or thread.is_alive() or system_thread.is_alive(): raise RuntimeError('Incomplete capture; do not label the run clean')

def trace(device, args):
    sid = str(uuid.UUID(args.session_id))
    if sid != args.session_id: raise RuntimeError('Use a canonical session UUID')
    data = device.call('exec-out', 'run-as', args.package, 'cat', f'files/sessions/{sid}/workspace/session-trace.jsonl')
    if len(data) > 16 * 1024 * 1024: raise RuntimeError('Trace is over 16 MiB; select a shorter QA session')
    lines = []
    for line in data.decode('utf-8').splitlines():
        if line.strip(): lines.append(json.dumps(safe_value(json.loads(line)), ensure_ascii=False))
    folder = Path(args.out); folder.mkdir(parents=True, exist_ok=True)
    path = folder / f'{sid}.trace.jsonl'
    if path.exists(): raise RuntimeError('Trace already exists; use a new output folder')
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(json.dumps({'file': str(path.resolve()), 'events': len(lines), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['snapshot', 'watch', 'trace', 'sessions'])
    parser.add_argument('--adb', required=True); parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='dev.androidagent.app.dev'); parser.add_argument('--out', required=True)
    parser.add_argument('--source-sha'); parser.add_argument('--label', default='snapshot')
    parser.add_argument('--seconds', type=int, default=300); parser.add_argument('--session-id')
    parser.add_argument('--stop-file', help='Optional local marker to end a capture after validation; never a device command')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_.]+', args.package): parser.error('Invalid package')
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', args.label): parser.error('Invalid label')
    if not 1 <= args.seconds <= 3600: parser.error('seconds must be 1..3600')
    if args.mode == 'trace' and not args.session_id: parser.error('trace needs --session-id')
    device = Device(args)
    if args.mode == 'snapshot': snapshot(device, args)
    elif args.mode == 'watch': watch(device, args)
    elif args.mode == 'trace': trace(device, args)
    else: print(device.text('shell', 'run-as', args.package, 'ls', 'files/sessions'))

if __name__ == '__main__': main()
