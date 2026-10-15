# AI Audit, Re-review, and Batch Audit Workflows

These workflows are designed to run on the consolidated PR #29 line. The repository's configured default branch is required for batch dispatch so a batch cannot be started from an arbitrary working branch.

## Single-PR audit

Use **Actions → AI Pull Request Audit → Run workflow**:
- Enter the PR number.
- Select `audit` for a fresh review of the current pinned PR diff.
- Select `re-review` after fixes to compare the latest pinned diff against the latest `github-actions[bot]` audit or re-review report.

The re-review loads only the previous report authored by `github-actions[bot]`, redacts it before external transmission, pins and rechecks the current PR base/head SHAs, and asks the model to classify prior findings as `fixed`, `still_open`, or `uncertain`. To avoid silently omitting findings, it refuses to re-review a previous report larger than 16,000 characters rather than truncating it. Fixed/still-open classifications are only displayed as confirmed when their cited lines match the submitted diff. AI findings remain advisory; CI and human source review are still required.

## Dedicated re-review

Use **Actions → AI PR Re-review After Fixes → Run workflow** and enter one PR number. This is a convenience entry point that invokes the same pinned audit engine in re-review mode.

A prior audit report must exist. If it does not, the workflow fails rather than pretending that it has re-checked old findings.

## Batch audit

Use **Actions → Batch AI PR Audit → Run workflow**:
- `pr_numbers` must be a JSON array of positive integers, for example `[29,31,32,33]`.
- The default batch includes historical PRs #1–#29 and follow-up PRs #31–#33. PR #30 is intentionally excluded per the current project decision.
- Choose `audit` or `re-review`.
- At most 50 unique PRs are accepted per run.
- Up to three PR audits run in parallel. A failure on one PR does not cancel the others.
- Dispatch must be initiated from the repository default branch.
- Fork-origin PR diffs are rejected by the audit engine because repository secrets must not be sent to an external model.

The batch calls the reusable `ai-pr-audit.yml` workflow directly. It does not rely on recursively dispatching another workflow with `GITHUB_TOKEN`, which is commonly blocked by GitHub's workflow recursion protections.

## Evidence and status semantics

- Audit/re-review reports include the exact audited head SHA, base SHA, and merge-base SHA.
- A report is not posted if the PR head/base changes during audit.
- Candidate findings whose evidence cannot be matched to the pinned diff are discarded.
- `fixed` means the re-review model found diff evidence consistent with the fix; it is not a substitute for tests.
- `still_open` means the model found diff evidence consistent with the issue remaining.
- `uncertain` means the submitted diff cannot prove either state.
- A green workflow means the audit workflow ran successfully, not that the code is defect-free.
