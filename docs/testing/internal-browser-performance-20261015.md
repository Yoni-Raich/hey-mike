# Internal browser performance, 2026-10-15

The user's report described one successful Google-to-httpbin click taking
27.7 s on their phone. That time was measured outside the tool. It has not been
reproduced or assigned a root cause here. These changes remove two separately
reproduced wait problems and add measurements for the next slow occurrence.

## Changes

- Every tool call returns monotonic backend time, exclusive phase times,
  protocol counts, slowest command, errors/timeouts, document events and
  redirect count. Phase times and CDP time overlap; do not sum them.
- Plans return timing, action status and verification status for each step.
  No verification means `not_requested`. Completed/uncertain actions are
  never replayed automatically. One final snapshot covers the whole plan.
- The app logs detailed timings under `MikeBrowserTiming`. Each record has
  a call ID and numbered chunks of at most 3000 payload characters. Field
  values, selectors, URLs, page text, workspace paths, headers and bodies
  are excluded. Log failures cannot turn a completed action into a failure.
- Navigation accepts an interactive DOM. Settling and condition waits bound
  nested protocol reads, preserve Stop, and report settling-budget expiry.
- Hit testing checks the visual viewport. The Android keyboard can pan it
  independently from layout scroll; the existing scroll-and-recheck path now
  handles a radio button above the keyboard-panned viewport.

`browser(operation="plan")` already is the browser's `act_plan` equivalent.
It uses DOM refs/labels, the same approvals and input ledger as single calls,
and resolves again after every step. The screen planner's `call` allowlist is
not changed. The shipped browser skill explains this split and timing fields.

## Controlled before/after checks

Two new regressions failed with timing instrumentation but the old wait code:

| Fixture | Before | After |
| --- | ---: | ---: |
| Usable document with an image delayed 8 s | 8535 ms | 396 ms |
| Nested protocol read during the 3 s settling budget | 10050 ms | at most 3050 ms |

The image result uses live desktop Chrome, not a phone. The nested-read result
uses coroutine virtual time and an injected delayed connection. A separate
test bounds a delayed verification read to its requested 200 ms. Tests also
cover per-step verification status, one final snapshot, log privacy, log-sink
failure, Stop propagation and a synthetic visual viewport excluding a control.

## Physical WebView measurements

Xiaomi 23053RN02Y, Android 15, WebView 154.0.8037.107, unlocked and in the
foreground. This is a separate, test-signed, non-debuggable probe app using
the production `:web` BrowserSession, WebToolGateway, page helper and native
WebViewInput. It does not replace or instrument Hey Mike. The target is not
the phone hosting the source chat. No app data was cleared or uninstalled.

Three consecutive repetitions, same WebView/profile (not cold-cache trials):

| Operation | Individual ms | Median ms |
| --- | --- | ---: |
| Open Google | 2163, 1909, 1937 | 1937 |
| Plan: search `httpbin forms post`, Enter, verify `/search` | 3966, 2525, 3433 | 3433 |
| Click the `forms/post` result | 1640, 1214, 1632 | 1632 |
| Open `https://httpbin.org/forms/post` directly | 1256, 613, 758 | 758 |
| Plan: three fills, Medium radio, Mushroom checkbox | 2481, 2419, 2445 | 2445 |

All 15 performance calls succeeded. Returned snapshots retained the test name,
email and delivery text, with Medium and Mushroom checked. No order was sent.
Click records each counted one redirect. Protocol timeouts were zero. One
search plan reported one settling-budget expiry; its URL verification and
final snapshot succeeded. Typical final snapshots took 14-31 ms; the slowest
search snapshot took 192 ms. Filling the five-control form spent about
1.83 s in settling and 0.49-0.56 s in other action work. These are small-sample
observations, not a percentile guarantee or a comparison against the user's
different phone/network/relay.

The probe has a narrow preapproval callback for its Google `forms/post` result
link only. Other sends/eval remain refused. This does not test the real app's
approval UI. Initial harness attempts were retained: a `loadDataWithBaseURL`
fixture produced a `data:` history entry and was correctly blocked; the
harness now serves its fixture at a normal intercepted HTTPS address. A
later run refused the result link because the test callback denied approval.
Those refused calls are not navigation timings. The next run isolated the
keyboard pan (visual offsetTop 296 CSS px, radio center about 218 CSS px),
then the visual-viewport correction made the full five-step plan pass.

