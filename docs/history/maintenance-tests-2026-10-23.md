# Second maintenance pass — 2026-10-23

Baseline: `origin/dev` at `ff5729f74e004c4fac281dc332ee0d018277af1e`.
Work is isolated on `test/maintenance-tests-20261023`. The main checkout and
other worktrees have unrelated work and were left unchanged. PR #143 remains
a separate, unmerged CI-trigger change; this pass does not depend on it.

## CI failure triage

Read the latest 100 Android workflow runs, covering 2026-10-07 through
2026-10-23, then inspected all six failed-run logs:

| Failed runs | Finding | Current disposition |
| --- | --- | --- |
| [38761978235](https://github.com/Yoni-Raich/hey-mike/actions/runs/38761978235), [38765181042](https://github.com/Yoni-Raich/hey-mike/actions/runs/38765181042) | `CodexRelayProcessTest.repeatedHandshakesDoNotResetTheInvalidFrameRecoveryBudget` | Already fixed by `5b5884aa`: publish terminal state before waking readers. Do not repeat that fix. |
| [38288563506](https://github.com/Yoni-Raich/hey-mike/actions/runs/38288563506), [38288577871](https://github.com/Yoni-Raich/hey-mike/actions/runs/38288577871) | Three SSH delta fixture failures | Already fixed by `71e4d869`: use the Linux shell for exec fixtures. |
| [39075917008](https://github.com/Yoni-Raich/hey-mike/actions/runs/39075917008), [39132645204](https://github.com/Yoni-Raich/hey-mike/actions/runs/39132645204) | `BrowserIntegrationTest > classMethod`, `IllegalStateException`, source line -1 | Unresolved. The retained console logs lack the Chrome startup message/stack; they do not establish the root cause. |

Commands: `gh run list --repo Yoni-Raich/hey-mike --workflow android.yml
--limit 100 --json databaseId,headSha,headBranch,event,status,conclusion,url,createdAt`,
then `gh run view <id> --repo Yoni-Raich/hey-mike --log-failed`.
Passing PR and failing push runs can have the same head SHA but different
merge checkouts. That pairing alone is not proof of a flaky test.

## Reproduced timeout-test race

`AndroidDeviceToolsTest.readUiTimeoutIsTypedAndNeverFallsBack` asked a hanging
fake transport to time out in 10 real milliseconds, then required one call.
Production correctly subtracts elapsed time before dispatching its dump.
On a slow or cold JVM, those 10ms can pass first: the tool returns a typed
timeout without dispatching, while the test incorrectly requires one call.

The unchanged focused Gradle test passed once on Windows/JDK 21. A bounded
repeat using the same compiled test and interpreted JVM (`java -Xint`)
reproduced the race:

```text
Before: iteration=0 ... expected:<1> but was:<0>
Before: iterations=100 failures=1
After:  iterations=100 failures=0
```

The temporary repeat harness used JUnitCore to run
`Request.method(AndroidDeviceToolsTest.class,
"readUiTimeoutIsTypedAndNeverFallsBack")` 100 times in one JVM. Both comparisons
used `java -Xint --class-path <Gradle test runtime classpath>
RepeatTimeoutTest.java 100`. This deliberately slow JVM is a reproduction
condition, not a measurement of normal production performance.

## Implemented change

The test now uses `kotlinx-coroutines-test:1.9.0`, matching this module's
coroutines version. It launches the call with the real default six-second
budget and explicitly advances virtual time. Assertions check that:

- one dump starts and remains suspended;
- no completion or cancellation happens one virtual millisecond before the deadline;
- the call completes at the deadline with `ui_timeout` and `stable:false`;
- the hanging operation is cancelled exactly once and no fallback dump runs.

The test's own temporary workspace is removed in `finally`. Production code,
timeouts, lint rules, CI checks and test coverage are preserved. No APK or
runtime dependency changes; the added dependency is test-only.

## Other bounded checks

A source scan covered 241 production Kotlin files and found no private
function declaration whose name appeared only once in that scan. This is a
narrow candidate search, not a dead-code audit: public APIs, properties,
resources, reflection, generated consumers and runtime use were not proved.
No code was removed on that basis.

No agent-instruction A/B replay was performed, so this pass makes no claim
about prompt efficiency, token savings or changed agent behavior. Existing
instructions were kept. Chrome class-setup diagnosis is the next unresolved
CI candidate; weakening or skipping the test would not resolve it.

## Validation

Focused test and the 100-run interpreted-JVM comparison passed after the
change. The scoped `:device-tools:testDebugUnitTest :core:test` run passed:
106 device-tools tests and 809 core tests, no failures/errors/skips. Existing
core opt-in warnings remain. Hosted CI is recorded in the focused PR.
Local Gradle uses a task-specific central-storage config
with a high ceiling so it does not prune other worktrees' build output.
No local app assembly, install, device test or full release gate was run for
this test-only change.
