# Mike Connect v1

Mike Connect lets an Android app offer named functions to Mike. The app owns
the implementation and data. Mike owns consent, user approvals and dispatch.
There is no per-app code in Mike.

## Modules and delivery

- connect-protocol: plain JVM codec, MCP tool descriptions and a bounded JSON
  Schema 2020-12 profile.
- connect-sdk: Android service, Binder contract, capability DSL, caller
  verification, durable write reservations, cancellation, elicitation and UI tokens.
- connect-sdk-testing: in-process test host and capability conformance checks.
- connect-host: Android discovery, signer verification, catalog caching and
  bounded lazy service bindings.
- core ConnectedAppsGateway: one model tool, grants, schema checks, approvals,
  unknown outcomes and untrusted-data envelopes. Each run has its own gateway.
- app: Keystore-sealed grants, connection settings, history and developer Try.
- samples/connect-clock: a small provider used for conformance and phone tests.

The three public modules contain new Apache-2.0 code and their own LICENSE.
They have no dependency on Mike app modules. Host and app code keep the
repository license. Publish locally with:

    gradlew.bat :connect-protocol:publish :connect-sdk:publish :connect-sdk-testing:publish

This creates an ordinary Maven repository under ignored artifacts/connect-maven.
An external Android project adds that repository and depends on
dev.heymike.connect:connect-sdk:1.0.0. The protocol JAR is transitive.
No public package, tag or remote artifact has been published by this work.

## Kotlin integration

    class ClockConnection : MikeConnectService() {
        override fun MikeConnect.Builder.configure() {
            capability("get_theme") {
                title = "Read clock theme"
                description = "Returns the current clock settings."
                handleJson { repository.currentThemeAsJson() }
            }
        }
    }

Import dev.heymike.connect.*. For a typed handler, use
handle<Input, Output> with kotlinx.serialization serializable classes.
Schema.generate uses serialization descriptors and optional @Describe.
For Java, override configure(Builder), call addCapability(name, Consumer),
set explicit schemas and use handle(CapabilityHandler).

Declare the exported service with action dev.heymike.connect.action.CONNECT,
android:enabled="@bool/mike_connect_enabled", metadata
dev.heymike.connect.binding=1, and a short dev.heymike.connect.summary.
The SDK merges its UI trampoline and narrow queries for Mike's package names.
The library can be installed on API 24+, but the integration is disabled below
API 30. No network, accessibility, storage or custom signature permission is required.

## Trust and consent

TrustedAgents.HEY_MIKE currently pins this computer's dev.androidagent.app.dev
test certificate. It does not claim to identify an official production build.
A publisher must explicitly add a TrustedAgent(package, certificateSha256)
for other Mike builds or other agents. A broad debug authentication bypass is
not implemented. Provider service methods verify Binder caller identity on
every transaction, including cancel.

Connections are off by default. A user reviews the provider's capabilities,
package and signing certificate, then enables it in Settings > Connected apps.
The host pins the provider signer. Grants also bind capability name, access,
UI mode and schema hash. New functions, expanded schemas, changed UI mode or
access changes remain off until reviewed. Losing Keystore integrity fails closed.
Keystore sealing protects stored files from raw edits; it is not a claim of
protection against arbitrary compromised code running as Mike itself.

Read calls run with their connection grant. Writes run when requested by the
user, unless Ask before this change is enabled. Sensitive calls always use
the existing approval card. The model cannot pass an approval flag.
Disabling a grant cancels an in-flight host call; a completed side effect is
never reported as undone.

## Protocol profile

The wire uses MCP revision 2026-07-28 JSON-RPC request/response semantics.
Every request carries protocolVersion, clientInfo and clientCapabilities in
params._meta. The binding is Android-specific, not a standard transport that
an arbitrary desktop MCP client already understands.

Implemented methods: tools/list and tools/call; resultType complete and
input_required for one form field with a string or enumerated choice.
Only the real user's answer is sent as inputResponses. The host permits at
most two question rounds. Unsupported methods return a JSON-RPC error.
Schemas support object, array, primitive types, nullable unions, enum, const,
numeric bounds, lengths, item counts, anyOf/oneOf/allOf and local $defs refs.
Unsupported assertions/dialects and external refs are rejected.
This bounded profile is not a claim of full JSON Schema or all MCP features.

Messages are capped at 256 KiB and pre-parse depth is bounded. The model sees
up to 20,000 characters of app output. Schemas have depth, node and execution
budgets. At most four requests run per caller in a provider. The host binds
lazily with a three-second timeout, keeps at most eight bindings and retires
idle ones after a minute.

Descriptions and results remain untrusted external data for Codex and Claude.
The tool is included by the same gateway catalog used by local engines and
the computer tool relay. Compatibility still requires tests for each path.

## Reliable changes

Pass the same requestId only for identical capability and arguments.
The SDK records a durable reservation before running a write. Concurrent
duplicates cannot run the handler again. The completed result is retained.
After a process crash, an unfinished reservation reports outcome_unknown.
An id with different arguments reports request_id_conflict.
Unresolved reservations are never automatically evicted; a full ledger refuses
new writes. Completed results expire after ten minutes, so reads of actual
state remain the safest recovery for an older unknown outcome.

The host retries only a read after a lost binding, once. It never automatically
retries a write. Stop cancels the coroutine and sends the request's cancellation
to the provider. The provider must make long work cooperative and ask questions
before committing a change. Multi-step handlers remain responsible for their
own database transaction and rollback semantics.

## UI

CallContext.openUi accepts only an explicit component inside the provider's
own package. The SDK issues a random 256-bit bearer token for that stored
intent, valid once for 60 seconds. Only the authenticated Binder callback gets
the token. The trampoline consumes the token rather than trusting identity
strings in activity extras. This corrects the proposal's assumption that an
activity can authenticate its launcher with Binder.getCallingUid.
Mike claims the existing device lease before launching the trampoline.

In Dev Debug, Developer tools enables JSON Try controls. They use the same
grant and schema path; sensitive approvals, questions and UI handoffs should
be tested through a real chat. They are not silently approved by the Try UI.

## Scope

Version 1 implements the local capability connection. Remote/cloud transports,
AppFunctions adapters, general resource/blob fetching, remote UI rendering,
cross-profile access and v2 app-event automations are separate extensions.
The original proposal describes those later paths; none should be claimed
from a successful local read/write test. LumaStand is the real app integration
for this work; StreamFlix remains a separate app integration.

See PROGRESS.md for executed checks and exact installed-device evidence.