The probe's later CDP screenshot check remains intermittent: one run passed;
the final performance run ended `FAIL` at `Screenshot unavailable` after all
15 benchmark calls completed. That failure is preserved in `probe-v6.txt`.
This is not a full probe PASS, nor proof of the app host's bitmap fallback.

## Follow-up: false approval on a result path

The user's hosting-phone report at about 13:00 on 2026-10-15 says the result
click spent 120.02 seconds awaiting approval, then returned `approval_denied`
without clicking. Other successful operations took 0.79-3.56 seconds in the
backend, and a direct form open took 1.34 seconds. This is user-supplied test
evidence; this retained PC chat did not make those calls. The report confirms
that the form was filled and shown, with no order submitted.

Source diagnosis: `needsApproval` matched `\bpost\b` in the link label
`forms/post`. The slash is a word boundary, so a navigation label was treated
as a publishing action. A physical follow-up also captured Google's nested
`<a><div role="heading"><div role="link">forms/post</div></div></a>` structure:
the helper stopped at the inner link-role div instead of the real anchor.
`AgentCoordinator` has a 120000 ms local approval
timeout; the browser host maps a non-allowed outcome to false and returns
`approval_denied`. This explains the reported approval wait. It does not
explain the earlier, separate 27.7-second event.

The fix resolves a link-role child to its enclosing anchor, then removes
URL/path tokens only when testing `post` on ordinary HTTP(S)
GET links. It retains the other sensitive-word checks on the full label,
keeps form checks, and excludes buttons, button-role links, downloads,
non-GET action links and non-web schemes. It changes neither the approval
timeout nor the user's approval answer.

Four live Chrome regressions were run before the fix: two failed for path
and full-URL labels, while the sensitive-action and non-web checks passed.
The first fix passed 66 web tests and lint but still refused all three
physical Google result clicks in probe versions 7 and 8. These failed calls
were kept, and the actual nested structure was added as another regression.
The refusal test covers nine controls: Post comment, a button named
forms/post, a delete link, an ordinary POST form, a button-role link, POST
data-method/data-turbo-method links, a nested Post comment and a download.
The probe's old Google-specific approval
callback was removed; it now refuses every approval request again.

Final focused check: `:web:testDebugUnitTest :web:lintDebug --no-daemon`
passed in 1m2s: 67 tests, no failures/errors/skips (43 live CDP, 7 host,
7 policy, 8 gateway, 2 wait). The final full gate passed in 1m20s with
`test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug
:web:lintDebug :app:lintDevDebug --no-daemon`, using version overrides
1151 / `0.14.0-dev.browser-approval1`. Runtime preparation tests passed
15 tests. An intermediate focused build was blocked before tests by the
build-storage hook trying to prune a locked probe dex file; stopping the
idle Gradle daemons and running builds sequentially allowed the checks to
finish. No manual deletion or cleanup of other worktrees was done.

Physical probe version 9 uses the same Xiaomi/WebView as the earlier runs,
is non-debuggable and refuses every approval callback. All 15 benchmark
calls succeeded in three consecutive repetitions:

| Operation | Individual ms | Median ms |
| --- | --- | ---: |
| Open Google | 2180, 1958, 2144 | 2144 |
| Search plan | 2810, 2633, 3798 | 2810 |
| Click forms/post | 2179, 1477, 1126 | 1477 |
| Direct form open | 1027, 791, 1281 | 1027 |
| Five-action form plan | 2480, 2446, 2426 | 2446 |

The result clicks recorded one redirect each, zero protocol timeouts, and
no approval callback invocation. The approval phase still measures a tiny
local guard check (0.0-0.1ms), not an approval wait. No order was sent.
The later screenshot smoke check ended FAIL at `Screenshot unavailable`;
its completed output was pulled separately after the benchmark. Do not
infer a full probe or app-host PASS from the benchmark. This is not
a same-phone before/after latency comparison or a guarantee for other sites.

