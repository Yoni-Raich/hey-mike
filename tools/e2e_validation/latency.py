"""Separate Android rendering, input-to-next-draw, and agent/tool timing. Stdlib only."""
import csv
import io
import json
import math
import re
import statistics


def summary(values):
    ordered = sorted(values)
    if not ordered:
        return {'samples': 0, 'medianMs': None, 'p95Ms': None, 'maxMs': None}
    return {'samples': len(ordered), 'medianMs': round(statistics.median(ordered), 3),
            'p95Ms': round(ordered[max(0, math.ceil(.95*len(ordered))-1)], 3), 'maxMs': round(ordered[-1], 3)}


def frame_stats(text):
    durations, misses, skipped_flags = [], 0, {}
    # Each Android window has its own profile block and header. Do not assume column order.
    for block in text.split('---PROFILEDATA---')[1::2]:
        rows = csv.DictReader(io.StringIO(block.strip()))
        for row in rows:
            try:
                flags = int(row.get('Flags', '1'))
                if flags != 0:
                    skipped_flags[str(flags)] = skipped_flags.get(str(flags), 0)+1
                    continue
                start, end = int(row['IntendedVsync']), int(row['FrameCompleted'])
                if start <= 0 or end <= start or end >= 2**63-1: continue
                elapsed = (end-start)/1e6
                durations.append(elapsed)
                deadline = int(row.get('FrameDeadline') or 0)
                if deadline > start and end > deadline: misses += 1
            except (ValueError, KeyError, TypeError): continue
    return {**summary(durations), 'deadlineMisses': misses if durations else None,
            'availability': 'available' if durations else 'unavailable', 'skippedFlags': skipped_flags,
            'limits': 'Frame pipeline duration. It is not tap-to-visible-content or model latency. Android retains a bounded recent frame buffer.'}


def gfx_summary(text):
    """Keep Android's reported counters separate from parsed frame samples."""
    rendered = re.search(r'Total frames rendered:\s*(\d+)', text)
    janky = re.search(r'Janky frames:\s*(\d+)', text)
    return {'renderedFrames': int(rendered[1]) if rendered else None,
            'jankyFrames': int(janky[1]) if janky else None,
            'limits': 'Counters reported by Android for this reset interval. They are not tap latency or a percentile of parsed rows.'}


def input_draw(log):
    samples, statuses = [], {}
    for line in log.splitlines():
        match = re.search(r'MikeUiLatency\s*:\s*(\{.*\})', line)
        if not match: continue
        try: event=json.loads(match[1])
        except ValueError: continue
        statuses[event.get('status')] = statuses.get(event.get('status'), 0)+1
        value=event.get('inputToDrawMs')
        if event.get('status') == 'drawn' and isinstance(value, (int,float)): samples.append(value)
    return {**summary(samples), 'statuses': statuses,
            'limits': 'App dispatch of touch ACTION_UP to next draw callback. Excludes touch hardware, GPU/display presentation and completion of navigation. Idle warmed UI only.'}


def agent_stats(events):
    calls, elapsed, failures = {}, [], 0
    first_user = next((e['timestampMs'] for e in events if e.get('type') == 'user'), None)
    first_call = next((e['timestampMs'] for e in events if e.get('type') == 'tool_call'), None)
    final = [e['timestampMs'] for e in events if e.get('type') == 'assistant' and e.get('phase') == 'final_answer']
    finished = [e['timestampMs'] for e in events if e.get('type') == 'turn_finished' and e.get('status') == 'completed']
    for e in events:
        key=(e.get('threadId'),e.get('turnId'),e.get('requestId'))
        if e.get('type') == 'tool_call': calls[key]=e
        elif e.get('type') in ('tool_result','tool_cancelled') and key in calls:
            value=e.get('elapsedMs')
            if isinstance(value,(int,float)): elapsed.append(value)
            if not e.get('success'): failures+=1
    return {'calls': len(calls), 'failedOrCancelled': failures, 'monotonicTool': summary(elapsed),
            'firstToolWallMs': None if first_user is None or first_call is None else first_call-first_user,
            'completionWallMs': None if first_user is None or not (finished or final) else (finished or final)[-1]-first_user,
            'limits': 'Outside-tool time includes network, scheduling, streaming and model work. Never call it pure inference time. Approvals can be included in per-call elapsed time.'}
