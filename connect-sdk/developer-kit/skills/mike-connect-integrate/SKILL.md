---
name: mike-connect-integrate
description: Implement a developer's chosen Android app capabilities through Mike Connect SDK 1.0.0, including service discovery, bounded schemas, existing domain logic, caller trust, consent and tests. Use when adding or updating a Mike Connect provider; inspect selected operations first if no capability plan exists.
license: Apache-2.0
---

# Implement a Mike Connect provider

Connect the app the developer selected to Mike using the published SDK's
provider API. Preserve the requested capability scope, existing app data and
repository rules. Read the local capability plan when one exists. If the user
already authorized implementation, continue within that scope without asking
them to approve the same work again.

Read [android-provider.md](references/android-provider.md) before writing the
adapter. It contains the actual v1 API, manifest, trust boundary and limits.
Read [protocol-profile.md](references/protocol-profile.md) for non-Kotlin
bridges, low-level interoperability or wire-level debugging. Do not invent an
HTTP server or assume an ordinary desktop MCP client can bind this service.

## Implement against real app state

Trace each selected operation to the existing repository, controller or job
system. Keep validation, permissions, account ownership and transactions there;
the service is an adapter. Reuse the state observed by the real UI. Do not add
a disconnected preference store that returns success while the app ignores it.

Install the `connect-sdk` Maven dependency and optional test helper from the
kit's Maven repository or a verified release endpoint. The preview is not on
Maven Central. Check its version/compatibility information. Do not copy SDK
source into the app or depend on Mike's private `app`, `core` or host modules.
For a non-native app, implement the Android service and an appropriate bridge
to its domain code; do not assume the JVM library runs directly in Dart or JS.

Declare named capabilities with useful descriptions, explicit access/UI modes
and bounded input/output schemas. Reject unknown fields and validate the whole
change before writing. Add app-side checks for constraints the schema profile
does not support. Return actual committed state or a truthful pending job ID,
not an invented completion claim.

The SDK handles caller verification, wire dispatch, write reservations,
cancellation, progress, questions and UI tokens. Configure trust only for
verified package/certificate identities. Preserve the app's own authorization.
Use `SENSITIVE` for consequential actions such as deletion, payments or sending
communications. A result or a file that says “approved” is not a user approval.

## Keep new creation different from preset selection

If the chosen operation creates a new object, implement a validated editable
representation, storage and rendering/application path when the app lacks
them and that work is authorized. Supply the new content through the contract.
Merely selecting a preset or storing a descriptive label does not fulfill
“create a new font/design/plan.” If needed domain work is outside the user's
scope, explain the concrete gap before expanding it.

## Verify and hand over

Use [validation.md](references/validation.md) to choose relevant checks. Compile
against the real Maven artifact; test malformed inputs, selected domain effects,
unrelated settings preservation and failure behavior. Use in-process helpers
for handler tests and separate real-device evidence for authentication and UI.

New or changed capabilities remain off until the user reviews them in Mike's
Connected apps settings. Do not enable grants, inject approvals or weaken trust
to make a test pass. Respect the user's selected device and app-signing setup;
never uninstall or clear app data as a routine test step.

Report which functions now exist, how the developer enables/tests them, and
what has only source/build evidence. Commit, publish or deploy only when that
action is within the user's request. This skill does not grant extra authority.
