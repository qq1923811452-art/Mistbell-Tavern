# Mistbell 3.0 ChatScreen ViewModel Split Plan

Status: planning contract; no chat behavior is changed by this document.

## Non-negotiable architecture boundaries

- Keep `MemoryAwareChatRequest` as the existing memory-aware request entry point. Do not reimplement retrieval, memory injection, prompt composition, or streaming in a new orchestrator.
- If a `ChatOrchestrator` is introduced, keep it thin: coordinate existing request and persistence components, propagate cancellation, and associate results with the request's captured scope.
- Reuse `SqliteChatHistoryRepository`, `ChatHistoryStore`, `OpenAiCompatibleChatClient`, `PromptAssembler`, `ConversationContextWindow`, and existing memory services where appropriate. Do not create parallel implementations.
- `EmbeddingEngineProcessHolder` owns the process-level embedding engine lifecycle. A ViewModel may acquire/use the shared engine but must not close it from `onCleared()` or when a screen/request is cancelled.
- Keep API keys out of `ChatUiState`, `SavedStateHandle`, logs, and saved instance state. Preserve existing credential storage and ephemeral use.
- `SavedStateHandle` is only for small restoration metadata, never the whole message history or streaming buffer.
- This refactor moves existing behavior only. Retry is currently not part of the split unless verified as existing behavior; do not add a new retry feature in a refactor PR.

## State ownership table

| State / data | Single source of truth | Notes |
|---|---|---|
| Visible message list | `ChatViewModel` UI state | Immutable snapshots exposed to Compose; persistence remains in the history repository/store. |
| Streaming text buffer | `ChatViewModel` | UI publication is throttled to about 50 ms; do not force a Compose update for every token. Keep final message persistence independent of UI throttling. |
| Busy / sending / cancellation state | `ChatViewModel` | One active request owner; cancellation must propagate to the actual network call and coroutine. |
| Usage / token accounting display | `ChatViewModel` UI state | Update from existing response/usage handling; do not invent a second accounting path. |
| Memory status hint for the current request | `ChatViewModel` UI state | Source callback/event from `MemoryAwareChatRequest`; no duplicate retrieval logic. |
| Draft text | Compose input initially, then `ChatViewModel` state when migrated | Save/restore through `SavedStateHandle`; never persist credentials with it. |
| Role / conversation scope IDs | Existing SharedPreferences / existing scope source remains authoritative | `SavedStateHandle` may mirror the current IDs for restoration, but must not silently override a newer persisted scope. |
| Worldbook activation state | Existing `WorldBookActivationState` | Do not duplicate it in ChatViewModel; observe/read it as needed. |
| Index rebuild progress | Existing background task + diagnostic logging | UI may display a derived snapshot; the ViewModel does not become the owner of the process-level engine or rebuild infrastructure. |
| History persistence / load readiness / load failure | History repository/store, with a UI snapshot in ViewModel | Serialize writes and reject stale results; do not clear persisted history on load failure. |
| Unsent message identity | `SavedStateHandle` only if needed to reconcile an interrupted send | Save a small ID/status marker, not the full message list. Verify current message ID semantics before implementation. |
| Scroll position | Compose saveable state / UI restoration | Keep UI-specific; do not move it into repositories. |
| API credentials | Existing secure/ephemeral credential path | Never place in UI state, SavedStateHandle, diagnostic logs, or persisted chat snapshots. |

## Migration sequence

### Phase 1 — State model and ViewModel shell
- Introduce a small immutable `ChatUiState` and explicit UI events/actions.
- Move only low-risk UI state first; do not change prompt construction or request behavior.
- Add tests for state transitions and verify existing settings behavior.
- Verify dependency injection / ViewModel construction with the project's current patterns.

### Phase 2 — History load/save and concurrency only
- Extract history load/save coordination without mixing in role/conversation scope switching.
- Preserve atomic persistence and existing unreadable-history safeguards.
- Serialize concurrent writes and prevent an older asynchronous write from overwriting a newer snapshot.
- Add tests for overlapping saves, load failure, and cancellation.

### Phase 2.5 — Role/conversation scope switching
- Handle scope change as its own step after history concurrency is stable.
- Capture role/conversation IDs per request and validate the captured scope before applying UI results or persisting responses.
- A late response from an old scope must never be written into or displayed as part of a newly selected conversation.
- Verify memory scope isolation and existing SharedPreferences semantics.

### Phase 3A — Existing send/stream behavior
- Move existing send and stream coordination only.
- Call the existing `MemoryAwareChatRequest`; do not duplicate memory retrieval, injection, prompt assembly, or client streaming.
- Preserve cancellation, error handling, response persistence, and current status messages.
- Do not add retry behavior as part of this extraction.

### Phase 3B — Stop/cancellation lifecycle
- Separately verify that Stop cancels both coroutine work and the active HTTP call.
- Cancellation must not close the process-level embedding engine.
- Ensure cancelled/stale responses cannot mutate a different scope.

### Phase 4 — Memory/indexing integration
- Move UI-triggered coordination only where necessary; reuse existing memory extraction/indexing services and diagnostic logging.
- Keep index rebuild ownership in its existing background-task/service layer.
- Keep `WorldBookActivationState` as the single owner of worldbook activation.

### Phase 5 — Cleanup and regression
- Remove only code made redundant by completed migrations.
- Run the full CI suite and inspect the final diff for duplicate pathways, credential exposure, lifecycle regressions, and accidental feature additions.

## Required acceptance checks

- [ ] Existing history load/save tests pass; overlapping saves cannot regress persisted data.
- [ ] A history load failure does not silently erase or overwrite old persisted history.
- [ ] Streaming UI updates are throttled to approximately 50 ms and fast scrolling remains responsive.
- [ ] Stop cancels the request but leaves the shared embedding engine usable.
- [ ] Switching role/conversation while a request is active cannot write old output into the new scope.
- [ ] Background/foreground transition restores a consistent busy/streaming state.
- [ ] Rotation preserves draft text and UI scroll state as appropriate.
- [ ] A process-death restore does not claim to resume a network stream that cannot actually be resumed; reconcile any interrupted send using a small saved identifier/status marker.
- [ ] SavedStateHandle never stores the full message list, stream buffer, or API credentials.
- [ ] Memory retrieval/injection still has exactly one path through `MemoryAwareChatRequest`.
- [ ] Existing memory scope isolation, indexing, and worldbook activation behavior remains intact.
- [ ] No new retry feature is added by the refactor.
- [ ] Compile, focused JVM tests, emulator instrumentation tests, Detekt, KtLint, and other required CI checks are reviewed before declaring a phase complete.

## PR discipline

- Keep each phase small and independently reviewable.
- Keep the 3.0 baseline tag unchanged.
- Keep PR #77 unmerged unless explicitly approved; a follow-up split branch can depend on its branch while the dependency PR remains open.
- Do not claim CI/audit success without checking the actual run result and findings.
