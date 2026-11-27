# PocketNode

**PocketNode** turns an Android smartphone into a local, hardware-aware LLM inference server with an OpenAI-compatible HTTP API, foreground service persistence, and GPU acceleration.

Reference device: Pixel 7 Pro (Tensor G2, Mali-G710, 12 GB RAM).

## Status

**Stage 1 of 5: server core with a fake backend, no on-device inference yet.**

| Stage | Content | State |
|---|---|---|
| 1 Foundation | OpenAI-compatible API, auth, queue, streaming, tests, CI | done |
| 2 Engine | LiteRT-LM backend (GPU / CPU), model folder, foreground service, dashboard | planned |
| 3 Robustness | RAM guard, thermal handling, autostart, model download | planned |
| 4 NPU probe | Diagnostics screen that measures whether the Tensor G2 NPU is reachable | planned |
| 5 Embeddings | `/v1/embeddings` on the NPU, only if the probe succeeds | open |

Design and plan (German):

- [Design](docs/superpowers/specs/2026-11-27-pocketnode-design.md)
- [Plan for stage 1](docs/superpowers/plans/2026-11-27-pocketnode-stufe-1-fundament.md)

## Modules

| Module | Content |
|---|---|
| `:core` | Pure Kotlin/JVM: engine contracts, `EngineManager` (queue, model switching), HTTP API (Ktor CIO), `FakeBackend`. No Android classes, so its tests run without the Android SDK. |
| `:app` | Android app. In stage 1 only the manifest and an empty start screen. Only built when an Android SDK is present (`ANDROID_HOME` or `local.properties`). |

## Try it on a desktop

Requires JDK 17 or newer.

```bash
POCKETNODE_API_KEY=dev ./gradlew :core:run
```

`POCKETNODE_PORT` changes the port (default 8080). Without `POCKETNODE_API_KEY` a random key is generated and printed.

```bash
curl -s localhost:8080/health

curl -s localhost:8080/v1/models -H "Authorization: Bearer dev"

curl -sN localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer dev" \
  -H "Content-Type: application/json" \
  -d '{"model":"fake-small","stream":true,"messages":[{"role":"user","content":"Hi"}]}'
```

The fake backend always answers `Hallo von PocketNode.` It exists to verify clients such as Open WebUI against the API before a real model is involved.

## API

| Endpoint | Auth | Behaviour |
|---|---|---|
| `GET /health` | no | Status (`ok`, `loading`, `no_model`), loaded model, active accelerator, queue length |
| `GET /v1/models` | Bearer | All installed models |
| `POST /v1/chat/completions` | Bearer | JSON response, or an SSE stream with `"stream": true` |

Supported request fields: `model`, `messages` (roles `system`, `user`, `assistant`; text only), `stream`, `max_tokens`, `max_completion_tokens`, `temperature`, `top_p`, `top_k`, `stop`, `stream_options.include_usage`. Unknown fields are ignored.

One generation runs at a time, up to four more wait in a queue. A request for another installed model switches the model. When a streaming client disconnects, the generation stops.

### Errors

All errors use the OpenAI format `{"error": {"message", "type", "code"}}`.

| Status | `code` | Meaning |
|---|---|---|
| 400 | `invalid_json`, `messages_required`, `unsupported_role`, `unsupported_content`, `unsupported_parameter`, `invalid_max_tokens`, `invalid_stop`, `invalid_sampling`, `model_not_loaded`, `context_length_exceeded` | Invalid request |
| 401 | `invalid_api_key` | API key missing or wrong |
| 404 | `model_not_found`, `not_found` | Model not installed, or unknown endpoint |
| 405 | `method_not_allowed` | Wrong HTTP method |
| 413 | `request_too_large` | Body larger than 4 MiB |
| 429 | `queue_full` | Queue is full, see `Retry-After` |
| 500 | `internal_error` | Unexpected error, details only in the log |
| 503 | `no_model`, `model_load_failed`, `unavailable` | No model, loading failed, or the device refuses requests (see `Retry-After`) |

A failure after a stream has started is sent as an event `data: {"error": …}`; the stream then ends without `[DONE]`.

## Build and test

```bash
./gradlew :core:test            # 107 tests, no Android SDK needed
./gradlew :app:assembleDebug    # needs the Android SDK (API 37)
```

CI runs both on every push and stores the debug APK as an artifact.

## NPU on the Tensor G2

Google's official NPU path (Tensor SDK, LiteRT-LM NPU models) only covers Tensor G5 and G6. On the Pixel 7 Pro, chat models therefore run on the GPU with the CPU as fallback. Stage 4 adds a probe that measures whether the NPU is reachable without root for small int8 models; see section 9 of the design.

## License

MIT
