# Automated PR review

The optional `PR Review` workflow uses independent discovery → merged candidates →
cross-verification → review, extending the discovery/verification pattern used by Qwen Code.
It uses Python's standard library; there is no new
application dependency or network behavior in the Android app.

## Setup

The workflow and `tools/pr_review.py` must first reach the default branch. Local
changes alone do not activate it. In repository **Settings → Secrets and
variables → Actions**, configure:

| Kind | Name | Value |
| --- | --- | --- |
| Variable | `PR_REVIEW_PROVIDER` | Optional: `openai` (default) or `anthropic` |
| Variable | `PR_REVIEW_MODEL` | Optional for OpenAI: defaults to `gpt-6.1-sol`; required for Anthropic |
| Secret | `PR_REVIEW_ANTHROPIC_API_KEY` | Existing Anthropic key, if using Anthropic |
| Secret | `PR_REVIEW_OPENAI_API_KEY` | Existing OpenAI key, if using OpenAI |
| Variable | `PR_REVIEW_ENABLED` | `true` to enable automatic reviews |

The default runs two independent OpenAI `gpt-6.1-sol` reviews and cross-verifies their candidates.
To use it, only the OpenAI key and the automatic-review enable flag above need
to be configured. No Anthropic key is needed for this default.

The selected model must support structured JSON output: Anthropic Messages
`output_config.format`, or OpenAI Responses `text.format` with strict JSON schema.
Provider and model choices remain configurable. There is no provider failover or retry: a
configuration/quota error fails the review visibly instead of producing a clean
verdict or unexpectedly spending on another provider.

Optional configuration:

| Kind | Name | Behavior |
| --- | --- | --- |
| Variable | `PR_REVIEW_VERIFY_PROVIDER` | Second independent reviewer; defaults to the primary provider. Both reviewers also cross-verify the merged candidates |
| Variable | `PR_REVIEW_VERIFY_MODEL` | Second reviewer's model; defaults to the primary model when using the same provider; required when switching providers |
| Variable | `PR_REVIEW_LOWEST_PRIORITY` | `P1`, `P2` (default) or `P3`: the most minor priority the review reports. Any other value fails the run before a model call |
| Secret | `PR_REVIEW_ANTHROPIC_BASE_URL` | Optional HTTPS base URL including `/v1`; default `https://api.anthropic.com/v1` |
| Secret | `PR_REVIEW_OPENAI_BASE_URL` | Optional HTTPS base URL including `/v1`; default `https://api.openai.com/v1` |

## What the review looks for

Two settings change the review without code changes:

- **Review rules.** `docs/PR_REVIEW_RULES.md` on the default branch lists areas to check closely
  and findings to skip. It is the first trusted rules file, so the rules budget cannot drop it.
  It directs attention but does not lower the evidence a finding needs. Only its first 6,000
  characters are used.
- **Lowest priority.** `PR_REVIEW_LOWEST_PRIORITY` selects which priorities are reported. The
  definitions are sent to both model calls, and the JSON schema and local validation accept only
  the selected priorities:

| Priority | Meaning |
| --- | --- |
| P1 | Data loss or corruption, a crash, a security or privacy exposure, or a broken core flow such as playback, the library, updates or release signing |
| P2 | A reachable wrong result, race, lifetime or recovery bug with real but narrower impact |
| P3 | A minor but concrete defect: a small user-visible error in copy, layout or accessibility, an unhandled edge case with low impact, or changed behavior that its tests no longer cover |

`P1` reports only P1, `P2` reports P1 and P2, and `P3` reports all three. Style preferences,
speculative improvements and pre-existing bugs are not reported at any setting. Each review
header names the reported priorities and whether the rules file was used.

For OpenAI plus Claude, set `PR_REVIEW_PROVIDER=openai`,
`PR_REVIEW_MODEL=gpt-6.1-sol`, `PR_REVIEW_VERIFY_PROVIDER=anthropic`, and
`PR_REVIEW_VERIFY_MODEL=claude-sonnet-5-5`. Configure both keys. The existing
`VERIFY` variable names are retained for compatibility; the second model now
discovers bugs independently as well as checking candidates. Reusing the same
provider/model for both reviews is supported. Separate calls do not guarantee
different reasoning or correctness.

Before enabling automatic runs, use **Actions → PR Review → Run workflow** on the
default branch. Enter an open PR number and leave `publish` unchecked. Inspect
the workflow summary, then enable automatic reviews or manually publish a review.
The default workflow token needs permission to write PR reviews; organization or
repository policy can restrict that permission.

## Behavior

The initial policy uses the same configured pipeline for every eligible PR.
There is no automatic complexity classifier or model routing. Size limits bound
the review workload; they do not classify a small change as low risk.

- Automatic review starts after `Android CI` completes for a PR, including forks
  whose CI has been allowed to run. CI failures/timeouts are reported separately
  and do not prevent review. Cancelled/skipped CI runs and draft/closed PRs are
  skipped. Marking a draft ready triggers Android CI again.
- The script resolves the PR through GitHub, including a commit-association lookup
  when a fork's workflow payload has no PR list. An obsolete CI commit cannot be
  used as the status of a newer PR commit.
- Trusted project rules come from the checked-out default-branch snapshot:
  `docs/PR_REVIEW_RULES.md`, `AGENTS.md`, `PRODUCT.md`, `CONTEXT.md`, `docs/PRD.md`,
  `CONTRIBUTING.md`, and applicable nested agent instructions. Changed instructions are reviewed as
  data, not adopted as authority.
