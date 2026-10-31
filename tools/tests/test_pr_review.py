import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pr_review as review


HEAD, BASE = "a" * 40, "b" * 40
PATH = "app/src/main/java/example/Engine.kt"
PATCH = "@@ -1,3 +1,3 @@\n class Engine {\n-    val active = false\n+    val active = true\n }"
PR = {"number": 7, "title": "Change engine", "body": "Fix playback", "state": "open", "draft": False,
      "changed_files": 1, "head": {"sha": HEAD},
      "base": {"sha": BASE, "repo": {"full_name": "example/Vesqen"}}}
FINDING = {"path": PATH, "line": 2, "side": "RIGHT", "priority": "P2", "title": "Preserve idle state",
           "body": "At initial creation active is true even before playback starts; callers treat it as playing."}
ENV = {"GITHUB_EVENT_NAME": "workflow_dispatch", "PR_NUMBER": "7", "PR_REVIEW_PUBLISH": "true",
       "PR_REVIEW_PROVIDER": "anthropic", "PR_REVIEW_MODEL": "test-model",
       "ANTHROPIC_API_KEY": "test-only-not-a-credential"}


class FakeGitHub:
    repository = "example/Vesqen"

    def __init__(self):
        self.pr = copy.deepcopy(PR)
        self.files = [{"filename": PATH, "status": "modified", "additions": 1, "deletions": 1, "patch": PATCH}]
        self.reviews = []
        self.posts = []
        self.sources = []
        self.pr_reads = 0
        self.change_on_read = None
        self.run = {"path": ".github/workflows/android.yml", "event": "pull_request", "status": "completed",
                    "conclusion": "success", "head_sha": HEAD, "pull_requests": [{"number": 7}]}
        self.tree = []
        self.associated = [{"number": 7}]
        self.diff_base = BASE

    def get(self, path):
        if path == "pulls/7":
            self.pr_reads += 1
            if self.pr_reads == self.change_on_read:
                self.pr["head"]["sha"] = "c" * 40
            return copy.deepcopy(self.pr)
        if path == "actions/runs/123":
            return copy.deepcopy(self.run)
        if path == f"git/trees/{HEAD}?recursive=1":
            return {"tree": self.tree, "truncated": False}
        if path == f"compare/{BASE}...{HEAD}":
            return {"merge_base_commit": {"sha": self.diff_base}}
        raise AssertionError(f"Unexpected GET: {path}")

    def pages(self, path):
        if path == "pulls/7/files":
            return iter(copy.deepcopy(self.files))
        if path == "pulls/7/reviews":
            return iter(copy.deepcopy(self.reviews))
        if path == f"commits/{HEAD}/pulls":
            return iter(copy.deepcopy(self.associated))
        raise AssertionError(f"Unexpected pagination: {path}")

    def source(self, path, sha):
        self.sources.append((path, sha))
        return "class Engine {\n    val active = true\n}"

    def post(self, path, payload):
        self.posts.append((path, payload))


