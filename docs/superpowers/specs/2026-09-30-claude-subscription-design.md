# Claude subscription engine (on-phone) — design

Date: 2026-09-30. Status: approved by the user in chat. Branch: `feat/claude-subscription` (from `dev`).

**Changed on 2026-10-01**, at the user's request. This document is the first design and is kept as written. Since then the engine is no longer fixed for the life of a chat, both engines' models share one menu, a computer chat can run on that computer's own Claude Code, and voice works in a Claude chat (on Codex). The decisions are in `docs/ARCHITECTURE.md`, section "Claude as a full engine". The compliance rules below are unchanged.

## Goal

A user with a Claude subscription (Pro, Max, Team, Enterprise) can use Mike on the phone with that subscription. Codex stays as it is. The engine is chosen per chat and is fixed for the life of the chat.

v1 is **phone only**. It has no PC host, no API-key mode and no voice for Claude chats.

## Compliance rules (non-negotiable)

These follow Anthropic's Claude Code "Legal and compliance" page, the same basis as Hermes' official `claude-subscription-directsdk` plugin.

1. Run the **official, unmodified** `claude` binary. Never patch it (no `patchelf`, no interpreter edits). Verify its bytes against Anthropic's manifest.
2. **Never bundle** the binary in the APK or in a release. Its licence is "All rights reserved". Download it on the phone from `downloads.claude.ai` at first use.
3. The user signs in **inside `claude`** (`claude auth login`). The app relays the URL and the pasted code to that process only. The app never reads, copies, backs up, logs or uploads anything under `CLAUDE_CONFIG_DIR` (`.credentials.json`, `.claude.json`). Sign-in state comes only from `claude auth status`.
4. No `setup-token` or `CLAUDE_CODE_OAUTH_TOKEN` handling. Do not spoof user agents or headers or `CLAUDE_CODE_ENTRYPOINT`. Never use `--bare` (it ignores subscriptions).
5. Scrub `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`, `CLAUDE_CODE_OAUTH_TOKEN` and `CLAUDE_CODE_USE_*` from the child environment.
6. UI wording: "Use your own Claude subscription (runs Anthropic's Claude Code). Not affiliated with Anthropic." Do not use "Claude Code" in the app name, feature name or logo.

## Proven on a real phone (spike, 2026-09-30)

Device: Redmi Note 12, Android 15 (SDK 35), HyperOS 2.0, kernel 6.6. The test ran from a real app process: `untrusted_app`, targetSdk 35, seccomp filter active.

- Works: `nativeLibraryDir/libld_musl.so <filesDir>/claude --version`, which prints `2.1.285 (Claude Code)` in about 300 ms. The headless command `-p --output-format stream-json --verbose --tools ""` works through a CONNECT proxy and returns a real `401` from `api.anthropic.com` for a fake key. No SIGSYS.
- Fails: exec of anything in `filesDir` (EACCES, W^X). `/system/bin/linker64` as the loader fails with "Could not find a PHDR".
- DNS: there is no `/etc/resolv.conf`, so every request must use `HTTPS_PROXY` (the existing `LocalhostConnectProxy` pattern). Bun honours it.
- `CLAUDE_CODE_MAX_RETRIES` defaults to 10 with backoff. Set it to 2, or a failed request stalls for about a minute.
- `claude auth login` needs no TTY. On stdout it prints `If the browser didn't open, visit: <https URL>`, then `Paste code here if prompted >`, and reads the code from stdin. `BROWSER=true` stops it trying to open a browser itself.
- `claude auth status` prints JSON: `loggedIn`, `authMethod`, `apiProvider`, `configDirectory`.

Not yet proven: Android 16 and other kernels (`epoll_pwait2`), a real subscription login and a long chat, MCP over loopback, the built-in file tools.

## Pinned inputs