- Each independent reviewer receives the PR description, complete supported text patches,
  bounded source excerpts at the reviewed commits, and up to five matching
  `NameTest`, `NameTests` or `test_name` files. It can propose at most eight concrete
  defects at the selected priorities (P1/P2 by default). Each reviewer first has
  up to two rounds to request source lookups, then produces its final candidates.
  Neither discovery sees the other's candidates or lookup history, even if one
  produces no candidates.
- Source lookup supports literal, case-sensitive text/path search and numbered
  file reads around a requested line. `HEAD` is the PR's immutable head; `BASE`
  is its merge base. This can supply unchanged callers, state owners, persistence,
  contracts and tests. Lookup plans use a validated JSON schema; models receive
  no shell, executable tools, network access or ability to choose a download URL.
- Exact duplicates are merged by path, side, line and normalized title, retaining
  discovery provenance. Both models then independently challenge the combined
  candidates using both lookup histories. The verifier prompt asks them to reject
  semantic duplicates; different wording is not blindly collapsed by line number.
  Findings confirmed by both appear in the main findings section. Split verdicts
  remain visible in a separate **Disagreements — human review required** section,
  including both reasons. Candidates rejected by both are omitted. A disagreement
  never becomes an unqualified clean verdict. With no candidates from either
  discovery, verification is not called.
- Publication is one consolidated **COMMENT** review bound to the head commit,
  written in detailed English. Each finding explains its trigger, impact,
  code evidence and suggested fix, followed by verification evidence. The report
  also lists input scope, CI status, file/line links, each reviewer's discovery and
  retrieval activity, and coverage limits. Verification reasons in the published
  report are excerpted at 500 characters per reviewer when necessary.
  It never approves, requests changes,
  modifies code, merges, or dismisses another review. Manual runs default to
  summary-only mode: the same detailed report is written to the Actions summary
  without posting to the PR. This changes the destination, not the detail level.
- The exact base/head pair is deduplicated against prior reviews by
  `github-actions[bot]` using the current pipeline version. Older single-discovery
  reviews do not suppress the first enhanced review. The script checks for drift before model calls and before
  publication. GitHub also binds the review to the inspected head, so a push
  racing the final API request cannot turn it into approval of the new commit.

## Trust, privacy and limits

The privileged workflow runs only the trusted script from the default branch.
It never checks out or executes PR code, installs PR dependencies, downloads CI
artifacts, restores caches, or gives models executable tools. Source archives for
the immutable head and merge base are read in memory and never extracted to disk.
Only the GitHub archive endpoint may redirect, once, to the exact repository and
commit on `codeload.github.com`; the API Authorization header is not forwarded.
Provider redirects remain refused. Source and PR text
are untrusted prompt data. Provider responses are validated locally, including
changed-line locations and complete verification verdicts. API secrets are never
included in the prompt. Provider error bodies
are not printed. Keys are supplied only to the review step.

Selected source code, PR descriptions and project rules are sent to the
configured providers/gateways. Review results are visible to PR readers; dry-run
results are visible to workflow-summary readers. OpenAI response storage is
disabled with `store=false`; provider/account retention policies still apply.
Known sensitive file names and certificate/key formats are excluded, but this
is not a secret scanner or a guarantee that source contains no private data.

Limits: 40 changed files, 60,000 text-diff characters, 60,000 initial source-context
characters and 24,000 rule characters. Each reviewer has two lookup rounds with
at most four requests each, a 60,000-character total retrieval budget, 10,000
characters per file read, and 20 results per search. The initial head path list is
capped at 12,000 characters; searches still cover the indexed files. Each source
archive is limited to 16 MiB downloaded, 64 MiB expanded and 5,000 entries; text
files over 300,000 bytes, symlinks, sensitive paths, binary/unsupported formats and
invalid UTF-8 are excluded and counted. An archive failure prevents a review verdict.

There are at most eight model calls: up to three per independent discovery
(two lookup plans plus final candidates), and one cross-verification per model
when candidates exist. The output limit remains 8,192 tokens per call, with a
25-minute workflow timeout. Reports over 60,000 characters fail before publication.
File excerpts and exclusions are
listed in the review. Missing/truncated text patches and oversized PRs produce
an explicit skipped summary with no verdict. API failures, invalid output and
incomplete discovery/verification fail the job without publishing a partial review.

Callers and tests beyond the initial excerpts and requested lookups may be absent.
Lookup availability does not mean every file was read. This change has not been
benchmarked against a historical-bug corpus. Deleted-line
context and links use the PR's merge-base snapshot, matching the old side of
GitHub's diff. This is bounded static review, not exhaustive analysis.
Android CI, instrumentation, manual device QA, and milestone/device acceptance
remain separate evidence. Do not make this advisory job a required merge check.

## Local regression checks

```bash
python3 -m unittest discover -s tools/tests -p 'test_pr_review*.py' -v
python3 -m unittest discover -s tools/tests -v
```

Tests use fake model responses and GitHub snapshots; they do not spend API
credits or publish anything. Existing Android CI discovers them automatically.

References: [Qwen review workflow](https://github.com/QwenLM/qwen-code/blob/main/.github/workflows/qwen-code-pr-review.yml),
[GitHub workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run),
[OpenAI structured output](https://developers.openai.com/api/docs/guides/structured-outputs),
[Anthropic Messages API](https://platform.claude.com/docs/en/api/messages/create).
