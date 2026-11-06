# Provider validation

Choose checks that establish the behavior your integration changes. Build a
small independent consumer against the Maven artifact at least once: a passing
Mike monorepo build can hide accidental dependencies on unpublished app code.

## Contract and domain tests

Create the actual service with Robolectric or an instrumentation context and
use `dev.heymike.connect.testing.ConnectTestHost(service).call(name, arguments)`
from a coroutine. The helper returns the operation result object, so inspect
`isError` and `structuredContent`. `ConnectConformance.verify(definitions)`
checks descriptor validity and wire round trips. Do not put test-only trust
or routes into the shipping app to make a handler test run.

Test useful invariants, for example:

- invalid fields, IDs, types, sizes and combined patches cause no partial write;
- a valid call changes the same persisted state the app UI actually observes;
- unrelated settings, account ownership and existing permissions survive;
- each important write has a read that verifies the real result;
- cancellation, interrupted work and unknown results do not become false success;
- questions happen before effects, and a resumed handler does not repeat them;
- creation produces a real saved/renderable object rather than a preset label;
- output stays bounded even at maximum page or object size.

## Android boundary

Inspect the merged manifest, release package/version, service enabled resource,
discovery action, binding metadata and UI trampoline. Check a minified build so
R8 does not hide a release-only failure. Existing app signing keys must stay
private and unchanged for a data-preserving update.

A helper call is **not** a cross-UID Binder test. On the selected test device,
verify a trusted caller works and an untrusted UID/certificate is rejected.
Try a disabled/revoked connection, a newly added capability, an ordinary read,
a write and any sensitive approval/elicitation/UI flow in scope. The human
reviews and enables grants in Mike; the agent never edits the grant store.

Use the target's normal permissions and device policy. Do not bypass a
restricted install or accessibility setting. Install updates without clearing
data. Do not run a connected instrumentation task that uninstalls the main app
on a device containing important sessions; invoke an appropriate runner only
when that test is authorized and preserves the device's state.

## Completion evidence

Record the exact artifact/version and summarize relevant checks. Keep distinct:
source inspection, unit tests, compilation/package metadata, installation,
real UI, Binder authentication and a full model-driven task. If a physical
test is unavailable, say which flow remains unverified. Don't claim all engines,
voice, cloud paths or production trust from one successful local call.
