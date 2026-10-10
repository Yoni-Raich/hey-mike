"""Pair app-owned tool traces; validate chosen QA result contracts, not prose claims."""
import argparse
import json
from pathlib import Path

def analyze(events, expectations=None, strict=False):
    calls, completed, errors, results = {}, set(), [], []
    for event in events:
        kind = event.get('type')
        if kind not in ('tool_call', 'tool_result', 'tool_cancelled'): continue
        key = (event.get('threadId'), event.get('turnId'), event.get('requestId'))
        if not key[-1]: errors.append('Missing requestId'); continue
        if kind == 'tool_call':
            if key in calls: errors.append('Duplicate tool call: ' + str(key))
            calls[key] = event
            continue
        if key not in calls: errors.append('Result without call: ' + str(key)); continue
        if key in completed: errors.append('Duplicate tool result: ' + str(key))
        completed.add(key)
        call = calls[key]
        if event.get('name') != call.get('name'): errors.append('Call/result tool name mismatch')
        if not isinstance(event.get('success'), bool): errors.append('Result success is not boolean')
        if kind == 'tool_cancelled' and (event.get('success') is not False or event.get('errorType') != 'cancelled'):
            errors.append('Invalid cancellation terminal event')
        text = event.get('text', '')
        payload = None
        try: payload = json.loads(text)
        except (TypeError, ValueError): pass  # Some tools, notably device_status, return plain text by contract.
        if isinstance(payload, dict) and isinstance(payload.get('ok'), bool) and payload['ok'] != event.get('success'):
            errors.append('Envelope ok disagrees with ToolResult.success: ' + str(key))
        elapsed = event.get('timestampMs', 0) - call.get('timestampMs', 0)
        if elapsed < 0: errors.append('Negative wall-clock delta; inspect clock change')
        results.append({'name': event.get('name'), 'arguments': call.get('arguments'), 'success': event.get('success'),
                        'payload': payload, 'text': text, 'wallClockMs': elapsed,
                        'elapsedMs': event.get('elapsedMs'), 'cancelled': kind == 'tool_cancelled',
                        'requestId': key[-1], 'threadId': key[0], 'turnId': key[1]})
    pending = [list(key) for key in calls if key not in completed]
    errors.extend('Call without result: ' + str(key) for key in pending)
    for expected in expectations or []:
        matches = [r for r in results if r['name'] == expected['name'] and
                   all((r['arguments'] or {}).get(k) == v for k, v in expected.get('arguments', {}).items())]
        if len(matches) != expected.get('count', 1): errors.append('Unexpected count for ' + expected['name'])
        for result in matches:
            if result['success'] != expected.get('success', True): errors.append('Unexpected success for ' + expected['name'])
            if expected.get('json', False) and not isinstance(result['payload'], dict): errors.append('Expected JSON object: ' + expected['name'])
            for field, value in expected.get('fields', {}).items():
                actual = result['payload']
                for part in field.split('.'):
                    actual = actual.get(part) if isinstance(actual, dict) else None
                if actual != value:
                    errors.append('Unexpected payload field: ' + expected['name'] + '.' + field)
            if 'text_contains' in expected and expected['text_contains'] not in result['text']:
                errors.append('Expected content missing from ' + expected['name'])
    if strict and expectations is not None and len(calls) != sum(e.get('count', 1) for e in expectations):
        errors.append('Unexpected total tool-call count')
    return {'status': 'FAIL' if errors else 'PASS', 'toolCalls': len(calls), 'toolResults': len(results),
            'errors': errors, 'results': results, 'cancelledCalls': sum(r['cancelled'] for r in results),
            'limits': 'Pairing and selected contracts only. Review final replies, UI, dispatch after Stop, and unexpected extra calls separately. Wall-clock deltas are not monotonic tool timings.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace'); parser.add_argument('--expect'); parser.add_argument('--out', required=True)
    parser.add_argument('--strict', action='store_true')
    args = parser.parse_args()
    events = [json.loads(line) for line in Path(args.trace).read_text(encoding='utf-8').splitlines() if line.strip()]
    expected = json.loads(Path(args.expect).read_text(encoding='utf-8')) if args.expect else None
    report = analyze(events, expected, args.strict)
    path = Path(args.out)
    if path.exists(): parser.error('Output exists; choose a new report name')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': report['status'], 'toolCalls': report['toolCalls'], 'errors': report['errors'], 'report': str(path.resolve())}))
    raise SystemExit(0 if report['status'] == 'PASS' else 1)

if __name__ == '__main__': main()
