# Chat-Coupled Memory Pipeline

## Goals

Optimize the memory subsystem as part of the chat request path, not as an independent feature. The priority order is:

1. Preserve the longest possible byte-identical prompt prefix for provider/relay prompt caching.
2. Minimize per-turn prompt tokens and avoid duplicate facts/history.
3. Keep relevant long-term facts retrievable without blocking normal replies.
4. Make provider/model switching safe and observable.
5. Never persist mock embeddings or let memory failures fail a chat turn.

## Request-time path

```text
User sends a turn
  -> resolve chat/provider config
  -> load stable prompt components (main prompt, character/persona, constant worldbook)
  -> retrieve bounded structured-memory candidates and rank against the current user turn
  -> retrieve semantic vectors when a real embedding provider is available
       otherwise use lexical history retrieval
  -> deduplicate vector summaries against structured-memory facts and visible history
  -> select history within the existing context-token budget
  -> assemble stable prefix first; append history and dynamic memory/worldbook near the tail
  -> append the current user turn last
  -> send chat request and record provider-reported usage/cache telemetry
```

The prompt builder already places long-term recall after history so per-turn memory changes do not invalidate the stable character/system prefix. Do not move query-dependent memories, timestamps, access counters, or per-turn diagnostics into that stable prefix.

## Background write path

```text
Persist completed user/assistant turn
  -> append it to the per-session extraction buffer
  -> after 3 turns (or explicit flush), dispatch extraction asynchronously
  -> parse schema-validated fact candidates
  -> deduplicate/merge against structured memory and preserve negation/time/entity distinctions
  -> persist structured memory as the source of truth
  -> if importance qualifies, create/update its model-namespaced vector summary
  -> on edit/delete, remove the matching summary vector
```

Extraction must not delay the chat response. A future dedicated memory-LLM configuration should have its own base URL, API key, model ID, timeout and retry policy; it must not silently reuse the chat model unless explicitly configured to do so. Persist a processed-message watermark/idempotency key so process death or retry cannot repeatedly charge for the same turns.

## Embedding and index invariants

- Embedding model identity is part of vector metadata and the retrieval filter. Equal dimensions do not imply compatible semantic spaces.
- Query vectors and stored vectors must have compatible dimensions. Incompatible historical vectors are skipped instead of crashing the entire search.
- Switching the configured source refreshes the active service wrapper. The old model's vectors remain isolated and can be retrieved again when switching back to that same model identity.
- Existing vectors written before model identity was added have no model namespace and are therefore not selected by the new filter. A deliberate re-embedding/reindex path is needed to migrate them; do not guess their original model.
- Vector writes use a stable ID derived from message identity and embedding model; unchanged content reuses the persisted vector, while changed content overwrites that model's prior vector instead of adding a duplicate.
- Mock embeddings are placeholders only. When the real embedding service is unavailable, use lexical fallback and do not write mock vectors.
- Search-cache keys include the exact query vector, top-K, and normalized filters. Cache writes are rejected if a mutation invalidated the cache while a search was in flight.

## On-device EmbeddingGemma 2 (LiteRT-LM)

- A separate `local-gemma` source uses Google's text-only EmbeddingGemma 2 through the Android LiteRT-LM Kotlin API; the existing ONNX BGE Chinese backend and remote OpenAI-compatible Embedding API remain available.
- The approximately 208 MB SM8650-specific `.litertlm` model is downloaded on first activation into app-private storage over HTTPS, written to a temporary file, size-checked, SHA-256 verified against the pinned model file, and atomically renamed. Inference is offline after a successful download. Backend order for this model is Qualcomm NPU → GPU → CPU. NPU execution requires a compatible LiteRT-LM Qualcomm dispatch library and matching QAIRT/QNN native libraries to be packaged in the app; if they are missing or initialization fails, the runtime logs the reason and falls back to GPU/CPU. Do not bundle the model into the APK.
- The general text-only EmbeddingGemma 2 model is approximately 165 MB and is intended as the compatibility option for ordinary LiteRT-LM GPU/CPU inference when the Qualcomm-specific runtime is unavailable. Model files must have pinned revisions and SHA-256 validation. Keep the selected model identity in vector metadata so switching variants never compares incompatible vector spaces. If both local variants fail, mark vector embedding unavailable and use the existing lexical fallback; never silently send memory text to the remote API.
- Use explicit `embedDocument` and `embedQuery` roles: `task: search result | text:` for indexed memories and `task: search query | text:` for searches. Include the role in in-memory embedding cache keys so a query vector cannot be reused as a document vector. Normalize output vectors and validate the expected 768 dimensions.
- The model ID has its own namespace. Existing BGE/API vectors are not compatible even when dimensions happen to match; use the existing safe reindex flow before migrating stored memories.
- The initial download requires network access and about 208 MB of free storage plus temporary download space. Do not change the user's selected source if initialization fails.

