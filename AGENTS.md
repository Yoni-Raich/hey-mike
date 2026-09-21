# AGENTS.md — Hey Mike

Read `PROGRESS.md` and `docs/ARCHITECTURE.md` before work. Write short, clear English. Goal: on-device Codex chat with session files, wireless self-ADB, visible device control, live steering, local stop.

## Build / test (Windows uses `gradlew.bat`)

- Quick: `./gradlew :app:assembleDevDebug`; unit: `./gradlew :core:test`; scoped: `./gradlew :device-tools:test :core:test`
- Full gate (evidence for releases): `./gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug --no-daemon` + `python -m unittest tools.test_prepare_runtime` + `git diff --check`
- CI (`android.yml`) runs only `:core:test :app:assembleDevDebug :app:lintDevDebug` on Java 17 — passing CI does not equal full gate.
- `:app:lintDevDebug` is expected to pass. A new lint error is a real failure: fix it, or suppress that one issue with `tools:ignore` and a stated reason (as for `QUERY_ALL_PACKAGES` in the manifest). Never add a lint baseline or weaken CI to go green.
- `preBuild` runs `tools/prepare_runtime.py`: stages pinned Codex app-server ARM64+x86_64 binaries, patches helper lookup to `libcodex_codemode.so`, fails closed on upstream layout change. Every staged native file must be named `lib*.so`: Android extracts nothing else from a release (non-debuggable) APK, so a helper named otherwise works in debug builds and vanishes in release builds. Runtime sources live under `research/runtime/`, never in the APK directly.
- Flavors: `dev` (`dev.androidagent.app.dev`) for daily work, `prod` for release. `version.properties` is the single version source (`app/build.gradle.kts` reads it).

## Architecture (see `docs/ARCHITECTURE.md`)

- Modules (`settings.gradle.kts`): `:app` (Compose UI, wiring) `:core` (contracts, coordinator, run state) `:engine-codex` (app-server JSON-RPC) `:runtime` (binary staging, process supervision, CONNECT proxy) `:workspace` (sessions/files) `:adb` (pairing identity, discovery, transport) `:a11y` (accessibility bridge) `:device-tools` (sole agent device gateway) `:overlay` (floating card, stop) `:voice` (realtime audio).
- Rules: UI observes app events, never raw Codex JSON. All device ops go through `device-tools`; arbitrary shell = visible-control mode with overlay states Starting/Thinking/Running/Controlling/Stopping/Done/Error. One active run per phone. Stop revokes new tool calls first, then interrupts — never claim completed side effects were undone.
- On-device skills: canonical sources `app/src/main/assets/agent_stack/skills/*/SKILL.md`, installed at startup to app-private `$HOME/.agents/skills`; engine discovers via `skills/list`. No hard-coded slash list.

## Device

- Any Android device or emulator shown by `adb devices -l` with state `device` may be used for scoped work. Before every device operation, select the target serial deliberately and pass it as `adb -s <serial>`; never rely on a default or guess when multiple devices are attached. Do not use entries in `unauthorized` or `offline` state. The designated physical test phone is the former Nothing A059 target; its Work profile (user 11) and Private space (user 10) notes apply only to that device.
- **Never run `./gradlew connectedDevDebugAndroidTest` against a device you care about.** AGP installs the app and the test APK, runs, and then **uninstalls both**. That deletes the app's private data with it: the Codex sign-in under `CODEX_HOME`, every session, and the staged runtime binaries, which then have to be downloaded and prepared again. Installing the test APK also **disables the accessibility service**, and Android 13+ restricted settings mean it cannot be re-enabled from adb - `settings put secure enabled_accessibility_services` is silently rejected and reads back `null`. This was learned by doing it: 2026-09-08.
- To run instrumented tests without losing that state: `adb -s <serial> install -r` the app APK, `adb -s <serial> install -r` the androidTest APK, enable accessibility by hand, then invoke the runner directly with `adb -s <serial> shell am instrument -w -e class <fqcn> <pkg>.test/androidx.test.runner.AndroidJUnitRunner`. Nothing is uninstalled and the permissions survive.
- Instrumented tests that need the accessibility service must `assumeTrue` on it rather than fail. There is no programmatic way to grant it, so a hard failure would only report that a human has not touched the phone.
- **Instrumented tests can never see a live accessibility service.** `am instrument` force-stops the package to take its process, which unbinds the service; the manager marks it crashed and does not rebind while instrumentation owns the package. Verified with a 45s per-test wait: every test still skipped. `A11yServiceHandle` is process-local, so no process arrangement fixes this. Cover the a11y path some other way.
- Enable the accessibility service only **after** the app process is running. Enabling it against a cold package leaves it in `Crashed services` and it never binds. Start `MainActivity`, wait, then flip the setting.
- Xiaomi/HyperOS devices refuse `adb install` without a SIM card (`INSTALL_FAILED_USER_RESTRICTED`, raised by `com.miui.securitycenter`). Not something to work around - it is a device security control.
- x86_64 emulator app-server exits `SIGSYS` (159) — not a valid runtime target; Q8 is. Emulator Compose fixture tests do not prove real chat, ADB, or voice.

## Workflow

- `dev` = integration, `main` = validated releases. Conventional commits, `git commit -s` with your own model identity only (`Signed-off-by: <model>`; verify with `git show -s --format=%B HEAD`). Work only in assigned paths; read-only tasks never write files.
- Preserve `android_ai_agent_mvp_brief.md`. Record decisions in `docs/ARCHITECTURE.md`, real command evidence + untested gaps in `PROGRESS.md` (build/install alone prove nothing). PRs use `.github/pull_request_template.md` (Change / Checks / Remaining gaps + UI evidence for visual changes).
- Release: bump `versionCode`+`versionName` in `version.properties` first, then build, verify APK metadata (package, version), zipalign + APK Signature Scheme v3 (debug key = test-only), install that exact signed APK on a phone and run one task that uses a device tool (release APKs are not debuggable and Android treats them differently), then tag `vX.Y.Z` with matching `artifacts/hey-mike-X.Y.Z.apk` (`android-agent-X.Y.Z.apk` up to v0.7.3). Never replace a published asset.
- Never commit `local.properties`, `*.keystore`/`*.jks`, pairing codes, credentials, tokens, or raw personal screen captures (`artifacts/`, `**/build/`, logs already gitignored).