| Item | Value |
|---|---|
| Claude Code version | `2.1.285` |
| Binary URL | `https://downloads.claude.ai/claude-code-releases/2.1.285/linux-arm64-musl/claude` |
| Binary sha256 / size | `31efc4136bc678575f4c6730e248d34f89dbfea0468be1c5d012af199cd62ee8` / `232077120` |
| Manifest | `https://downloads.claude.ai/claude-code-releases/2.1.285/manifest.json` (GPG key `31DD DE24 DDFA B679 F42D 7BD2 BAA9 29FF 1A7E CACE`, verified at spike time; the app pins the binary sha256 directly) |
| musl loader package | `https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/musl-1.2.5-r12.apk`, sha256 `ac281d1e7f9e9c447c51e309317b975f48be6edaf3ab91ae73b959cf86703782` |
| Loader file inside it | `lib/ld-musl-aarch64.so.1`, sha256 `b5afeb0dcc9e22e92f1088566b0d1c1562ef567a4abebaf4b0fd14000800b8ed`, 723480 bytes, MIT licence |

Claude is arm64 only. On x86_64 the Claude option shows "Not available on this device".

## Architecture

```
UI (engine picker, Claude sign-in, download progress)
  -> AgentCoordinator (unchanged run/stop/tool/approval logic)
    -> RoutingAgentEngine (routes by chat engine: Codex local | Codex computer | Claude)
      -> ClaudeCodeEngine (:engine-claude)
           - one `claude -p` stream-json process per open chat
           - LoopbackMcpServer (:mcp-loopback) exposes the phone tools as mcp__mike__*
           - MCP tools/call  ->  EngineEvent.ToolCall  ->  coordinator  ->  answerTool  ->  MCP result
      -> ClaudeProcessHost (:runtime, AndroidClaudeHost)
           - libld_musl.so (jniLibs) + downloaded, verified binary in filesDir
           - private HOME / CLAUDE_CONFIG_DIR, CONNECT proxy with Anthropic allowlist
```

Tool calls go through the **existing** `EngineEvent.ToolCall` / `answerTool` path. The coordinator's overlay states, approvals, revoke-before-interrupt, late-call handling and trace therefore stay identical for both engines, and no coordinator refactor is needed.

## Shared contracts (already on the branch, in `core/Contracts.kt`)

- `enum class EngineKind { CODEX, CLAUDE }`
- `interface ClaudeProcessHost { status; homeDirectory; prepare(); start(args, workingDirectory, extraEnv): Process; stopAll() }`
- `AgentEngine.completeLogin(code: String): AccountStatus`, which throws by default.

The `:mcp-loopback` API, fixed so WP-C and WP-D can work in parallel (package `dev.androidagent.mcp`):

```kotlin
class LoopbackMcpServer(
    serverName: String,                                  // "mike"
    tools: () -> List<ToolDefinition>,
    call: suspend (name: String, arguments: JsonObject) -> ToolResult,
) {
    fun start(): McpEndpoint      // binds 127.0.0.1:0 only; new random bearer token per start
    fun stop()                    // closes the socket; in-flight calls get cancelled
}
data class McpEndpoint(val url: String, val bearerToken: String) {
    /** JSON for `claude --mcp-config` (type http, Authorization header). */
    fun claudeMcpConfig(serverName: String): String
}
```

## Components

### WP-A: sessions carry an engine (`:core`, `:workspace`)
- Add `ChatSession.engine: EngineKind = EngineKind.CODEX`.
- Add `SessionStore.createSession(engine: EngineKind)`, keeping the old overload as CODEX.
- `LocalSessionStore`: schema v3 adds an `engine TEXT NOT NULL DEFAULT 'CODEX'` column, migrated in place so existing chats read as CODEX.
- `WorkspaceSeeder`: also install the default skills into `<claudeHome>/.claude/skills`, using the same placeholder filling and cleanup rules as `$HOME/.agents/skills`. The seeder gets the Claude home path from a parameter and does not construct it.
- Tests: migration from v2, round trip, default CODEX.

