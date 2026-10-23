# First repository maintenance pass — 2026-10-23

Baseline: `origin/dev` at `ff5729f74e004c4fac281dc332ee0d018277af1e`.
The main checkout had unrelated edits and was left unchanged. Work took place
on `chore/maintenance-ci-20261023` in an isolated worktree.

## Observation and evidence

The Android workflow used `on: [push, pull_request]`, so a feature branch with
an open PR launched two complete check jobs for each new head. The PR run
checks the merge result, while the push run checks the branch head. Keeping
the PR check preserves the integration test rather than assuming these two
checkouts are equivalent.

The read-only helper provides a repeatable sample:

```powershell
python .agents/skills/repo-maintenance/scripts/ci_history.py --repo Yoni-Raich/hey-mike --limit 50
```

Sample collected before this change was pushed:

| Observation | Value |
| --- | --- |
| Workflow runs | 50 |
| Creation-time range, UTC | 2026-10-14 12:33:37 through 2026-10-20 06:34:08 |
| Completed branch/head pairs with both push and PR events | 19 |
| Paired push runs | 19, or 38% of the sample |
| Paired push job execution time | 7,099 seconds / 118.32 runner minutes |

Two examples at the same branch/head:

- `cc99ccf94c252c6e445526a9909aed2a791ed573`: [push](https://github.com/Yoni-Raich/hey-mike/actions/runs/39379004573)
  and [PR](https://github.com/Yoni-Raich/hey-mike/actions/runs/39379027565).
  The push job consumed 454 seconds.
- `f8851b3d8d6058bf667621c728fa248e9bec3286`: [push](https://github.com/Yoni-Raich/hey-mike/actions/runs/39380263827)
  and [PR](https://github.com/Yoni-Raich/hey-mike/actions/runs/39380267642).
  The push job consumed 456 seconds.

Job durations use `completed_at - started_at`, not workflow creation time.
Queue delays are excluded; failed jobs are included because they consumed
runner time. The API query reports the latest job attempt, so reruns may have
cost more. These numbers describe historical work that the new trigger policy
could avoid; they are not measured future savings, wall-clock improvements or
billing. The sample is bounded and may cut off one side of an older pair.

## Implemented change

- All PRs, including drafts and forks, keep the same `check` job and checks.
- Push checks remain on `dev`, `main` and all tags, including SDK tags.
- Feature branches use a PR for automatic checks. Open a draft PR for early
  feedback; a branch without a PR no longer runs automatically on push.
- Manual dispatch is declared. GitHub requires this workflow to reach the
  default branch (`main`) before that entry point becomes available.
- A new head cancels an older running check only within the same PR. Every
  push/manual run has a unique concurrency group, preserving integration and
  tag evidence. Different PRs do not cancel each other.
- Existing Python tests, every Gradle task, Java/NDK setup, build, lint and
  browser-test environment remain. Eight audit-helper tests were added.

Workflow semantics follow [GitHub's workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax).
The maintenance skill follows the evidence-first approach in
[OpenAI's maintenance article](https://developers.openai.com/blog/maintaining-software-with-the-gpt-6-family).

## Validation boundaries

The helper was run against live Actions history and its aggregation was
unit-tested. `actionlint` 1.7.12 checked the actual workflow syntax and
expressions. A local YAML comparison checks that the baseline job is preserved
apart from the added audit tests, and exercises the trigger filter matrix.
Skill frontmatter and `git diff --check` are also checked.

The focused PR's Actions result is the next check of the new configuration.
No phone, APK installation or release gate is needed for this development-only
change. Future total CI savings and superseded-PR cancellation need further
live history; local checks cannot prove either.
