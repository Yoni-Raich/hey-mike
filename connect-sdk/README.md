# Mike Connect for Android

Let Mike use your app's functions through a local Android service. Your app
defines the actions, input, output and access level. Mike discovers the service,
asks the user to enable it and checks permissions before each call.

## Add the library

In your project's `settings.gradle.kts`, add the Maven repository that contains
the SDK. For a local build of this repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("/path/to/hey-mike/artifacts/connect-maven") }
    }
}
```

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("dev.heymike.connect:connect-sdk:1.0.0")
}
```

The SDK is currently built into a local Maven repository. It has not been
published to Maven Central. Run `:connect-protocol:publish :connect-sdk:publish
:connect-sdk-testing:publish` in the Mike repository to build the artifacts.

## Expose a function

```kotlin
import dev.heymike.connect.*
import kotlinx.serialization.json.*

class ClockConnection : MikeConnectService() {
    override fun MikeConnect.Builder.configure() {
        capability("get_theme") {
            title = "Read clock theme"
            description = "Returns the clock's saved color."
            access = Access.READ
            handleJson {
                val prefs = getSharedPreferences("clock", MODE_PRIVATE)
                buildJsonObject { put("color", prefs.getString("color", "blue")) }
            }
        }
        capability("set_theme") {
            title = "Change clock color"
            description = "Sets the clock to blue or white and returns its saved color."
            access = Access.WRITE
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("color", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(listOf("blue", "white").map(::JsonPrimitive)))
                }) })
                put("required", JsonArray(listOf(JsonPrimitive("color"))))
                put("additionalProperties", false)
            }
            handleJson { arguments ->
                val color = arguments.getValue("color").jsonPrimitive.content
                check(getSharedPreferences("clock", MODE_PRIVATE).edit().putString("color", color).commit())
                buildJsonObject { put("color", color) }
            }
        }
    }
}
```

Put the service inside `<application>` in your manifest. Replace the class
name with its full name in your app:

```xml
<service
    android:name="com.example.clock.ClockConnection"
    android:enabled="@bool/mike_connect_enabled"
    android:exported="true">
    <intent-filter>
        <action android:name="dev.heymike.connect.action.CONNECT" />
    </intent-filter>
    <meta-data android:name="dev.heymike.connect.binding" android:value="1" />
    <meta-data android:name="dev.heymike.connect.summary"
        android:value="Read and change the clock's appearance." />
</service>
```

The integration works on Android 11+. An app can still install on Android 7+
with its Connect service disabled below Android 11.

## Trust and operation rules

`TrustedAgents.HEY_MIKE` currently pins the local Mike dev test certificate,
not a production identity. For another build, explicitly add
`trust(TrustedAgent(packageName, certificateSha256))` in `configure` using the
verified package and signing certificate. Do not accept every caller.

Use `READ` for reads, `WRITE` for ordinary changes and `SENSITIVE` for actions
such as deleting data. Sensitive calls always require the user's approval in
Mike. UI handoffs need `ui = Ui.MAY` or `Ui.ALWAYS` and
`openUi(Intent(this@ClockConnection, YourActivity::class.java))` in the handler.
Keep questions (`requireChoice` or `requireText`) before changes. A resumed
handler runs again with the user's answer, so it must not repeat earlier effects.

Handlers run off the main thread. Use your existing repository and database
transactions. Make long work cooperative with coroutine cancellation. Stop
cannot undo a completed change. The SDK saves write request IDs before
dispatch and retains completed results for ten minutes; for older uncertain
outcomes, read actual state before another write.

Use `dev.heymike.connect:connect-sdk-testing:1.0.0` for in-process checks.
Those checks do not prove Binder caller authentication or physical-device
behavior. See the [sample clock](../samples/connect-clock),
[implementation guide](../docs/design/MIKE_CONNECT_IMPLEMENTATION.md) and
[protocol design](../docs/design/MIKE_CONNECT_SDK.md).

License: Apache-2.0. The public SDK modules have no dependency on Mike's app code.

## Developer kit and coding-agent skills

See [developer-kit/README.md](developer-kit/README.md) for the portable package,
standalone consumer example and two skills: audit an app's useful capabilities,
then implement the selected provider. Their source stays with the SDK; generated
ZIPs and the local Maven repository stay under ignored `artifacts/`.
The [publishing guide](../docs/MIKE_CONNECT_PUBLISHING.md) describes an independent
SDK release line in this repository and the remaining production release gates.
