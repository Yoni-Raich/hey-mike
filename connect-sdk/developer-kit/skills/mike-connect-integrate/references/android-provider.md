# Android provider API — SDK 1.0.0

## Dependency and service

Use the Maven repository included with the developer kit or a verified SDK
release repository. Artifact coordinates are:

```kotlin
implementation("dev.heymike.connect:connect-sdk:1.0.0")
testImplementation("dev.heymike.connect:connect-sdk-testing:1.0.0")
```

`connect-protocol` is a transitive JVM dependency. SDK installation requires
API 24+, Connect requires API 30+. Java bytecode is version 17, and the preview
artifacts use Kotlin 2.1.0. For explicit JSON schemas, the consumer does not need
the Kotlin serialization compiler plugin; the JSON library is transitive.

A concrete service (the example uses actual preferences; a real app normally
delegates to its existing repository):

```kotlin
import dev.heymike.connect.*
import kotlinx.serialization.json.*

class AppConnection : MikeConnectService() {
    override fun MikeConnect.Builder.configure() {
        capability("get_settings") {
            title = "Read display settings"
            description = "Returns the saved display accent, blue or amber."
            access = Access.READ
            outputSchema = accentOutput()
            handleJson {
                val value = getSharedPreferences("display", MODE_PRIVATE)
                    .getString("accent", "blue") ?: "blue"
                buildJsonObject { put("accent", value) }
            }
        }
        capability("set_accent") {
            title = "Change display accent"
            description = "Save the display accent. Other settings stay unchanged."
            summary = "Change the app's display accent."
            access = Access.WRITE
            ui = Ui.NONE
            inputSchema = accentOutput()
            outputSchema = accentOutput()
            handleJson { args ->
                val value = args.getValue("accent").jsonPrimitive.content
                check(getSharedPreferences("display", MODE_PRIVATE).edit()
                    .putString("accent", value).commit())
                buildJsonObject { put("accent", value) }
            }
        }
    }
}

private fun accentOutput() = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", buildJsonObject {
        put("accent", buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("blue", "amber").map(::JsonPrimitive)))
        })
    })
    put("required", JsonArray(listOf(JsonPrimitive("accent"))))
}
```

The default input is an empty object rejecting extra fields. Set an explicit
`outputSchema` for inspectable contracts. `handle<Input, Output>` can derive
schemas from serialization descriptors when both classes are `@Serializable`;
that path needs the consumer's serialization plugin matching its Kotlin version.
Java can override `configure(Builder)`, call `addCapability(name, Consumer)`,
set explicit JSON schemas and register `handle(CapabilityHandler)`.

Add the service under `<application>` using its fully qualified class name:

```xml
<service
    android:name="com.example.app.AppConnection"
    android:enabled="@bool/mike_connect_enabled"
    android:exported="true">
    <intent-filter>
        <action android:name="dev.heymike.connect.action.CONNECT" />
    </intent-filter>
    <meta-data android:name="dev.heymike.connect.binding" android:value="1" />
    <meta-data android:name="dev.heymike.connect.summary"
        android:value="Read display settings and change the display accent." />
</service>
```

The SDK supplies the API-level gate and UI trampoline. Connect does not need
internet, accessibility, broad storage permission or ADB to execute app logic.
The app still needs any normal permissions its selected operation uses.

## Trust is separate from user consent

Every Binder transaction verifies the caller's UID, package and signing
certificate. `TrustedAgents.HEY_MIKE` in this preview pins:

```text
package: dev.androidagent.app.dev
certificate SHA-256: f65c3469483d5b059543c7a67d369578bf3627a02795d77fc76a244444dda1ee
```

This is a dev test identity. Do not present it as official production trust.
For an additional publisher-verified build use
`trust(TrustedAgent(packageName, certificateSha256))` in `configure`. This
**adds** to the preview's default list; it does not replace the dev caller.
That additive API and the bundled test default are a production release
decision. Do not ship a public production trust policy by blindly adding a
certificate. Never accept every UID/package or trust an identity string from
request arguments. Certificate fingerprints are public; private signing keys
never belong in the integration or kit.

Separately, Mike pins the provider's signer and stores user-approved grants
per capability. New capabilities and changed name/access/UI/schema bindings
need review. The model cannot enable grants. `SENSITIVE` uses Mike's approval
flow; ordinary writes honor the user's Ask first setting. Preserve any stricter
policy in the app itself.

## Schemas and limits

Capability names match `[a-z][a-z0-9_]{1,47}`. Titles are nonblank, at most 60
characters; descriptions are nonblank, at most 1000; summaries at most 240.
Use at most 64 capabilities. `timeoutMs` is 1000–60000, default 15000.

The bounded schema profile supports `type`, `properties`, `required`,
`additionalProperties`, `items`, `enum`, `const`, `minimum`, `maximum`,
`minLength`, `maxLength`, `minItems`, `maxItems`, `anyOf`, `oneOf`, `allOf`,
`description`, `title`, `default`, `$schema`, `$defs` and local `$ref`.
Schemas have a 32,000-character cap, depth 8 and 200 nodes; validation also has
an execution budget. Unknown keywords such as `pattern`/`format` are rejected.
Only the 2020-12 dialect and local definitions are supported.

Use `additionalProperties:false` and explicit size bounds. Perform business
validation before committing any change. Wire JSON is at most 262144 bytes.
The host limits combined structured/text result data to 19000 characters before
its envelope; aim below 16000. v1 does not provide general resource/blob fetch,
remote transport, event subscriptions or cross-profile access.

## Threading, questions and effects

Handlers run off the main thread. Use the app's normal IO/transaction path and
switch to `Dispatchers.Main.immediate` only where its state/UI contract needs it.
Keep long work cooperative with cancellation and don't swallow cancellation as
a successful result. Stop does not roll back a completed effect.

The SDK reserves a write request durably before dispatch. Identical request IDs
cannot run concurrently; completed results live for ten minutes. An unfinished
reservation reports `outcome_unknown`, and changed arguments for an existing ID
produce `request_id_conflict`. A full unresolved ledger refuses new writes.
Use the domain operation's transaction and external idempotency key where needed.
The host retries a lost read once and does not automatically retry writes.

`CallContext` provides `requestId`, `origin`, `userApproved`,
`progress(fraction, message)`, `requireChoice(key, question, options)` and
`requireText(key, question)`. Questions rerun the handler with an answer: ask
before effects, validate answers and avoid side effects before asking. A field
inside app input cannot authorize itself by being named `approved`.

For UI, declare `ui = Ui.MAY` or `Ui.ALWAYS` and use
`openUi(Intent(this@AppConnection, YourActivity::class.java))`. The explicit
component must belong to the provider's own package. The SDK returns a random,
one-use, 60-second token. Mike opens the trampoline while holding the screen
lease. Returning a token proves a UI request, not visible screen completion.
Do not call `startActivity` from a background service as a workaround.

Use `ConnectException(code, message)` for a truthful app failure; keep error
messages short and free of private data. Returning a successful result with a
fictional saved value is not error handling.
