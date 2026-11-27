# PocketNode Stufe 1 (Fundament) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein OpenAI-kompatibler HTTP-Server mit Auth, Warteschlange, Streaming und Fehlerformat, der gegen ein Test-Backend vollständig geprüft ist und auf dem Desktop läuft, plus ein baubares Android-Gerüst mit CI.

**Architecture:** Zwei Gradle-Module. `:core` ist reines Kotlin/JVM und enthält Engine-Verträge, `EngineManager`, API und Tests. `:app` ist das Android-Gerüst und hängt von `:core` ab. In dieser Stufe enthält `:app` nur Manifest und einen leeren Startbildschirm; die echte Engine folgt in Stufe 2.

**Tech Stack:** Kotlin 2.x, Gradle (Kotlin DSL, Version Catalog), Ktor Server (CIO), kotlinx.serialization, kotlinx.coroutines, JUnit 5 mit `kotlin-test`, `ktor-server-test-host`, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-27-pocketnode-design.md` (Abschnitte 5, 6, 12, 13)

**Umfang:** Dieser Plan deckt Stufe 1 der Spec ab. Die Stufen 2 bis 5 bekommen je einen eigenen Plan, weil sie von Ergebnissen auf dem Gerät abhängen.

## Global Constraints

- Paket-Wurzel in beiden Modulen: `com.pocketnode.server`
- `applicationId` und `namespace`: `com.pocketnode.server`
- `minSdk = 31`, ABI nur `arm64-v8a`
- Alle Versionen stehen in `gradle/libs.versions.toml`. Kein `latest.release`, keine dynamischen Versionen. Der Umsetzer wählt beim Anlegen die jeweils aktuelle stabile Version.
- `:core` darf keine `android.*`-Klassen importieren.
- JSON wird immer über kotlinx.serialization erzeugt, nie als zusammengesetzter String.
- Alle Fehlerantworten haben das Format `{"error":{"message":"…","type":"…","code":"…"}}`.
- Warteschlange: genau 1 laufende Generierung, höchstens 4 wartende.
- `/health` ist ohne Token erreichbar, alles unter `/v1/` nur mit Token.
- Commit-Nachrichten im Stil `feat: …`, `test: …`, `chore: …`.

## Review Focus

1. **Kaputte Eingabe:** ungültiges JSON, fehlendes oder leeres `messages` → 400 im Fehlerformat, kein 500. (Test in Task 5)
2. **Abbruch beim Warten:** Ein Client trennt die Verbindung, während seine Anfrage in der Warteschlange steht → der Platz wird frei, die nächste Anfrage kommt dran. (Test in Task 3)
3. **Backend-Fehler mitten im Stream:** → der Stream endet mit einem Fehler-Event, die Warteschlange ist danach frei, die nächste Anfrage funktioniert. (Tests in Task 3 und Task 6)
4. **Abweichende Auth-Header:** `bearer` kleingeschrieben, doppelte Leerzeichen, falsches Schema, leerer Token → 401 oder Erfolg, nie 500. (Test in Task 5)
5. **Fehlgeschlagener Modellwechsel:** Das Laden wirft → 503, der Zustand zeigt „kein Modell geladen“, eine spätere Anfrage versucht das Laden erneut. (Test in Task 3)

---

## File Structure

```text
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew*, .gitignore
.github/workflows/ci.yml
README.md
core/build.gradle.kts
core/src/main/kotlin/com/pocketnode/server/
  engine/Types.kt             Accelerator, ModelFile, LoadedModel, ChatMessage, GenerationRequest, GenerationEvent
  engine/InferenceBackend.kt  Schnittstelle
  engine/EngineException.kt   Fehlerarten
  engine/GenerationLimits.kt  max_tokens und stop als Flow-Operator
  engine/ModelCatalog.kt      Schnittstelle + InMemoryModelCatalog
  engine/EngineManager.kt     Warteschlange, Modellwechsel, Zustand
  engine/FakeBackend.kt       Test- und Desktop-Backend
  api/Dto.kt                  Request- und Response-Typen
  api/RequestMapper.kt        Validierung, DTO → GenerationRequest
  api/Errors.kt               ApiError, Zuordnung Exception → Status
  api/Auth.kt                 Bearer-Prüfung
  api/Module.kt               Ktor-Modul mit Routen
  api/Sse.kt                  Streaming-Antwort
  api/PocketNodeServer.kt     start/stop mit CIO
  DevMain.kt                  Desktop-Start mit FakeBackend
