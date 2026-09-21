---
name: repo-structure-guard
description: Use this skill whenever an agent works inside the Hey Mike repository: before coding, reviewing, adding files, building, releasing, cleaning, or creating another skill. It explains the exact repository layout, module ownership, safe file paths, generated-output rules, branch flow, release gates, and evidence rules so agents do not scatter files or damage another worktree.
---

# Hey Mike repository guard

This is a development skill for agents that work on the repository.

It is **not** an on-device skill and must not be copied into the APK. Keep this
file under `.agents/skills/repo-structure-guard/SKILL.md`. On-device skills
belong only under `app/src/main/assets/agent_stack/skills/<name>/SKILL.md`.

The goal is simple: keep the repository easy to understand, keep ownership
clear, and leave no build output, private data, or temporary agent files in the
source tree.

## First read and first checks

Before changing anything:

1. Read `AGENTS.md`.
2. Read `PROGRESS.md`.
3. Read `docs/ARCHITECTURE.md`.
4. Run `git status --short --branch`.
5. Run `git worktree list --porcelain`.
6. Check the current branch and the target branch before editing.

If another worktree has changes, leave it alone. Do not clean, reset, delete,
or reformat another agent's worktree. Keep unrelated changes out of your
commit, even when they are convenient to fix.

## Repository map

The repository root contains product source, build configuration, public docs,
and agent instructions. Do not add random files to the root.

| Path | Owns | Keep here |
|---|---|---|
| `app/` | Compose UI, app lifecycle, wiring, packaging | App code and app tests/resources |
| `core/` | Contracts, coordinator, run state, cancellation | Neutral business contracts and tests |
| `engine-codex/` | Codex app-server JSON-RPC | Protocol and event mapping |
| `runtime/` | Runtime staging and process supervision | Runtime code and tests |
| `workspace/` | Sessions, files, artifacts, session folders | Workspace code and tests |
| `adb/` | Pairing identity, discovery, transport | ADB code and tests |
| `device-tools/` | The only agent device gateway | Device actions and tests |
| `a11y/` | Optional accessibility observation/control | Accessibility code and tests |
| `overlay/` | Floating card, status, local Stop | Overlay code and tests |
| `voice/` | Realtime microphone, speaker, and voice lifecycle | Voice code and tests |
| `docs/` | Architecture, setup, testing, releases, history | Human-facing project docs |
| `tools/` | Build and runtime preparation helpers | Deterministic developer tools |
| `research/runtime/` | Pinned runtime sources and reproducibility notes | Runtime source and notes, not APK output |
| `.agents/skills/` | Skills for agents developing this repository | Development skills only |
| `app/src/main/assets/agent_stack/skills/` | Skills shipped inside the Android app | On-device skills only |
| `.github/` | CI, templates, Dependabot, PR rules | Repository automation and contribution metadata |
| `third_party/` | Verified third-party license files | License texts and attribution evidence |

The module list is defined by `settings.gradle.kts`. If a module is added or
removed, update the architecture documentation in the same change.

## Root files and allowed placement

Keep these kinds of files at the root when they already exist:

- `AGENTS.md`: repository rules for development agents.
- `README.md`: short public entry point.
- `PROGRESS.md`: current evidence and current gaps only.
- `CHANGELOG.md`, `LICENSE`, `NOTICE`, `SECURITY.md`, `CONTRIBUTING.md`, and
  `CODE_OF_CONDUCT.md`: public project files.
- `android_ai_agent_mvp_brief.md`: preserve it; do not move or delete it.
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
  `version.properties`, `gradlew`, and `gradlew.bat`: build entry points.

Put long explanations in `docs/`, not in `AGENTS.md` or random new root files.
Put historical evidence in `docs/history/` with a date in the filename. Keep
the root `PROGRESS.md` short enough to read during every task.

Tests stay with their owner module, normally under `src/test/` or
`src/androidTest/`. Do not create a new top-level `tests/` tree for Android
module tests.

## Developer skills versus app skills

There are two different skill systems:

### Skills for repository agents

Path: `.agents/skills/<skill-name>/SKILL.md`.

Use this for instructions about coding, review, repository structure, release
work, or agent workflow. It is source-control content and can be pushed with
the project.

### Skills shipped to the Android app

Path: `app/src/main/assets/agent_stack/skills/<skill-name>/SKILL.md`.

Use this only when the app itself must install the skill into the user's app
workspace. It changes APK contents and must follow the runtime skill catalog
rules in `docs/ARCHITECTURE.md`. Never put a repository-maintenance skill here
just because it is also written in `SKILL.md` format.

## Change ownership rules

Use the smallest owner surface that solves the task:

- UI or lifecycle wiring belongs in `app`.
- Shared contracts belong in `core`.
- Codex protocol behavior belongs in `engine-codex`.
- Runtime binary staging or process behavior belongs in `runtime` and
  `tools/prepare_runtime.py`.
