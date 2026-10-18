# Internal browser: Xiaomi depth checks, 15 October 2026

The user reported an `obscured` Medium radio after three form fills and asked
for deep Xiaomi checks before another build is sent to the hosting phone.
Work is isolated in `internal-browser-fixes-20261015`. No hosting-phone
copy or installation was made during these investigations.

## Reproduced causes and changes

1. With an open keyboard, WebView's visual viewport can pan inside the layout
   viewport. `DOM.getContentQuads` returns visual points, while
   `DOM.getNodeForLocation` takes document points. The previous code passed
   the same point to both. The global hit test now adds
   `cssVisualViewport.pageX/pageY`; native touch keeps the visual point.
   Adding only `offsetX/offsetY` solved pan but missed document scrolling.
2. A site's smooth-scroll CSS could leave `scrollIntoView` moving when the
   next hit test ran. Agent scrolling and field focus now request instant
   scrolling. No click is retried and genuine covers remain blocked.
3. MDN's `main` is `display:contents`. `checkVisibility()` is false for that
   wrapper although its children are rendered. The helper pruned the whole
   article, losing text and links. A rendered-range check now follows these
   wrappers while preserving hidden-subtree exclusions.

## Physical setup and evidence

Xiaomi `23053RN02Y`, Android 15, serial `cd4928027d76`, USB selected explicitly
for every ADB call. WebView `154.0.8037.107`. The separate, non-debuggable,
test-signed `dev.androidagent.browserprobe` runs the production
`AndroidBrowserHost`, parked overlay WebView, `BrowserFrame`, native input
and `WebToolGateway`. Display-over-apps permission was enabled through the
normal Android Settings UI. All approval callbacks refuse. No uninstall,
clear-data, global settings write or connected-test task was used.

Raw evidence is ignored under
`artifacts/browser-batching-20261015/deep-xiaomi/`. Probe APKs are retained
alongside that directory. Public-page snapshots and diagnostics are not
committed.

- `keyboard-red-v12.jsonl`: eight failing clicks across four frame heights,
  two repeats each. Geometry shows the target visible and locally hittable.
- `keyboard-geometry-red-v13.jsonl`: diagnostic reads show raw quad points
  hitting a legend, while adding the viewport pan hits the radio. This run
  ended FATAL when a diagnostic hit test was outside the viewport; it is
  incomplete, not a passing run.
- `keyboard-offset-only-v14.jsonl`: four pan cases pass; four scrolled cases
  still fail. This intermediate fix was not delivered.
- `keyboard-green-v15.jsonl`: all eight click calls pass after using document
  coordinates. Later probe versions put verification inside plan steps and
  repeat this matrix with explicit checked-state verification.
- `sites-contents-red-v16.jsonl`: MDN's ancestor chain records a visible link
  under `main { display:contents }` with a zero wrapper box and false
  `checkVisibility`. The diagnostic site run was interrupted before completion
  and is not counted as passing.
- Earlier site diagnostics exposed harness mistakes: duplicate href selectors,
  positional selectors on hydrated pages and a GitHub product-menu link
  mistaken for repository Issues. Final scenarios use snapshot refs and match
  the repository destination. These are not claimed as product defects.
- Earlier control diagnostics used a nonexistent chat directory. The host
  correctly treated it as a deleted chat and discarded its page. The final
  harness creates both test workspaces. A revoked gateway also correctly
  throws before dispatch; that expected exception is now checked explicitly.

## Final scenario contract

Probes 17 and 19 write one JSONL row per completed call and a terminal `FINISHED`
failure count. A missing terminal row or any FATAL is incomplete.

- `sites`: two rounds of Wikipedia Android to Linux and back; MDN JavaScript
  to its reference and back; GitHub WHATWG HTML to repository Issues and back;
  scroll/read sequences; external WHATWG navigation. GitHub's external link
  requests a popup, which the product deliberately refuses. The scenario
  observes that refusal, then opens the observed URL in the same page and
  independently checks the destination. Popup refusal is not navigation proof.
