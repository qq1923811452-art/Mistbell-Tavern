# Mistbell Tavern 3.0 — Native Runtime Dependency Matrix

Status: investigation and packaging checklist; **not a claim that GPU/NPU works yet**.
Target device: Redmi K70 Pro, Snapdragon 8 Gen 3 / SM8650, Android arm64-v8a.
Current app runtime: LiteRT-LM Android AAR 0.18.0.

This document deliberately distinguishes (1) libraries that belong in the APK, (2) device/OS-provided libraries, and (3) build-time or conditional dependencies. Do not package a library solely because its filename looks relevant. Pin its source revision, verify ABI and transitive dependencies, check redistribution terms, then prove it with real inference on the target device.

## 1. Common LiteRT-LM runtime

| Component | Expected handling | Validation |
|---|---|---|
| `com.google.ai.edge.litertlm:litertlm-android:0.18.0` AAR and its transitive Android/JNI dependencies | Gradle dependency; let Gradle resolve the exact version | Record resolved dependency graph and inspect final APK's native entries |
| LiteRT-LM upstream Android `prebuilt/android_arm64/*.so` | APK `jniLibs/arm64-v8a/` for the pinned v0.18.0 device-test build | Record upstream tag commit, per-file SHA-256, license, ABI and final APK contents |
| C/C++ runtime such as `libc++_shared.so`, if any resolved native object needs it | Include only if required by ELF dependency inspection; use a compatible single copy | Inspect `DT_NEEDED` for every packaged .so; reject conflicting duplicate runtime copies |
| Android system libraries (for example `liblog.so`, `libandroid.so`, `libdl.so`, `libm.so`, `libc.so`) | Supplied by Android; do not copy system stubs into APK | Resolve every ELF dependency against Android NDK/API level and device |

## 2. GPU path

### App-packaged candidates
- All relevant official LiteRT-LM v0.18.0 `prebuilt/android_arm64/*.so`, not just the two currently preflighted plugin names.
- Explicitly verify `libLiteRtGpuAccelerator.so` and `libLiteRtOpenClAccelerator.so`.
- Inspect the complete set for other plugins (for example TopK/sampler or WebGPU components). Include only components that are required by this app/runtime configuration, while preserving upstream provenance and license notices.

### Device-provided requirements
- A compatible system OpenCL implementation/driver. **Do not bundle Qualcomm's proprietary device driver or copy random system-driver .so files from another phone.**
- `AndroidManifest.xml` has optional `uses-native-library` declarations for `libvndksupport.so` and `libOpenCL.so`. These declarations only describe optional device-provided libraries; they do **not** make those files APK payloads or guarantee availability on every ROM.
- The ELF audit classifies these two dependencies separately from public Android NDK libraries and now fails if either is accidentally placed in `jniLibs/arm64-v8a/`.

### Acceptance gate
1. Build the manual device-test APK from the pinned source.
2. Inspect APK contents and ELF dependencies.
3. Install on the Redmi K70 Pro and run a real embedding request.
4. Confirm a finite, non-empty vector with expected model dimensions and record latency/backend/fallback.
5. If GPU initialization succeeds but inference fails, report GPU as failed and preserve the root-cause logs.

## 3. Architecture gate: validate the embedding API path before bundling NPU libraries

**New priority: do not start by adding Qualcomm libraries to the current Kotlin LiteRT-LM engine.** The official LiteRT documentation separately lists an **EmbeddingGemma semantic-similarity C++ sample with CPU/GPU/NPU inference** using the LiteRT CompiledModel API, while the current Mistbell adapter uses LiteRT-LM's Kotlin EmbeddingEngine API. These are different runtime/API paths. The existence of an NPU dispatch library does not establish that the current Kotlin API exposes or can execute the required Qualcomm backend.

**Upstream sample inspection (pinned snapshot):** Google AI Edge `litert-samples` commit `d15f8ae929ebeb0e404c4d3e0cd28e2061e15aac`, path `samples/litert/semantic_similarity/build_from_source/`. Its README describes a C++ LiteRT CompiledModel app and an Android deploy script that can request `--accelerator npu --soc_man Qualcomm`. The default embedder is `embeddinggemma-300M_seq256_mixed-precision.tflite`, based on the `embeddinggemma-300m` model; this is **not** the exact Mistbell Qualcomm SM8650 `.litertlm` model. The README also says precompiled NPU models are not yet available, so this is a useful runtime/dependency reference, not proof that our exact model is NPU-compatible.

