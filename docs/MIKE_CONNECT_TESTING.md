# Mike Connect validation

Build checks and physical-device evidence are separate. A local test cannot
prove Xiaomi background behavior, real caller certificates or a working chat.
See PROGRESS.md for checks that actually ran.

## Local checks

Run the protocol, SDK and gateway tests, then build and lint the app:

```text
gradlew.bat :connect-protocol:test :connect-sdk:test :core:test :workspace:test
gradlew.bat :app:assembleDevDebug :app:assembleDevRelease :app:lintDevDebug :connect-sdk:lintDebug :connect-host:lintDebug
```

The SDK's Robolectric service test stubs package certificate lookup to check
dispatch and cancellation. It does not prove Android's signing boundary.
The ledger tests cover an unfinished write after process restart, cached results
and caller isolation. Gateway tests cover grants, schema changes, sensitive
approvals, uncertain writes, input forms, output limits and live revocation.

## Device setup

Select a device in state `device` from `adb devices -l`. Always use
`adb -s <selected-serial>`. Install with `install -r` to preserve existing data.
Keep the exact artifact hashes, package metadata and signing certificates.
If Xiaomi refuses installation, the user must resolve its security prompt.

Install the built Mike app, the signed LumaStand app and the sample clock.
In Mike, first verify both connections appear disabled. The user then reviews
the known clock capabilities and enables the connections in Connected apps.
Test the normal user controls, rather than changing grant files.

Do not use Gradle's connected test tasks on a phone with Mike data. They
uninstall packages at the end. Do not instrument Mike while checking its live
accessibility service; instrumentation force-stops the app.

## Real chat checks

Use a new synthetic test chat and keep its trace locally:

1. Use `connected_apps list` and `describe`. Check real service discovery and
   the LumaStand schemas, with no app-specific code added to Mike.
2. Read LumaStand's theme. Set a supported color, then read it back. Check its
   visible settings and clock. Open the clock through `open_clock`.
3. Turn off `set_theme` using the user control. A chat call must be denied
   before dispatch. Turn the connection off and on; the denied capability and
   Ask before this change setting must keep their values.
4. On the sample, read `writes`, then call `set_theme` twice with identical
   arguments and the same requestId. The counter must rise only once. Reusing
   that id with different arguments must fail with `request_id_conflict`.
5. Call `choose_color`. Check the real question and real selected answer.
   Test refusal without inventing an answer.
6. Call `sensitive_test`. Refuse the approval once, then approve once. Only
   the accepted call may return `approved: true`. This is a simulation.
7. Call `slow_read`, press Stop during its wait and verify cancellation.
   A new call after Stop must not dispatch under the stopped run.
8. Repeat a background read while another app is visible. Record whether a
   cold provider bind and UI handoff work on this specific Android build.

For cross-UID denial, install only the sample's test APK and run its runner
directly (using the deliberately selected serial):

```text
adb -s <selected-serial> shell am instrument -w -e class dev.heymike.connect.sample.CallerSecurityTest dev.heymike.connect.sample.test/androidx.test.runner.AndroidJUnitRunner
```

This test runs under the sample UID and calls the installed LumaStand service
with a valid protocol request and a forged approval flag. Binding-level access
must throw SecurityException and the action must return an untrusted-caller
protocol error. The SDK test sample has no trust grant in LumaStand.

Keep personal screen captures and raw traces out of Git. Record failed and
skipped checks, including any missing physical-device evidence. A successful
local read/write does not validate the computer relay, voice or app-event
automation paths.
