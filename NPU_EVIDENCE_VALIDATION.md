# PR #61: NPU Evidence, GPU/CPU Benchmarks, and Memory Scale Validation

This note records reproducible checks, expected outcomes, and rollback guidance for the current
`codex/mistbell-3.0-core-features` branch. It does not change the known-good device baseline:
Redmi K70 Pro / SM8650, Android 16, with the existing `libcdsprpc.so` manifest entry preserved.

## 1. Compile and JVM tests

Run from the repository root:

```bash
./gradlew -p mistbell-v3 :app:compileDebugKotlin --no-daemon --no-parallel
./gradlew -p mistbell-v3 :app:testDebugUnitTest --no-daemon --no-parallel
```

Expected: both tasks finish successfully. The NPU evidence policy tests reject a mismatched model/APK
hash, a failed or missing input length, a dimension other than 768, or any missing required mapping.
The memory scale test expects 1k / 5k / 10k profiles, with Large capped at 10k for the current
8,000,000-vector-value budget at 768 dimensions.

## 2. Device-generated NPU evidence (P1)

1. Install the Qualcomm SM8650 device-test APK built by the `Qualcomm HTP V75 Runtime Package`
   workflow.
2. Select the Qualcomm SM8650 model and run **运行 NPU 输入长度矩阵（7 档并生成证据）**.
3. After the matrix completes, use **导出最新 NPU 证据 JSON** to export
   `npu-evidence-<timestamp>.json`. The app also stores the JSON and its `.sha256` sidecar in
   its private `filesDir/npu-evidence/` directory.

Expected success log:

```text
embedding.npu.input_length_matrix.completed - cases=7; successes=7; failures=0; status=verified; evidence=npu-evidence-<timestamp>.json; apkSha256MatchesCurrent=true
```

A `verified` status means the model/APK hash checks, seven input-length cases, 768-dimensional outputs, required runtime-library mappings, and evidence sidecar checks passed. It does **not** prove that inference executed on NPU hardware: `hardwareExecutionVerified` intentionally remains `false` until backend-specific execution evidence is available. `evidenceLevel="runtime_loaded"` means runtime-load evidence only.

Each NPU matrix case records `payloadChars` (source text length) and `formattedChars` (the actual string passed to LiteRT-LM after prompt formatting). The legacy `inputChars` field continues to represent the seven payload lengths.

The file can only be marked `verified` when the installed APK SHA-256 remains stable during the
matrix, the model SHA-256 equals the pinned SM8650 model hash, the seven input lengths are exactly
1/32/74/128/256/512/1024, every case succeeds with a 768-dimensional vector, all three required
libraries appear in `/proc/self/maps`, and the evidence JSON matches its SHA-256 sidecar.
Otherwise the result must remain `packaged_not_device_verified`. No packaged manifest status is
edited by hand.

## 3. Same-model GPU and CPU experiments (P2)

With the Qualcomm model selected, press **实验：同一 Qualcomm 模型 GPU / CPU 基准**. This is an
isolated diagnostic route and does not change the production NPU-only backend policy for the
Qualcomm model. Each backend/input-length pair performs one warm-up and five measured inferences.

Expected per-case log (a failed experiment is also a useful result):

```text
embedding.benchmark.case - backend=GPU; payloadChars=74; formattedChars=101; warmup=1; repeats=5; p50Ms=<n>; p90Ms=<n>; result=success; dimensions=768
embedding.benchmark.case - backend=CPU; payloadChars=74; formattedChars=101; warmup=1; repeats=5; p50Ms=<n>; p90Ms=<n>; result=success; dimensions=768
```

The exact formatted character count depends on the stable query prefix; compare p50/p90 for the same
payload length. If the Qualcomm-targeted bundle is not accepted by GPU or CPU, the failure reason
is logged and the other backend still gets its own attempt.

## 4. Memory scale cap (P3)

The binary snapshot permits at most 8,000,000 float values and 128 MiB per snapshot. At 768
dimensions, 100k records would require 76.8 million float values before metadata, so it cannot fit
the existing codec limits. The benchmark profiles are now 1k / 5k / 10k rather than advertising an
unreachable 100k tier. The vector and text codec record caps are aligned at 50k.

Reproduce with the unit-test command in section 1; the test
`recordLimitMatchesMemoryTextStoreLimit` checks the aligned caps.

## 5. Diagnostic export and duplicate close logs (P4)

- The export action is disabled while the matrix is running; the storage layer also rejects an
  export that was already open when the matrix started. This prevents a mid-matrix partial snapshot.
- The matrix no longer calls `close()` both before and after each case. Expect one
  `embedding.engine.closed` event per completed matrix case, rather than a redundant empty close
  at the start of each case.
- The build workflow emits `npu_status=packaged_not_device_verified` plus
  `npu_evidence_policy=runtime-generated-json-must-match-current-apk`. The workflow cannot claim
  a device result before a device has generated the evidence file.

If export is attempted through an already-open picker during the matrix, the expected exception/log
message is `Diagnostic export is paused while the NPU input matrix is running.`; the app catches
the failure and shows a retry message rather than exporting a truncated log.

## 6. PR #57 / #61 CI conflict resolution

For `.github/workflows/ci.yml`, PR #57's removal of the routine `apk-build` job is retained.
PR #61's branch-name exclusions remain on `instrumented-tests` for both
`codex/mistbell-development-3.0` and `codex/mistbell-3.0-core-features`. No main/base branch was
modified and PR #61 remains Draft.

## Rollback

All work is on the PR #61 feature branch; do not reset or force-push the branch. The changes share
some Kotlin files, so if rolling back the entire round, revert commits in reverse chronological
order and rerun section 1:

```bash
git revert d0db1dd1d10f2a06040fde006b20acf28f9944d0
git revert d97c02fb79e2abcebb5593af864b96bb97c4bc40
git revert a54a215021e79f263a5a7794a90d403a1b825a81
git revert 362aed5bacad06b2d7c6a6574c0ce1ffbb2a93cb
git revert 66e8a9ff05fb5c1d755fd0dc841bfb875e0da66d
git revert a5c773f5c3db8f63ed3e3a17d49c60086808ccbc
```

For a single feature rollback, prefer a new corrective commit that removes only that feature and
preserves shared engine changes; do not cherry-pick or revert overlapping engine commits blindly.


## 7. Additional AI-audit findings that were tested

- The native-library audit previously used an over-escaped RPATH/RUNPATH regular expression. It now uses the ELF dynamic-tag syntax and runs a synthetic RUNPATH detector self-test before auditing libraries. Expected line: `RPATH/RUNPATH detector self-test passed.`
- The optional generic device-test APK job previously depended on `mistbell-v3/BUILD_DEVICE_APK`, a marker that remained in the PR branch and therefore caused repeated APK builds on every PR update. The marker has been removed; the job is now gated by the explicit `workflow_dispatch` boolean input `build_device_test_apk`, default false. Routine PR CI remains compile plus JVM tests.
- Other audit suggestions were checked against the actual source/workflow rather than automatically applied. For example, the final APK workflow checks out `github.sha` and runs compile/unit tests before packaging; the model import path also validates the full model SHA against the selected profile. Those claims did not justify weakening or replacing the existing checks.
