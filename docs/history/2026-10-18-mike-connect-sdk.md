# Mike Connect v1 evidence — 2026-10-18

Implementation worktree: `C:/Users/SHIRA/Documents/AI/hey-mike-connect-sdk`,
branch `feat/mike-connect-sdk`, based on `origin/dev` c33b168.
The original design worktree and main dirty checkout were preserved.

## Implemented

The independent Apache-2.0 protocol, SDK and testing modules expose a bounded
MCP message/schema profile over authenticated Android Binder. The host discovers
services, pins publishers and maintains lazy bindings. Each run's connected_apps
gateway enforces sealed per-function grants, real sensitive-action approval,
elicitation, cancellation and uncertain-write handling. Settings owns consent
and activity history. Existing engines and the computer relay share this catalog.

The final review fixed a mismatch between string RPC job IDs and Binder cancel
IDs. Job completion also removes tasks cancelled before execution. A service
test now checks the running handler's cancellation, rather than just the host's
cancel flag. Other regression checks cover repeated write IDs, conflicting
arguments, Parcel size limits and preserving denied/ask-first capability grants
when a connection is switched off and back on. New or expanded functions stay off.

LumaStand is a separate normal child chat in its own project. Its real settings
repository and StateFlow are used for get_theme, partial set_theme and open_clock.
The parent did not change the child's application implementation. The sample
clock supplies harmless approval/question/cancellation/idempotency fixtures.

## Build commands and results

Java: Eclipse Adoptium 21.0.12.8. Android SDK: the existing local SDK.
An isolated Gradle user home was used, in-process Kotlin, two workers, and
Chrome enabled for web tests. No global build-cleaning hook was invoked by the
parent worktree.

Final frozen command (PASS, 9m38s):

```text
gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug :connect-sdk:lintDebug :connect-host:lintDebug :app:assembleDevDebug :samples:connect-clock:assembleDebug :samples:connect-clock:assembleDebugAndroidTest --no-daemon --console=plain -g <isolated-gradle-home> -Pkotlin.compiler.execution.strategy=in-process --max-workers=2
python -m unittest tools.test_prepare_runtime
git diff --check
```

- Full XML totals: 298 suites, 2,958 cases across variants, zero failures/errors,
  34 skips. Counts include repeated cases in different build variants.
- Runtime preparation Python: 16 passed. Error messages in its output are
  expected malformed-input fixtures; the suite ends with OK.
- Protocol: 6 cases. SDK: 6 cases per variant, including 4 Robolectric service
  cases and 2 durable-ledger cases. Gateway: 12 cases. New cases have no skips.
- App lint: 0 errors, 21 warnings; voice: 0/2; SDK: 0/1; host: 0/1.
  No baseline or lint policy was weakened.
- LumaStand: 39 JVM cases, zero failures/errors/skips; debug/release lint has
  zero errors. Its signed minified APK keeps the service and UI trampoline.

Earlier checkpoints also passed the full gate. The final command above ran after
the cancellation, consent and Parcel fixes. No implementation source changed
after that command started; only documentation and ignored evidence were added.

Raw build logs are local in the Windows temp folder with prefix
`mike-connect-`. Generated evidence is under ignored
`artifacts/connect-validation/`, including `build-evidence.json`,
`packaged-apks.json`, signatures, badging and the refused install record.

## Artifacts

Mike package: `dev.androidagent.app.dev`, versionCode **1160**,
versionName **0.14.0-dev.connect-sdk1**.

| Artifact | SHA-256 |
|---|---|
| `artifacts/hey-mike-0.14.0-dev.connect-sdk1-dev-debug.apk` | `c6ba96a612d0a359eea97a99d7fccf541c8551089c0b092ec180db726d9dcc87` |
| `artifacts/hey-mike-0.14.0-dev.connect-sdk1-test-dev-release.apk` | `4821b033d3f8ce8733d417a3f053dc6d4a40b2b8d5c8478c8f5a8dd327b978e2` |
| `connect-sdk-1.0.0.aar` | `56c4136ea3697e3ae6096e1c79decdb215ec53c426b37b165b90cb8b9de0de05` |
| `connect-protocol-1.0.0.jar` | `cf770e9b14905751a712ef330eb76bb78e042607a1efd443a8f597b6e6c745fb` |
| `artifacts/mike-connect-sdk-1.0.0-local.zip` | `2542e6e6619be464546c2828a7b70c1c238363d209393dc267726d6ea5a540aa` |
| LumaStand `artifacts/LumaStand-1.7.1-signed.apk` | `05e640e9a51dce212231c4064075d78a5417781837eefa3ceef65dec3d7ac9d7` |

