# Browser batching and app-side timing — 2026-10-15

## Problem and change

The user's hosting-phone report on 1151 took 79.4s wall time, with 14.97s in
outer tool calls and 14.21s browser backend time. The remaining 64.43s was
not split into model, orchestration or other work. The earlier false approval
on forms/post was already fixed; it is not the cause of this remaining gap.

A known form can now be edited, checked and handed over in one browser plan.
The read-only `check` step accepts `checks` of live values/checked states,
including unchecked boxes. Checks share a bounded timeout, reject secrets and
send no input. `show:true` hands over only after every step passes. A failed
check stops later steps; a failed handoff retains the completed ledger. The
same host ownership guard and approval handling serve both handoff paths.
The maximum known sequence is now 24 steps, still capped at 80 seconds.
The browser tool schema and shipped agent instructions describe this path.

The coordinator also records app-side queue/invocation/result handling times
and run totals in the session trace. The residual bucket is labelled outside
tool calls rather than pure thinking. It can still include model work and
remote commands; this change does not pretend to measure hidden reasoning.

## Controlled physical comparison

Xiaomi 23053RN02Y, Android 15, separate non-debuggable probe version 10,
using production BrowserSession, WebToolGateway, page helper and native touch.
The fixed fixture is served by the probe at its intercepted HTTPS address.
It does not access private sites or submit a form. This is not the hosting
phone, nor the full Hey Mike presentation UI.

Each path fills Query and Address, presses Apply once, verifies the same final
DOM with an independent CDP read and leaves the page in user control. Setup
navigation and that independent judge are outside the measured interval.
The split path uses fill, fill, click, snapshot and show. The batched path
uses one plan with three actions, a check of both values, and show:true.
No model delay was inserted. Pair order alternates to reduce warmup bias.

| Path | Calls per run | Individual backend ms | Median ms | Reply UTF-8 bytes |
| --- | ---: | --- | ---: | --- |
| Split | 5 | 1535, 1451, 1445 | 1451 | 2877, 2892, 2885 |
| Batched | 1 | 1509, 1474, 1455 | 1474 | 2365, 2364, 2366 |

All six paths passed the same final DOM checks and user-control check.
Calls were reduced by 80%; median returned text was 18.0% smaller (2885 to
2365 bytes). Backend medians are similar, with batching 23ms slower in this
small sample. The measured benefit is fewer model/tool exchanges and less
returned text, not faster page loading. No token count or model/wall-time
speedup is claimed. A new full task on the hosting phone is still needed to
measure whether its agent chooses the batched path and how much time it saves.

The probe ends BATCHING_PASS for this focused mode. It does not run the
separate screenshot smoke check, whose previously observed failure remains
open. It does not establish real app host handoff or locked/background proof.

## Trace interpretation

`tool_timing` records queueMs, dispatchMs, approvalMs, resultDeliveryMs and
totalMs. Arrival is when the coordinator receives the tool event; delivery
ends when engine.answerTool returns. Local UI/trace work is included; this
does not measure network acknowledgement or pure model time. Approval time
is part of dispatch and must not be counted twice.

`run_timing` records totalMs, toolCalls, toolMs, approvalMs, outsideToolsMs and
firstResponseMs when present. Its residual includes work outside calls served
by this coordinator. Correlate request and turn IDs in the raw trace rather
than calling the entire residual model thinking. New timing entries contain
no arguments or result/page text. Existing detailed tool traces stay local.

## Evidence

Ignored folder: artifacts/browser-batching-20261015/. It contains the five
initial failing integration tests, green web/core logs, probe APK, raw six-run
batching-v10.jsonl, derived comparison.json, probe package metadata and scoped
logcat. Raw public test responses are kept there, not committed.

Core virtual-clock coverage splits 600ms queue, 1000ms invocation and 300ms
delivery, and checks that private arguments are absent from timing entries.
Web regressions cover exact live values, checked false, delayed updates,
secret refusal, failed checks stopping later actions/handoff, a 14-field
form in one plan, and a lost handoff reply without repeated input.

Final full gate passed in 2m51s with version overrides 1152 /
0.14.0-dev.browser-batching1:

```powershell
.\gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug :web:lintDebug :app:lintDevDebug '-PversionCodeOverride=1152' '-PversionNameOverride=0.14.0-dev.browser-batching1' --no-daemon --console=plain
python -m unittest tools.test_prepare_runtime
git diff --check
```

The live Chrome environment was set for the gate. Web: 74 tests per variant,
core: 787, with no failures/errors/skips in those modules. Whole matrix:
2881 executions including repeated build variants, zero failures/errors,
34 existing skipped cases. Runtime preparation: 15 passed. Five new browser
tests were initially red before implementation. Detailed logs and the parsed
test summary are in the ignored evidence folder.
