# Mistbell-Tavern Memory and Prompt-Cost Optimization Plan

Status: research proposal; no behavior changes implied by this document alone.

## Objective

Reduce actual prompt input tokens and cost while preserving roleplay continuity, worldbook semantics, and prompt-cache hit rate across DeepSeek and Gemini-compatible relays. Do not optimize token count in isolation: measure cache hits, uncached input, output quality, recall quality, and failure behavior together.

## Current implementation observations

- Prompt assembly separates stable character/system content from dynamic history, memory recall, activated worldbook entries, and the final user message.
- Activated non-constant worldbook entries are appended after history to protect the stable prefix. Constant entries remain in their semantic prompt positions.
- History selection keeps a continuous newest window and reserves space for the reply.
- Structured memories are selected from a bounded candidate pool, with always-keep high-importance facts, profile/relationship facts, and keyword matches.
- Semantic vector recall applies a configured threshold and top-k; when embeddings are unavailable, lexical history retrieval is used.
- Structured-memory summaries may also exist in the vector store; prompt construction already attempts to remove duplicates.
- When the full conversation history fits, the historical-recall block is omitted because those source messages are already present. When history is truncated, recall may be injected.
- Cache usage telemetry is being extended to recognize DeepSeek cache counters, OpenAI-compatible nested cached-token fields, and Gemini cached-content token counts. Provider/relay reporting differences still need empirical verification.
- Memory extraction runs as a separate LLM task and uses a low temperature and disabled thinking where supported.

## Reference projects and reusable ideas

These projects are references, not dependencies. Do not copy their code or assume their defaults fit this Android application.

- SillyTavern UwU Memory: protected recent turns, background summarization, vector retrieval, configurable top-k/threshold, and graceful fallback.
  https://github.com/ICSLI/SillyTavern-UwU-Memory
- CharMemory: extract structured facts and relationships into human-editable memory text.
  https://github.com/bal-spec/sillytavern-character-memory
- Timeline Memory: chapter/timeline summaries and context-aware retrieval for long-running plots.
  https://github.com/unkarelian/timeline-memory
- VectFox: event-oriented memory and hybrid dense/sparse retrieval.
  https://github.com/KritBlade/VectFox
- Memory Books: distinguish durable clips/facts from evolving side-prompts/trackers, with compaction and consolidation.
  https://github.com/0cyris/sillytavern-memorybooks

## Ranked optimization candidates

### P0 — establish a trustworthy baseline before changing selection logic

1. Record per-request prompt estimates and actual provider usage without logging conversation text. Track total prompt tokens, reported cached tokens, uncached tokens, cache telemetry availability, selected history count, structured-memory count, vector/lexical recall count, and worldbook activation count.
2. Calculate cost using provider-specific usage fields only when present. Mark unknown telemetry as unknown rather than assuming zero cache hits.
3. Add a repeatable fixture set of roleplay turns covering names/aliases, promises, relationship changes, inventory, time order, negation, group chat, worldbook triggers, and history truncation. Compare selected memory and injected-token count before/after changes.
4. Preserve stable prompt prefix byte-for-byte unless a test proves the change is safe. Avoid per-turn timestamps, similarity scores, volatile retrieval text, or other dynamic metadata in the stable prefix.

### P1 — high-value, low-risk prompt token reductions

1. Remove retrieval metadata from the model-facing prompt when it does not change model behavior. Similarity percentages are ranking diagnostics; keep them in debug/trace metadata, not the prompt.
2. Audit repeated headings, repeated facts, and structured-memory/vector-summary overlap using normalized fact identity. Keep distinct facts when punctuation, negation, time, or entities change meaning.
3. Use compact, consistent memory formatting. Do not include importance scores or timestamps in every line unless tests demonstrate they materially improve recall or temporal reasoning.
4. Apply a token budget to each dynamic section (structured memory, vector recall, lexical recall, activated worldbook) and select whole facts/events rather than truncating a fact mid-sentence.
5. Avoid injecting a lexical/vector recall excerpt when the same source message is already in the selected history window. For partial history, compare source IDs when available instead of relying only on fuzzy text containment.

### P1 — improve retrieval precision before reducing the number of memories

