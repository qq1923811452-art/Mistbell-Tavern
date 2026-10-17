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
- Android native-library declarations already present in `AndroidManifest.xml` for optional `libvndksupport.so` and `libOpenCL.so`; avoid duplicate declarations.

### Acceptance gate
1. Build the manual device-test APK from the pinned source.
2. Inspect APK contents and ELF dependencies.
3. Install on the Redmi K70 Pro and run a real embedding request.
4. Confirm a finite, non-empty vector with expected model dimensions and record latency/backend/fallback.
5. If GPU initialization succeeds but inference fails, report GPU as failed and preserve the root-cause logs.

## 3. Qualcomm NPU path — SM8650 / Hexagon V75

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
- [ ] Keep GPU and NPU inventories/provenance separate.
- [ ] Make Qualcomm runtime acquisition an explicit gated job until QAIRT source/version/license are approved; never fetch an unpinned SDK or silently accept a floating latest version.
- [ ] Generate a runtime manifest in the APK with source revisions, versions, hashes and validation state. Presence of a file must not set `status=verified`.
- [ ] Add tests for missing file, zero-byte file, wrong SoC generation, missing manifest, incompatible version, and unverified runtime.
- [ ] Final device acceptance separately verifies CPU, GPU and NPU real inference and records each backend's actual result/fallback reason.

## 6. Current state

- GPU: workflow fetches LiteRT-LM v0.18.0 prebuilts pinned to commit `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27`, records provenance/license/hash/inventory, then audits the merged APK's ELF architecture and dependencies. This workflow has not yet been run successfully; real Redmi K70 Pro inference is still required.
- NPU: not yet packaged; QAIRT/dispatch version compatibility and redistribution conditions are not yet verified.
- CPU: engine initialization previously succeeded, but a real inference call failed in the device diagnostic. CPU is not accepted until that is fixed and a real vector is returned.
- PR #61 must remain Open + Draft until CI, runtime verification and the user's device acceptance are complete.

## Official references

- LiteRT-LM Android setup and native-library directory guidance: https://developers.google.com/edge/litert-lm/android
- LiteRT-LM NPU guide (QAIRT and dispatch build/payload): https://developers.google.com/edge/litert-lm/npu
- LiteRT Qualcomm HTP instructions (host runtime, dispatch and V75 skeleton guidance): https://github.com/google-ai-edge/LiteRT/blob/main/litert/vendors/qualcomm/doc/HTP_INSTRUCTIONS.md

