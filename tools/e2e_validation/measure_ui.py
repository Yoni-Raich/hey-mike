"""Warmed drawer input-to-next-draw evidence. Use only an idle debug QA phone."""
import argparse
import json
from pathlib import Path
import re
import time
from runner import Runner
from latency import input_draw, frame_stats, summary, gfx_summary


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True); p.add_argument('--serial', required=True)
    p.add_argument('--package', default='dev.androidagent.app.dev')
    p.add_argument('--out', required=True); p.add_argument('--samples', type=int, default=20)
    a = p.parse_args()
    if not 20 <= a.samples <= 100: p.error('samples must be 20..100')
    if not re.fullmatch(r'[a-zA-Z0-9_.]+', a.package): p.error('invalid package')
    root = Path(a.out)
    if root.exists(): raise RuntimeError('Evidence folder exists; do not overwrite a run')
    r = Runner(a); initial = r.idle()
    r.device.call('shell', 'am', 'start', '-n', a.package+'/dev.androidagent.app.MainActivity',
                  '-f', '0x34000000', '--ez', 'dev.androidagent.app.QA_UI_LATENCY', 'true')
    time.sleep(.5)
    node = next(n for n in r.xml().iter('node') if n.get('content-desc') == 'Open chats and rules')
    coords = list(map(int, re.findall(r'\d+', node.attrib['bounds'])))
    x, y = (coords[0]+coords[2])//2, (coords[1]+coords[3])//2
    # Confirm the whole open/close sequence before repeating it, only while idle.
    r.tap_node(node); time.sleep(.6)
    opened = r.xml()
    if not any(n.get('content-desc') == 'Close chats' for n in opened.iter('node')):
        raise RuntimeError('Drawer verification failed; no repetition')
    close = next(n for n in opened.iter('node') if n.get('content-desc') == 'Close chats')
    bounds = list(map(int, re.findall(r'\d+', close.attrib['bounds'])))
    cx, cy = (bounds[0]+bounds[2])//2, (bounds[1]+bounds[3])//2
    r.tap_node(close); time.sleep(.6)
    if not any(n.get('content-desc') == 'Open chats and rules' for n in r.xml().iter('node')):
        raise RuntimeError('Close verification failed; no repetition')
    for _ in range(2):
        r.idle(); r.device.call('shell', 'input', 'tap', str(x), str(y)); time.sleep(.45)
        r.device.call('shell', 'input', 'tap', str(cx), str(cy)); time.sleep(.45)
    before = set(r.device.text('logcat', '-d', '-v', 'threadtime', 'MikeUiLatency:I', '*:S').splitlines())
    metadata = {'initialState': initial, 'samplesRequested': a.samples, 'warmups': 3,
                'sequence': 'Tap Open chats and rules; allow animation; tap Close chats',
                'scope': 'Idle warmed drawer, not keyboard, streaming or navigation completion',
                'phase': 'measuring', 'completedInputs': 0}
    record = root/'run.json'; r.checkpoint(record, metadata)
    pipeline, raw_runs, opening_logs, closing_logs, system_counters = [], [], [], [], []
    def capture_new():
        nonlocal before
        after = r.device.text('logcat', '-d', '-v', 'threadtime', 'MikeUiLatency:I', '*:S')
        new = '\n'.join(line for line in after.splitlines() if line not in before)+'\n'
        before = set(after.splitlines())
        return new
    for i in range(a.samples):
        r.idle()
        r.device.call('shell', 'dumpsys', 'gfxinfo', a.package, 'reset')
        r.device.call('shell', 'input', 'tap', str(x), str(y)); time.sleep(1.1)
        opening_logs.append(capture_new())
        raw = r.device.text('shell', 'dumpsys', 'gfxinfo', a.package, 'framestats')
        (root/f'frames-{i+1:02d}.txt').write_text(raw, encoding='utf-8')
        raw_runs.append(raw)
        pipeline.append(frame_stats(raw))
        system_counters.append(gfx_summary(raw))
        r.device.call('shell', 'input', 'tap', str(cx), str(cy)); time.sleep(1.1)
        closing_logs.append(capture_new())
        metadata['completedInputs'] = i+1; r.checkpoint(record, metadata)
    log = ''.join(opening_logs)
    (root/'input-draw.txt').write_text(log, encoding='utf-8')
    (root/'close-draw.txt').write_text(''.join(closing_logs), encoding='utf-8')
    result = {'inputToDraw': input_draw(log), 'frameRuns': pipeline,
              'closeInputToDraw': input_draw(''.join(closing_logs)),
              'pooledFrames': frame_stats('\n'.join(raw_runs)),
              'frameSamples': sum(run['samples'] for run in pipeline),
              'deadlineMisses': sum(run['deadlineMisses'] or 0 for run in pipeline) if any(run['samples'] for run in pipeline) else None,
              'systemCounters': system_counters,
              'frameMedianAcrossRuns': summary([run['medianMs'] for run in pipeline if run['samples']]),
              'limits': 'Each frame buffer is reset and retained separately. Closing frames are excluded. Frame-run medians are not a pooled frame percentile.'}
    if sum(result['inputToDraw']['statuses'].values()) != a.samples:
        raise RuntimeError('Input event count mismatch; retained raw evidence needs review')
    r.checkpoint(root/'results.json', result)
    metadata.update(phase='finished',finalState=r.idle());r.checkpoint(record,metadata)
    print(json.dumps(result))


if __name__ == '__main__': main()
