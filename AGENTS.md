# AI Contributor Branch Instructions

## Current source of truth for ongoing integration work

**Do not start new integrated project work from `feature` by default.**

The `feature` branch is a legacy/diverged work line. PR #31 (memory/embedding pipeline) and PR #32 (AI audit workflow hardening) were developed against it, but it is not the chosen baseline for the current consolidated work.

For ongoing work that continues the consolidated PR #29 line, use:

- **Working branch:** `codex/pr29-followup-integration-2026-10-15`
- **Created from:** the PR #29 head at the time of branch creation
- **PR #29:** https://github.com/qq1923811452-art/Mistbell-Tavern/pull/29

## Rules

1. Before editing, verify the current branch and its HEAD. For ongoing consolidated work, switch to or branch from `codex/pr29-followup-integration-2026-10-15`, not `feature`.
2. Do not blindly merge or copy all of `feature` into the PR #29 line. Port required changes from PR #31, PR #32, and other follow-up work selectively, reviewing conflicts and preserving PR #29's existing changes.
3. Keep PR #29 **Open + Draft** unless the project owner explicitly says otherwise. Do not merge it or change its state on your own.
4. PR #26 does not need to be merged into PR #29 merely to preserve history. Treat the PR #29 line as the current consolidated baseline for follow-up work.
5. Preserve and revalidate the Room schema files (including `26.json` and `27.json`) from the PR #29 line. Do not replace them with versions from `feature` without verifying they match the actual database schema and CI artifacts.
6. Before proposing completion, run the relevant CI and AI review workflows on the new working branch, and report the exact branch, commit SHA, and run results. Do not claim a review or CI passed unless the run confirms it.

If a task explicitly asks to maintain the legacy `feature` line, follow that request; otherwise, use the PR #29 follow-up integration branch above.
