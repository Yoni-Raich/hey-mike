# Claude computer session continuity

Base: dev `6692067402dba86841ef35c20fdad450166243bf`.

## Behavior

- List local Claude Code transcripts alongside Codex conversations, grouped by
  their computer and working folder. Keep engine and computer in every identity.
- Import visible history and resume the original Claude session ID. Do not copy
  credentials, change the transcript, or silently replace a missing session.
- Read transcripts on the computer; send bounded metadata/text projections over
  SSH. Images, tool payloads and account metadata stay there. Respect the custom
  Claude configuration directory and reject symlink/path escapes.
- Refresh external messages without deleting local messages, notes, attachments,
  queued work or drafts. Repeated refresh must be idempotent. Never sync over an
  active Mike turn or write while a detected external writer holds the session.
- Release only Mike's idle Claude process before a requested Windows Desktop
  handoff. Invoke the official `claude --desktop --resume <id>` in the signed-in
  desktop session. Preserve the same ID on return. Report a launch request
  separately from verified Desktop UI and a real resumed model turn.

## Checks

- Transcript fixtures: metadata, Unicode, content blocks, tool-only entries,
  sidechains, parent branches, incomplete tails, large records, malformed input,
  missing sessions, configuration directories and path/symlink rejection.
  Fork ancestors may retain the source session ID. Compaction uses its summary
  and `parentUuid`; it must not reconnect `logicalParentUuid` into a cycle.
- Engine/computer identity, duplicate import, refresh deduplication, local draft
  preservation, writer refusal, idle release and active-run refusal.
- Existing Codex listing/import/fork behavior and project grouping regressions.
- Scoped tests, app build/lint, full repository gate, exact-head CI.
- Read-only inventory against real Pc transcripts, then isolated QA history and
  Windows Desktop handoff where the live desktop permits it. Never stop an
  existing user session or change its transcript for validation.
- Record physical-device and real-model gaps explicitly; no installation or
  production release is implied by this implementation request.