core/src/test/kotlin/com/pocketnode/server/…   ein Testfile je Quelldatei
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/pocketnode/server/MainActivity.kt
```

---

### Task 1: Projektgerüst und CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, Gradle-Wrapper, `.gitignore`
- Create: `core/build.gradle.kts`, `core/src/test/kotlin/com/pocketnode/server/SmokeTest.kt`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/pocketnode/server/MainActivity.kt`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: Module `:core` (Plugins `kotlin("jvm")`, `kotlin("plugin.serialization")`, JUnit-5-Plattform, JVM-Ziel 17) und `:app` (`implementation(project(":core"))`).

- [ ] **Step 1: Wrapper, Settings, Version Catalog und `.gitignore` anlegen**

`libs.versions.toml` enthält Einträge für: kotlin, agp, ktor, coroutines, serialization-json, junit, compose-bom, activity-compose, core-ktx. `.gitignore`: `.gradle/`, `build/`, `local.properties`, `.idea/`, `*.iml`.

- [ ] **Step 2: `:core` mit einem Smoke-Test anlegen**

```kotlin
class SmokeTest {
    @Test fun `core module runs tests`() = assertEquals(4, 2 + 2)
}
```

- [ ] **Step 3: Test ausführen**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 1 Test bestanden

- [ ] **Step 4: `:app` anlegen**

`build.gradle.kts`: `compileSdk` und `targetSdk` aktuelle stabile API, `minSdk = 31`, `abiFilters += "arm64-v8a"`, Compose über das Kotlin-Compose-Compiler-Plugin, `versionName = "0.1.0"`.

