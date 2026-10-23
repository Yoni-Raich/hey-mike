import unittest

from ci_history import find_pairs, job_seconds, summarize


def run(run_id, event, sha="abc", branch="feat/example", status="completed"):
    return {
        "databaseId": run_id, "event": event, "headSha": sha,
        "headBranch": branch, "status": status,
        "createdAt": "2026-10-20T06:00:00Z", "url": f"https://example.test/{run_id}",
    }


def job(start="2026-10-20T06:05:00Z", end="2026-10-20T06:07:30Z", conclusion="success"):
    return {"started_at": start, "completed_at": end, "conclusion": conclusion}


class CiHistoryTest(unittest.TestCase):
    def test_pairs_need_both_events_at_the_same_branch_and_head(self):
        runs = [run(1, "push"), run(2, "pull_request"), run(3, "push", sha="def")]
        self.assertEqual([[runs[0], runs[1]]], find_pairs(runs))
        self.assertEqual([], find_pairs([run(1, "push"), run(2, "pull_request", branch="other")]))

    def test_unfinished_and_unrelated_events_do_not_form_pairs(self):
        self.assertEqual([], find_pairs([run(1, "push"), run(2, "pull_request", status="in_progress")]))
        self.assertEqual([], find_pairs([run(1, "push"), run(2, "workflow_dispatch")]))

    def test_counts_only_push_job_execution_excluding_queue_time(self):
        calls = []

        def fetch(run_id):
            calls.append(run_id)
            return [job(), job(end="2026-10-20T06:05:30Z", conclusion="failure")]

        report = summarize([run(1, "push"), run(2, "pull_request"), run(3, "push", sha="def")], fetch)
        self.assertEqual([1], calls)
        self.assertEqual(3, report["sample_runs"])
        self.assertEqual(1, report["paired_push_runs"])
        self.assertEqual(180, report["paired_push_job_seconds"])
        self.assertEqual(3, report["paired_push_runner_minutes"])
        self.assertEqual([2], report["pairs"][0]["pr_run_ids"])

    def test_multiple_pushes_share_one_pair_but_all_consumed_jobs_count(self):
        report = summarize([run(1, "push"), run(2, "push"), run(3, "pull_request")], lambda _: [job()])
        self.assertEqual(1, report["paired_heads"])
        self.assertEqual(2, report["paired_push_runs"])
        self.assertEqual(300, report["paired_push_job_seconds"])

    def test_empty_history_does_not_query_jobs(self):
        def fail(_):
            self.fail("No paired run should be queried")

        report = summarize([], fail)
        self.assertIsNone(report["sample_start"])
        self.assertEqual(0, report["paired_push_runner_minutes"])

    def test_timezones_are_compared_as_instants(self):
        self.assertEqual(150, job_seconds(job(start="2026-10-20T09:05:00+03:00")))

    def test_skipped_or_never_started_cancelled_job_consumes_no_job_time(self):
        for conclusion in ("skipped", "cancelled"):
            self.assertEqual(0, job_seconds(job(None, None, conclusion)))

    def test_missing_or_reversed_times_fail_instead_of_underreporting(self):
        for broken in (job(None, None), job(end=None), job(end="2026-10-20T06:04:00Z")):
            with self.assertRaises(ValueError):
                job_seconds(broken)


if __name__ == "__main__":
    unittest.main()