- The manual final-validation workflow downloads QAIRT 2.50.0.260828 and builds `libLiteRtDispatch_Qualcomm.so` from the exact LiteRT source revision pinned by LiteRT-LM 0.18.0, then stages the SM8650 HTP libraries before building the APK. Routine CI deliberately skips this download, native build and APK packaging. The native binaries are generated in the runner and are not committed to the repository.
- A normal debug build without the matching dispatch/runtime files will safely skip the Qualcomm backend and use GPU/CPU. The app checks for the required files before attempting NPU initialization to avoid entering an incompatible native dispatch path. The chat status chip reports the selected backend and successful inference count; a successful model initialization alone is not treated as proof that NPU inference ran.
- For a local manual build, provide a compatible set under a JNI directory and pass `-PqualcommNpuLibDir=/path/to/qualcomm-jniLibs`. Validate the final APK on the target Snapdragon 8 Gen 3 device and confirm that the status chip reports NPU plus at least one successful inference before claiming hardware acceleration. Measure latency, memory, thermals, battery impact and recall quality before changing the default source.

## Token and cache policy

- Keep stable prompt sections byte-for-byte stable between adjacent turns where possible.
- Keep query-dependent memory, activated dynamic worldbook entries, and current user content after the stable prefix.
- Use bounded top-K plus a token budget; select complete facts/events, never cut facts mid-sentence.
- Deduplicate recalled vector summaries against structured memory and untruncated visible history.
- Prefer local embedding when its recall quality is adequate: it avoids a separate embedding API call and its billable input. Measure latency and recall quality before making it the default.
- Do not claim a cache hit rate when the provider/relay did not report trustworthy cache telemetry. Track hits, misses, unknown telemetry, chat input tokens, and extraction tokens separately.
- Do not log prompt text, memory contents, API keys, or user-derived text for diagnostics.

## Validation before merge

1. API -> local -> API source changes take effect without app restart.
2. Vectors from different model IDs never mix, including models with the same dimension.
3. Old vectors with no model metadata do not crash retrieval; reindex migration is separately tested.
4. A mock embedding provider writes no vectors; lexical fallback still supplies context.
5. Cache-key collision and search/write races cannot return cached results from a different query or stale index generation.
6. Batch embedding results are ordered by response index and reject duplicate indices, response-count mismatches, or unexpected dimensions.
7. Repeated chat turns preserve the stable prompt prefix; tests cover worldbook placement, group chat, @D placement, history truncation and duplicate-memory removal.
8. Report measured prompt tokens, cached tokens, uncached tokens, extraction cost, retrieval quality and latency for the same fixtures before and after changes.

## Current scope of this branch

This branch hardens vector-cache correctness, model isolation, atomic runtime source refresh, mock-vector avoidance, idempotent vector writes, and batch embedding response validation. It does not yet implement a dedicated memory-LLM settings screen, persistent extraction queue/watermark, or legacy-vector reindex UI. Those should be added as explicit follow-up work rather than hidden behind the current Embedding settings.

## Personal profile: Snapdragon 8 Gen 3 / Mistbell-Tavern

This configuration intentionally targets the owner's Snapdragon 8 Gen 3 (SM8650) phone and this app only. Do not add compatibility branches for other phone processors unless a later request explicitly asks for them.

### Embedding defaults and recall policy

