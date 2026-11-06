---
name: worktree-lifecycle
description: Use this skill whenever an agent creates, builds in, finishes with, or cleans up a Git worktree or a temporary build of the Hey Mike repository - starting parallel work, reviewing a PR in its own checkout, making a baseline or release build, or when the user asks to tidy up, free disk space, or close and delete worktrees. It says where worktrees and build output live, how temporary builds behave, and above all how to close a worktree and delete its build safely without losing anyone's work.
---

# Worktrees and temporary builds

This is a development skill for agents working on this repository. It is not
an on-device skill.

Every worktree you open and every build you start must be closed by the end of
the task that needed it, or handed over by name in your final report. A
worktree that nobody remembers is a leak: it keeps gigabytes of build output
and hides uncommitted work that the next agent may delete by mistake.

The helper script does the checks for you:

```powershell
# What exists, what is safe to close, what is orphaned
powershell -NoProfile -File .agents\skills\worktree-lifecycle\scripts\worktree.ps1 status

# Close one worktree and delete its build output
powershell -NoProfile -File .agents\skills\worktree-lifecycle\scripts\worktree.ps1 remove -Path <worktree> -DeleteBranch
```

Run it from the main checkout, never from inside the worktree you remove.

After changing the helper, run its safety tests on disposable repositories:

```powershell
python .agents/skills/worktree-lifecycle/scripts/test_worktree.py
```

## 1. Opening a worktree

Open one only when the work really needs a separate checkout: parallel work
while another agent uses the main checkout, a PR review, a baseline build, or a
release build from a fixed commit. A small edit on the current branch does not
need one.

- **Location.** One home per tool, nothing else:
  - Claude Code: `<repo>\.claude\worktrees\<name>` (the `EnterWorktree` tool
    uses it).
  - Codex: `%USERPROFILE%\.codex\worktrees\<name>`.
  - Never create sibling folders such as `Documents\AI\hey-mike-<x>`, and never
    put a worktree inside another worktree.
- **Name.** `<topic>-<yyyymmdd>`, for example `pin-subtabs-20261019`. The
  date tells the next agent how old it is.
- **Base.** Fetch first and branch from the remote branch you will merge into:
  `git fetch origin` then
  `git worktree add -b <type>/<topic> <path> origin/dev`.
  Use `--detach` only for a read-only review or a build of a fixed commit.
- **Write down what you opened.** Keep the worktree path, branch and purpose
  in your notes for the task. You will need them to close it.

## 2. Building in a worktree

- A worktree has no `local.properties` (it is gitignored). Do not create one.
  Set `ANDROID_HOME=C:\Users\SHIRA\AppData\Local\Android\Sdk` for the Gradle
  process instead, and pass `--no-daemon`.
- Build output does **not** go to `<worktree>\app\build`. A Gradle init script
  (`%USERPROFILE%\.gradle\init.d\hey-mike-build-storage.gradle`) sends every
  module of every worktree to
  `<main checkout>\build\<bucket>\<module>`, where `<bucket>` is the first 16
  hex digits of SHA-256 of the lower-cased worktree path. Each bucket has a
  `.hey-mike-build.json` marker naming its worktree. `worktree.ps1 status`
  shows each worktree's bucket size.
- One build per worktree at a time. A second one fails with "Another Hey Mike
  build uses this worktree". `--configuration-cache` is refused.
- Buckets share a 5 GB limit. When a build starts or ends over the limit, the
  oldest **finished** buckets of other worktrees are deleted. So:
  - an APK you still need can disappear when someone builds elsewhere. Copy
    it out right after the build (see below);
  - a build that was killed or interrupted never records "finished", so its
    bucket is **never** removed automatically. Only closing the worktree, or
    the orphan cleanup in section 5, frees it.

### Temporary files from a build

