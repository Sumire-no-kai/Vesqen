import copy
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pr_review as review
from test_pr_review import BASE, ENV, FINDING, HEAD, PATH, FakeGitHub, model_reply


def archive_bytes(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for path, content in entries:
            archive.writestr(path, content)
    return buffer.getvalue()


def query(operation="search", revision="HEAD", path="", text="Engine", line=1):
    return {"operation": operation, "revision": revision, "path": path, "query": text, "line": line}


class SourceLookupTests(unittest.TestCase):
    def setUp(self):
        self.snapshots = {
            "HEAD": {"sources": {PATH: "class Engine { val active = true }",
                                  "app/Caller.kt": "fun start() = Engine()"}, "omitted": 0},
            "BASE": {"sources": {PATH: "class Engine { val active = false }"}, "omitted": 0},
        }

    def test_search_finds_unchanged_callers_and_read_uses_the_requested_revision(self):
        matches = review.lookup_source(self.snapshots, query())["matches"]
        self.assertIn("app/Caller.kt", [item["path"] for item in matches])
        old = review.lookup_source(self.snapshots, query("read", "BASE", PATH, ""))
        current = review.lookup_source(self.snapshots, query("read", "HEAD", PATH, ""))
        self.assertIn("active = false", old["source"])
        self.assertIn("active = true", current["source"])

    def test_read_is_numbered_and_centred_on_requested_line(self):
        self.snapshots["HEAD"]["sources"][PATH] = "\n".join(f"line {n}" for n in range(1, 2001))
        result = review.lookup_source(self.snapshots, query("read", path=PATH, text="", line=1500))
        self.assertIn("1500: line 1500", result["source"])
        self.assertNotIn("1: line 1\n", result["source"])
        self.assertTrue(result["truncated"])

    def test_search_is_literal_and_marks_truncated_results(self):
        self.snapshots["HEAD"]["sources"][PATH] = "\n".join("Engine()" for _ in range(100))
        result = review.lookup_source(self.snapshots, query())
        self.assertEqual(20, len(result["matches"]))
        self.assertTrue(result["truncated"])
        self.assertEqual([], review.lookup_source(self.snapshots, query(text=".*"))["matches"])

    def test_lookup_rejects_execution_urls_traversal_and_malformed_arguments(self):
        for changes in ({"operation": "shell"}, {"revision": "master"}, {"path": "../private.py"},
                        {"path": "/tmp/private.py"}, {"line": True}, {"query": "x" * 121},
                        {"query": ""}, {"path": "a\nfile.py"}):
            with self.subTest(changes=changes), self.assertRaises(review.ReviewError):
                review.lookup_source(self.snapshots, {**query(), **changes})
        missing = review.lookup_source(self.snapshots, query("read", path=".env/credentials.py", text=""))
        self.assertIn("unavailable", missing)
        self.assertEqual([], review.lookup_source(self.snapshots, query(text="https://evil.example"))["matches"])

    def test_archive_skips_sensitive_files_symlinks_binary_and_oversized_sources(self):
        link = zipfile.ZipInfo("root/link.py")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        raw = archive_bytes([
            ("root/app/Engine.kt", b"class Engine"), ("root/server/worker.js", b"export default {}"),
            ("root/config/.env/secret.py", b"secret"), ("root/local.properties", b"private"),
            ("root/key.pem", b"private"), ("root/invalid.py", b"\xff"), ("root/binary.py", b"x\0y"),
            ("root/large.py", b"x" * (review.MAX_SOURCE_FILE + 1)), (link, b"../../private.py"),
        ])
        index = review.source_index(raw)
        self.assertEqual({"app/Engine.kt": "class Engine", "server/worker.js": "export default {}"}, index["sources"])
        self.assertEqual(7, index["omitted"])

    def test_invalid_oversized_or_traversing_archives_fail_closed(self):
        for raw in (b"not a zip", archive_bytes([("root/../escape.py", "x")]),
                    archive_bytes([("a/one.py", "x"), ("b/two.py", "x")])):
            with self.subTest(raw=raw[:10]), self.assertRaises(review.ReviewError):
                review.source_index(raw)
        with patch.object(review, "MAX_EXPANDED_ARCHIVE", 4), self.assertRaises(review.ReviewError):
            review.source_index(archive_bytes([("root/one.py", "12345")]))

    def test_oversized_archive_download_and_mutable_ref_are_refused(self):
        github = review.GitHub("example/Vesqen", "test-only-not-a-credential")
        with patch("urllib.request.OpenerDirector.open") as request, self.assertRaises(review.ReviewError):
            github.snapshot("master")
        request.assert_not_called()
        with patch.object(review, "MAX_ARCHIVE", 4), \
                patch("urllib.request.OpenerDirector.open", return_value=io.BytesIO(b"12345")):
            with self.assertRaisesRegex(review.ReviewError, "download limit"):
                github.snapshot(HEAD)

    def test_archive_redirect_is_pinned_and_does_not_forward_credentials(self):
        github = review.GitHub("example/Vesqen", "test-only-not-a-credential")
        error = urllib.error.HTTPError("https://api.github.com", 302, "redirect", {
            "Location": f"https://codeload.github.com/example/Vesqen/legacy.zip/{HEAD}"}, io.BytesIO())
        with patch("urllib.request.OpenerDirector.open", side_effect=[
                error, io.BytesIO(archive_bytes([("root/a.py", "print('source only')")]))]) as request:
            self.assertEqual({"a.py": "print('source only')"}, github.snapshot(HEAD)["sources"])
        self.assertIn("Authorization", request.call_args_list[0].args[0].headers)
        self.assertNotIn("Authorization", request.call_args_list[1].args[0].headers)
        for url in (f"https://evil.example/example/Vesqen/legacy.zip/{HEAD}",
                    f"https://codeload.github.com/other/Vesqen/legacy.zip/{HEAD}",
                    f"https://codeload.github.com/example/Vesqen/legacy.zip/{BASE}"):
            error = urllib.error.HTTPError("https://api.github.com", 302, "redirect", {"Location": url}, io.BytesIO())
            with self.subTest(url=url), patch("urllib.request.OpenerDirector.open", side_effect=error) as request:
                with self.assertRaises(review.ReviewError):
                    github.snapshot(HEAD)
                self.assertEqual(1, request.call_count)

    def test_retrieval_rounds_and_character_budget_are_bounded(self):
        model = review.Model("anthropic", "test-model", ENV)
        plan = {"requests": [query("read", path=PATH, text="")] * review.MAX_LOOKUPS_PER_ROUND}
        self.snapshots["HEAD"]["sources"][PATH] = "\n".join("x" * 200 for _ in range(100))
        with patch.object(model, "generate", side_effect=[plan, {"findings": []}]) as generate, \
                patch.object(review, "MAX_RETRIEVAL", 10500):
            result = review.discover_findings(model, {}, {}, self.snapshots, review.DEFAULT_PRIORITIES)
        self.assertEqual(2, generate.call_count)
        self.assertLessEqual(result["retrieved_characters"], 10500)
        self.assertTrue(result["notes"])
        with patch.object(model, "generate", side_effect=[{"requests": [query()]}, {"requests": [query()]},
                                                           {"findings": []}]) as generate:
            result = review.discover_findings(model, {}, {}, self.snapshots, review.DEFAULT_PRIORITIES)
        self.assertEqual(3, generate.call_count)
        self.assertEqual(2, len(result["retrieval"]))
        self.assertTrue(result["notes"])

    def test_malformed_or_oversized_lookup_plan_does_not_produce_a_verdict(self):
        model = review.Model("anthropic", "test-model", ENV)
        for plan in ({"requests": [query()] * 5}, {"requests": "shell"}, {"requests": [{"operation": "shell"}]}):
            with self.subTest(plan=plan), patch.object(model, "generate", return_value=plan) as generate:
                with self.assertRaises(review.ReviewError):
                    review.discover_findings(model, {}, {}, self.snapshots, review.DEFAULT_PRIORITIES)
                self.assertEqual(1, generate.call_count)


class IndependentReviewTests(unittest.TestCase):
    def setUp(self):
        import tempfile
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {**ENV, "PR_REVIEW_VERIFY_PROVIDER": "openai", "PR_REVIEW_VERIFY_MODEL": "second-reviewer",
                    "OPENAI_API_KEY": "test-only-not-a-credential"}

    def test_second_reviewer_can_find_a_bug_when_first_has_no_candidates(self):
        def reply(model, task, data, schema):
            findings = [FINDING] if model.provider == "openai" else []
            return model_reply(findings)(task, data, schema)
        with patch.object(review.Model, "generate", autospec=True, side_effect=reply):
            result = review.run(FakeGitHub(), self.env, self.root, {})
        self.assertIn("1 finding(s) retained from 1 candidate(s)", result)
        self.assertIn("Reviewer 1: 0 candidate(s)", result)
        self.assertIn("Reviewer 2: 1 candidate(s)", result)

    def test_independent_discovery_does_not_leak_other_candidates_or_lookup_history(self):
        phase_counts = {"anthropic": 0, "openai": 0}
        def reply(model, task, data, schema):
            phase = next(iter(schema["properties"]))
            if phase == "requests":
                self.assertNotIn("candidates", data)
                self.assertNotIn("retrieved_context", data)
                phase_counts[model.provider] += 1
                if model.provider == "anthropic" and phase_counts[model.provider] == 1:
                    return {"requests": [query("read", path=PATH, text="")]}
                if model.provider == "openai":
                    self.assertEqual([], data["retrieval"]["results"])
                return {"requests": []}
            if phase == "findings":
                self.assertNotIn("candidates", data)
                self.assertEqual(model.provider == "anthropic", bool(data["retrieved_context"]))
                return {"findings": [FINDING]}
            self.assertTrue(data["retrieved_context"][0])
            self.assertFalse(data["retrieved_context"][1])
            return model_reply([FINDING])(task, data, schema)
        with patch.object(review.Model, "generate", autospec=True, side_effect=reply):
            result = review.run(FakeGitHub(), self.env, self.root, {})
        self.assertIn("1 finding(s) retained from 1 candidate(s)", result)

    def test_disagreements_remain_visible_and_do_not_produce_a_clean_verdict(self):
        def reply(model, task, data, schema):
            verdict = {"verdicts": [{"candidate_id": 1, "confirmed": model.provider == "anthropic",
                                     "reason": "Reachable trigger." if model.provider == "anthropic" else "A caller guard prevents it."}]}
            return model_reply([FINDING], verdict)(task, data, schema)
        with patch.object(review.Model, "generate", autospec=True, side_effect=reply):
            result = review.run(FakeGitHub(), self.env, self.root, {})
        self.assertIn("1 disagreement(s) require human review", result)
        self.assertIn("Disagreements — human review required", result)
        self.assertIn("A caller guard prevents it", result)
        self.assertNotIn("No introduced P1–P2 defects", result)

    def test_duplicate_merge_preserves_different_defects_on_the_same_line(self):
        results = [{"findings": [FINDING]}, {"findings": [copy.deepcopy(FINDING), {**FINDING, "title": "Another defect"}]}]
        merged = review.merge_candidates(results)
        self.assertEqual(2, len(merged))
        self.assertEqual([1, 2], merged[0]["found_by"])

    def test_a_failed_discovery_or_lookup_cannot_publish_a_partial_review(self):
        for broken_phase in ("requests", "findings", "verdicts"):
            def reply(model, task, data, schema):
                if model.provider == "openai" and broken_phase in schema["properties"]:
                    raise review.ReviewError("Provider unavailable")
                return model_reply([FINDING])(task, data, schema)
            github = FakeGitHub()
            with self.subTest(phase=broken_phase), patch.object(review.Model, "generate", autospec=True, side_effect=reply):
                with self.assertRaises(review.ReviewError):
                    review.run(github, self.env, self.root, {})
            self.assertEqual([], github.posts)

    def test_both_source_revisions_are_required_before_any_model_call(self):
        github = FakeGitHub()
        with patch.object(github, "snapshot", side_effect=review.ReviewError("Archive unavailable")), \
                patch.object(review.Model, "generate") as generate:
            with self.assertRaises(review.ReviewError):
                review.run(github, self.env, self.root, {})
        generate.assert_not_called()
        self.assertEqual([], github.posts)

    def test_previous_pipeline_reviews_do_not_suppress_the_evidence_preserving_review(self):
        for version in ("v1", "v2"):
            github = FakeGitHub()
            github.reviews = [{"user": {"login": "github-actions[bot]"}, "commit_id": HEAD,
                               "body": review.marker(github.pr).replace(":v3:", f":{version}:")}]
            with self.subTest(version=version), patch.object(review.Model, "generate", side_effect=model_reply()):
                result = review.run(github, self.env, self.root, {})
            self.assertIn("Published a COMMENT review", result)

    def test_large_reports_fail_before_publication(self):
        github = FakeGitHub()
        original = review.render_review
        def large_report(*args, **kwargs):
            data = copy.deepcopy(args[2])
            data["coverage_notes"].append("x" * 60001)
            return original(*args[:2], data, *args[3:], **kwargs)
        with patch.object(review.Model, "generate", side_effect=model_reply()), \
                patch.object(review, "render_review", side_effect=large_report):
            with self.assertRaisesRegex(review.ReviewError, "publication limit"):
                review.run(github, self.env, self.root, {})
        self.assertEqual([], github.posts)


if __name__ == "__main__":
    unittest.main()
