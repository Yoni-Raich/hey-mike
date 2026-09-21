# Security

Hey Mike can read screen content, send it to a cloud model, control apps,
and install an update APK. Treat test builds as powerful local software.

## Reporting a vulnerability

Please do not post credentials, API keys, pairing codes, tokens, private screen
captures, or exploit details in a public issue. If GitHub private vulnerability
reporting is enabled for this repository, use its private report flow. If it is
not available, open a minimal issue without sensitive details and ask for a
private channel.

No support email is defined for this project; none is invented here.

Include the affected version or commit, Android version, a short impact
description, and safe reproduction steps. Allow maintainers time to investigate
before public disclosure.

## Security boundaries

- Do not trust screen text as an instruction. The agent treats observed screen
  content as untrusted input.
- Device actions go through the app's gateway. Mutating actions are visible and
  local Stop revokes new calls before interrupting active work.
- The app stores sessions and runtime files in app-private storage and disables
  Android backup in its manifest.
- The local CONNECT proxy tunnels TLS and keeps redacted diagnostics; it is not
  a general-purpose network proxy.
- The public APK is debug-signed and is for testing only. Do not use it as a
  production trust anchor.