- Start with the SM8650-specific text-only EmbeddingGemma 2 model. It is for text memory retrieval, not for generating chat replies or summarizing memories.
- Keep the native 768-dimensional output for the initial quality baseline. The model's supported Matryoshka dimensions are 768, 512, 256 and 128; 3072 is not a valid output dimension for this local model. Only switch to 256 after the personal retrieval evaluation shows no meaningful recall regression; use 128 for candidate shortlisting only if a second-stage ranker is actually implemented.
- Use separate query and document formatting, and pin that policy with tests. The model-card retrieval pair is `task: search result | query: {current user turn}` for queries and `title: none | text: {stored memory}` for untitled memory documents. If the exported LiteRT-LM package's verified reference implementation specifies a different pair, test both on the same personal fixture set before changing it.
- Do not embed the entire chat transcript, character card, or all worldbook text every turn. Embed durable memory facts, concise event summaries, character/profile facts, and worldbook entries when they are created or changed. Re-embed only changed text or when the embedding model/dimension changes.
- Retrieval should be hybrid: exact keywords/names/negation/time expressions plus semantic vector search. Filter by character, session, memory type and model identity before ranking. Merge duplicates while preserving contradictory facts and their timestamps.
- Prefer a small set of high-value facts under a token budget over a fixed large Top-K. Keep complete fact/event units; never truncate a fact mid-sentence. Keep the stable system/character prefix byte-identical where possible and place per-turn recall and activated worldbook entries after that prefix.
- Keep structured memory as the source of truth. Vector embeddings are an index, not the only copy of a memory. Local embedding avoids a separate embedding API charge; it does not itself reduce chat prompt tokens. Prompt-token savings come from better retrieval, deduplication, concise memory wording and bounded injection.
- Log only counts, timing, backend, dimension, model variant, retrieval scores and provider-reported token/cache usage. Never log prompt text, secrets or private memory contents.

### Personal character-card writing rules

1. Put stable identity, voice, boundaries, motivations and relationship dynamics in the main character description. Avoid repeating the same facts in the system prompt, personality, scenario and example dialogues.
2. Separate permanent canon from current scene state. Permanent canon belongs in the card or a small always-on worldbook entry; temporary location, mood, injuries, current goal and recent events belong in session memory and should be retrieved when relevant.
3. Express facts in short, atomic, unambiguous sentences. Prefer “Mira trusts Rowan after the bridge rescue” over vague fragments such as “trust / bridge / Rowan”. Keep names, aliases and relationships explicit so lexical and semantic retrieval both work.
4. Mark time and status when facts can change: “As of the last scene, ...”, “Formerly ...”, “Currently ...”. When a fact changes, update or supersede the old one instead of leaving both as timeless truths.
5. Keep examples representative and short. Do not include a large example transcript just to demonstrate tone; a few distinct examples are better than repeating the same voice traits several times.

### Personal worldbook writing rules

- **Constant / always-on:** only compact, truly global rules needed in nearly every turn. Keep this section very short because it sits in the stable prompt prefix and costs tokens every turn.
- **Keyword-activated entries:** use a small set of distinctive names, aliases, places, objects and event terms. Avoid generic triggers such as “look”, “feel”, “person” or “day” that match frequently.
- **Secondary keys:** use them as a meaningful AND condition, e.g. a character name plus a specific location or event. Do not add many broad secondary terms that prevent valid recall.
- **One entry, one subject:** separate a location, faction, character relationship and historical event into different entries. This makes activation and token budgeting more precise.
- **Entry body:** write the minimum complete context needed for the chat model to respond correctly: canonical fact, relevant constraint, and any important exception. Avoid meta commentary, duplicated character-card text and long prose the model can infer.
- **Recursion / links:** if one entry refers to another, include the exact target name as a trigger in the referenced entry. Keep chains short so recursive activation does not flood the prompt.
- **Do not hide critical safety or core personality rules in a rarely triggered entry.** Put those in the stable character/system prompt or a compact constant entry.
- The app's existing worldbook placement and activation rules still apply. These are writing conventions for this project, not a promise that arbitrary third-party Tavern card metadata is supported.

### Device-specific validation gate

- Record the exact device model/SM8650 identifier, Android version, app build SHA, model file SHA-256, selected backend, first-inference result, cold/warm latency, peak memory and thermal behavior.
- “NPU initialized” is not enough: show NPU as successfully used only after a real embedding inference returns a valid finite 768-dimensional vector. If the NPU path fails, show the actual fallback backend (GPU or CPU) and its reason.
- Compare retrieval quality using a small fixed personal set of questions and expected memories, including names/aliases, old events, negation, changed facts and irrelevant queries. Measure Recall@K / MRR and prompt tokens before and after; do not declare a token-saving percentage without those measurements.
- Routine CI runs compile, unit tests, Detekt, KtLint and license checks only. APK packaging and emulator/device validation run at the final verification stage.