**Specific dependency gap to investigate:** at that pinned snapshot, the Qualcomm branch of `deploy_and_run_android.sh` pushes `libLiteRtDispatch_Qualcomm.so`, `libQnnHtp*Stub.so`, `libQnnHtp.so`, `libQnnSystem.so`, and matching `libQnnHtp*Skel.so`. It does **not visibly push `libQnnHtpPrepare.so`**. The official LiteRT NPU guide lists `libQnnHtpPrepare.so` among example Qualcomm host runtime dependencies. This is a concrete candidate omission in that sample script, but it must be verified against the exact LiteRT/QAIRT revision and ELF/runtime logs before declaring it the root cause. Also inspect all wildcard matches: do not assume the wildcard includes every needed HTP file or calculator stub.

Required sequence:

1. Pin and inspect the official EmbeddingGemma semantic-similarity C++ sample, its exact LiteRT revision, model format/revision, build steps, and Qualcomm runtime acquisition method. The reference snapshot inspected so far is `google-ai-edge/litert-samples@d15f8ae929ebeb0e404c4d3e0cd28e2061e15aac`.
2. Establish whether the user's exact Qualcomm SM8650 EmbeddingGemma 2 model is supported by that sample/runtime path. Do not substitute a Gemma 3 LLM model as proof of embedding-model support.
3. Build and run the official sample on an SM8650 device (or a reproducible device-test environment) with a small fixed embedding input. Capture the selected backend, output dimensions, finite-value check, latency, and QNN/HTP runtime logs. A successful build alone is not acceptance.
4. If the reference sample performs real NPU inference, evaluate a small JNI bridge or a supported integration path for Mistbell. Keep it isolated behind the existing embedding interface; do not replace the current CPU/GPU path.
5. Only then pin and package the exact required Qualcomm payload, subject to SDK redistribution terms and ELF dependency checks. If the sample cannot run the exact model, document the blocker and keep CPU/GPU usable rather than falsely enabling NPU.

Official reference: https://developers.google.com/edge/litert/next/npu (lists the EmbeddingGemma semantic-similarity C++ app for CPU/GPU/NPU) and https://developers.google.com/edge/litert/next/litert_lm_npu (documents the distinct LiteRT-LM Qualcomm NPU path).

## 4. Qualcomm NPU path — SM8650 / Hexagon V75

### Required core runtime candidates
These must come from a mutually compatible, pinned LiteRT-LM/LiteRT/QAIRT combination. The list is a checklist, not a claim that every file is always required by every build:

- LiteRT-LM v0.18.0 Android runtime/JNI dependencies from the exact AAR.
- `libLiteRtDispatch_Qualcomm.so`: build from the LiteRT revision vendored/pinned by the selected LiteRT-LM source, using the official Bazel target `@litert//litert/vendors/qualcomm/dispatch:dispatch_api_so`. The official guide requires NDK r28b or newer for the Android build. Do not use a random prebuilt dispatch binary from another release.
- The complete relevant Qualcomm dispatch/compiler shared-object outputs under `bazel-bin/external/litert/litert/vendors/qualcomm/*/*.so`, plus the complete `prebuilt/android_arm64/*.so` runtime set from the same LiteRT-LM checkout, as prescribed by the official LiteRT-LM NPU instructions. Inspect each candidate and include only files the selected target/model path needs; keep provenance for every file.
- QAIRT Android host runtime from one pinned SDK release: `libQnnSystem.so`, `libQnnHtp.so`, `libQnnHtpPrepare.so` and all `libQnnHtp*Stub.so` files required by that SDK/model.
- SM8650 Hexagon V75 DSP-side components from the same QAIRT release: matching `libQnnHtp*Skel.so` files from the SDK's `lib/hexagon-*/unsigned/` folder. Confirm that the selected files are V75/SM8650 compatible; do not rename a V73/V79 library to V75.
- Other QAIRT support files such as `libQnnIr.so`, `libQnnSaver.so`, `libLiteRtCompilerPlugin_Qualcomm.so`, or `libQnnHtpNetRunExtensions.so` are conditional. The official NPU flow's full output inventory and the ELF `DT_NEEDED` graph determine whether they are needed; do not guess based on names alone.
- Any additional dependencies revealed by the selected SDK's ELF `DT_NEEDED` graph (for example `libQnnIr.so`, `libQnnSaver.so`, `libQnnHtpNetRunExtensions.so`, or other QNN support libraries). These are conditional: only include if the chosen LiteRT-LM backend/model path requires them.
- Any version-specific worker/helper required by the exact upstream implementation. Do not add a third-party `libLiteRtQnnWorker.so` or patched dispatch package unless the pinned upstream source/build proves it is required and its origin/license are documented.

