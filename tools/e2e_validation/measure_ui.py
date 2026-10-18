"""Warmed drawer input-to-next-draw evidence. Use only an idle debug QA phone."""
import argparse
import json
from pathlib import Path
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
    def locate(label):
        matches = [n for n in r.xml().iter('node') if n.get('content-desc') == label]
        if len(matches) != 1:
            raise RuntimeError('Drawer state/selector changed; no further tap: '+label)
        return matches[0]

    node = locate('Open chats and rules')
    # Confirm the whole open/close sequence before repeating it, only while idle.
    r.tap_node(node); time.sleep(.6)
    close = locate('Close chats')
    r.tap_node(close); time.sleep(.6)
    locate('Open chats and rules')
    for _ in range(2):
        r.tap_node(locate('Open chats and rules')); time.sleep(.6)
        r.tap_node(locate('Close chats')); time.sleep(.6)
        locate('Open chats and rules')
    before = set(r.device.text('logcat', '-d', '-v', 'threadtime', 'MikeUiLatency:I', '*:S').splitlines())
    metadata = {'initialState': initial, 'samplesRequested': a.samples, 'warmups': 3,
                'sequence': 'Tap Open chats and rules; allow animation; tap Close chats',
                'scope': 'Idle warmed drawer, not keyboard, streaming or navigation completion',
                'stateVerification': 'Fresh unique selector before every tap; confirm open and closed for each cycle',
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
        opener = locate('Open chats and rules')
        r.device.call('shell', 'dumpsys', 'gfxinfo', a.package, 'reset')
        r.tap_node(opener); time.sleep(1.1)
        opening_logs.append(capture_new())
        raw = r.device.text('shell', 'dumpsys', 'gfxinfo', a.package, 'framestats')
        (root/f'frames-{i+1:02d}.txt').write_text(raw, encoding='utf-8')
        raw_runs.append(raw)
        pipeline.append(frame_stats(raw))
        system_counters.append(gfx_summary(raw))
        r.tap_node(locate('Close chats')); time.sleep(1.1)
        closing_logs.append(capture_new())
        locate('Open chats and rules')
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
    result['eventCountValid'] = all(part['statuses'] == {'drawn': a.samples}
                                  for part in (result['inputToDraw'], result['closeInputToDraw']))
    r.checkpoint(root/'results.json', result)
    if not result['eventCountValid']:
        metadata.update(phase='invalid_evidence', finalState=r.idle()); r.checkpoint(record, metadata)
        raise RuntimeError('Input event count mismatch; retained raw evidence needs review')
    metadata.update(phase='finished',finalState=r.idle());r.checkpoint(record,metadata)
    print(json.dumps(result))


if __name__ == '__main__': main()