The final Dev Release artifact is 1151 `0.14.0-dev.browser-approval1`,
291916297 bytes, ZIP-aligned and test-signed with v3 and the existing test
certificate. SHA-256:
`56c597ceea5c368081253cdc86a1dd5645a7701c35eb76f2e0000c94ae3224eb`.
Its packaged page helper equals the tested source byte for byte. Code was
committed as `9833bcc`. `copy_file` completed to `chat:browser-1151/` and
then local Downloads. Opening that file reached APK Manager's
"Installing app-dev-debug.apk" screen. Phone tool replies stopped during
the update, so installed 1151 metadata is not yet verified. There was no
second install attempt. Check metadata before retrying or replacing it.
Logical copy size is not measured network traffic or proof of a delta.

Follow-up evidence is ignored under `artifacts/browser-approval-20261015/`,
including the red-test XML, intermediate web/lint log, probe versions 7/8,
their refused benchmark calls and the public Google DOM diagnostic.
It also holds the final `benchmark-v9.jsonl`, its derived summary, scoped
probe logcat, final gate logs and the signed artifact in `delivery-1151/`.

## Evidence and reproduction

Ignored folder: `artifacts/browser-performance-20261015/`.
It contains red-test XML, focused/full build logs, probe APKs, the logcat
capture `baseline-logcat.txt` (also includes later probe versions), raw
`benchmark-v4/v5/v6.jsonl`, final `benchmark-summary.json`, geometry diagnostics
and `probe-v6.txt`. Do not commit those snapshots or raw logs.

Commands from the assigned worktree:

```powershell
$env:HEYMIKE_TEST_CHROME='C:\Program Files\Google\Chrome\Application\chrome.exe'
.\gradlew.bat :web:testDebugUnitTest :web:lintDebug --console=plain
.\gradlew.bat -p tools/browser-probe assembleRelease '-PprobeVersionCode=6'
# Select a connected device deliberately; replace <serial> below.
adb -s <serial> install -r <exact-probe-apk>
adb -s <serial> shell am start -S -n dev.androidagent.browserprobe/.ProbeActivity --ez benchmark true
adb -s <serial> pull /sdcard/Android/data/dev.androidagent.browserprobe/files/benchmark.jsonl <local-evidence>
adb -s <serial> logcat -v epoch -T 1 MikeBrowserTiming:I '*:S'
```

The three public repetitions are opt-in (`--ez benchmark true`). Default
probe runs keep their fixture/screenshot/public-HTTPS smoke checks. A repeat
resets only the probe's benchmark file, never Hey Mike data. Use a higher
probe version code for another delivery.

## Limits

After the performance checks, the full app was built from code commit
`a6be3dd` as Dev Release 1150 `0.14.0-dev.browser-timings2`, ZIP-aligned and
test-signed with APK Signature Scheme v3. Artifact SHA-256:
`b90c8b505fe0b6143457181f4bcef9f8dd5974d8c4c954560cb93308d23fb025`,
291916297 bytes. It was transferred with `copy_file` (logical file size is not
measured wire traffic), then opened through Android's installer. After the
user reported installation, live app metadata confirmed 1150 on the hosting
phone, with browser ready and accessibility connected. The installed base
APK hash was not read. This is install/status proof, not a browser task.

Final checks: `:web:testDebugUnitTest :web:lintDebug` PASS, 62 tests without
failures/skips (38 live CDP, 7 host, 7 policy, 8 gateway, 2 wait). Full gate
`test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug
:web:lintDebug :app:lintDevDebug --no-daemon --console=plain` passed in 1m32s,
with 62 web tests per variant. `python -m unittest tools.test_prepare_runtime`
passed 15 tests. The final gate follows corrected intermediate compile/test
errors; failed logs are retained, not presented as successful validation.

The 27.7 s event is still unexplained. Backend timings exclude model time,
SSH/MCP delivery, and result rendering. They do not split DNS, TLS and server
time inside a navigation command. A run on the hosting phone with the new
build and outer-call timing is still needed to measure that relay path.
The updated full app is installed on the hosting phone; the measured physical
browser actions above remain proof from its shared module in the separate
Xiaomi probe host. The retained computer chat's tool catalog still does not
expose a callable `browser`, despite the runtime readiness line. A fresh chat
can run the hosting-phone comparison. Locked/background behavior, production network guards, the real
handoff/approval UI and intermittent CDP screenshot capture are not resolved
by these timing measurements. Previously documented redirect/network-policy
gaps remain unchanged.