- `controls`: real cover refused with unchanged state; handoff blocks agent
  input; give-back allows it; revoke rejects input; cancellation after the
  first step prevents the later click. A 24-step plan finishes with handoff,
  and an independent DOM counter checks exactly 21 additional clicks. Two
  existing chat workspaces retain separate pages through attach/detach.
- `keyboard`: four native frame heights (2100, 1800, 1450, 1100 physical px),
  two repeats each, open composer keyboard, three real form fills, then Medium
  with checked-state verification. Geometry records pan, document scroll,
  native IME state and frame size.
- `forms`: three parked and three attached real httpbin form runs. Three
  fields, radio/checkbox updates, actual scroll commands and a final read-only
  check of ten controls. No form is submitted. A zero scroll range is reported
  honestly rather than counted as page movement.

Final measured results and package evidence are recorded in PROGRESS.md once
the runs and final gate complete. Probe 17's controls, keyboard and forms each
ended with zero failures. Probe 19's two site rounds ended with zero failures
in 48253ms; all four destination sites were read. Probe 18 retained a known
stale anchor during GitHub hydration. The final harness may refresh and retry
that one unperformed step only if the input counter is unchanged; it never
retries uncertain clicks or denied approvals. Probe 19 needed no recovery.
Final full gate passed in 2m38s: 2893 test executions, zero failures/errors,
34 existing skips, web 80 per variant with no skips, 15 runtime tests OK.
These module scenarios include no model
thinking, Compose chat UI, account login or human gestures. They do not prove
the hosting phone or end-to-end model wall time. Pinch zoom, closed shadow
roots and out-of-process frame coverage remain outside this test matrix.

## Exact application package and model smoke

Code commit `9fca73d7710aae5d51d542d054a490ce61e7859c` was rebuilt as Dev
Release 1153, `0.14.0-dev.browser-keyboard1`, non-debuggable, signed with the
existing local test key. ZIP alignment (16KiB) and APK v3 signing verified.
SHA-256: `f27240b19d676d6dc20865a3f2dc8c074883e6791cf6752c3c170cc79189d21e`,
291932681 bytes. The APK's page helper matches the committed source exactly.
The selected Xiaomi was updated with `adb -s cd4928027d76 install -r`.
Its installed base.apk hash and package version matched this artifact.

A normal on-phone ChatGPT/Codex 6.1 Sol Low chat then opened MDN's JavaScript
page, followed JavaScript reference and handed over the result. Screenshots
show the correct Reference heading/domain and You have control. The app's
own MikeBrowserTiming log records open 3163.4ms and plan 1798.0ms (click,
verification and handoff), both OK with zero CDP errors/timeouts. Total browser
backend time: 4961.4ms. PC UTC immediately before the Send tap to the final
handoff log entry: 17267.9ms. This includes the model/transport/application
work before handoff; it is not a pure model-thinking measurement and excludes
typing and any final assistant text after handoff. This different, shorter
task is not a controlled speedup comparison with the user's earlier form run.

The previous accessibility setting was enabled but not connected after the
update. It was restored through Android's visible off/on and confirmation
flow, including the normal Xiaomi countdown, while the app was running.
Final dumpsys showed a bound Hey Mike service and no crashed services.
No secure setting or restricted-setting workaround was used. Full-application
evidence is under `deep-xiaomi/app-e2e-timings.txt`, `e2e-measurement.json` and
`xiaomi-e2e-result.png`; signed APK evidence is under `delivery-1153/`.

## Hosting-phone delivery status

Only after the Xiaomi module and installed-application checks above passed,
the exact signed APK was copied with `copy_file` to
`chat:browser-1153/app-dev-debug.apk`, then to Downloads URI
`content://media/external_primary/downloads/1000235086`. Both copies completed.
Hosting metadata immediately before update was 1152. APK Manager's Install
was tapped once; the observed next screen said Installing app-dev-debug.apk
and Silent install over ADB. The following wait and fresh app-info reads did
not return, so their read-only waits were cancelled. Installation was not
replayed. Hosting installed version and post-update browser operation still
need live verification; the Xiaomi success does not fill that gap.
