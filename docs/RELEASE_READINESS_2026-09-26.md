# Dev release readiness — 2026-09-26

This is a preparation record, not a release. The source is the `dev` history
with `main` v0.13.0 merged into a preparation branch. No change was merged
into `main`, tagged, or published by this work.

## Checks completed

| Check | Result | Evidence |
| --- | --- | --- |
| Full local Gradle gate | PASS | `test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug --no-daemon`; 905 tasks, successful. |
| Runtime staging tests | PASS | `python -m unittest tools.test_prepare_runtime`; five tests. |
| Release package smoke | PASS | Xiaomi 23053RN02Y (`cd4928027d76`), Dev Release QA APK versionCode 1017, versionName `0.13.0-dev.release-qa`. |
| APK checks | PASS | `zipalign -c -p 4`, APK Signature Scheme v3, package `dev.androidagent.app.dev`, signer SHA-256 `f65c3469483d5b059543c7a67d369578bf3627a02795d77fc76a244444dda1ee`. Artifact SHA-256 `89CA6AC44632123BF5A3772B650375735E5966E5E315C756FF776E3E97089552`. Test key only. |
| In-place install | PASS | `adb -s cd4928027d76 install -r` succeeded. `firstInstallTime` remained 2026-09-23 16:24:36; versionCode became 1017. Accessibility remained enabled and bound. |
| Release runtime extraction | PASS | Five `libcodex_*.so` files are present in the installed app's `lib/arm64` directory. |
| Signed-in agent tool smoke | PASS | Existing chat on installed nondebuggable APK called `open_app` for Gym Test and `read_ui`; result reported `dev.androidagent.jevgym` in front and `source: accessibility`. No Gym data changed. |
| Manual standing rule | PASS | On the same APK, `automation_rule` created `qa-release-20260926`, dry-tested and ran it. `describe` reported `firedToday: 1`; Android notification contained `QA-rule-ran`. |
| Scheduled rule with screen off | PASS | Exact-alarm app-op was allowed. A Saturday 21:52 rule was saved and dry-tested; Android's alarm list showed an `RTC_WAKEUP` for Hey Mike. At 21:52:17, notification `QA-timer-ran` was present while display state remained OFF. Deep Doze and reboot were not exercised. The test rule repeats weekly and must be deleted after the phone is unlocked. |
| Stable updater channel | UNIT PASS | Parser rejects a Dev or missing `Package:` metadata for the prod package. Download and installer paths check the actual APK package, newer versionCode, and matching signing certificate. On-phone updater flow remains untested. |
| Direct shell UI dump | UNIT PASS | `shell` refuses a direct `uiautomator dump` before device execution and points the agent to `read_ui`. Obfuscated shell commands are outside this guard. |

## Open gates before a public release

1. Choose the target package. Current public v0.13.0 and Dev Nightly both use
   `dev.androidagent.app.dev`; Nightly codes are above 1000. A stable build of
   that package must have a code above every target installation. A first
   `dev.androidagent.app` production build installs beside Dev and does not
   migrate its private account and chat data.
2. Production signing is not configured. The QA APK above has the local debug
   certificate. Establish the protected release key and verify a signed
   candidate on a phone before claiming production readiness.
3. Test the updater on the phone with a real matching release asset, including
   rejection of an incompatible package or signer and preservation of app data
   after a valid update. Future stable release notes need a standalone
   `Package: dev.androidagent.app` line for the prod updater.
4. Complete the physical matrix below on the exact signed candidate; local
   builds and JVM tests do not prove these paths.

## Physical matrix still needed

| Area | Status | Required check |
| --- | --- | --- |
| First launch and upgrade onboarding | NOT TESTED | Fresh install, consent, sign-in, Xiaomi restricted Accessibility settings, overlay and notification handover, then upgrade with data retained. |
| Account lifecycle | NOT TESTED | Add second account, switch, continue an old thread, switch back, sign out and sign in. |
| Device tools | PARTIAL | Gym Test `open_app` and `read_ui` passed. Run full tool list with safe inputs, blocked inputs, verification, Stop, keyboard/IME and rotation lock. |
| Standing rules | PARTIAL | Manual notify and a scheduled alarm with screen off passed. Deep Doze, reboot re-arm and dedupe, allowed-package notification trigger, device-state trigger, approval refusal and Stop remain. |
| Voice and wireless ADB | NOT TESTED | Realtime voice turn; pair, reconnect and run a Wireless ADB-only tool. |
| Update installation | NOT TESTED | Matching signed asset, downgrade and signer rejection, data preservation. |

Do not run `connectedDevDebugAndroidTest` on the configured Xiaomi: it removes
the app and its private data. Use explicit `adb -s cd4928027d76` for every
phone command.