Mike's test certificate SHA-256 is
`f65c3469483d5b059543c7a67d369578bf3627a02795d77fc76a244444dda1ee`.
The non-debuggable Dev Release has 16 KiB native alignment and a verified v3
signature. This is a test-signed artifact, not a production release.

LumaStand package is `com.lumastand.app`, version **1.7.1**, code **10**,
certificate `c876e92c16e93f541da13300c8cfbed4c451d0b71322e203b5dd7804b3090a24`.
Its existing signing key was preserved. Exact child evidence is in
`C:/Users/SHIRA/Documents/AI/LumaStand/artifacts/VALIDATION-1.7.1.md`.

The local Maven repository is `artifacts/connect-maven`. The ZIP includes its
three modules, source JARs, the quickstart and Apache license. Archived artifact
hashes were checked against the published local files. The ZIP (174,925 bytes)
was copied with copy_file to the hosting phone's Downloads. This transfer does
not install anything on the separate USB Xiaomi. No remote Maven publication,
production tag or public release asset was created.

## Physical-device status and remaining checks

The deliberately selected USB device was the Xiaomi 23053RN02Y in `device`
state. The hosting phone is a separate device. A normal `adb -s <selected>
install -r` attempt for the then-current LumaStand 1.7.0 was refused by
Xiaomi's security screen:

```text
INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
```

The install was not bypassed or retried without the user present. A question
is pending asking the user to be at the Xiaomi to approve its installation UI.
The selected Xiaomi still has Mike 1156 / 0.14.0-dev.browser-pin and no
LumaStand package. No new APK was installed and no application data was cleared.

Real service discovery, background behavior on Xiaomi, caller certificates
across Android UIDs, actual approval/question cards, Stop from a live chat,
UI handoff and settings appearance have **not** been verified on hardware.
Robolectric stubs certificate lookup for service behavior tests; it is not
physical authentication evidence. The separate sample's CallerSecurityTest is
built but has not been run on a phone. No Mike instrumentation or Gradle
connected test task was used.

Use docs/MIKE_CONNECT_TESTING.md for the prepared real-chat and denial checks.
Voice and computer-relay live flows also remain untested. v2 app events,
resource/blob fetching, remote transports and AppFunctions adapters are outside
this local v1 implementation, as stated in the implementation guide.

## Follow-up: Nothing installation, 18:52–19:01 local

The user then asked to install both apps directly on the hosting Nothing for
their own test. A059 appeared briefly in the PC's ADB inventory, then disconnected.
Both artifacts were delivered with copy_file to the phone's Downloads:
LumaStand 1.7.1 and the exact assembled Dev Debug Mike 1160. copy_file returned
complete for both. Its byte count is the reconstructed file size; no independent
network-byte measurement was made, so this is not delta-efficiency evidence.

Opening the media content URIs selected the user's APK Manager. Its Install
control completed LumaStand's update, verified with apps_settings app_info.
Mike's self-update then interrupted the phone backend. The installation action
was not repeated. Once the same A059 returned in the ADB inventory, the parent
verified the model, both installed versions and both installed base.apk hashes:

- Mike `dev.androidagent.app.dev`: 1160 / 0.14.0-dev.connect-sdk1,
  `c6ba96a612d0a359eea97a99d7fccf541c8551089c0b092ec180db726d9dcc87`.
- LumaStand `com.lumastand.app`: 10 / 1.7.1,
  `05e640e9a51dce212231c4064075d78a5417781837eefa3ceef65dec3d7ac9d7`.

Both match the built artifacts. Mike MainActivity was reopened. The next trusted
phone context independently reported Mike 1160, accessibility connected and
Connected apps: none enabled. No data was cleared or package uninstalled. The
agent did not enable any Connect grant. The user is to review LumaStand's
capabilities and enable the connection before their manual test. This proves
installation on Nothing, not successful SDK calls or UI handoff. The earlier
Xiaomi installation and hardware-test gap remains separate.