### WP-B: runtime (`:runtime`, `tools/prepare_runtime.py`, `app/build.gradle.kts`)
- **`prepare_runtime.py`**: stage the pinned Alpine musl loader as `libld_musl.so` for **arm64-v8a only**.
  - Download the pinned apk into `.codex-work/runtime/`, verify its sha256, then extract `lib/ld-musl-aarch64.so.1` with the same safe-extract rules.
  - Verify the loader's sha256 and ELF header (arm64).
  - Fail closed on any mismatch.
  - Ship the musl MIT licence text as `assets/runtime/musl-COPYRIGHT`.
  - Extend `tools/test_prepare_runtime.py`.
- **`ClaudeRuntimeInstaller`**:
  - Download the pinned binary over HTTPS with `HttpURLConnection`, into `filesDir/runtime/claude/<version>/claude.part`.
  - Compute sha256 while streaming and check the size, then do an atomic rename to `claude`.
  - Expose progress as a `StateFlow`. Support cancel, and delete partial files on failure.
  - On start, delete old versions.
  - Check for free space (about 250 MB plus margin) first.
- **`AndroidClaudeHost : ClaudeProcessHost`**:
  - Launch with `ProcessBuilder(nativeLibraryDir/libld_musl.so, <binary>, *args)`. The binary is never exec'd directly.
  - Environment:
    - `HOME=filesDir/runtime/claude-home`
    - `CLAUDE_CONFIG_DIR=$HOME/.claude`
    - `TMPDIR=filesDir/runtime/claude-tmp`
    - `HTTPS_PROXY`/`HTTP_PROXY` and lowercase, pointing at its own `LocalhostConnectProxy`
    - `NO_PROXY=127.0.0.1,localhost`
    - `SSL_CERT_FILE` (the existing CA bundle)
    - `DISABLE_AUTOUPDATER=1`, `DISABLE_UPDATES=1`
    - `DISABLE_TELEMETRY=1`, `DISABLE_ERROR_REPORTING=1`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`
    - `CLAUDE_CODE_MAX_RETRIES=2`, `NO_COLOR=1`, `BROWSER=true`
    - Then apply the scrub list from compliance rule 5.
  - The proxy allowlist is `api.anthropic.com`, `claude.ai`, `claude.com` and `platform.claude.com`, port 443. Parameterise `NetDiagnostics`/`LocalhostConnectProxy`; Codex keeps its list.
  - `stopAll()` destroys children, force-kills after 2 s, then stops the proxy.
  - Exclude `runtime/claude-home` from backups (`data_extraction_rules` / `full_backup_content`).
- Tests: env builder (scrub list, no token vars), installer (hash mismatch deletes, size mismatch, resume after partial), allowlist.

### WP-C: engine (`:engine-claude`, new module, depends on `:core` and `:mcp-loopback`)
`ClaudeCodeEngine(host: ClaudeProcessHost) : AgentEngine`.

- **Process per chat.** `openSession(workspace, threadId, model, tools)` returns the chat's Claude `session_id`.
  - A new chat mints a UUID and starts with `--session-id=<uuid>`. A known id starts with `--resume=<id>`.
  - Arguments: `-p --input-format stream-json --output-format stream-json --verbose --include-partial-messages --replay-user-messages --model <m> [--effort <e>] --tools <builtin allowlist> --strict-mcp-config --mcp-config <json> --allowedTools "mcp__mike__*,<builtins>" --permission-mode dontAsk --setting-sources "" --system-prompt-file <file>`.
  - cwd is the chat workspace. Env: `ENABLE_TOOL_SEARCH=false`, `MCP_TOOL_TIMEOUT=600000`, `MCP_TIMEOUT=30000`.
  - A model or effort change restarts the process with `--resume` between turns.
- **Built-in tools.** The default allowlist is `Read,Edit,Write,Glob`, confined to the workspace (cwd). `Grep` and `Bash` are off unless WP-F proves them on the phone. Keep the list in one constant.
- **System prompt file.** Mike's persona, an engine-neutral port of `AGENT_INSTRUCTIONS` that does not mention OpenAI or Codex, plus the workspace `AGENTS.md` contents. Written to the chat's private dir.
- **Ids.** `threadId` is the Claude session id. `turnId` is an app-minted UUID per `startTurn`. Every event carries both, because the coordinator drops events without them.
- **`startTurn`.** Write one `user` frame.
  - Content blocks, in order: a text block with the `DeviceCapabilities` runtime context (port `deviceRuntimeContext`), then images as base64 `image` blocks, then the prompt.
  - `planModel` becomes a text instruction to agree a plan before acting.
  - `skill` becomes a `/<skill-name>` prefix.
  - Emit `TurnStarted` at once.
- **Event mapping.**

  | Claude output | EngineEvent |
  |---|---|
  | `stream_event` `text_delta` | `TextDelta` |
  | `assistant` text block | `MessageCompleted(phase="final_answer")` for the last text of the turn |
  | `assistant` `tool_use` (non-mcp) | `ItemActivity` |
  | `rate_limit_event` | `UsageChanged(limits)`, named `5-hour` / `weekly` |
  | `result` `usage` | `UsageChanged(usage)` |
  | `result` | `TurnFinished("completed" \| "failed" \| "interrupted", error)` |
  | `system/api_retry` | `Activity` |
  | process exit mid-turn | `Failure` + `TurnFinished("failed")` |

  Ignore unknown types. Tolerate extra fields.
- **Tools.** Start one `LoopbackMcpServer("mike", ...)` per engine. A `tools/call` emits `ToolCall(requestId, name without the "mcp__mike__" prefix, args, threadId, turnId)` and suspends until `answerTool(requestId, result)`. `ToolResult.imageBase64` becomes an MCP image content block. A call with no active turn gets an error result.
- **Steer.** Write another `user` frame while the turn runs. Try `priority:"next"`. If WP-F shows it is not honoured, use `priority:"now"` and merge the aborted cycle's `result` into the same logical turn. The choice is in one place.
- **Interrupt.** Send `control_request{subtype:"interrupt",cancel_queued:true}`. If no `result` arrives within 3 s, kill the process tree and emit `TurnFinished("interrupted")`. Never SIGTERM first.
- **`compact`.** Write a `/compact` user frame and wait for `compact_boundary` then `result`.
- **Account.**
  - `account()` runs `claude auth status`.
  - `login()` starts `claude auth login`, returns `AccountStatus(false, "Sign in to Claude", loginUrl=<parsed https URL>)` and keeps that process alive.
  - `completeLogin(code)` writes the code plus a newline to its stdin, waits for exit, re-reads status and emits `AccountChanged`.
  - `logout()` runs `claude auth logout`.
  - Never log stdout lines that could contain codes. Add Anthropic token shapes (`sk-ant-`) to the redactor.
- **Models.** `modelCatalog()` returns aliases `sonnet` (default), `opus`, `haiku`, with efforts `low`, `medium`, `high`, `xhigh` and `max`. Try the `initialize` control request's `models` first.
- **`skillCatalog`.** Scan `<claudeHome>/.claude/skills/*/SKILL.md` front matter.
- **`answerApproval`.** No-op, because `dontAsk` plus the allow rules never prompt. **Voice:** the engine does not implement `RealtimeVoiceEngine`.
- Tests: codec parsing against recorded JSON lines (fixtures from the spike plus the documented schema), turn lifecycle with a fake `Process`, tool bridging, interrupt fallback, login URL parsing and code relay, env and args, and redaction.

### WP-D: `:mcp-loopback` (new pure-Kotlin/Android library, no new heavy dependencies)
- A hand-rolled HTTP/1.1 server on a `ServerSocket` bound to `127.0.0.1` only. MCP Streamable HTTP: `POST /mcp` with JSON-RPC 2.0, answered with `application/json` (no SSE needed).
- Methods: `initialize` (protocol version echo, `capabilities.tools`), `notifications/initialized` (202), `tools/list`, `tools/call`, `ping`, `notifications/cancelled`.
- Return `401` without the exact bearer (constant-time compare), `405`/`404` for anything else, and a 413 above a body limit (8 MB).
- Handle concurrent requests. Cancellation cancels the call's coroutine.
- `tools/call` returns `{content:[{type:"text",text}, {type:"image",data,mimeType:"image/png"}], isError: !success}`.
- Tests: raw-socket requests covering auth, list, call with image, cancel, malformed JSON, and non-loopback bind refusal.

### WP-E: app integration (after A–D merge)
- **`AgentGraph`**:
  - Build `AndroidClaudeHost` and `ClaudeCodeEngine`.
  - `RoutingAgentEngine` routes by the chat's `engine`. Resolve it via `sessionIdOf(workspace)` and the session store at `openSession`, then remember `threadId → engine`.
  - Prefix request ids per engine so they cannot collide.
- **Accounts**: account, login, models and usage become per-engine in `AgentViewModel`.
  - The coordinator's sign-in check uses the chat's engine.
  - Replace the hard-coded strings "Starting Codex", "Codex could not finish" and "Sign in to Codex in Settings first." with engine-aware text.
- **Onboarding and Settings**:
  - A provider choice between "ChatGPT (Codex)" and "Claude subscription".
  - The Claude card shows the download state (size, progress, cancel), the sign-in button (opens the URL with `ACTION_VIEW`), a paste field that calls `completeLogin`, the signed-in state, sign-out, and the compliance notice.
  - Privacy copy is per engine, and the Anthropic policies link is `https://www.anthropic.com/legal/privacy`.
- **New chat**: picks the default engine, with a per-chat choice. The chat header shows which engine it uses.
- **Composer**: the model and effort chips come from the chat's engine. Voice and mic are hidden for Claude chats. `/compact` works on both engines.
- **Other UI**: usage for Claude chats shows the `5-hour` and `weekly` limits. The home-screen widget stays Codex-only.
- **Docs and CI**:
  - Record the decisions in `docs/ARCHITECTURE.md`, and the evidence and gaps in `PROGRESS.md`.
  - Add `:engine-claude:testDebugUnitTest` and `:mcp-loopback:testDebugUnitTest` to `.github/workflows/android.yml`.

### WP-F: device verification (Redmi `PVRWC6JJJN8P9LPR`)
- Build and install `dev` debug. The Xiaomi dialog needs an Install tap within 5 s; the user authorised tapping it.
- **Never** run `connectedDevDebugAndroidTest`. Use `am instrument` with a scoped class.
- Verify, and record results in `PROGRESS.md`:
  1. The download and verification run, and the binary sha256 on the phone matches the pinned value.
  2. The sign-in screen shows a URL.
  3. A chat turn with a fake key over the full engine returns a clean auth error, not a hang.
  4. MCP loopback: `claude` lists `mcp__mike__*` tools in `system/init`.
  5. Steer `priority` behaviour.
  6. Which built-ins work (`Read`/`Write`/`Glob`/`Grep`/`Bash`).
  7. Stop during a tool call.
- A real subscription sign-in needs the user's account, so it is left to the user and listed as an open gap.

## Error handling
- **Binary missing or unverified**: the chat cannot start and shows "Download Claude Code in Settings". There is no silent download on metered networks without consent.
- **Not signed in**: same pattern as Codex.
- **`rate_limit_event` with status `rejected`**: the turn fails with the reset time.
- **Process crash**: `Failure` event, the next turn respawns with `--resume`, and the stderr tail is redacted.
- **Protocol drift**: unknown events are ignored, the version is pinned, and `system/init.claude_code_version` is checked against the pin, with a warning on mismatch.

## Out of scope for v1
PC or computer mode for Claude, API-key mode, voice for Claude chats, multi-account Claude, a Claude usage widget, Android below 11 (minSdk is already 30), and x86_64.

## Risks
- **Billing.** Anthropic may move `claude -p` usage to a separate credit pool; the June 2026 change was paused, not cancelled.
- **Android and kernel differences.** Bun on Android 16 (`epoll_pwait2`) and seccomp changes. WP-F covers only one phone.
- **Protocol.** `priority`, interrupt `cancel_queued` and `initialize` are documented only in SDK source, so they are pinned and feature-detected.
