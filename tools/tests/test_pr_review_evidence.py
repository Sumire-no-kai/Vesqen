import copy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pr_review as review
from test_pr_review import ENV, FINDING, HEAD, PATH, FakeGitHub, model_reply


class ReviewEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_discovery_keeps_distinct_evidence_with_the_same_title_and_anchor(self):
        second = {**FINDING, "body": "A separate trigger crashes after cancellation."}
        result = review.validate_findings({"findings": [FINDING, second, FINDING]}, {PATH: {"RIGHT": {2}}})
        self.assertEqual([FINDING, second], result)

    def test_discovery_uses_the_higher_priority_for_identical_evidence_without_mutating_input(self):
        higher = {**FINDING, "priority": "P1"}
        candidates = [copy.deepcopy(FINDING), higher]
        result = review.validate_findings({"findings": candidates}, {PATH: {"RIGHT": {2}}})
        self.assertEqual([higher], result)
        self.assertEqual("P2", candidates[0]["priority"])

    def test_merge_keeps_different_bodies_and_combines_identical_evidence_at_higher_priority(self):
        second = {**FINDING, "body": "A separate trigger crashes after cancellation."}
        higher = {**FINDING, "priority": "P1"}
        results = [{"findings": [copy.deepcopy(FINDING)]}, {"findings": [second, higher]}]
        merged = review.merge_candidates(results)
        self.assertEqual(2, len(merged))
        self.assertEqual("P1", merged[0]["priority"])
        self.assertEqual([1, 2], merged[0]["found_by"])
        self.assertEqual(second["body"], merged[1]["body"])
        self.assertEqual("P2", results[0]["findings"][0]["priority"])

    def test_two_same_title_findings_survive_the_complete_pipeline(self):
        second = {**FINDING, "body": "A separate trigger crashes after cancellation."}
        env = {**ENV, "PR_REVIEW_VERIFY_PROVIDER": "openai", "PR_REVIEW_VERIFY_MODEL": "second-reviewer",
               "OPENAI_API_KEY": "test-only-not-a-credential"}
        def reply(model, task, data, schema):
            return model_reply([FINDING if model.provider == "anthropic" else second])(task, data, schema)
        github = FakeGitHub()
        with patch.object(review.Model, "generate", autospec=True, side_effect=reply):
            report = review.run(github, env, self.root, {})
        self.assertIn("2 finding(s) retained from 2 candidate(s)", report)
        self.assertIn(second["body"], github.posts[0][1]["body"])

    def test_code_context_precedes_prose_when_the_budget_would_skip_code(self):
        github = FakeGitHub()
        prose = {**github.files[0], "filename": "docs/notes.md"}
        github.files.insert(0, prose)
        github.pr["changed_files"] = 2
        def source(path, sha):
            return "d" * 5000 if path.endswith(".md") else "class Engine {\n    val active = true\n}"
        with patch.object(github, "source", side_effect=source) as read, patch.object(review, "MAX_CONTEXT", 6000):
            data, _ = review.collect_context(github, github.pr, self.root)
        self.assertEqual(PATH, read.call_args_list[0].args[0])
        self.assertIn(PATH, data["context"])
        self.assertLessEqual(sum(len(item["source"]) for item in data["context"].values()), 6000)

    def test_dot_test_and_suffix_test_files_are_supplied_as_context(self):
        for module, test in (("server/usage/worker.js", "server/usage/test/worker.test.js"),
                             ("tools/worker.py", "tools/tests/worker_test.py")):
            with self.subTest(test=test):
                github = FakeGitHub()
                github.files[0]["filename"] = module
                github.tree = [{"path": test, "type": "blob"}]
                data, _ = review.collect_context(github, github.pr, self.root)
                self.assertIn(test, data["context"])
                self.assertIn((test, HEAD), github.sources)

    def test_complete_diff_at_the_new_limit_is_retained_and_one_character_over_is_skipped(self):
        self.assertEqual(100000, review.MAX_DIFF)
        prefix = "@@ -1 +1 @@\n-old\n+"
        for size in (100000, 100001):
            with self.subTest(size=size):
                github = FakeGitHub()
                text = prefix + "x" * (size - len(prefix))
                github.files[0]["patch"] = text
                if size == 100001:
                    with self.assertRaisesRegex(review.ReviewSkipped, "100000-character"):
                        review.collect_context(github, github.pr, self.root)
                else:
                    data, _ = review.collect_context(github, github.pr, self.root)
                    self.assertEqual(text, data["files"][0]["patch"])

    def test_a_head_change_while_loading_snapshots_prevents_all_paid_calls(self):
        github = FakeGitHub()
        github.change_on_read = 3
        with patch.object(review.Model, "generate", side_effect=model_reply()) as generate:
            with self.assertRaises(review.ReviewSkipped):
                review.run(github, ENV, self.root, {})
        generate.assert_not_called()
        self.assertEqual([], github.posts)

    def test_a_head_change_after_discovery_prevents_verification_calls(self):
        github = FakeGitHub()
        github.change_on_read = 4
        with patch.object(review.Model, "generate", side_effect=model_reply([FINDING])) as generate:
            with self.assertRaises(review.ReviewSkipped):
                review.run(github, ENV, self.root, {})
        self.assertEqual(4, generate.call_count)
        self.assertTrue(all("verdicts" not in call.args[-1]["properties"] for call in generate.call_args_list))
        self.assertEqual([], github.posts)

    def test_a_head_change_after_verification_still_prevents_publication(self):
        github = FakeGitHub()
        github.change_on_read = 5
        with patch.object(review.Model, "generate", side_effect=model_reply([FINDING])) as generate:
            with self.assertRaises(review.ReviewSkipped):
                review.run(github, ENV, self.root, {})
        self.assertEqual(6, generate.call_count)
        self.assertEqual([], github.posts)


if __name__ == "__main__":
    unittest.main()
