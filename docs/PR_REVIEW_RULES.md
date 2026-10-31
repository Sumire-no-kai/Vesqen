# PR review rules

The automated PR review (`docs/PR_REVIEW.md`) reads this file from the default branch and gives it to
the model as trusted project rules, before `AGENTS.md`. Edit it on `master` to change what the
reviewer looks at; a pull request that edits this file is reviewed as data and takes effect only
after it is merged. Only the first 6,000 characters are used. Either English or Chinese works.

The repository variable `PR_REVIEW_LOWEST_PRIORITY` sets how minor a finding may be (`P1`, `P2` or
`P3`). These rules direct the reviewer's attention; they do not lower the evidence a finding needs.

## Check closely

- Library data that users cannot rebuild: stable catalog IDs, favorites, play counts, playlists,
  added folders and recovery state in `LibraryCatalog`. Only a completed scan may prune unseen rows.
- Playback lifetime: queue checkpoints and listening history belong to `PlaybackService` and the
  application lifetime, not to a screen.
- Audio Proof claims: confidence, provenance, observation time and unavailable reasons must survive.
  Source format, route selection, direct support, active bit-perfect requests and external
  verification are separate claims and must not be merged.
- Network and privacy: anything that sends data (usage statistics, device reports, update checks)
  must match the privacy policy and its consent rules.
- Build flavors: the self-updater and `REQUEST_INSTALL_PACKAGES` stay in `src/github`, never in
  `src/main` or the Play build.
- Release and signing tooling: `tools/github_release.py` and `.github/workflows/github-release.yml`.

## Do not report

- Nothing yet. Add findings the reviewer should skip, for example a known limitation that an issue
  already tracks.