class ReviewFlowTests(unittest.TestCase):
    def setUp(self):
        self.github = FakeGitHub()
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "AGENTS.md").write_text("Only a completed scan may prune rows.")

    def test_discover_verify_and_publish_only_a_comment_bound_to_the_commit(self):
        verdict = {"verdicts": [{"candidate_id": 1, "confirmed": True, "reason": "Initial creation reaches the active branch."}]}
        with patch.object(review.Model, "generate", side_effect=[{"findings": [FINDING]}, verdict]) as model:
            summary = review.run(self.github, ENV, self.root, {})
        self.assertEqual(2, model.call_count)
        self.assertIn("Only a completed scan", model.call_args_list[0].args[1]["trusted_rules"]["AGENTS.md"])
        path, payload = self.github.posts[0]
        self.assertEqual("pulls/7/reviews", path)
        self.assertEqual("COMMENT", payload["event"])
        self.assertEqual(HEAD, payload["commit_id"])
        self.assertIn(review.marker(PR), payload["body"])
        self.assertIn("1 finding(s) retained from 1 candidate(s)", summary)
        self.assertIn("not an approval", summary)
        self.assertEqual([(PATH, HEAD)], self.github.sources)

    def test_no_candidates_uses_one_call_and_does_not_claim_verification_or_tests(self):
        with patch.object(review.Model, "generate", return_value={"findings": []}) as model:
            summary = review.run(self.github, ENV, self.root, {})
        self.assertEqual(1, model.call_count)
        self.assertIn("0 finding(s) retained from 0 candidate(s)", summary)
        self.assertIn("does not execute tests", summary)
        self.assertIn("not run (no candidates)", summary)

    def test_dry_run_never_publishes(self):
        with patch.object(review.Model, "generate", return_value={"findings": []}):
            summary = review.run(self.github, {**ENV, "PR_REVIEW_PUBLISH": "false"}, self.root, {})
        self.assertIn("Dry run", summary)
        self.assertEqual([], self.github.posts)

    def test_verified_rejection_is_not_published_as_a_finding(self):
        verdict = {"verdicts": [{"candidate_id": 1, "confirmed": False, "reason": "The supplied caller guards this state."}]}
        with patch.object(review.Model, "generate", side_effect=[{"findings": [FINDING]}, verdict]):
            summary = review.run(self.github, ENV, self.root, {})
        self.assertIn("0 finding(s) retained from 1 candidate(s)", summary)
        self.assertNotIn(FINDING["title"], summary)

    def test_discovery_and_verification_can_use_different_providers(self):
        env = {**ENV, "PR_REVIEW_VERIFY_PROVIDER": "openai", "PR_REVIEW_VERIFY_MODEL": "test-verifier",
               "OPENAI_API_KEY": "test-only-not-a-credential"}
        verdict = {"verdicts": [{"candidate_id": 1, "confirmed": True, "reason": "Trigger traced."}]}
        with patch.object(review.Model, "generate", autospec=True, side_effect=[{"findings": [FINDING]}, verdict]) as model:
            review.run(self.github, env, self.root, {})
        self.assertEqual("anthropic", model.call_args_list[0].args[0].provider)
        self.assertEqual("openai", model.call_args_list[1].args[0].provider)
        self.assertEqual("test-verifier", model.call_args_list[1].args[0].model)

    def test_default_and_empty_configuration_use_only_gpt_6_1_sol_for_both_passes(self):
        base_env = {key: ENV[key] for key in ("GITHUB_EVENT_NAME", "PR_NUMBER", "PR_REVIEW_PUBLISH")}
        base_env["OPENAI_API_KEY"] = "test-only-not-a-credential"
        verdict = {"verdicts": [{"candidate_id": 1, "confirmed": True, "reason": "Trigger traced."}]}
        for override in ({}, {"PR_REVIEW_PROVIDER": "", "PR_REVIEW_MODEL": "",
                              "PR_REVIEW_VERIFY_PROVIDER": "", "PR_REVIEW_VERIFY_MODEL": ""}):
            with self.subTest(override=override), patch.object(
                    review.Model, "generate", autospec=True, side_effect=[{"findings": [FINDING]}, verdict]) as model:
                review.run(FakeGitHub(), {**base_env, **override}, self.root, {})
            self.assertEqual(2, model.call_count)
            for call in model.call_args_list:
                self.assertEqual("openai", call.args[0].provider)
                self.assertEqual("gpt-6.1-sol", call.args[0].model)

    def test_lowest_priority_sets_the_instructions_schema_and_accepted_findings(self):
        verdict = {"verdicts": [{"candidate_id": 1, "confirmed": True, "reason": "Trigger traced."}]}
        minor = {**FINDING, "priority": "P3"}
        with patch.object(review.Model, "generate", autospec=True, side_effect=[{"findings": [minor]}, verdict]) as model:
            summary = review.run(self.github, {**ENV, "PR_REVIEW_LOWEST_PRIORITY": "p3"}, self.root, {})
        discovery = model.call_args_list[0]
        self.assertIn("- P3:", discovery.args[0].system)
        self.assertEqual(["P1", "P2", "P3"],
                         discovery.args[3]["properties"]["findings"]["items"]["properties"]["priority"]["enum"])
        self.assertIn("[P3] Preserve idle state", summary)
        self.assertIn("Reported priorities: P1–P3", summary)

        with patch.object(review.Model, "generate", autospec=True, return_value={"findings": []}) as model:
            summary = review.run(FakeGitHub(), {**ENV, "PR_REVIEW_LOWEST_PRIORITY": "P1"}, self.root, {})
        self.assertNotIn("- P2:", model.call_args.args[0].system)
        self.assertIn("No introduced P1 defects", summary)
        with patch.object(review.Model, "generate", side_effect=[{"findings": [FINDING]}]):
            with self.assertRaises(review.ReviewError):
                review.run(FakeGitHub(), {**ENV, "PR_REVIEW_LOWEST_PRIORITY": "P1"}, self.root, {})

    def test_default_priorities_stay_p1_and_p2(self):
        with patch.object(review.Model, "generate", autospec=True, return_value={"findings": []}) as model:
            summary = review.run(self.github, ENV, self.root, {})
        self.assertIn("- P2:", model.call_args.args[0].system)
        self.assertNotIn("- P3:", model.call_args.args[0].system)
        self.assertIn("Reported priorities: P1–P2", summary)

    def test_invalid_lowest_priority_fails_before_spending_api_budget(self):
        for value in ("P4", "high", "P0"):
            with self.subTest(value=value), patch.object(review.Model, "generate") as model:
                with self.assertRaisesRegex(review.ReviewError, "P1, P2 or P3"):
                    review.run(self.github, {**ENV, "PR_REVIEW_LOWEST_PRIORITY": value}, self.root, {})
            model.assert_not_called()

    def test_review_rules_come_first_and_survive_a_long_prd(self):
        (self.root / "docs").mkdir()
        (self.root / review.REVIEW_RULES).write_text("Check closely: queue checkpoints.")
        for name in ("PRODUCT.md", "CONTEXT.md", "docs/PRD.md", "CONTRIBUTING.md"):
            (self.root / name).write_text("x" * 7000)
        rules = review.trusted_rules(self.root, [PATH])
        self.assertEqual(review.REVIEW_RULES, next(iter(rules)))
        self.assertIn("queue checkpoints", rules[review.REVIEW_RULES])
        with patch.object(review.Model, "generate", return_value={"findings": []}):
            summary = review.run(self.github, ENV, self.root, {})
        self.assertIn(f"Review rules: `{review.REVIEW_RULES}`", summary)

    def test_review_header_says_when_there_are_no_review_rules(self):
        with patch.object(review.Model, "generate", return_value={"findings": []}):
            summary = review.run(self.github, ENV, self.root, {})
        self.assertIn("Review rules: none on the default branch", summary)

    def test_anthropic_override_still_requires_its_own_model(self):
        with patch.object(review.Model, "generate") as model:
            with self.assertRaises(review.ReviewError):
                review.run(self.github, {**ENV, "PR_REVIEW_MODEL": ""}, self.root, {})
        model.assert_not_called()

    def test_failed_or_incomplete_verification_cannot_publish_a_clean_result(self):
        for second in ({"verdicts": []}, {"verdicts": [{"candidate_id": 9, "confirmed": True, "reason": "x"}]}):
            with self.subTest(second=second), patch.object(review.Model, "generate", side_effect=[{"findings": [FINDING]}, second]):
                with self.assertRaises(review.ReviewError):
                    review.run(self.github, ENV, self.root, {})
        self.assertEqual([], self.github.posts)

    def test_head_drift_after_review_discards_result(self):
        self.github.change_on_read = 3
        with patch.object(review.Model, "generate", return_value={"findings": []}):
            with self.assertRaisesRegex(review.ReviewSkipped, "changed base/head"):
                review.run(self.github, ENV, self.root, {})
        self.assertEqual([], self.github.posts)

    def test_duplicate_bot_review_skips_before_spending_api_budget(self):
        self.github.reviews = [{"user": {"login": "github-actions[bot]"}, "commit_id": HEAD, "body": review.marker(PR)}]
        with patch.object(review.Model, "generate") as model:
            with self.assertRaisesRegex(review.ReviewSkipped, "already has"):
                review.run(self.github, ENV, self.root, {})
        model.assert_not_called()

    def test_human_cannot_spoof_a_completed_bot_review(self):
        self.github.reviews = [{"user": {"login": "contributor"}, "commit_id": HEAD, "body": review.marker(PR)}]
        self.assertFalse(review.already_reviewed(self.github, PR))
        other_base = copy.deepcopy(PR)
        other_base["base"]["sha"] = "d" * 40
        self.github.reviews[0]["user"]["login"] = "github-actions[bot]"
        self.assertFalse(review.already_reviewed(self.github, other_base))

    def test_draft_skips_without_model_calls(self):
        self.github.pr["draft"] = True
        with patch.object(review.Model, "generate") as model:
            with self.assertRaises(review.ReviewSkipped):
                review.run(self.github, ENV, self.root, {})
        model.assert_not_called()

    def test_automatic_run_can_resolve_a_fork_with_empty_pr_associations(self):
        self.github.run["pull_requests"] = []
        number, ci = review.review_target(self.github, "workflow_run", {"workflow_run": {"id": 123}}, "")
        self.assertEqual(7, number)
        self.assertEqual({"conclusion": "success", "head_sha": HEAD}, ci)

    def test_stale_ci_does_not_review_a_new_head_or_attribute_old_ci_to_it(self):
        self.github.change_on_read = 2
        with patch.object(review.Model, "generate") as model:
            with self.assertRaisesRegex(review.ReviewSkipped, "changed after"):
                review.run(self.github, {**ENV, "GITHUB_EVENT_NAME": "workflow_run"}, self.root, {"workflow_run": {"id": 123}})
        model.assert_not_called()

    def test_unrelated_or_cancelled_workflow_cannot_trigger_review(self):
        for field, value in (("path", ".github/workflows/untrusted.yml"), ("event", "push"), ("conclusion", "cancelled")):
            with self.subTest(field=field):
                github = FakeGitHub()
                github.run[field] = value
                with self.assertRaises(review.ReviewSkipped):
                    review.review_target(github, "workflow_run", {"workflow_run": {"id": 123}}, "")

    def test_missing_patch_and_oversized_diff_are_explicitly_skipped(self):
        for patch_text in ("", "@@ -1 +1 @@\n-old\n+" + "x" * review.MAX_DIFF):
            with self.subTest(patch_text=patch_text[:20]):
                self.github.files[0]["patch"] = patch_text
                with self.assertRaises(review.ReviewSkipped):
                    review.collect_context(self.github, PR, self.root)

    def test_sensitive_files_are_excluded_from_model_context(self):
        self.github.files.append({"filename": ".env.production", "status": "added", "additions": 1, "deletions": 0,
                                  "patch": "@@ -0,0 +1 @@\n+PRIVATE_VALUE=do-not-send"})
        self.github.pr["changed_files"] = 2
        data, _ = review.collect_context(self.github, self.github.pr, self.root)
        self.assertNotIn("do-not-send", json.dumps(data))
        self.assertNotIn(".env.production", [path for path, sha in self.github.sources])
        self.assertTrue(any("sensitive" in note for note in data["coverage_notes"]))

    def test_matching_tests_are_included_as_data_and_context_is_bounded(self):
        test_path = "app/src/test/java/example/EngineTest.kt"
        self.github.tree = [{"path": test_path, "type": "blob"}]
        data, _ = review.collect_context(self.github, PR, self.root)
        self.assertIn(test_path, data["context"])
        excerpt, partial = review.source_excerpt("\n" * 10000, {5000}, 100)
        self.assertTrue(partial)
        self.assertLessEqual(len(excerpt), 100)
        self.assertIn("4980:", excerpt)

    def test_deleted_file_uses_base_snapshot_and_left_side_anchors(self):
        self.github.diff_base = "d" * 40
        self.github.files[0].update(status="removed", additions=0, deletions=1, patch="@@ -2 +1,0 @@\n-old")
        data, anchors = review.collect_context(self.github, PR, self.root)
        self.assertEqual([(PATH, "d" * 40)], self.github.sources)
        self.assertEqual("d" * 40, data["pr"]["diff_base_sha"])
        self.assertEqual({2}, anchors[PATH]["LEFT"])
        self.assertEqual("LEFT", data["context"][PATH]["side"])

    def test_renamed_file_links_left_findings_to_the_original_path(self):
        old_path = "app/src/main/java/example/OldEngine.kt"
        self.github.files[0].update(status="renamed", previous_filename=old_path)
        data, _ = review.collect_context(self.github, PR, self.root)
        model = review.Model("anthropic", "test-model", ENV)
        body = review.render_review(self.github.repository, PR, data,
                                    [{**FINDING, "side": "LEFT", "verification": "Trigger traced."}],
                                    1, model, model, None)
        self.assertIn(f"/blob/{BASE}/{old_path}#L2", body)

    def test_python_test_naming_and_unicode_line_separators(self):
        self.github.files[0]["filename"] = "tools/worker.py"
        test_path = "tools/tests/test_worker.py"
        self.github.tree = [{"path": test_path, "type": "blob"}]
        data, _ = review.collect_context(self.github, PR, self.root)
        self.assertIn(test_path, data["context"])
        lines = review.changed_lines('@@ -1 +1 @@\n-old\n+"a\u2028b"')
        self.assertEqual({1}, lines["RIGHT"])
        self.assertEqual(('1: "a\u2028b"', False), review.source_excerpt('"a\u2028b"\n', {1}))


