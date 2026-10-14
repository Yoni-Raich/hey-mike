# Claude computer tool search — 2026-10-14

## Change

`ClaudeCodeEngine.startOnComputer` now passes `COMPUTER_CHAT_ENV`. It keeps
the MCP connection and call timeouts, but does not set `ENABLE_TOOL_SEARCH`.
The computer's environment, user settings and Claude Code defaults decide
tool search. Phone chat processes still use their existing `CHAT_ENV`.

Mike's SDK MCP server still lists all its tools and answers calls through the
same gateway. Approval, interrupt and Stop handling did not change. Existing
processes keep the environment they started with; the new environment applies
when a computer chat process is started or restarted.

The launch test checks the environment actually passed to `ClaudeComputer`,
not only the constant. It checks both timeouts and the absence of the forced
tool-search value. The phone launch test checks that its old value remains.

## Controlled Windows measurement

Two fresh native CLI sessions used Claude Code 2.1.296, Opus 5.5, xhigh effort,
the same project folder, Mike system-prompt addition, greeting and runtime
context. Both had the same 36 Mike SDK MCP tool definitions and the computer's
normal settings/connectors. Only the forced tool-search environment differed.

The probe used the same stream-json initialization and MCP response shape as
`StdioMcp`. It supplied the tools' actual `input_schema` definitions. Tool
responses were synthetic read-only fixtures: no physical phone operation.
An initial probe supplied the wrong schema wrapper, exposed no usable Mike
tools and was discarded. The numbers below are from the corrected probe.

| First greeting | Forced `false` | Setting omitted |
| --- | ---: | ---: |
| Total input, including cache reads/writes | 98,411 | 30,077 |
| Output | 103 | 106 |
| Mike tool calls | 0 | 0 |
| `/context` | 98.4k | 30.1k |
| MCP tools loaded upfront | 52.2k | Deferred |
| System tools loaded upfront | 21.8k | 5.3k |

Input fell by 68,334 tokens, or 69.4%. This includes built-in tools that the
CLI also defers when tool search is active. Both requests used prompt caching;
this is context/input evidence, not a subscription-quota or cash-cost ratio.

In the corrected session, `ToolSearch` discovered `mcp__mike__device_status`,
and the CLI called the SDK server once and consumed its fixture answer.
Another turn discovered `read_ui`; the fixture held its answer. An interrupt
with `cancel_queued=true` received a success acknowledgement. The pending
fixture was returned as stopped, and the CLI ended with `aborted_streaming`
in 15ms. No tool was dispatched after the Stop fence. That timing is a local
fixture measurement, not a phone Stop-latency claim.

## Checks and artifact

- `:engine-claude:testDebugUnitTest :remote:testDebugUnitTest :core:test`: pass.
- `test assembleDevDebug assembleDevRelease assembleDevDebugAndroidTest
  :voice:lintDebug :app:lintDevDebug --no-daemon --max-workers=2`: pass.
  2,667 unit executions, zero failures/errors, 32 existing skips.
- `python -m unittest tools.test_prepare_runtime`: 15 tests, pass.
- `git diff --check`: pass.
- Dev Debug: `dev.androidagent.app.dev`, code 1147,
  `0.14.0-dev.tool-search1`, built with version overrides. Debug key, APK
  signature v2 and 16KiB ZIP alignment verified. Dev Release is unsigned.
- Debug APK: 331,619,930 bytes; SHA-256
  `c3fb84a8828e39fe49cec8ff9f69e7f977057a8807b3483aa1f1aa10518f60ae`.
- `copy_file` completed to the chat. This reports reconstructed file size;
  network wire bytes were not measured. `install_apk` returned
  `backend_unavailable` / `adb_not_connected` before connection. Once Wireless
  ADB connected, installation was requested using that same phone file. The
  self-update lost its response. A later independent `apps_settings(app_info)`
  confirms the chat phone is on code 1147 / `0.14.0-dev.tool-search1`.
  The install was not replayed and no data was cleared. After restoring
  Wireless ADB, `shell` resolved the installed path with `pm path` and hashed
  `base.apk` with `sha256sum`. Its hash matches the built APK exactly. This
  establishes installation of that artifact, not a full installed-app flow.

Private logs and the probe are outside Git under
`%TEMP%/hey-mike-tool-search-20261014`. Build outputs are in the managed,
ignored build-storage bucket. No raw session or personal screen data is
included in this change.

## Remaining gaps

An installed-app Claude PC greeting/device action/Stop,
Linux live behavior and a signed non-debuggable release install are pending.
Tool search adds a discovery round trip when a task needs a deferred tool.
Users who explicitly disable tool search retain that setting. Existing
sessions can keep previously discovered tools/history, so a fresh chat is
needed for a clean before/after measurement.
