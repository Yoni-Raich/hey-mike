# Standalone preferences provider

This small Android app reads and changes a saved display accent. Its Activity
observes the same preferences as its Connect service. It has no dependency on
Mike's app/host modules. Source and example configuration are Apache-2.0.

Requirements: Android SDK platform 35, JDK 17+, Gradle 8.9, normal Google/Maven
dependency access. The example pins AGP 8.7.3 and Kotlin 2.1.0 to match the
SDK's validated consumer toolchain. Set `ANDROID_HOME` or a private
`local.properties`; don't commit local paths. A wrapper is intentionally not
bundled; use an installed compatible Gradle or your existing project's wrapper:

```text
gradle -p /path/to/kit/examples/preferences-provider :app:assembleDebug
```

The default Maven path is `../../maven`, relative to this project. When running
from the source tree instead of an extracted kit, pass
`-PmikeConnectRepository=/absolute/path/to/hey-mike/artifacts/connect-maven`.
Both read and write have bounded schemas; the write returns the saved value.
The tiny UI updates immediately if a connected call changes the accent.

Installing is optional and requires the developer's chosen test device.
After installation, open Mike's Connected apps, review this app and enable
`get_settings`/`set_accent`. Ask Mike to read the accent, change it to amber,
then read it back. The preview trusts the kit's documented dev test caller.
The code does not enable a connection or widen that default trust.
