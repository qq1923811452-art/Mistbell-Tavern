# Mistbell 3.0 ChatScreen ViewModel Split Plan

Status: staged refactor contract. This document does not change runtime behavior.

## Non-negotiable boundaries

- Reuse `MemoryAwareChatRequest` as the existing memory-aware request entry point. Do not duplicate retrieval, memory injection, prompt composition, or streaming.
- Any future orchestrator must remain thin: coordinate existing request/persistence components, propagate cancellation, and associate results with the request's captured scope.
- Reuse `SqliteChatHistoryRepository`, `ChatHistoryStore`, `OpenAiCompatibleChatClient`, `PromptAssembler`, `ConversationContextWindow`, and existing memory services.
- `EmbeddingEngineProcessHolder` owns the process-level embedding engine. A ViewModel must not close it from `onCleared()` or request cancellation.
- Never put API keys in UI state, `SavedStateHandle`, logs, or saved instance state.
- `SavedStateHandle` is for small restoration metadata, not message history or streaming buffers.
- Move existing behavior only. Do not add retry behavior unless it is verified to already exist.

## State ownership

| State | Owner / source of truth | Boundary |
|---|---|---|
| Visible message list | ChatViewModel UI state | Immutable UI snapshots; persistent data remains in the history store |
| Streaming text buffer | ChatViewModel | Throttle UI publication to about 50 ms; persist final content independently |
| Busy / send / cancellation | ChatViewModel | One active request owner; cancellation reaches coroutine and HTTP call |
| Usage display | ChatViewModel UI state | Reuse existing usage handling |
| Memory status hint | ChatViewModel UI state | Reuse callbacks/events from MemoryAwareChatRequest |
| Draft text | Compose input, then ChatViewModel when migrated | Small saved state only |
| Role/conversation IDs | Existing SharedPreferences/scope source | SavedStateHandle may mirror IDs but cannot silently override newer persisted scope |
| Worldbook activation | Existing WorldBookActivationState | Do not create a second owner |
| Index rebuild progress | Existing background task and diagnostics | ViewModel may expose a derived snapshot, not own the process engine |
| History persistence/load status | Existing repository/store plus UI snapshot | Serialize writes, reject stale snapshots, preserve data on load failure |
| Scroll position | Compose saveable state | Keep UI-specific |
| API credentials | Existing secure/ephemeral credential path | Never put in UI state or persisted chat snapshots |

## Migration sequence

1. **Foundation and state models:** move existing declarations without behavior changes; verify construction patterns and dependencies.
2. **History load/save:** extract coordination only; preserve atomic storage, recovery behavior, and stale-write protection.
3. **Role/conversation switching:** isolate scope changes; late results from an old scope must not be shown or persisted in a new scope.
4. **Send/stream coordination:** call the existing MemoryAwareChatRequest; preserve errors, persistence, cancellation, and status messages.
5. **Stop lifecycle:** cancel coroutine and HTTP request without closing the shared embedding engine.
6. **Memory/indexing coordination:** reuse existing services; WorldBookActivationState remains the activation owner.
7. **Cleanup and regression:** remove only code made redundant by completed migrations.

## Required acceptance checks

- History load/save tests pass; overlapping saves cannot regress persisted data.
- Load failure does not erase or overwrite existing history.
- Streaming UI updates remain throttled and scrolling responsive.
- Stop cancels a request while leaving the shared embedding engine usable.
- Switching scope during a request cannot leak old output into the new conversation.
- Background/foreground and rotation preserve consistent UI state.
- Process-death recovery does not pretend to resume a network stream; use only small ID/status markers if needed.
- SavedStateHandle never stores message history, stream buffers, or credentials.
- Memory retrieval/injection has one path through MemoryAwareChatRequest.
- No retry feature is added.
- Review compile, unit tests, emulator tests, Detekt, KtLint, and required CI before declaring a phase complete.

## PR discipline

- Keep each phase reviewable and Draft until explicitly approved.
- Keep the 3.0 baseline tag unchanged.
- Do not merge dependency or feature PRs without explicit approval.
- Report CI/audit results only after checking actual runs and findings.
