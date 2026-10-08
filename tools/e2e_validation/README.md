# Xiaomi validation helpers

Run only on a selected disposable/test phone. First inspect `adb devices -l`;
pass `--adb <absolute-adb.exe>` and `--serial <selected-device>` to every helper.
No default device, offline/unauthorized use, clear-data, uninstall, restricted
grant workaround or automatic uncertain replay.

- `collect.py snapshot`: scoped app/exit/crash evidence and completion manifest.
- `collect.py watch --seconds 600 --stop-file <local-marker>`: UID-scoped app and
  system-event capture. Create the marker after tests to finish normally.
  Missing/incomplete completion metadata means partial capture.
- `runner.py state`: read-only debug receiver; requires DUMP permission.
- `runner.py tap-idle --label <observed-label>`: idle-only semantic action.
- `runner.py prompt --prompt-file <UTF8-file> --case-id <unique-id>`: exact app-IME
  delivery, stored draft oracle, checkpoint before Send and IME restoration.
  Refuses overwrite of drafts/checkpoints. Non-screen cases may start with
  accessibility off; cases needing it must check the state explicitly.
- `runner.py wait --case-id <existing-id> --seconds 45`: observe, export the
  exact turn and check pairing. A pairing PASS is not an E2E case PASS.
- `measure_ui.py --samples 20 --out <new-folder>`: opt-in debug warmed drawer
  open/close timing, separate raw gfxinfo per interval and counts of failed draws.
- `fixture/`: native QA app with Unicode input, saved-value oracle, rebuilt
  controls, delayed UI and clearly simulated negatives. Build separately; do
  not bypass a device install restriction. Simulated permission failure does
  not prove real Android permission handling.

During active model turns use raw screenshots, trace and the read-only receiver.
Never run uiautomator then: it temporarily suppresses the service. Use the app's
normal CLEAR_TOP/SINGLE_TOP launch flags when returning to an existing activity.
When a Send or Stop outcome is uncertain, inspect its existing checkpoint and
trace; never restart the case automatically. Physical Stop clicks require a
fresh observed control and an in-flight case; cancelled traces retain prior
steps, not a promise to undo them.

Raw traces/screens/logs stay in ignored local `artifacts/`, with synthetic data.
See `docs/testing/e2e-findings-fixes-20261008/RESULTS.md` for actual evidence,
versions, harness mistakes and remaining work.
