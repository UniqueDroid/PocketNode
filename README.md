# PocketNode

**PocketNode** turns an Android smartphone into a local, hardware-aware LLM inference server with an OpenAI-compatible HTTP API, foreground service persistence, and GPU acceleration.

Reference device: Pixel 7 Pro (Tensor G2, Mali-G710, 12 GB RAM).

## Status

**Stage 2 of 5: real inference with LiteRT-LM (GPU with CPU fallback), Android app with foreground service.**

Stage 2 has been verified with a real model on a desktop CPU only. GPU acceleration, the foreground service and the file picker still need the device test in [docs/geraetetest-stufe-2.md](docs/geraetetest-stufe-2.md).

| Stage | Content | State |
|---|---|---|
| 1 Foundation | OpenAI-compatible API, auth, queue, streaming, tests, CI | done |
| 2 Engine | LiteRT-LM backend (GPU / CPU), model folder, foreground service, dashboard | done, device test open |
| 3 Robustness | RAM guard, thermal handling, autostart, model download | planned |
| 4 NPU probe | Diagnostics screen that measures whether the Tensor G2 NPU is reachable | planned |
| 5 Embeddings | `/v1/embeddings` on the NPU, only if the probe succeeds | open |

Design and plan (German):

- [Design](docs/superpowers/specs/2026-11-27-pocketnode-design.md)
- [Plan for stage 1](docs/superpowers/plans/2026-11-27-pocketnode-stufe-1-fundament.md)
- [Plan for stage 2](docs/superpowers/plans/2026-11-27-pocketnode-stufe-2-engine.md)

## Modules

| Module | Content |
|---|---|
| `:core` | Pure Kotlin/JVM: engine contracts, `EngineManager` (queue, model switching), HTTP API (Ktor CIO), `LiteRtLmBackend`, model folder, `FakeBackend`. No Android classes, so its tests run without the Android SDK. |
| `:app` | Android app: settings, foreground service, overview, models and settings screens. Only built when an Android SDK is present (`ANDROID_HOME` or `local.properties`). |

## On the phone

1. Install the debug APK from the latest CI run (artifact `pocketnode-debug-apk`) on an `arm64-v8a` device with Android 12 or newer.
2. Copy a `.litertlm` model into the app's model folder, or import it in the app under **Models**:
   ```bash
   adb push model.litertlm /sdcard/Android/data/com.pocketnode.server/files/models/
   ```
3. Tap **Start server**, then load a model under **Models**. The overview shows the address, the active accelerator (GPU or CPU) and the API key (with QR code).
4. Disable battery optimisation for PocketNode and keep the phone on a charger with a charge limit (e.g. 80 %).

The overview shows a clear notice when the GPU was requested but the model runs on the CPU. The reason is in logcat under the tag `PocketNode`.

## Try it on a desktop

Requires JDK 17 or newer for the fake backend, JDK 25 for a real model.

```bash
POCKETNODE_API_KEY=dev ./gradlew :core:run
```

`POCKETNODE_PORT` changes the port (default 8080). Without `POCKETNODE_API_KEY` a random key is generated and printed. Without `POCKETNODE_MODELS_DIR` the server uses the fake backend.

```bash
curl -s localhost:8080/health

curl -s localhost:8080/v1/models -H "Authorization: Bearer dev"

curl -sN localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer dev" \
  -H "Content-Type: application/json" \
  -d '{"model":"fake-small","stream":true,"messages":[{"role":"user","content":"Hi"}]}'
```

The fake backend always answers `Hallo von PocketNode.` It exists to verify clients such as Open WebUI against the API before a real model is involved.

## Run a real model on a desktop

The desktop run can also use the real LiteRT-LM engine on the CPU. LiteRT-LM 0.20.0 is compiled for Java 25, so this needs **JDK 25**.

```bash
mkdir -p ~/pocketnode-models
# Any .litertlm model, e.g. from https://huggingface.co/litert-community
curl -L -o ~/pocketnode-models/granite-350m.litertlm \
  https://huggingface.co/litert-community/granite-4.0-350m-litert-lm/resolve/main/granite-4.0-350m_q8_ekv1280.litertlm

POCKETNODE_MODELS_DIR=~/pocketnode-models POCKETNODE_API_KEY=dev ./gradlew :core:run
```

Every non-empty `.litertlm` file in the folder is a model; its id is the file name without the extension. Files added or removed while the server runs show up on the next request. The desktop run binds to `127.0.0.1` unless `POCKETNODE_HOST` is set.

Tests against a real model run when `POCKETNODE_TEST_MODEL` points at a `.litertlm` file:

```bash
POCKETNODE_TEST_MODEL=~/pocketnode-models/granite-350m.litertlm ./gradlew :core:test
```

## API

| Endpoint | Auth | Behaviour |
|---|---|---|
| `GET /health` | no | Status (`ok`, `loading`, `no_model`), loaded model, active accelerator, queue length |
| `GET /v1/models` | Bearer | All installed models |
| `POST /v1/chat/completions` | Bearer | JSON response, or an SSE stream with `"stream": true` |

Supported request fields: `model`, `messages` (roles `system`/`developer`, `user`, `assistant`; text only; the last message must be from `user`), `stream`, `max_tokens`, `max_completion_tokens`, `temperature`, `top_p`, `top_k`, `stop`, `stream_options.include_usage`. Unknown fields are ignored.

One generation runs at a time, up to four more wait in a queue. A request for another installed model switches the model. When a streaming client disconnects, the generation stops.

### Errors

All errors use the OpenAI format `{"error": {"message", "type", "code"}}`.

| Status | `code` | Meaning |
|---|---|---|
| 400 | `invalid_json`, `messages_required`, `last_message_must_be_user`, `unsupported_role`, `unsupported_content`, `unsupported_parameter`, `invalid_max_tokens`, `invalid_stop`, `invalid_sampling`, `model_not_loaded`, `context_length_exceeded` | Invalid request |
| 401 | `invalid_api_key` | API key missing or wrong |
| 404 | `model_not_found`, `not_found` | Model not installed, or unknown endpoint |
| 405 | `method_not_allowed` | Wrong HTTP method |
| 413 | `request_too_large` | Body larger than 4 MiB |
| 429 | `queue_full` | Queue is full, see `Retry-After` |
| 500 | `internal_error` | Unexpected error, details only in the log |
| 503 | `no_model`, `model_load_failed`, `unavailable` | No model, loading failed, or the device refuses requests (see `Retry-After`) |

When a stop sequence cuts the output, `usage` is omitted. A failure after a stream has started is sent as an event `data: {"error": …}`; the stream then ends without `[DONE]`.

## Build and test

```bash
./gradlew :core:test                 # no Android SDK needed
./gradlew :app:testDebugUnitTest     # needs the Android SDK (API 37)
./gradlew :app:assembleDebug
```

CI runs the tests, lint and the APK build on every push to `main` and on pull requests, and stores the debug APK as an artifact.

## NPU on the Tensor G2

Google's official NPU path (Tensor SDK, LiteRT-LM NPU models) only covers Tensor G5 and G6. On the Pixel 7 Pro, chat models therefore run on the GPU with the CPU as fallback. Stage 4 adds a probe that measures whether the NPU is reachable without root for small int8 models; see section 9 of the design.

## License

MIT