- Sessions and durable files belong in `workspace`.
- Pairing and transport belong in `adb`.
- Phone actions belong in `device-tools`; do not add a second device gateway.
- Accessibility behavior belongs in `a11y`.
- Floating controls and local Stop belong in `overlay`.
- Audio and realtime voice belong in `voice`.
- Documentation-only changes belong in `docs/` or the named public root file.

Do not solve a module problem by copying code into another module. If a new
cross-module contract is needed, define it in `core` and record the boundary
in `docs/ARCHITECTURE.md`.

## Files that must never be committed

Generated, local, private, or downloaded files stay out of Git:

- `**/build/`, `.gradle/`, `.kotlin/`
- `local.properties`
- `artifacts/` and APKs produced for local release checks
- `.codex-work/`, `.codex-remote-attachments/`, and `Temps_and_logs/`
- `research/runtime/downloads/` and staged runtime binaries
- `*.log`, `__pycache__/`, `*.pyc`
- `.idea/`, local settings, screenshots, and raw personal captures
- `*.keystore`, `*.jks`, `.env*`, tokens, credentials, pairing codes, and
  device serials

Use a temporary directory outside the repository for experiments. Do not use
`git clean -fdX`: ignored files can include runtime caches, local settings,
release evidence, and user data.

## Safe work flow

For a normal change:

1. Confirm the worktree is clean or record the user's existing changes.
2. Create or use a focused branch. `dev` is integration; `main` is the
   validated release branch.
3. Make the smallest change in the owning path.
4. Run focused tests first.
5. Run `git diff --check` and inspect `git diff --name-only`.
6. Update `PROGRESS.md` with real command evidence and untested gaps when the
   change affects behavior, testing, or release state.
7. Commit with a conventional message and `git commit -s` using the current
   model identity. Verify the `Signed-off-by` trailer.
8. Push the focused branch and use the repository PR template.

Use `apply_patch` for tracked file edits. Do not rewrite unrelated formatting.
Do not mix a feature, a cleanup, a release bump, and a skill rewrite in one
unexplained commit unless the user explicitly asks for that bundle.

## Device and evidence rules

Before every device command, select a valid device deliberately. Use
`adb -s <serial>` for every ADB operation. Never rely on the default device,
and never use an `unauthorized` or `offline` target.

Separate these claims:

- compile/build proves compilation and packaging only;
- unit tests prove the tested logic only;
- APK install proves installation only;
- Compose fixtures do not prove a real phone UI;
- emulator success does not prove ARM64 phone success;
- a physical run proves only the exact flow and device tested;
- release signing and production readiness require their own evidence.

Never turn a build result into a claim that chat, login, ADB, voice, visual
quality, or device control worked on a phone.

## Standard checks

Use the smallest useful check first:

```powershell
.\gradlew.bat :app:assembleDevDebug --no-daemon
.\gradlew.bat :core:test --no-daemon
.\gradlew.bat :device-tools:test :core:test --no-daemon
```

For release evidence, run the full gate from `AGENTS.md`:

```powershell
.\gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug --no-daemon
python -m unittest tools.test_prepare_runtime
git diff --check
```

The CI workflow is smaller than the full gate. Do not claim the full gate from
a green CI run. `:app:lintDevDebug` is expected to pass; do not make it pass by
weakening CI, adding a lint baseline, or changing unrelated source files. Fix
the finding, or suppress that one issue with `tools:ignore` and a stated
reason.

Never run `connectedDevDebugAndroidTest` on a phone whose app data matters.
That flow can uninstall the app, delete sign-in and sessions, and disable the
Accessibility service. Use the protected install-and-run procedure in
`AGENTS.md` when a selected instrumentation class is really needed.

## Release rules

Only prepare a release from validated `main`:

1. Update `versionCode` and `versionName` together in `version.properties`.
2. Run the full release gate.
3. Verify APK package, flavor, version, alignment, signature scheme, and SHA-256.
4. Keep the APK in ignored local `artifacts/` only when release work needs it.
5. Use a new tag `vX.Y.Z`; never replace a published asset.
6. Write release notes that state signing type, exact checks, and all untested
   hardware/account/runtime paths.
7. Keep debug/test-signed APKs clearly marked as test artifacts.

Do not create a production claim just because `assembleDevRelease` succeeds.
Do not commit keys or upload an APK that was built from a dirty or unvalidated
branch.

## Agent completion report

End every repository task with this short handoff:

```text
Changed: <files or "none">
Checks: <commands and PASS/FAIL>
Evidence: <what the checks prove>
Not tested: <important gaps>
Commit/PR: <hash or URL>
Generated files left: <none, or exact ignored paths>
```

If the task is blocked, report the exact blocker and stop. Do not leave a
half-created directory, copied APK, debug log, or guessed documentation path
behind.