- APKs, logs and test reports you keep for the task go to
  `%TEMP%\hey-mike\<task>\`. Use `artifacts\` only for release work, where
  `AGENTS.md` asks for it.
- Never write logs or copies into the repository root, into a worktree's
  root, or into a worktree home such as `.claude\worktrees\`.
- Delete `%TEMP%\hey-mike\<task>\` when the task ends, unless the user wants a
  file from it; then say where it is.

## 3. When to close a worktree

Close it in the same task, as the last step, when any of these is true:

- its PR is merged, or the branch is pushed and the user does not need the
  checkout any more;
- it was a review, a baseline build or a release build, and the result is
  reported;
- the task was abandoned or failed, and nothing in it is worth keeping (if
  something is, commit and push it first, or ask).

If you must leave a worktree open (work continues in another session, a build
is still running), say so in your final report with its path and why.

## 4. Closing a worktree (the important part)

### Who may close what

- A worktree **you opened in this task**: close it yourself, following the
  steps below.
- Any other worktree, including ones that look old or merged: **ask the user
  first**. List the path, branch, what `status` says, and its build size.
  Another agent may be using it right now.
- The main checkout (`Documents\AI\Android-agent-use`): never.

### Steps

1. `cd` to the main checkout. Windows cannot delete the folder your shell is
   in.
2. Make sure nothing is lost. In the worktree:
   - `git status --short` must be empty, or the changes committed;
   - `git rev-list HEAD --not --remotes` must be empty, or the branch pushed.
   If work would be lost and you cannot push it, stop and ask the user.
3. Make sure no build runs in it. `status` shows `BUILD RUNNING`. Wait for it
   to end; never delete a bucket under a running build.
4. Copy out anything you still need from its bucket (an APK, a report).
5. Run:

   ```powershell
   powershell -NoProfile -File .agents\skills\worktree-lifecycle\scripts\worktree.ps1 remove -Path <worktree> -DeleteBranch
   ```

   The script refuses on uncommitted work, commits that exist on no remote, a
   locked worktree, a running build, or a failed Git check. It also refuses
   when the shell is inside the worktree, including a subfolder. It validates
   the bucket marker and holds its build lock, then runs `git worktree remove`.
   Only after Git succeeds and both the folder and registration are gone does
   it delete the bucket. A Git refusal never falls back to a recursive folder
   delete. With `-DeleteBranch`, it deletes the local branch only if Git says
   it is merged (`git branch -d`).
6. Check the result: the path is gone, `git worktree list` no longer shows it,
   and its bucket is gone from `<main checkout>\build\`.
7. Delete `%TEMP%\hey-mike\<task>\` if you made one.

`-DiscardChanges` removes a worktree that still has uncommitted or unpushed
work. Use it only after the user said, in this task, that this work may be
lost.

### Without the script

If the script cannot run, do the same by hand, in this order:

```powershell
git -C <worktree> status --short               # must be empty
git -C <worktree> rev-list HEAD --not --remotes  # must be empty
# find the bucket: build\<id>\.hey-mike-build.json must name <worktree>
git worktree remove <worktree>
# Stop on ANY error. Confirm the folder and Git registration are both gone.
# Recheck the bucket marker and take its .active.lock before deleting it.
Remove-Item -LiteralPath <main checkout>\build\<id> -Recurse -Force
git branch -d <branch>                          # only if merged
```

### Locked files

On Windows, `git worktree remove` or the folder delete can fail with "Permission
denied" or "Directory not empty". A process still has files open there.

1. Close any shell, editor or explorer window in that folder, and `cd` out.
2. A Gradle or Kotlin daemon may hold files. Run `worktree.ps1 status`. Only if
   **no** worktree shows `BUILD RUNNING`, run `.\gradlew.bat --stop` from the
   main checkout. It stops every Gradle daemon of that version, including
   other agents' builds, so never run it while any build runs.
3. Run `remove` again only if Git still lists the worktree. If Git dropped
   its metadata but left the folder, inspect its remaining files first. Delete
   that exact leftover folder and bucket only after confirming they contain
   no work to keep and no build runs; then run `git worktree prune`.
4. If it still fails, stop and tell the user the path and the error. Do not
   kill processes you cannot identify.

### Branches

- `-DeleteBranch` and `git branch -d` remove only merged local branches.
  Squash-merged branches look unmerged to Git; delete them with
  `git branch -D` only after checking the PR is merged, and only for branches
  you created in this task.
- Never delete a remote branch unless the user asks. GitHub can delete it on
  merge.

## 5. Finding and cleaning leftovers

Run `worktree.ps1 status` when you start a task that builds, and when the user
asks to tidy up or free disk space. It reports:

- every registered worktree with uncommitted files, commits on no remote,
  merged or not, bucket size, and running builds;
- **build buckets with no registered worktree** - output of worktrees that
  were deleted without the script. Delete one with
  `Remove-Item -LiteralPath <bucket> -Recurse -Force` after checking its marker
  names a worktree that no longer exists;
- **folders in the worktree homes that Git does not know** - often deleted
  worktrees whose folder stayed, sometimes Codex scratch folders. These can
  hold uncommitted files. Show the list to the user and delete only what they
  approve.

For worktrees that are merged and clean but were not opened by you, propose a
list to the user and close each with `remove` after they agree.

## Never

- Never delete a worktree folder with `Remove-Item` or `rm -rf` while Git
  still lists it. Use `git worktree remove`, so Git's metadata goes too.
- Never use `git worktree remove --force`, `git clean -fdX`, `git reset --hard`
  or `git branch -D` on work you did not create without the user's OK.
- Never delete `<main checkout>\build\` as a whole, or a bucket of a worktree
  that still exists and may be building.
- Never leave a worktree, a bucket, or a temp folder from your task behind
  without naming it in your final report.

## Report line

Add this to the task's completion report:

```text
Worktrees: <closed: path (branch) | left open: path - reason | none>
Temporary builds: <deleted | kept: path - reason | none>
```