class ProviderAndValidationTests(unittest.TestCase):
    def test_openai_uses_responses_structured_output_and_disables_storage(self):
        env = {"OPENAI_API_KEY": "test-only-not-a-credential"}
        result = {"status": "completed", "output": [{"type": "reasoning"}, {"type": "message", "content": [
            {"type": "output_text", "text": '{"findings":[]}'}]}]}
        with patch.object(review, "request_json", return_value=result) as request:
            self.assertEqual({"findings": []}, review.Model("openai", "test-model", env).generate("Review", {}, review.DISCOVERY_SCHEMA))
        url, headers, payload = request.call_args.args
        self.assertEqual("https://api.openai.com/v1/responses", url)
        self.assertFalse(payload["store"])
        self.assertTrue(payload["text"]["format"]["strict"])
        self.assertNotIn("tools", payload)
        self.assertEqual(8192, payload["max_output_tokens"])

    def test_anthropic_uses_messages_structured_output_without_executable_tools(self):
        result = {"stop_reason": "end_turn", "content": [{"type": "text", "text": '{"findings":[]}'}]}
        with patch.object(review, "request_json", return_value=result) as request:
            review.Model("anthropic", "test-model", ENV).generate("Review", {}, review.DISCOVERY_SCHEMA)
        url, headers, payload = request.call_args.args
        self.assertEqual("https://api.anthropic.com/v1/messages", url)
        self.assertEqual("2023-06-01", headers["anthropic-version"])
        self.assertEqual("json_schema", payload["output_config"]["format"]["type"])
        self.assertNotIn("tools", payload)

    def test_custom_https_gateway_and_model_configuration(self):
        model = review.Model("openai", "test-model", {"OPENAI_API_KEY": "test-only-not-a-credential",
                            "OPENAI_BASE_URL": "https://gateway.example/v1/"})
        self.assertEqual("https://gateway.example/v1", model.base)
        for base in ("http://gateway.example/v1", "https://user:password@example.com/v1", "https://example.com/v1?key=x"):
            with self.subTest(base=base), self.assertRaises(review.ReviewError):
                review.Model("openai", "test-model", {"OPENAI_API_KEY": "test-only-not-a-credential", "OPENAI_BASE_URL": base})
        with self.assertRaises(review.ReviewError):
            review.Model("openai", "", {"OPENAI_API_KEY": "test-only-not-a-credential"})

    def test_provider_failure_refusal_or_truncation_never_becomes_no_findings(self):
        cases = [("openai", {"status": "incomplete", "output": []}),
                 ("openai", {"status": "completed", "output": [{"type": "message", "content": [{"type": "refusal"}]}]}),
                 ("anthropic", {"stop_reason": "max_tokens", "content": []}),
                 ("anthropic", {"stop_reason": "end_turn", "content": [{"type": "text", "text": "not JSON"}]})]
        env = {**ENV, "OPENAI_API_KEY": "test-only-not-a-credential"}
        for provider, response in cases:
            with self.subTest(provider=provider, response=response), patch.object(review, "request_json", return_value=response):
                with self.assertRaises(review.ReviewError):
                    review.Model(provider, "test-model", env).generate("Review", {}, review.DISCOVERY_SCHEMA)

    def test_model_cannot_invent_paths_or_unchanged_line_locations(self):
        anchors = {PATH: review.changed_lines(PATCH)}
        for change in ({"path": "other.kt"}, {"line": 1}, {"line": True}, {"side": []}, {"priority": []}):
            with self.subTest(change=change), self.assertRaises(review.ReviewError):
                review.validate_findings({"findings": [{**FINDING, **change}]}, anchors)
        self.assertEqual([FINDING], review.validate_findings({"findings": [FINDING, FINDING]}, anchors))

    def test_markdown_disables_model_images_html_and_notifications(self):
        text = review.markdown('![image](https://example.com) <img src="x"> @maintainer')
        self.assertNotIn("![", text)
        self.assertNotIn("<img", text)
        self.assertNotIn("@maintainer", text)

    def test_untrusted_paths_cannot_escape_repository(self):
        for path in ("../.env", "/tmp/secret", "src\nfile.kt"):
            with self.subTest(path=path), self.assertRaises(review.ReviewError):
                review.reviewable(path)

    def test_http_failures_hide_response_bodies_and_redirects_are_not_followed(self):
        error = urllib.error.HTTPError("https://example.com/private", 401, "secret error", {}, io.BytesIO(b"private prompt"))
        with patch("urllib.request.OpenerDirector.open", side_effect=error):
            with self.assertRaisesRegex(review.ReviewError, "HTTP 401") as raised:
                review.request_json("https://example.com", {}, label="Provider")
        self.assertNotIn("private", str(raised.exception))
        self.assertIsNone(review.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.example"))


if __name__ == "__main__":
    unittest.main()
