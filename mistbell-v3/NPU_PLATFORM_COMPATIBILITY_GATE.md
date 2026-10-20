# Mistbell 3.0 — Qualcomm NPU platform compatibility gate

Status: source-level compatibility finding; **not a claim of successful NPU inference**.

Target device: Redmi K70 Pro / SM8650 / Android arm64-v8a.
Current app module: `mistbell-v3/app`.
Current LiteRT-LM dependency: `com.google.ai.edge.litertlm:litertlm-android:0.18.0`.
Current app `minSdk`: **26**.

## Finding: the NPU platform floor must be handled separately

Google's current LiteRT NPU Android packaging guide says NPU support requires Android API 31+ and arm64-v8a, and its Qualcomm runtime packaging guidance uses legacy JNI library packaging. The app currently has `minSdk = 26`. Do **not** raise the entire app's minimum SDK just to enable NPU: that would unnecessarily block CPU/GPU users on older supported Android versions.

Required implementation policy:

1. Keep the app's current general minimum SDK unless product requirements explicitly change.
2. Before attempting Qualcomm NPU initialization, check `Build.VERSION.SDK_INT >= 31` and `Build.SUPPORTED_ABIS` includes `arm64-v8a`.
3. If either check fails, mark NPU as `UNSUPPORTED_PLATFORM` with a user-readable reason, and continue through the configured GPU/CPU policy. Do not report NPU as available merely because the dispatch library is present.
4. Keep platform support distinct from runtime availability, model compatibility, backend selection, and successful inference. These are separate states.
5. Do not enable the NPU option for the current EmbeddingGemma 2 model until the exact model/runtime combination has passed a real inference test on SM8650. Google's LiteRT-LM Qualcomm NPU support table documents Gemma3-1B for SM8650; that table is not itself evidence that this exact embedding model works on that backend.

## Dependency and packaging gates

- Qualcomm runtime libraries must be a pinned, mutually compatible LiteRT/LiteRT-LM + QAIRT set for Android arm64-v8a and SM8650/Hexagon V75.
- Before adding any SDK library, record its official source/revision, SHA-256, ABI/architecture, license and redistribution status.
- Inspect `DT_NEEDED` for every packaged native library and check the complete dependency graph. Do not infer the runtime inventory from filenames alone.
- Treat `libQnnHtpPrepare.so` as a required candidate because the official LiteRT-LM Qualcomm guide explicitly stages it, but only the pinned build's dependency graph and device logs can establish whether it is required for this specific path.
- Do not bundle device-provided OpenCL drivers, firmware, or random vendor libraries from another phone.
- Routine CI should not build an APK. Use JVM/clean-room checks for source changes; reserve APK creation, installation, and real device inference for final acceptance.

## Evidence references

- LiteRT NPU Android requirements and Qualcomm runtime packaging: https://developers.google.com/edge/litert/next/npu
- LiteRT-LM Qualcomm NPU instructions and currently listed supported models: https://developers.google.com/edge/litert/next/litert_lm_npu
- LiteRT-LM Android Kotlin API, including `Backend.NPU(nativeLibraryDir = ...)`: https://developers.google.com/edge/litert-lm/android

## Acceptance criteria

NPU is only considered working after a real embedding call on the Redmi K70 Pro returns a non-empty finite vector of the expected dimension, and logs prove the selected backend was NPU without silent GPU/CPU fallback. Record model SHA-256, runtime manifest/version, latency, Android version, and relevant QNN/HTP errors. Test GPU and CPU independently; a failed NPU attempt must not make either path unusable.