### AOT versus JIT / compile-at-runtime distinction
- The selected Qualcomm `.litertlm` model may contain precompiled/model-specific assets. Confirm the official model card and the LiteRT-LM v0.18.0 code path before choosing the runtime set.
- If the path uses a precompiled context/AOT asset, the Qualcomm compiler plugin may not be required.
- If the path performs on-device JIT/compiler-plugin work, evaluate `libLiteRtCompilerPlugin_Qualcomm.so` plus the additional QNN compiler dependencies (commonly including `libQnnIr.so` and `libQnnSaver.so`, and possibly the HTP prepare/compiler support). Do not assume these are needed or unnecessary without verifying the exact code path.
- Keep all HTP stub, skeleton, calculator-stub and host runtime files from a single compatible QAIRT release. The skeleton is loaded by the DSP and is not interchangeable with an Android host .so.

### System / device requirements — not APK payloads
- Qualcomm firmware, DSP/HTP support, vendor driver and device-specific system integration already provided by the installed ROM.
- The actual SM8650/V75 support must be verified on the user's Redmi K70 Pro. APK libraries cannot install or replace kernel drivers, DSP firmware, vendor partitions or missing system services.
- Required runtime search paths/environment behavior must match LiteRT-LM's Android API. Use `context.applicationInfo.nativeLibraryDir` for bundled NPU libraries where the API requires it; test whether APK packaging mode exposes files to DSP loading as expected.

### Official build recipe and model-support gate
- Official LiteRT-LM NPU guide: https://developers.google.com/edge/litert/next/litert_lm_npu. Its Qualcomm flow downloads QAIRT, builds the Qualcomm dispatch target, then stages the QAIRT host libraries, HTP stub/skeleton files, the Qualcomm shared-object outputs and `prebuilt/android_arm64/*.so`.
- The guide currently lists a Gemma 3 1B Qualcomm model for SM8650. It does **not** by itself prove that the selected EmbeddingGemma 2 model can execute on the NPU through the same path. Confirm the exact EmbeddingGemma 2 model card/runtime requirements and run that exact model on SM8650 before advertising NPU support.
- QAIRT versions are released separately from LiteRT-LM. Use the version explicitly validated with the chosen LiteRT/LiteRT-LM source and model; a version number shown as an example in docs is not an automatic compatibility guarantee.

### Build integration added (not yet device-verified)

