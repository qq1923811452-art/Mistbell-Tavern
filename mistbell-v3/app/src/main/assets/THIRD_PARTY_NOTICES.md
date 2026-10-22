# Third-Party Notices

Mistbell Tavern 3.0 (`mistbell-v3/`) is an independent implementation. The archived 2.x
application under tag `2.x-final` is covered by the repository root `LICENSE` (MIT); the 3.0
application does not include that code.

This file lists third-party components that are bundled with, or downloaded by, the 3.0 app.
Full license texts are kept next to this file when required by the license.

## Bundled Android / Kotlin libraries

| Component | Version | License |
|---|---|---|
| AndroidX Activity, Compose (UI, Material3, Animation, Graphics), Lifecycle, WorkManager | BOM-managed | Apache License 2.0 |
| Kotlin standard library, kotlinx.coroutines | via Kotlin 2.4.x | Apache License 2.0 |
| OkHttp | 4.12.0 | Apache License 2.0 |
| org.json (Android platform) | platform | Apache License 2.0 |

Apache 2.0 obligations: retain the license text, provide NOTICE content if present, and state
that the files were modified where applicable. See `LICENSES/Apache-2.0.txt`.

## On-device inference runtime

| Component | Version | License | Notes |
|---|---|---|---|
| LiteRT-LM (Google AI Edge) | 0.18.0 | Apache License 2.0 | JNI + Java API |
| Dawn / WebGPU native libraries | bundled with LiteRT-LM | BSD-3-Clause | `libwebgpu_dawn.so` |
| LiteRT GPU / OpenCL samplers | bundled with LiteRT-LM | Apache License 2.0 | `libLiteRt*Accelerator.so`, `libLiteRtTopK*Sampler.so` |

## Qualcomm AI Engine Direct (QAIRT) — NPU runtime

| Component | License | Notes |
|---|---|---|
| `libQnnHtp*.so`, `libQnnSystem.so`, `libQnnHtpV75Skel.so`, `libQnnHtpPrepare.so` | Qualcomm SDK license | Bundled only in the NPU build (`enableQualcommNpuRuntime`) |

**Redistribution has not been confirmed.** The Qualcomm SDK terms may not permit shipping these
runtime libraries inside a publicly distributed APK. Before publishing an NPU-enabled build,
verify the QAIRT license terms; otherwise distribute a CPU/GPU build and fetch the runtime
separately.

## Models (not bundled)

| Model | License | Notes |
|---|---|---|
| EmbeddingGemma 2 (int4) | Gemma Terms of Use + Gemma Prohibited Use Policy | Downloaded by the user; not part of the APK |
| LiteRT-LM chat model (user selected) | Defined by the model provider | User supplied |

The app must present the model terms before download and keep them reachable afterwards.

## Assets

Background art and character avatars generated for this project are original assets and are not
covered by third-party licenses. User-imported character cards remain the property of their
authors.

## Updating this file

`./gradlew -p mistbell-v3 :app:dependencies` lists the Java graph. Native libraries can be listed
with `unzip -l app-debug.apk | grep 'lib/arm64-v8a/'`. Add new entries in the same section and
record the license before release.

## User content and fan works

| Content | Status | Notes |
|---|---|---|
| User-imported character cards (JSON/PNG) | Not bundled | Imported at runtime from the user's device; the app does not redistribute them |
| User-imported world books | Not bundled | Same as above |
| Character artwork and backgrounds supplied by the user | Not bundled | Used only on the user's device; rights remain with their creators |
| Third-party fictional settings (e.g. 崩坏学园2, Fate, Cthulhu Mythos) | Fan use | Names and settings belong to their respective rights holders; the app ships no such text by default |

When distributing an APK, do not include imported cards or world books in the package, and do not
present bundled sample content as licensed third-party material.

## Model terms in the app

`EmbeddingGemma 2` and any downloaded chat model require presenting their terms before download and
keeping them reachable afterwards. The current build shows the download source but does not yet
provide a dedicated terms screen; add one before a public release.
