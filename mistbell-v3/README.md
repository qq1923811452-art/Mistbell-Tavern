# Mistbell-Tavern 3.0 — Clean-Room Implementation

## Non-negotiable rule

This directory is the new 3.0 implementation. Application code must be designed and written from a clean slate; do not import, copy, or depend on the legacy `app/` source tree or on code from historical PRs. Legacy sources may be consulted only to identify user-visible capabilities, data formats that must be imported, and regression cases. Any compatibility layer must be newly implemented and tested against fixtures.

The repository's existing application remains untouched while this independent implementation is built. Do not delete or replace it until 3.0 passes the final acceptance gates.

## Target device and platform

- Primary device: Redmi K70 Pro, Snapdragon 8 Gen 3 / SM8650, 16 GB RAM.
- Android application, Kotlin, Jetpack Compose, coroutine-based asynchronous work.
- Keep routine validation fast. Build the full APK, run emulator/instrumented tests, and perform physical-device acceptance only at the final validation stage.
- Hardware acceleration is a capability to detect and measure, not a claim to make from device model alone.

## Architecture boundaries

```
app
├── core/model          immutable domain models and result/error types
├── core/storage        database, migrations, encrypted preferences, backup/restore
├── core/network        endpoint policy, HTTP clients, streaming and redaction
├── feature/chat        conversation UI, request orchestration, prompt assembly
├── feature/worldbook   new Mistbell format, legacy import, recursive scan/activation
├── feature/character   new character-card format, legacy import/export
├── feature/memory      facts/history, retrieval, local vector persistence/rebuild
├── feature/embedding   runtime abstraction, model management, backend capability probe
├── feature/image       independent image API and private-file handling
├── feature/settings    independent provider configuration and diagnostics
└── platform/inference runtime adapters and truthful CPU/GPU/NPU telemetry
```

These are logical boundaries, not a requirement to create a large number of Gradle modules prematurely. Keep interfaces explicit so runtime and provider implementations can be tested independently.

## Required design invariants

1. Chat, memory extraction, embeddings, and image generation have independent provider settings and credentials. A provider failure must not silently route secrets to another origin.
2. Memory extraction is a separately configurable model/API. Batch eligible conversation turns (target: every three turns), persist pending work, and retry safely without losing facts or duplicating history.
3. Structured facts retain temporal history. Conflicts supersede prior facts rather than erasing them; retrieval excludes superseded facts from active context.
4. Embedding vectors and indexes persist locally, survive app restarts, and support explicit integrity checking and rebuild. API caches are an optimization only, never the sole copy of vector data.
5. EmbeddingGemma 2 is the preferred local-model candidate. LiteRT-LM and other runtimes must be verified for Android/SM8650 compatibility before selection. Do not claim NPU use unless a runtime/device signal proves execution; expose actual backend, fallback reason, and current state. CPU/GPU fallback is allowed when it is the most reliable measured path.
6. Prompt assembly is deterministic: stable prefix first, volatile memories and activated worldbook entries later. Optimize token use without changing semantic meaning or needlessly harming provider prompt-cache hits.
7. Worldbook and character cards get a Mistbell-specific documented format. Legacy formats remain readable through newly written importers and fixtures. Constant/always-on entries, keyword activation, recursion, ordering, token budgets, and cycle protection require explicit tests.
8. The image API is independent of chat credentials, constrains response size and redirects, and stores generated assets in app-private storage until explicitly exported.
9. UI interactions use intentional, measured animations; no abrupt state changes for ordinary transitions. Animation must remain responsive under long chat histories.
10. Secrets are encrypted at rest; logs and audit reports redact credentials and private prompts. Backup/restore is transactional, validated, and non-destructive on failure.
11. Room schema changes require versioned migrations and migration tests. No destructive fallback for user data.
12. No feature is considered implemented because a file, PR, model dependency, setting, or AI report exists. Completion requires a traced call path, persistence/error-path checks, focused tests, and evidence.

## Delivery sequence

- Stage 0: independent project scaffold, package boundaries, build configuration, and architecture decision records.
- Stage 1: domain models, endpoint/security policy, persistence interfaces, and test fixtures.
- Stage 2: chat/API core and settings.
- Stage 3: new worldbook/character formats plus legacy importers.
- Stage 4: independent memory extraction, fact history, vector persistence/rebuild.
- Stage 5: EmbeddingGemma 2/runtime experiments and honest hardware telemetry.
- Stage 6: image API, UI motion/performance, backup/restore and hardening.
- Stage 7: cross-feature integration, targeted regression gates, then final APK/emulator/Redmi K70 Pro acceptance.

## Acceptance record

Each feature must have a checklist entry with: implementation path, data flow, persistence behavior, failure behavior, tests run and their exact result, plus device-only checks where applicable. Untested items remain explicitly incomplete.
