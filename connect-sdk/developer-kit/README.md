# Mike Connect developer kit

Connect your Android app to Mike through named, typed functions. Your app keeps
its own data and business rules. Mike handles discovery, user grants and calls.

This kit is a **local developer preview**. The SDK libraries are version 1.0.0;
the kit has its own preview version in `release.json`. The current default
trusted caller is a Mike **dev test build**, not an official production identity.
No public Maven Central endpoint or production certificate is supplied here.

## Start with your coding agent

Point any agent that can read Markdown and your source tree at one of these files:

1. [mike-connect-app-audit](skills/mike-connect-app-audit/SKILL.md): inspect your
   app and propose useful, feasible capabilities with source evidence. This is
   a read-only workflow. It also identifies product features your app would need
   before it can offer the requested action.
2. [mike-connect-integrate](skills/mike-connect-integrate/SKILL.md): implement
   the selected capabilities using the SDK, your existing repositories and
   bounded schemas. Covers tests, caller trust, cancellation and UI handoff.

Example prompts:

> Read `skills/mike-connect-app-audit/SKILL.md`. Inspect this app and recommend
> which functions Mike should be able to use. Do not change code.

> Read `skills/mike-connect-integrate/SKILL.md`. Implement the read and write
> capabilities selected in our capability plan. Keep existing app settings.

You can read the files directly without installing them. For skill discovery,
copy each complete skill folder, including its `references` and `assets`, into
your agent's documented project or user skill directory. Codex supports
`.agents/skills/`; Claude Code supports `.claude/skills/`. The instructions use
plain files and do not depend on a Mike account, a particular agent API, or a
hard-coded workspace path. Keep the kit's license with redistributed copies.

## Use the libraries

The extracted kit includes `maven/`, a local Maven repository. Add its absolute
path to the consumer project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri(providers.gradleProperty("mikeConnectRepository").get())
            content { includeGroup("dev.heymike.connect") }
        }
    }
}
```

Set `mikeConnectRepository=/absolute/path/to/extracted-kit/maven` in your local
Gradle properties, or pass `-PmikeConnectRepository=...`. Windows paths can use
forward slashes. In your app:

```kotlin
dependencies {
    implementation("dev.heymike.connect:connect-sdk:1.0.0")
    testImplementation("dev.heymike.connect:connect-sdk-testing:1.0.0")
}
```

The protocol JAR is transitive. Do not copy the service or AIDL implementation
into your app. Normal Android/Kotlin dependencies still come from Google and
Maven Central; the kit is not a complete offline Android build environment.

The library installs on API 24+, while Connect works on API 30+. Use Java 17
bytecode and a compatible Kotlin compiler (the current artifacts use Kotlin
2.1.0). Explicit JSON schemas with `handleJson` do not need the serialization
compiler plugin in the consumer. Typed `handle<Input, Output>` classes do.

See the [Android integration reference](skills/mike-connect-integrate/references/android-provider.md)
for service code, manifest entries and the trust boundary. The
[preferences example](examples/preferences-provider/README.md) is a small
standalone project that resolves these Maven artifacts without Mike's app modules.

## Package contents

| Path | Purpose |
| --- | --- |
| `maven/` | Three public libraries, POM/module metadata, source JARs and checksums |
| `skills/` | Two portable developer-agent skills and their references |
| `examples/preferences-provider/` | Standalone Android provider example |
| `release.json` | Exact SDK/profile version, binary hashes and preview limits |
| `MANIFEST.sha256` | SHA-256 of every payload file |
| `LICENSE` | Apache-2.0 for this kit, public SDK modules and example |

This kit does not include the Mike app, host internals, Android runtime binaries,
signing keys, local settings, user data or physical-device logs. The public
libraries have no dependency on Mike's AGPL app modules. Ordinary external
dependencies retain their own licenses.

Maintainers: the source of this directory is `connect-sdk/developer-kit` in the
Mike repository. Generate a new kit with `python tools/package_mike_connect.py
--bundle-version 1.0.0-preview.1`. Never replace an already shared version.
