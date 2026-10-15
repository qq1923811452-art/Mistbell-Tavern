# AI Contributor Branch Instructions

## Current source of truth for ongoing integration work

**Do not start new integrated project work from `feature` by default.**

The `feature` branch is a legacy/diverged work line. PR #31 (memory/embedding pipeline) and PR #32 (AI audit workflow hardening) were developed against it, but it is not the chosen baseline for the current consolidated work.

For ongoing work that continues the consolidated PR #29 line, use:

- **Canonical integration branch:** `codex/full-project-audit-pr29-2026-10-14` (the source branch of PR #29)
- **Follow-up staging branch:** `codex/pr29-followup-integration-2026-10-15` (temporary branch for review before its changes are integrated into PR #29)
- **PR #29:** https://github.com/qq1923811452-art/Mistbell-Tavern/pull/29

## Rules

1. Before editing, verify the current branch and its HEAD. For ongoing consolidated work, switch to or branch from `codex/full-project-audit-pr29-2026-10-14`, not `feature`. The `codex/pr29-followup-integration-2026-10-15` branch is staging only; once its reviewed changes are integrated, new work should start from the PR #29 source branch.
2. Do not blindly merge or copy all of `feature` into the PR #29 line. Port required changes from PR #31, PR #32, and other follow-up work selectively, reviewing conflicts and preserving PR #29's existing changes.
3. Keep PR #29 **Open + Draft** unless the project owner explicitly says otherwise. Do not merge it or change its state on your own.
4. PR #26 does not need to be merged into PR #29 merely to preserve history. Treat the PR #29 line as the current consolidated baseline for follow-up work.
5. Preserve and revalidate the Room schema files (including `26.json` and `27.json`) from the PR #29 line. Do not replace them with versions from `feature` without verifying they match the actual database schema and CI artifacts.
6. Before proposing completion, run the relevant CI and AI review workflows on the new working branch, and report the exact branch, commit SHA, and run results. Do not claim a review or CI passed unless the run confirms it.
7. For AI review, use `.github/workflows/ai-pr-audit.yml`; select `re-review` after fixes so previous bot findings are compared with the latest pinned diff. Use `.github/workflows/ai-pr-audit-batch.yml` for multi-PR audits (up to 50 PRs, three concurrent audits). Batch dispatch must be run from the repository default branch. PR #30 is intentionally excluded from the default batch list until the owner asks otherwise.

If a task explicitly asks to maintain the legacy `feature` line, follow that request; otherwise, use the PR #29 source branch above.
