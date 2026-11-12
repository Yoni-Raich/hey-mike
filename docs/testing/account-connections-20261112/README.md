# Account connections in ordinary Mike settings

2026-11-12. Source base: `2a8092ee` (Manager removal). Connector source was
extracted from `side/mike-manager` into a neutral `core.connections` package.
No Manager board, notification reader, team or scheduled mail check was added.

Settings > Account connections lists account capabilities for ChatGPT/Codex
and Claude Code. Each connection starts off and can be disabled even while
unavailable. The Accounts button opens the existing sign-in settings.

## Checks

On Windows, with `ANDROID_HOME` set and no `local.properties`:

```powershell
.\gradlew.bat test :app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug --no-daemon --console=plain --continue
python -m unittest tools.test_prepare_runtime tools.test_codex_runtime
git diff --check
```

All passed. JVM XML reports contain 3,091 case executions over all tested
variants, zero failures/errors and 34 skips. Repeated Android build variants
are included in that count; it is not a count of unique tests. Python: 24 passed.

The added checks cover default-off grants, provider separation, restored grants
requiring fresh metadata, unavailable switches, a late refresh after disabling,
unknown/write-only tools, Stop and in-flight result suppression, fresh service
annotations, ephemeral Codex reads, original thread catalogs, Claude metadata
probes without model messages, and revocation during a Claude startup handshake.
The host environment test confirms inherited settings cannot enable connectors.
The Claude fixture preserves the original Gmail `search_threads` behavior:
`readOnly=true` can coexist with `openWorld=true`. That hint describes external
data, not permission to write; see the [MCP annotation reference](https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/).
Codex keeps the original bridge's stricter `openWorldHint` filter.

## UI fixture run

A task-owned clean API 35 x86_64 AOSP ATD emulator used a Pixel 7 profile.
The Dev Debug APK and its androidTest APK were installed manually with `-r`.
Only this class was invoked with `am instrument`:

```text
dev.androidagent.app.ui.AccountConnectionsUiTest
Time: 7.684
OK (3 tests)
```

The fixtures never start a model, sign in, or read a real account. They cover
both provider cards and the explicit off switch, disabling an unavailable saved
grant, and the empty state's Accounts/Refresh actions. Screenshots were inspected
for readable text and intact controls. The component is rendered directly in the
fixture, not a live signed-in chat. The emulator does not prove the on-device
Codex runtime, which remains unsupported on this x86_64 image.

| Provider fixture | Empty accounts |
| --- | --- |
| ![Two provider cards with default-off example connections](providers.png) | ![Empty accounts with sign-in and refresh instructions](empty.png) |

## Remaining gaps

- No live Gmail, Calendar or other account data was read.
- No physical phone install, ARM64 runtime, or voice account read was tested.
- The supplied phone snapshot reported Manager build 1214. No phone update was
  performed in this task.
- No release APK or full release gate was produced.
