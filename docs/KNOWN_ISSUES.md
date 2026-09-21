# Known issues

Hey Mike is a Developer Preview. The following limits are known and should
be treated as part of the current product state.

- The complete signed-in Codex chat and device-control flow is not yet proven
  on a supported phone. Login setup evidence is not the same as a signed-in
  streamed conversation.
- The x86_64 emulator app-server exits with `SIGSYS` (159). Use an ARM64 phone
  as the practical runtime target until this is fixed and re-tested.
- Physical validation is incomplete for realtime voice, Accessibility control,
  overlay visuals, Wireless ADB reconnect, recovery, and several device tools.
- Instrumentation cannot cover a live Accessibility service because it
  force-stops the app process. Accessibility checks need a separate physical
  or manual test path.
- `connectedDevDebugAndroidTest` can uninstall the app and remove private
  sign-in, sessions, and runtime files. Do not use it on a stateful phone.
- Android 13+ may require Accessibility to be enabled manually after the app is
  running. Enabling it while the package is cold can leave the service crashed.
- Some Xiaomi/HyperOS phones can refuse ADB installation without a SIM. This is
  a device security policy, not an app workaround.
- One phone supports one active agent run at a time. Local Stop prevents new
  work and interrupts active work, but completed side effects cannot be undone.
- Realtime voice is experimental. Audio routing, interruption, latency, and
  transcript quality still need physical-device validation.

See [Progress](../PROGRESS.md) for the short current status and
[Testing](TESTING.md) for evidence boundaries.
