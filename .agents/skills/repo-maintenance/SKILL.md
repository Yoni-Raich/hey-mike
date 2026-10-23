---
name: repo-maintenance
description: Find and implement evidence-based maintenance in Hey Mike when asked to audit unused code, stale experiments, flaky tests, CI cost, or agent instructions. Use for maintenance requests, not every code change.
---

# Evidence-based repository maintenance

This is a development skill under `.agents/skills/`, separate from the skills
shipped in the APK. Follow the repository guard and worktree lifecycle rules.
Use the current integration branch as the baseline; the main checkout may be
old or contain another task's work.

## Choose a useful first change

Start with the requested area, or one small, measurable maintenance task when
the user leaves the area open. Gather a few candidates and compare expected
benefit, confidence, affected owners and validation cost. Finish the strongest
small change before expanding. Keep uncertain findings as proposals.

Choose evidence to match the task:

| Area | Useful evidence |
| --- | --- |
| Unused code | Repository-wide references, manifests/resources, reflection and serialization, Git history, external consumers and runtime use when available |
| Stale experiments | Current configuration, shipped behavior, indirect dependencies, owner intent and operational fallback or kill-switch roles |
| Flaky tests | Failing and passing runs at the same revision, failure logs, a bounded reproduction and a rerun after the fix |
| CI waste | Recent completed runs, event/head pairs, actual job durations and unchanged required checks |
| Agent instructions | One observed bad instruction and the original task, then an authorized A/B replay from the same snapshot/model with only that instruction changed |

No search hit alone proves code is unused. Missing runtime or owner evidence
is a gap, not proof of non-use. Keep safeguards and public contracts when their
purpose is unclear. Follow the session's deletion/approval rules before removal.
For instruction comparisons, report task quality, calls, tokens, time and cost
when measured; do not replay tasks that would repeat real side effects.

## Read-only CI sample

From the repository root, with GitHub CLI signed in:

```powershell
python .agents/skills/repo-maintenance/scripts/ci_history.py --repo Yoni-Raich/hey-mike --limit 50
python .agents/skills/repo-maintenance/scripts/test_ci_history.py
```

The helper only reads Actions history. It groups completed push/PR runs by
branch and head SHA and sums job execution time for the paired push runs,
including failed work. Queue time is excluded. Only the latest job attempt is
available through this query, so reruns may have consumed more time.

A PR checks the merge result; its paired push checks the branch head. Treat
the pair as a possible trigger optimization, not identical test evidence.
Runner time is an opportunity estimate, not money saved or a measured speedup.
After changing triggers, check the new head's Actions runs before claiming that
the duplicate was removed. Keep integration and release checks intact.

## Implement and close

Make a focused change and test the failure or cost that justified it. Preserve
required checks, lint rules and behavior. Record the baseline SHA, evidence
links, exact checks, outcome and remaining gaps in `PROGRESS.md`; put longer
audits in dated `docs/history/` files. Use a small draft PR for review under the
repository rules. This skill grants no extra permission to merge, publish,
change live services or schedule recurring work.

For each finding, separate the observation, supporting evidence, proposed
change, measured result and untested assumptions. A local pass does not prove
phone behavior, production compatibility or future CI savings.

Method: [OpenAI's software maintenance example](https://developers.openai.com/blog/maintaining-software-with-the-gpt-6-family).
