"""Read Actions history and measure paired push/PR runner time. No writes."""

import argparse
from collections import defaultdict
from datetime import datetime
import json
import re
import subprocess
import sys


def gh_json(*args):
    result = subprocess.run(
        ["gh", *args], capture_output=True, text=True, encoding="utf-8",
        check=True, timeout=60,
    )
    return json.loads(result.stdout)


def find_pairs(runs):
    grouped = defaultdict(list)
    for run in runs:
        if run["status"] == "completed" and run["event"] in ("push", "pull_request"):
            grouped[(run["headBranch"], run["headSha"])].append(run)
    return [
        group for _, group in sorted(grouped.items())
        if {run["event"] for run in group} == {"push", "pull_request"}
    ]


def job_seconds(job):
    start, end = job["started_at"], job["completed_at"]
    if start is None and end is None and job["conclusion"] in ("skipped", "cancelled"):
        return 0
    if not start or not end:
        raise ValueError("Completed job has incomplete timestamps")
    seconds = (
        datetime.fromisoformat(end.replace("Z", "+00:00"))
        - datetime.fromisoformat(start.replace("Z", "+00:00"))
    ).total_seconds()
    if seconds < 0:
        raise ValueError("Job ended before it started")
    return seconds


def summarize(runs, fetch_jobs):
    pairs = []
    total_seconds = 0
    for group in find_pairs(runs):
        pushes = [run for run in group if run["event"] == "push"]
        seconds = sum(job_seconds(job) for run in pushes for job in fetch_jobs(run["databaseId"]))
        total_seconds += seconds
        pairs.append({
            "branch": group[0]["headBranch"],
            "head_sha": group[0]["headSha"],
            "push_run_ids": [run["databaseId"] for run in pushes],
            "pr_run_ids": [run["databaseId"] for run in group if run["event"] == "pull_request"],
            "push_urls": [run["url"] for run in pushes],
            "push_job_seconds": seconds,
        })
    dates = sorted(run["createdAt"] for run in runs)
    return {
        "sample_runs": len(runs),
        "sample_start": dates[0] if dates else None,
        "sample_end": dates[-1] if dates else None,
        "paired_heads": len(pairs),
        "paired_push_runs": sum(len(pair["push_run_ids"]) for pair in pairs),
        "paired_push_job_seconds": total_seconds,
        "paired_push_runner_minutes": round(total_seconds / 60, 2),
        "pairs": pairs,
        "limits": [
            "Same branch/head; PR merge results may differ from push checkouts.",
            "Latest job attempt only; queue time excluded; failed work included.",
            "Historical opportunity estimate, not measured savings or billing.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="GitHub owner/repository")
    parser.add_argument("--workflow", default="android.yml")
    parser.add_argument("--limit", type=int, default=50)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        parser.error("--repo must be owner/repository")
    if args.limit < 1:
        parser.error("--limit must be positive")
    runs = gh_json(
        "run", "list", "--repo", args.repo, "--workflow", args.workflow,
        "--limit", str(args.limit), "--json",
        "databaseId,headSha,headBranch,event,status,conclusion,createdAt,url",
    )

    def fetch_jobs(run_id):
        pages = gh_json(
            "api", "--paginate", "--slurp",
            f"repos/{args.repo}/actions/runs/{run_id}/jobs?per_page=100",
        )
        return [job for page in pages for job in page["jobs"]]

    print(json.dumps({"repo": args.repo, "workflow": args.workflow, **summarize(runs, fetch_jobs)}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.SubprocessError, ValueError, KeyError) as error:
        print(f"CI history audit failed: {error}", file=sys.stderr)
        sys.exit(1)
