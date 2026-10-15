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
- The approximately 165 MB `.litertlm` model is downloaded on first activation into app-private storage over HTTPS, written to a temporary file, size-checked, and atomically renamed. Inference is offline after a successful download. Do not bundle the model into the APK.
- GPU initialization is attempted first; if it fails, initialize the same model on CPU. If both fail, mark vector embedding unavailable and use the existing lexical fallback; never silently send memory text to the remote API.
- Use `task: search result | text:` for indexed memories and `task: search query | text:` for search requests. Normalize output vectors and validate the expected 768 dimensions.
- The model ID has its own namespace. Existing BGE/API vectors are not compatible even when dimensions happen to match; use the existing safe reindex flow before migrating stored memories.
- The initial download requires network access and about 165 MB of free storage plus temporary download space. Do not change the user's selected source if initialization fails.
- Published Android benchmarks use Pixel/Samsung devices, not Snapdragon 8 Gen 3. Validate latency, memory, thermals, battery impact and recall quality on the target phone before changing the default source. This first integration uses the portable CPU/GPU model; a device-specific Qualcomm NPU model can be evaluated separately after runtime-library compatibility is confirmed.

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