Manifest: Berechtigungen `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Innerhalb von `<application>`: `uses-native-library` für `libvndksupport.so`, `libOpenCL.so`, `libOpenCL-pixel.so`, jeweils `android:required="false"`. `android:allowBackup="false"`. Noch kein Service-Eintrag.

`MainActivity`: Compose-Bildschirm mit dem Text „PocketNode“ und der Zeile „Stufe 1: Server-Kern, noch ohne Engine“.

- [ ] **Step 5: CI anlegen**

`ci.yml`: Auslöser `push` und `pull_request`. Ein Job auf `ubuntu-latest` mit JDK 17 und `gradle/actions/setup-gradle`: `./gradlew :core:test :app:assembleDebug`, danach Upload von `app/build/outputs/apk/debug/*.apk` als Artefakt und der Testberichte bei Fehlschlag.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "chore: Projektgerüst mit :core, :app und CI"
```

Hinweis: `:app:assembleDebug` braucht das Android SDK und wird nur in der CI geprüft. Lokal reicht `./gradlew :core:test`.

---

### Task 2: Engine-Verträge, Limits und FakeBackend

**Files:**
- Create: `engine/Types.kt`, `engine/InferenceBackend.kt`, `engine/EngineException.kt`, `engine/GenerationLimits.kt`, `engine/FakeBackend.kt`
- Test: `engine/GenerationLimitsTest.kt`, `engine/FakeBackendTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class Accelerator { CPU, GPU, NPU }
data class ModelFile(val id: String, val path: String, val sizeBytes: Long)
data class LoadedModel(val id: String, val accelerator: Accelerator, val contextTokens: Int)

enum class Role { SYSTEM, USER, ASSISTANT }
data class ChatMessage(val role: Role, val text: String)
data class Sampling(val temperature: Double? = null, val topP: Double? = null, val topK: Int? = null)
data class GenerationRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int? = null,
    val sampling: Sampling = Sampling(),
    val stop: List<String> = emptyList(),
)
enum class FinishReason { STOP, LENGTH }
data class Usage(val promptTokens: Int, val completionTokens: Int)
sealed interface GenerationEvent {
    data class Token(val text: String) : GenerationEvent
    data class Done(val reason: FinishReason, val usage: Usage?) : GenerationEvent
}

interface InferenceBackend {
    val name: String
    val supported: Set<Accelerator>
    suspend fun load(model: ModelFile, preferred: Accelerator, cpuThreads: Int): LoadedModel
    fun generate(request: GenerationRequest): Flow<GenerationEvent>   // endet immer mit genau einem Done
    suspend fun unload()
}

sealed class EngineException(message: String) : Exception(message) {
    class QueueFull(val retryAfterSeconds: Int) : EngineException
    class ModelNotFound(val modelId: String) : EngineException
    class ModelNotLoaded(val modelId: String) : EngineException      // anderes Modell verlangt, Wechsel aus
    class NoModelAvailable : EngineException                          // nichts geladen, nichts installiert
    class ModelLoadFailed(val modelId: String, cause: Throwable) : EngineException
    class Unavailable(val reason: String, val retryAfterSeconds: Int) : EngineException
    class ContextLengthExceeded(val limitTokens: Int) : EngineException
}

fun Flow<GenerationEvent>.withLimits(maxTokens: Int?, stop: List<String>): Flow<GenerationEvent>

class FakeBackend(
    val tokens: List<String> = listOf("Hallo", " von", " PocketNode", "."),
    val tokenDelayMs: Long = 0,
    val failLoadFor: Set<String> = emptySet(),        // Modell-IDs, deren Laden wirft
    val failAfterTokens: Int? = null,                 // wirft IllegalStateException nach n Tokens
    override val supported: Set<Accelerator> = setOf(Accelerator.CPU, Accelerator.GPU),
) : InferenceBackend {
    val loadCalls: List<String>                       // geladene Modell-IDs in Reihenfolge
    val activeGenerations: Int                        // gerade laufende generate-Flows
}
```

`FakeBackend.load` gibt den gewünschten Beschleuniger zurück, wenn er in `supported` liegt, sonst `CPU`. `contextTokens = 4096`. `Usage` im `Done`: `promptTokens` = Summe der Wortanzahl aller Nachrichten, `completionTokens` = Anzahl ausgegebener Tokens.

`withLimits` zählt jedes `Token`-Event als ein Token.

- [ ] **Step 1: Tests für `withLimits` schreiben**

```kotlin
@Test fun `stops after maxTokens with LENGTH`()        // 4 Tokens, maxTokens=2 → 2 Tokens, Done(LENGTH)
@Test fun `passes through without limits`()            // 4 Tokens, Done(STOP) unverändert
@Test fun `cuts at stop sequence inside one token`()   // ["ab", "cXd"], stop=["X"] → Text "abc", Done(STOP)
@Test fun `cuts at stop sequence spanning two tokens`()// ["Hal", "lo EN", "D mehr"], stop=["END"] → Text "Hallo ", Done(STOP)
@Test fun `does not hold back text forever`()          // stop=["XYZ"], Tokens ohne Treffer → voller Text kommt an
@Test fun `maxTokens zero yields only Done LENGTH`()
```

Geprüft wird jeweils der zusammengesetzte Text aller `Token`-Events und das letzte Event.

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :core:test --tests '*GenerationLimitsTest'`
Expected: FAIL, `withLimits` nicht definiert

- [ ] **Step 3: Typen, Schnittstelle, Exceptions und `withLimits` implementieren**

Ansatz für `stop`: Text puffern und nur den Teil weitergeben, der sicher kein Anfang einer Stop-Sequenz mehr sein kann (Pufferlänge höchstens längste Stop-Sequenz minus 1). Am Ende den Rest ausgeben. Das `Usage` des ursprünglichen `Done` bleibt erhalten; bei vorzeitigem Ende ist `usage = null`.

- [ ] **Step 4: Tests für `FakeBackend` schreiben und implementieren**

```kotlin
@Test fun `emits configured tokens then Done STOP`()
@Test fun `load records calls and reports accelerator`()      // preferred=NPU → CPU
@Test fun `load throws for ids in failLoadFor`()
@Test fun `throws after failAfterTokens`()
@Test fun `activeGenerations returns to zero after cancel`()  // tokenDelayMs=50, nach erstem Token abbrechen
```

- [ ] **Step 5: Alle Tests ausführen**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: Engine-Verträge, Limits und FakeBackend"
```

---

### Task 3: ModelCatalog und EngineManager

**Files:**
- Create: `engine/ModelCatalog.kt`, `engine/EngineManager.kt`
- Test: `engine/EngineManagerTest.kt`

**Interfaces:**
- Consumes: alles aus Task 2
- Produces:

```kotlin
interface ModelCatalog {
    fun list(): List<ModelFile>
    fun find(id: String): ModelFile?
}
class InMemoryModelCatalog(models: List<ModelFile>) : ModelCatalog

fun interface AdmissionGate {
    fun rejection(): EngineException.Unavailable?     // null = Anfrage zulassen
    companion object { val Open = AdmissionGate { null } }
}

data class EngineConfig(
    val maxWaiting: Int = 4,
    val autoSwitch: Boolean = true,
    val preferred: Accelerator = Accelerator.GPU,
    val cpuThreads: Int = 4,
    val queueRetryAfterSeconds: Int = 5,
)

data class EngineState(
    val loaded: LoadedModel? = null,
    val loading: String? = null,       // Modell-ID während des Ladens
    val running: Boolean = false,
    val waiting: Int = 0,
    val lastError: String? = null,
)

class EngineManager(
    private val backend: InferenceBackend,
    private val catalog: ModelCatalog,
    private val config: EngineConfig = EngineConfig(),
    private val gate: AdmissionGate = AdmissionGate.Open,
) {
    val state: StateFlow<EngineState>
    fun generate(modelId: String?, request: GenerationRequest): Flow<GenerationEvent>
    suspend fun load(modelId: String)
    suspend fun unload()
}
```

Regeln für `generate` (kalter Flow, alle Prüfungen beim Einsammeln):

1. `gate.rejection()` liefert etwas → werfen.
2. Läuft bereits eine Generierung und warten schon `maxWaiting` Anfragen → `QueueFull(queueRetryAfterSeconds)`.
3. Sonst einreihen (FIFO). `state.waiting` zählt die Wartenden.
4. Wenn dran: Zielmodell bestimmen. `modelId == null` → geladenes Modell; ist keines geladen, das erste aus `catalog.list()`; ist der Katalog leer → `NoModelAvailable`.
5. `modelId` nicht im Katalog → `ModelNotFound`.
6. Zielmodell ist nicht das geladene: bei `autoSwitch` laden, sonst `ModelNotLoaded`. Ist gar nichts geladen, wird unabhängig von `autoSwitch` geladen.
7. Laden wirft → `backend.unload()`, `state.loaded = null`, `lastError` gesetzt, `ModelLoadFailed`.
8. `backend.generate(request).withLimits(request.maxTokens, request.stop)` weiterreichen.
9. Bei Ende, Fehler oder Abbruch den Platz freigeben, auch wenn die Anfrage noch wartete.

- [ ] **Step 1: Tests schreiben**

Alle Tests mit `runTest` und `FakeBackend`. Katalog: `m1`, `m2`.

```kotlin
@Test fun `loads first catalog model when nothing loaded and model is null`()
@Test fun `unknown model throws ModelNotFound`()
@Test fun `empty catalog throws NoModelAvailable`()
@Test fun `switches model when autoSwitch is on`()             // m1, dann m2 → loadCalls == [m1, m2]
@Test fun `autoSwitch off throws ModelNotLoaded for other model`()
@Test fun `runs one generation at a time`()                    // 3 parallele Anfragen, activeGenerations nie > 1
@Test fun `serves waiting requests in arrival order`()
@Test fun `sixth concurrent request throws QueueFull`()        // 1 läuft + 4 warten, Nr. 6 → QueueFull(5)
@Test fun `cancelled waiting request frees its slot`()         // Review Focus 2
@Test fun `cancelled running request stops backend and serves next`()
@Test fun `backend failure mid stream frees the queue`()       // Review Focus 3: failAfterTokens=1, danach normale Anfrage
@Test fun `failed load leaves no model loaded and retries later`() // Review Focus 5: failLoadFor={m2}
@Test fun `gate rejection throws Unavailable before queueing`() // state.waiting bleibt 0
@Test fun `applies maxTokens and stop from request`()
@Test fun `state reflects loading running and waiting`()
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :core:test --tests '*EngineManagerTest'`
Expected: FAIL, `EngineManager` nicht definiert

- [ ] **Step 3: `ModelCatalog`, `InMemoryModelCatalog` und `EngineManager` implementieren**

Ansatz: ein `Mutex` für die laufende Generierung plus ein Zähler für Wartende, der vor dem `lock()` erhöht und in `finally` gesenkt wird. Kotlins `Mutex` ist fair, das ergibt die FIFO-Reihenfolge.

- [ ] **Step 4: Tests ausführen**

Run: `./gradlew :core:test --tests '*EngineManagerTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: EngineManager mit Warteschlange und Modellwechsel"
```

---

### Task 4: API-Typen und Request-Zuordnung

**Files:**
- Create: `api/Dto.kt`, `api/RequestMapper.kt`, `api/Errors.kt`
- Test: `api/RequestMapperTest.kt`, `api/DtoTest.kt`

**Interfaces:**
- Consumes: `GenerationRequest`, `ChatMessage`, `Role`, `Sampling`, `EngineException`
- Produces:

```kotlin
val ApiJson: Json   // ignoreUnknownKeys = true, encodeDefaults = true, explicitNulls = false

@Serializable data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<MessageDto> = emptyList(),
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    val stop: JsonElement? = null,              // String oder Array von Strings
    val n: Int? = null,
    val tools: JsonElement? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)
@Serializable data class MessageDto(val role: String, val content: JsonElement? = null)
@Serializable data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = false)

// Antworttypen: ChatCompletionResponse, Choice, AssistantMessage, UsageDto,
// ChatCompletionChunk, ChunkChoice, Delta, ModelList, ModelItem, HealthResponse,
// ErrorBody(error: ErrorDetail(message, type, code))

class ApiError(val status: Int, val type: String, val code: String, message: String,
               val retryAfterSeconds: Int? = null) : Exception(message)

fun ChatCompletionRequest.toGenerationRequest(): GenerationRequest   // wirft ApiError(400, …)
fun Throwable.toApiError(): ApiError
```

Feste Werte in den Antworten: `object` ist `chat.completion`, `chat.completion.chunk`, `list` oder `model`; `owned_by` ist `pocketnode`; `id` ist `chatcmpl-<UUID>`; `created` ist die Unix-Zeit in Sekunden.

`HealthResponse`: `status` (`ok`, `loading`, `no_model`, `unavailable`), `model`, `accelerator` (klein geschrieben), `queue`, `thermal` (nullable), `version`.

Regeln für `toGenerationRequest`:

| Eingabe | Ergebnis |
|---|---|
| `messages` leer | 400 `invalid_request_error` / `messages_required` |
| Rolle nicht `system`, `user`, `assistant` | 400 / `unsupported_role` |
| `content` ist String | Text |
| `content` ist Array aus `{"type":"text","text":…}` | Texte mit `\n` verbunden |
| `content` enthält anderen Typ oder fehlt | 400 / `unsupported_content` |
| `n > 1` | 400 / `unsupported_parameter` |
| `tools` gesetzt und kein leeres Array | 400 / `unsupported_parameter` |
| `max_tokens` und `max_completion_tokens` | `max_completion_tokens` gewinnt |
| Token-Limit negativ | 400 / `invalid_max_tokens` |
| `stop` String | Liste mit einem Eintrag; leere Strings werden entfernt |
| `stop` Array mit mehr als 4 Einträgen | 400 / `invalid_stop` |
| `temperature` außerhalb 0..2, `top_p` außerhalb 0..1 | 400 / `invalid_sampling` |

Zuordnung in `toApiError`:

| Exception | Status | type | code | Retry-After |
|---|---|---|---|---|
| `ApiError` | unverändert | | | |
| `QueueFull` | 429 | `rate_limit_error` | `queue_full` | ja |
| `ModelNotFound` | 404 | `invalid_request_error` | `model_not_found` | |
| `ModelNotLoaded` | 400 | `invalid_request_error` | `model_not_loaded` | |
| `NoModelAvailable` | 503 | `server_error` | `no_model` | |
| `ModelLoadFailed` | 503 | `server_error` | `model_load_failed` | |
| `Unavailable` | 503 | `server_error` | `unavailable` | ja |
| `ContextLengthExceeded` | 400 | `invalid_request_error` | `context_length_exceeded` | |
| `SerializationException` und Ktors `BadRequestException` | 400 | `invalid_request_error` | `invalid_json` | |
| alles andere | 500 | `server_error` | `internal_error` | |

Die Meldung bei 500 lautet fest „Interner Fehler“, die Ursache geht nur ins Log.

- [ ] **Step 1: Tests schreiben**

`RequestMapperTest`: je ein Test pro Zeile der Regeltabelle, dazu `maps roles and sampling into GenerationRequest` und `ignores unknown fields`.
`DtoTest`: `chunk serializes without null fields`, `response has fixed object values`, `toApiError` je Zeile der Zuordnungstabelle.

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :core:test --tests '*api*'`
Expected: FAIL, Typen nicht definiert

- [ ] **Step 3: `Dto.kt`, `RequestMapper.kt`, `Errors.kt` implementieren**

- [ ] **Step 4: Tests ausführen**

Run: `./gradlew :core:test --tests '*api*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: API-Typen, Validierung und Fehlerzuordnung"
```

---

### Task 5: Ktor-Modul mit Auth, Health, Models und nicht gestreamtem Chat

**Files:**
- Create: `api/Auth.kt`, `api/Module.kt`
- Test: `api/ModuleTest.kt`

**Interfaces:**
- Consumes: `EngineManager`, `ModelCatalog`, alles aus Task 4
- Produces:

```kotlin
class ApiDeps(
    val engine: EngineManager,
    val catalog: ModelCatalog,
    val apiKey: () -> String?,              // null = Auth abgeschaltet
    val corsEnabled: () -> Boolean = { false },
    val thermal: () -> String? = { null },
    val version: String = "0.1.0",
)

fun Application.pocketNodeModule(deps: ApiDeps)

fun checkBearer(header: String?, expected: String): Boolean
```

`checkBearer`: Schema ohne Beachtung der Großschreibung, beliebig viele Leerzeichen zwischen Schema und Token, Vergleich mit `MessageDigest.isEqual` auf den UTF-8-Bytes.

Fehlt der Token oder ist er falsch: 401, `type = "authentication_error"`, `code = "invalid_api_key"`, Header `WWW-Authenticate: Bearer`.

Größenlimit für Request-Bodies: 4 MiB, darüber 413 mit `code = "request_too_large"`.

`/health`-Status: `loading`, wenn `state.loading != null`; sonst `ok`, wenn ein Modell geladen ist; sonst `no_model`.

CORS: Ist `corsEnabled()` wahr, werden alle Origins sowie die Header `Authorization` und `Content-Type` erlaubt.

- [ ] **Step 1: Tests schreiben**

Mit `testApplication`, `FakeBackend`, Katalog `m1`/`m2`, API-Key `test-key`.

```kotlin
@Test fun `health works without token and reports no_model`()
@Test fun `health reports ok model and accelerator after first chat`()
@Test fun `models requires token`()                              // 401 + Fehlerformat + WWW-Authenticate
@Test fun `models lists catalog`()                               // ids [m1, m2], owned_by pocketnode
@Test fun `auth accepts lowercase scheme and extra spaces`()     // Review Focus 4
@Test fun `auth rejects wrong scheme empty token and wrong key`()// Review Focus 4, jeweils 401
@Test fun `auth disabled when apiKey is null`()
@Test fun `chat returns completion`()                            // content "Hallo von PocketNode.", finish_reason stop, usage gesetzt
@Test fun `chat with max_tokens 2 returns finish_reason length`()
@Test fun `chat with unknown model returns 404 model_not_found`()
@Test fun `invalid json returns 400 invalid_json`()              // Review Focus 1
@Test fun `missing messages returns 400 messages_required`()     // Review Focus 1
@Test fun `empty body returns 400`()                             // Review Focus 1
@Test fun `oversized body returns 413`()
@Test fun `queue full returns 429 with Retry-After 5`()
@Test fun `gate rejection returns 503 with Retry-After`()
@Test fun `unknown route returns 404 in error format`()
@Test fun `cors headers only when enabled`()
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :core:test --tests '*ModuleTest'`
Expected: FAIL, `pocketNodeModule` nicht definiert

- [ ] **Step 3: `Auth.kt` und `Module.kt` implementieren**

Ansatz: `ContentNegotiation` mit `ApiJson`, `StatusPages` ruft `toApiError()` und setzt bei Bedarf `Retry-After`. Die Auth-Prüfung läuft als Routen-Interceptor für `/v1`. Der nicht gestreamte Chat sammelt `engine.generate(...)` ein. Bei `stream = true` antwortet diese Task noch mit 400 `unsupported_parameter`; Task 6 ersetzt das.

- [ ] **Step 4: Tests ausführen**

Run: `./gradlew :core:test --tests '*ModuleTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: HTTP-API mit Auth, Health, Models und Chat"
```

---

### Task 6: Streaming per SSE

**Files:**
- Create: `api/Sse.kt`
- Modify: `api/Module.kt` (Zweig `stream = true`)
- Test: `api/SseTest.kt`

**Interfaces:**
- Consumes: `ChatCompletionChunk`, `EngineManager.generate`
- Produces:

```kotlin
suspend fun ApplicationCall.respondChatStream(
    events: Flow<GenerationEvent>,
    completionId: String,
    model: String,
    includeUsage: Boolean,
)
```

Ablauf des Streams, jede Zeile als `data: <json>\n\n`:

1. Chunk mit `delta = {"role":"assistant","content":""}`
2. Je Token ein Chunk mit `delta = {"content":"…"}`
3. Chunk mit leerem `delta` und `finish_reason` (`stop` oder `length`)
4. Nur bei `includeUsage`: Chunk mit `choices = []` und `usage`
5. `data: [DONE]`

Header: `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`. Nach jedem Event wird geflusht.

Fehler **vor** dem ersten Event (Warteschlange voll, Modell fehlt) ergeben eine normale Fehlerantwort mit Statuscode. Dazu wird das erste Event eingesammelt, bevor die Antwort beginnt. Fehler **nach** Beginn des Streams ergeben ein Event `data: {"error":{…}}` im Fehlerformat, danach endet der Stream ohne `[DONE]`.

- [ ] **Step 1: Tests schreiben**

```kotlin
@Test fun `stream has role chunk content chunks finish chunk and DONE`()
@Test fun `stream content concatenates to full text`()            // "Hallo von PocketNode."
@Test fun `all chunks share id and model`()
@Test fun `stream with max_tokens 2 ends with finish_reason length`()
@Test fun `include_usage adds usage chunk before DONE`()
@Test fun `no usage chunk by default`()
@Test fun `queue full before stream start returns 429 json not sse`()
@Test fun `unknown model before stream start returns 404 json`()
@Test fun `backend failure mid stream emits error event and no DONE`()   // Review Focus 3
@Test fun `request after mid stream failure succeeds`()                   // Review Focus 3
@Test fun `client disconnect stops generation`()   // tokenDelayMs=50, Antwort nach erstem Chunk abbrechen,
                                                   // danach backend.activeGenerations == 0
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :core:test --tests '*SseTest'`
Expected: FAIL (`stream = true` liefert noch 400)

- [ ] **Step 3: `Sse.kt` implementieren und in `Module.kt` einhängen**

- [ ] **Step 4: Alle Tests ausführen**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: SSE-Streaming mit Abbruch und Fehler-Events"
```

---

### Task 7: Server-Start, Desktop-Lauf und README

**Files:**
- Create: `api/PocketNodeServer.kt`, `DevMain.kt`, `README.md`
- Modify: `core/build.gradle.kts` (Plugin `application`, `mainClass = "com.pocketnode.server.DevMainKt"`)
- Test: `api/PocketNodeServerTest.kt`

**Interfaces:**
- Consumes: `pocketNodeModule`, `ApiDeps`
- Produces:

```kotlin
class PocketNodeServer(private val deps: ApiDeps, private val host: String = "0.0.0.0", private val port: Int = 8080) {
    val isRunning: Boolean
    fun start()          // zweiter Aufruf ohne Wirkung
    fun stop()           // Schonfrist 1 s, Timeout 3 s; ohne laufenden Server ohne Wirkung
    val boundPort: Int   // tatsächlicher Port, wichtig bei port = 0
}

fun generateApiKey(): String   // "pn-" + 32 zufällige Bytes als URL-sicheres Base64 ohne Padding (SecureRandom)
```

`DevMain`: startet den Server mit `FakeBackend(tokenDelayMs = 40)` und dem Katalog `fake-small`, `fake-large`. Port aus `POCKETNODE_PORT` (Standard 8080), Key aus `POCKETNODE_API_KEY` oder neu erzeugt. Gibt Adresse und Key auf der Konsole aus.

- [ ] **Step 1: Tests schreiben**

```kotlin
@Test fun `starts on free port and answers health`()     // port = 0, echter HTTP-Aufruf mit java.net.http.HttpClient
@Test fun `start twice is a no-op`()
@Test fun `stop releases the port`()                     // danach neuer Server auf demselben Port startbar
@Test fun `generateApiKey has prefix and is unique`()    // beginnt mit "pn-", Länge ≥ 40, zwei Aufrufe verschieden
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :core:test --tests '*PocketNodeServerTest'`
Expected: FAIL, `PocketNodeServer` nicht definiert

- [ ] **Step 3: `PocketNodeServer`, `generateApiKey` und `DevMain` implementieren**

- [ ] **Step 4: Tests ausführen**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 5: Von Hand gegenprüfen**

```bash
POCKETNODE_API_KEY=dev ./gradlew :core:run &
curl -s localhost:8080/health
curl -s localhost:8080/v1/models -H "Authorization: Bearer dev"
curl -sN localhost:8080/v1/chat/completions -H "Authorization: Bearer dev" \
  -H "Content-Type: application/json" \
  -d '{"model":"fake-small","stream":true,"messages":[{"role":"user","content":"Hi"}]}'
```

Expected: `/health` liefert JSON mit `"status":"no_model"`, `/v1/models` zwei Einträge, der Stream endet mit `data: [DONE]`.

- [ ] **Step 6: README schreiben**

Englisch. Inhalt: Kurzbeschreibung aus der Vorlage, Projektstatus („Stage 1 of 5: server core with a fake backend, no on-device inference yet“), Aufbau der Module, Desktop-Start mit den drei `curl`-Befehlen, Tabelle der Endpunkte und Fehlercodes, Verweis auf Spec und Plan, Hinweis zur NPU auf dem Tensor G2 mit Verweis auf Abschnitt 9 der Spec.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: Server-Start, Desktop-Lauf und README"
```

---

## Abschluss der Stufe

- [ ] `./gradlew :core:test` ist grün
- [ ] CI ist grün, das Debug-APK liegt als Artefakt vor
- [ ] Der Desktop-Lauf aus Task 7 funktioniert
- [ ] Optional: Open WebUI gegen den Desktop-Lauf verbinden und einen Chat führen

Danach folgt der Plan für Stufe 2 (LiteRT-LM-Backend, Service, Übersicht).