1. Audit candidate-pool bias: structured memory candidates are pre-limited by importance/recency before keyword ranking. Test whether a lower-importance but highly relevant old promise/event is excluded before matching.
2. Keep hybrid retrieval complementary: dense semantic recall handles paraphrases; lexical search handles exact names, numbers, rare terms, and quoted details. Deduplicate after merging, not by score alone.
3. Add event fields where supported by the current schema: entity/participants, event time/order, location, status, source message IDs, and confidence. Introduce only through backward-compatible migration and tests.
4. Treat changing state as a tracker/current-state fact plus a historical event when needed. For example, “currently carrying item X” can change, while “received item X on day Y” remains historical.
5. Use recency as a tie-breaker or explicit temporal signal, not as a blanket replacement for semantic relevance. Protect canonical facts, boundaries, and explicit promises from decay.

### P2 — reduce memory-generation overhead

1. Keep extraction asynchronous and separate from the main chat API request. Never make normal reply latency depend on a successful memory extraction.
2. Extract only when a turn contains candidate-worthy information; retain the existing low-quality filters and add regression fixtures before changing thresholds.
3. Prefer incremental extraction of the newest unprocessed turns over resending large dialogue windows. Persist processed message IDs or a stable source watermark and invalidate it on edit/delete/branch.
4. Ask the extraction model for compact, schema-valid facts with source references; reject unsupported facts and malformed/partial JSON rather than saving guesses.
5. Measure extraction input/output tokens separately from chat tokens. A reduction in chat prompt tokens is not a win if it causes much higher extraction spend or worse continuity.

### P2 — cache-aware request layout for DeepSeek and Gemini

1. Treat the prompt as stable prefix + recent history + dynamic memory/worldbook + final user turn, but preserve semantic placement requirements where the roleplay engine depends on them.
2. Do not assume all providers/relays implement the same cache contract. Verify real usage responses from the exact relay/model; record when the gateway omits or renames counters.
3. Compare several consecutive turns from the same session. A single request cannot establish cache hit rate. Report hit tokens / (hit + miss tokens) only when both values are trustworthy.
4. Test Gemini cached-content metadata independently from OpenAI-compatible nested cached_tokens and DeepSeek prompt_cache_hit_tokens; never double-count fields if a relay returns multiple representations.
5. Do not insert random IDs, current timestamps, unstable ordering, or dynamic retrieval results into the beginning of the prompt. Keep deterministic ordering for stable sections.

## Risks and guardrails

- Never delete or summarize away negation, promises, relationship boundaries, identity, item ownership, or irreversible plot events without explicit evaluation.
- Do not move constant worldbook entries or alter @D/depth semantics as a token-only optimization.
- Do not change database schema until a read-only audit identifies a concrete need; any schema change requires migration tests and backward compatibility.
- Keep all debug telemetry content-free: no raw prompt, message, memory, API key, or user-derived text in logs.
- Preserve graceful fallback when embedding or memory APIs fail.
- Keep PR #29 Open and Draft; do not merge it or change its state as part of this work.

## Proposed experiment sequence

1. Fix and pass current CI, then validate cache telemetry against the actual relay response format.
2. Add a compact-prompt regression test for removing similarity metadata while preserving the recalled text exactly.
3. Add a benchmark fixture set and report prompt tokens / cache hits / memory recall correctness before changing selection algorithms.
4. Test candidate-pool ranking, duplicate recall, and per-section budgets independently behind settings or pure functions.
5. Ask two configured repository relay models for independent recommendations; reconcile their proposals against source evidence and tests rather than accepting them automatically.
6. Implement one change per commit, run CI and APK build on the exact latest SHA, then perform an independent AI review.

## Acceptance criteria

- No regression in tests for history continuity, worldbook activation/placement, structured memory filtering, vector deduplication, or API parsing.
- Lower median prompt tokens on the fixture set without reducing required-fact recall.
- Stable prefix remains unchanged across adjacent turns when stable inputs are unchanged.
- Cache-hit telemetry is provider-grounded; unknown/missing fields remain explicitly unknown.
- Model A and Model B proposals include evidence, expected token impact, semantic risk, and tests. Unsupported findings are rejected.