- `mistbell-v3/app/build.gradle.kts` now configures legacy JNI packaging and an opt-in `packageQualcommNpuRuntime` Gradle task. Ordinary builds keep emulator/CPU-GPU ABI flexibility; NPU packaging builds are arm64-only.
- `.github/scripts/package-litertlm-qualcomm-runtime.sh stages an explicit HTP V75 allowlist from a locally installed QAIRT SDK plus a separately built `libLiteRtDispatch_Qualcomm.so`. It writes a separate `qualcomm-sm8650.properties` manifest and SHA-256 inventory. The manifest is deliberately set to `packaged_not_device_verified` with ABI fields unverified, so the existing runtime gate continues to reject NPU until compatibility and real device inference are proven.
- This script does **not** download QAIRT, build the dispatch plugin, or assert that any arbitrary QAIRT version is compatible. Those inputs must be pinned and reviewed first. Invoke the opt-in build only after setting `QUALCOMM_QAIRT_ROOT`, `QUALCOMM_QAIRT_VERSION` and `LITERT_QUALCOMM_DISPATCH_SO`:

  ```bash
  QUALCOMM_QAIRT_ROOT=/path/to/qairt/<exact-version> \\
  QUALCOMM_QAIRT_VERSION=<exact-version> \\
  LITERT_QUALCOMM_DISPATCH_SO=/path/to/libLiteRtDispatch_Qualcomm.so \\
  ./gradlew :app:assembleDebug -PenableQualcommNpuRuntime=true
  ```

- After staging, run the native ELF audit and inspect the final APK's `lib/arm64-v8a/` entries. Do not set manifest fields to `verified` until the exact LiteRT-LM 0.18.0 + QAIRT/dispatch combination passes real inference on the Redmi K70 Pro.

### Required compatibility evidence before packaging
- Exact LiteRT-LM v0.18.0 source tag **and full tag commit SHA**.
- The LiteRT revision used by that tag/AAR and the matching Qualcomm dispatch build provenance.
- Exact QAIRT version/build, download source, checksum, Android ABI, Hexagon architecture (V75), and the complete selected library inventory.
- `readelf -d` / equivalent dependency graph for every candidate .so; no unresolved `DT_NEEDED` entries and no accidental mixing of incompatible versions.
- License and redistribution review for each Qualcomm SDK/runtime artifact. If redistribution is not permitted or unclear, do not embed it in the APK; document a lawful acquisition/install path instead.
- Model ID/revision/SHA-256 and confirmation that the Qualcomm SM8650 model is designed for the selected runtime combination.
- Runtime logs showing successful NPU inference on the target phone, not merely plugin discovery or engine initialization.

## 4. Do not package these blindly

- `libOpenCL.so` copied from another handset or vendor image.
- `libQnn*.so`, V75 skeleton/stub files, or dispatch libraries taken from unrelated SDK releases, another SoC, or an unknown GitHub release.
- Host Linux libraries or libraries built for a different Android ABI/API level.
- Duplicate/conflicting `libc++_shared.so` copies.
- Build tools, host compilers, `litert_lm_main`, debug utilities, or SDK binaries that are not runtime dependencies of the Android app.
- Proprietary firmware, system/vendor partitions, or DSP skeletons of the wrong architecture.
- Experimental community patches/workers unless explicitly reviewed, pinned, licensed, and required by the exact implementation.

## 5. Build pipeline gates to implement

- [x] Pin full upstream commit SHA for LiteRT-LM v0.18.0 in addition to checking the tag name.
- [x] Download official GPU prebuilts reproducibly; record source URL, tag/commit, file name, byte size and SHA-256. (NPU inventory remains blocked on approved QAIRT/dispatch source.)
- [x] Preserve the upstream LiteRT-LM license in the GPU device-test APK; additional notices remain required for any future third-party runtime.
- [x] Validate the selected GPU prebuilt files are non-empty and expected plugin files exist; final APK ELF audit checks AArch64 and non-empty libraries.
- [x] Inspect final APK ELF architecture, RPATH/RUNPATH and `DT_NEEDED` dependencies; fail build for unknown dependencies.
- [x] Keep `libOpenCL.so` and `libvndksupport.so` classified as device/vendor-provided dependencies rather than public NDK libraries; reject accidentally bundling them in APK `jniLibs/`.
- [x] Keep GPU and NPU inventories/provenance separate.
- [x] Add an opt-in Gradle packaging task and a strict local-QAIRT staging script; normal CI does not require proprietary SDK files.
- [ ] Make a reproducible Qualcomm runtime acquisition/build job available after QAIRT source/version/license and dispatch compatibility are approved; never fetch an unpinned SDK or silently accept a floating latest version.
- [x] Generate a GPU runtime manifest in the APK with upstream source revision, library hashes and `validation_state=packaged_not_device_verified`; manifest presence does not imply device verification.
- [x] Generate a separate Qualcomm/NPU runtime manifest and SHA-256 inventory during opt-in staging; it remains explicitly unverified and cannot enable NPU yet.
- [ ] Add tests for missing file, zero-byte file, wrong SoC generation, missing manifest, incompatible version, and unverified runtime.
- [ ] Final device acceptance separately verifies CPU, GPU and NPU real inference and records each backend's actual result/fallback reason.

## 6. Current state

- GPU packaging: Clean-Room CI run `38992442688` succeeded for source `f16ee722cf281707701fca0bbc5c23cc1aad9b4b` and published device-test artifact `mistbell-v3-device-test-apk-436` (artifact ID `12086265080`). The APK's `SOURCE.txt` matches that exact commit; `SHA256SUMS.txt` validates the APK; all seven GPU library hashes embedded in `assets/native-runtime/litertlm-gpu.sha256` match the actual `lib/arm64-v8a/*.so` bytes. This verifies reproducible packaging and integrity checks only. Real GPU inference on the Redmi K70 Pro is still required.
- NPU: Gradle packaging integration and a strict staging script now exist. The actual Qualcomm .so payload is **not yet staged in this branch** because no approved local QAIRT SDK and matching dispatch build were available to this edit. QAIRT/dispatch compatibility, redistribution conditions and device inference remain unverified; NPU remains disabled.
- CPU: engine initialization previously succeeded, but a real inference call failed in the device diagnostic. CPU is not accepted until that is fixed and a real vector is returned.
- PR #61 must remain Open + Draft until CI, runtime verification and the user's device acceptance are complete.

## Official references

- LiteRT-LM Android setup and native-library directory guidance: https://developers.google.com/edge/litert-lm/android
- LiteRT-LM Qualcomm NPU build guide (QAIRT SDK, dispatch target, runtime payload): https://developers.google.com/edge/litert/next/litert_lm_npu
- LiteRT-LM Android setup (GPU plugin libraries and NPU nativeLibraryDir): https://developers.google.com/edge/litert-lm/android
- LiteRT Qualcomm HTP instructions (host runtime, dispatch and V75 skeleton guidance): https://github.com/google-ai-edge/LiteRT/blob/main/litert/vendors/qualcomm/doc/HTP_INSTRUCTIONS.md
- EmbeddingGemma 2 Qualcomm SM8650 model file and pinned SHA-256: https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/blob/main/embeddinggemma-2-740m_Qualcomm_SM8650.litertlm

