# Manager evidence loop — 2026-11-04

Branch: `fix/manager-situation-20261102`, continued in the existing
`C:\Users\SHIRA\.codex\worktrees\manager-situation-20261102` checkout from
`ef91320d`. Other worktrees and the dirty main checkout were preserved.

## Change

- Stop generic Hebrew/English words selecting unrelated memory in injection and recall.
- Give Manager explicit instructions to persist changed task evidence, uncertainty
  and people facts, and to verify tool success before claiming a save.
- Keep bounded temporary conclusions with source ancestry, observation time,
  expiry and confidence, separate from verified current state.
- Add a single permission-aware Android location read. Old fixes, a changed boot,
  excluded profile or revoked permission cannot become current location.
- Allow an explicit reminder on a proposed task without accepting/executing it.
- Preserve chat direction, sender and displayed time when present in visible DOM;
  absent attribution/date remains unknown.
- Persist new pause reasons/times and expose read-only runtime status. Do not
  auto-resume or change Stop behavior. An old pause has no recorded historical cause.
- Display current-day counters even while discovery is paused; preserve stored
  budget receipts. A read-only phone query confirmed discovery off and an empty
  inbox, with 48 stored attempts belonging to the previous local day. No historical
  pause actor/time was present in the installed build.

## Validation

The scoped gate uses JDK 21, Android SDK 35 and Gradle 8.9 with an isolated Gradle
home and a high storage ceiling to avoid deleting other finished build buckets:

```text
gradlew.bat :core:test :device-tools:testDebugUnitTest :web:testDebugUnitTest
  :workspace:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug
  :app:lintDevDebug --no-daemon --offline --console=plain
```

`HEYMIKE_TEST_CHROME` points to installed Chrome for the web tests. These tests
read synthetic DOM fixtures in an isolated headless profile, not a live account.
Final gate: PASS, 1,417 cases (1,403 passed, 14 existing workspace skips), zero
failures/errors. Core 970, device-tools 114, web 95, workspace 66, app 172. All
61 Chrome/CDP fixture cases executed, including the new chat-attribution case.
App lint: zero errors, 21 warnings, four information findings. Diff check passed.

New fixtures cover irrelevant memory retrieval; reminders without execution;
probable completion across restart; observation expiry/revocation/role boundaries;
location age, accuracy, profile, permission and reboot handling; persisted pause
reason; and browser message metadata with hidden/sidebar content excluded.

## Evidence limits and generated output

No APK installation, personal-message read, board mutation, permission grant,
real GPS measurement or notification was performed for implementation validation.
The hosting phone remains on 1190. The debug package built on this branch is a
compile artifact, not an update candidate; no version bump or release was made.
No full release gate, physical UI rendering, real provider, background/Doze or
model-driven conversation/evidence-save flow has been verified.

The existing worktree stays open for the Manager branch. Its build bucket is
`C:\Users\SHIRA\Documents\AI\Android-agent-use\build\706667d0f17cf13e`.
The task's temporary Gradle home/logs under
`%TEMP%\hey-mike\manager-evidence-loop-20261104` were removed after validation;
the shared cache junction targets remain intact. No new worktree was created.
