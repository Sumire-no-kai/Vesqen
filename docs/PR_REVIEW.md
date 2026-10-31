# Automated PR review

The optional `PR Review` workflow follows the discovery → verification → review
pattern used by Qwen Code. It uses Python's standard library; there is no new
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

The default uses OpenAI `gpt-6.1-sol` for both discovery and candidate verification.
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
| Variable | `PR_REVIEW_VERIFY_PROVIDER` | Defaults to the discovery provider |
| Variable | `PR_REVIEW_VERIFY_MODEL` | Defaults to the discovery model when using the same provider; required when switching providers |
| Secret | `PR_REVIEW_ANTHROPIC_BASE_URL` | Optional HTTPS base URL including `/v1`; default `https://api.anthropic.com/v1` |
| Secret | `PR_REVIEW_OPENAI_BASE_URL` | Optional HTTPS base URL including `/v1`; default `https://api.openai.com/v1` |

For example, use Anthropic for discovery and OpenAI for verification by setting
`PR_REVIEW_PROVIDER=anthropic`, its model, `PR_REVIEW_VERIFY_PROVIDER=openai`, and
its verifier model. Configure both keys. Reusing the same provider/model for two
separate calls is also supported. Different models do not guarantee independence
or correctness.

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
  `AGENTS.md`, `PRODUCT.md`, `CONTEXT.md`, `docs/PRD.md`, `CONTRIBUTING.md`, and
  applicable nested agent instructions. Changed instructions are reviewed as
  data, not adopted as authority.
- Discovery receives the PR description, complete supported text patches,
  bounded source excerpts at the reviewed commits, and up to five matching
  `NameTest`, `NameTests` or `test_name` files. It can propose at most eight concrete
  P1/P2 defects. A second call challenges every candidate. Only retained findings appear in the review.
  With no candidates, there is no verification call.
- Publication is one consolidated **COMMENT** review bound to the head commit,
  with file/line links and coverage limits. It never approves, requests changes,
  modifies code, merges, or dismisses another review. Manual runs default to
  summary-only mode.
- The exact base/head pair is deduplicated against prior reviews by
  `github-actions[bot]`. The script checks for drift before model calls and before
  publication. GitHub also binds the review to the inspected head, so a push
  racing the final API request cannot turn it into approval of the new commit.

## Trust, privacy and limits

The privileged workflow runs only the trusted script from the default branch.
It never checks out or executes PR code, installs PR dependencies, downloads CI
artifacts, restores caches, or gives models executable tools. Source and PR text
are untrusted prompt data. Provider responses are validated locally, including
changed-line locations and complete verification verdicts. API secrets are never
included in the prompt. HTTP redirects are refused, and provider error bodies
are not printed. Keys are supplied only to the review step.

Selected source code, PR descriptions and project rules are sent to the
configured providers/gateways. Review results are visible to PR readers; dry-run
results are visible to workflow-summary readers. OpenAI response storage is
disabled with `store=false`; provider/account retention policies still apply.
Known sensitive file names and certificate/key formats are excluded, but this
is not a secret scanner or a guarantee that source contains no private data.

Limits: 40 changed files, 60,000 text-diff characters, 60,000 source-context
characters, 24,000 rule characters, at most two model calls, 8,192 output tokens
per call, and a 15-minute workflow timeout. File excerpts and exclusions are
listed in the review. Missing/truncated text patches and oversized PRs produce
an explicit skipped summary with no verdict. API failures, invalid output and
incomplete verification fail the job without publishing a review.

Callers and tests beyond the supplied excerpts may be absent. Deleted-line
context and links use the PR's merge-base snapshot, matching the old side of
GitHub's diff. This is bounded static review, not exhaustive analysis.
Android CI, instrumentation, manual device QA, and milestone/device acceptance
remain separate evidence. Do not make this advisory job a required merge check.

## Local regression checks

```bash
python3 -m unittest discover -s tools/tests -p 'test_pr_review.py' -v
python3 -m unittest discover -s tools/tests -v
```

Tests use fake model responses and GitHub snapshots; they do not spend API
credits or publish anything. Existing Android CI discovers them automatically.

References: [Qwen review workflow](https://github.com/QwenLM/qwen-code/blob/main/.github/workflows/qwen-code-pr-review.yml),
[GitHub workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run),
[OpenAI structured output](https://developers.openai.com/api/docs/guides/structured-outputs),
[Anthropic Messages API](https://platform.claude.com/docs/en/api/messages/create).
