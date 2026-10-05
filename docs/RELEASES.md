# Releases

Hey Mike releases are Developer Preview artifacts until production signing
and complete phone E2E are established.

## Current public artifact

The latest published artifact is v0.14.0:

- flavor and label: `prod`, `Hey Mike`;
- package: `dev.androidagent.app`;
- versionCode: `1019`;
- APK: `hey-mike-0.14.0.apk`;
- SHA-256: `abc894688e9303c002c62f47a9618d547023d4a05328eb06b89681d9dd78dce9`;
- signing: APK Signature Scheme v3 with the local Android debug key.

This is test-only signing. It is installable for local testing, not a production
release. The public release page is
[v0.14.0 on GitHub](https://github.com/Yoni-Raich/hey-mike/releases/tag/v0.14.0).
v0.10.0 was the first release named Hey Mike.

Assets are named `hey-mike-X.Y.Z.apk` from v0.10.0; earlier releases used
`android-agent-X.Y.Z.apk`. v0.6.1 to v0.7.2 cannot control the phone: their
release APKs install without the code-mode helper. v0.7.3 is the first
published APK with device control.

## Check the release APK on a phone

A release APK is not debuggable, and Android treats it differently from the
debug builds used during development (for example, it extracts only `lib*.so`
native files). Before publishing, install the signed candidate itself on a
phone, confirm every staged `lib*.so` is in the app's `nativeLibraryDir`, and
run one short task that uses a device tool.

## Version rules

Before a release, update both `versionCode` and `versionName` in
`version.properties`. The tag, APK asset name, and embedded APK version must
match. Never replace a published asset with a different file.

The in-app updater uses separate channels. The `dev` package
(`dev.androidagent.app.dev`) reads the `dev-nightly` prerelease and requires a
higher versionCode. The `prod` package (`dev.androidagent.app`) reads the latest
normal release. Its release notes must contain a standalone
`Package: dev.androidagent.app` line; otherwise the updater refuses the asset.
The downloaded APK must also have that package, a higher versionCode, and the
installed app's signing certificate before the installer opens. Keep the
metadata, APK manifest, and actual signing certificate aligned.

Public releases use **Prod Release**, package `dev.androidagent.app`, as the
published v0.14.0 APK does. Dev is a separate app and nightly channel. Do not
publish a Dev APK as the normal release. Version 0.15.0 uses code 1100 and
the same certificate as v0.14.0, so it updates the regular app in place.
It does not move private account or chat data between the two packages.

Release candidates should be built from the validated `main` history. Keep
`dev` builds for integration and testing. A flavor named `prod` does not by
itself provide production signing or production readiness.

## Validation recipe

```powershell
.\gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug --no-daemon
.\gradlew.bat :app:assembleProdRelease :app:lintProdRelease :app:testProdReleaseUnitTest --no-daemon
python -m unittest tools.test_prepare_runtime
git diff --check
```

For a candidate APK, also verify its package and version with Android build
tools, check zip alignment, and verify APK Signature Scheme v3. Record the
exact artifact hash and every untested hardware or account path. A release
record must say clearly when a physical device was not tested.

## Signing and distribution

The current public APK uses a local debug key. Users should treat it as a
testing artifact and install it only when they accept that trust boundary.
Production distribution needs a separately managed release key, a documented
key-protection process, a production package choice, and fresh phone E2E proof.
Do not commit keystores, credentials, pairing codes, or tokens.
