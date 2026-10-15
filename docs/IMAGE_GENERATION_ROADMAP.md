# Mistbell-Tavern image generation roadmap

## Scope

Image generation has its own provider configuration, separate from chat-completion and embedding credentials. The first adapter supports OpenAI-compatible `POST /images/generations` endpoints. The current settings UI can save a provider, model, and key and generate a test image into app-private storage. This is the API foundation, not yet the complete in-chat image plugin.

## Planned provider adapters

1. **OpenAI-compatible image endpoint** — current first stage; configurable base URL, key, model, size; accepts either `data[0].url` or `data[0].b64_json`.
2. **ComfyUI workflow API** — next preferred open-source adapter. Submit API-format workflow JSON to `POST /prompt`, track `prompt_id` through history or WebSocket, then download outputs through the view endpoint. Keep the workflow JSON user-selectable and use named placeholders for positive prompt, negative prompt, seed, dimensions, and optional reference image. Do not assume every workflow uses the same node IDs.
3. **Stable Diffusion WebUI-compatible API** — optional adapter for `/sdapi/v1/txt2img` and `/sdapi/v1/img2img`; implement the protocol from documentation rather than importing server code or dependencies.
4. Additional cloud-specific adapters only when the user's actual provider needs them. Do not treat every provider's API as OpenAI-compatible just because both generate images.

## Chat integration requirements

- Manual generation from the current scene and a custom prompt.
- Build an image prompt from the selected character's visual description, the recent scene, the user's request, and only relevant worldbook facts; do not send the full chat transcript or the whole worldbook by default.
- Keep image prompt construction separate from the text-chat prompt, so it does not alter the stable chat prefix or lower chat cache hit rate.
- Make prompt preview/editing available before a costly request; allow reusing the same prompt with a new seed for variants.
- Store generated files locally and associate them with the chat message/session. Do not put expiring remote URLs in long-term chat history.
- Make automatic image generation opt-in, with a cooldown and duplicate-request guard. Never trigger a generation just because the assistant mentions a generic visual noun.
- Keep API keys in SecureStore, redact secrets from errors, cap downloaded image sizes, and let users clear generated files.
- Add per-session or per-character style presets and negative prompts after the basic pipeline is stable.

## Open-source technologies worth evaluating

- **ComfyUI** as the backend/workflow executor: its API lets the app submit a saved workflow while the desktop/server handles model execution. This is more flexible than hard-coding one diffusion model into the Android app.
- **FLUX.2 Klein 4B** as a self-hosted generation/editing option when a suitable desktop/server GPU is available; check the model license and deployment requirements at integration time.
- **Qwen Image Edit / FLUX Kontext-style editing workflows** for later character-reference image editing and scene continuity, using a user-provided reference image.
- **SQLite FTS5 + vector search + optional reranking** for worldbook and memory selection. This affects what context reaches the image prompt and chat model, rather than adding a large database service for a single-user app.

## Device boundary

The user's Snapdragon 8 Gen 3 is the target for local text embedding and memory retrieval. Full diffusion image generation should use a separately configured remote API or a reachable self-hosted ComfyUI/WebUI server by default; do not promise high-quality image generation on the phone's NPU. The app should show the configured backend and actual result, not label a request as "local" unless inference really occurred on-device.

## License boundary

Integrate with documented APIs and formats; do not copy AGPL implementation code into this MIT project. Review each model and workflow license separately. A model being downloadable or supported by ComfyUI does not automatically mean every use is permitted.
