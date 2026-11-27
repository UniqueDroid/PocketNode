# PocketNode Stufe 2 (Engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein echtes Modell antwortet über die API: zuerst auf dem Desktop (CPU), dann als Android-App mit Foreground Service und Übersicht auf dem Pixel 7 Pro (GPU, CPU als Rückfallebene).

**Architecture:** `LiteRtLmBackend` liegt in `:core` und wird gegen `litertlm-jvm` kompiliert. Die Android-Bibliothek `litertlm-android` hat dieselben Klassen, `:app` bringt sie zur Laufzeit mit. Dadurch läuft dasselbe Backend auf dem Desktop und lässt sich dort mit einem echten Modell prüfen. `:app` bekommt Einstellungen (DataStore), einen App-Container, den Foreground Service und drei Bildschirme.

**Tech Stack:** wie Stufe 1, dazu LiteRT-LM 0.20.0 (`litertlm-jvm`, `litertlm-android`), AndroidX DataStore, Lifecycle/ViewModel, Navigation Compose, ZXing Core (QR-Code).

**Spec:** `docs/superpowers/specs/2026-11-27-pocketnode-design.md` (Abschnitte 5, 6, 7, 10, 11, 13)

**Umfang:** Stufe 2 der Spec, dazu die offenen Punkte aus dem Review von Stufe 1. Nicht enthalten: RAM-Prüfung, Thermik, Autostart, Akku-Ausnahme, Download, Leerlauf-Entladen (Stufe 3), NPU-Test (Stufe 4).

## Global Constraints

- Alle Constraints aus dem Plan zu Stufe 1 gelten weiter (Paket `com.pocketnode.server`, `minSdk = 31`, nur `arm64-v8a`, gepinnte Versionen, `:core` ohne `android.*`, JSON nur über kotlinx.serialization, OpenAI-Fehlerformat).
- LiteRT-LM ist auf **0.20.0** gepinnt, für `litertlm-jvm` und `litertlm-android` dieselbe Version.
- Die UI, die Benachrichtigung und `/health` zeigen immer den **tatsächlich aktiven** Beschleuniger, nie den gewünschten.
- Der Server startet nie ohne API-Key, außer der Schalter „Auth abschalten“ ist bewusst gesetzt.
- JNI-Aufrufe von LiteRT-LM blockieren. Sie laufen nie auf dem Main-Thread und nie auf Ktors Threads.
- UI-Texte liegen in `strings.xml`, Deutsch (`values-de`) und Englisch (`values`).
- Alles, was sich ohne Gerät prüfen lässt, hat JVM-Tests. Was nur auf dem Gerät prüfbar ist, steht in der Checkliste in Task 7.

## Geprüfte Fakten zu LiteRT-LM 0.20.0

Aus den Klassen der Bibliothek gelesen, nicht aus der Dokumentation:

```kotlin
EngineConfig(modelPath: String, backend: Backend, visionBackend: Backend?, audioBackend: Backend?,
             maxNumTokens: Int?, maxNumImages: Int?, cacheDir: String?, …)
Backend.CPU(threadCount: Int?, …)   Backend.GPU()   Backend.NPU(nativeLibraryDir: String)
Engine(config).initialize()         engine.createConversation(config)      engine.close()
ConversationConfig(systemInstruction: Contents?, initialMessages: List<Message>, tools, samplerConfig: SamplerConfig?,
                   …, maxOutputToken: Int?, …)
SamplerConfig(topK: Int, topP: Double, temperature: Double, seed: Int)
Message.system(String)  Message.user(String)  Message.model(String)       Role: SYSTEM, USER, MODEL, TOOL
conversation.sendMessageAsync(message: Message): Flow<Message>            // Text in contents als Content.Text
conversation.cancelProcess()        conversation.close()
conversation.getBenchmarkInfo(): BenchmarkInfo   // lastPrefillTokenCount, lastDecodeTokenCount,
                                                 // lastDecodeTokensPerSecond, maxNumTokens
```

`litertlm-jvm` enthält native Bibliotheken für Linux x86_64 und aarch64, macOS und Windows. Auf Hugging Face gibt es unter `litert-community` frei ladbare Modelle (z. B. `granite-4.0-350m-litert-lm`, `gemma-4-E2B-it-litert-lm`).

## Review Focus

1. **GPU-Start scheitert:** `Backend.GPU()` wirft beim Initialisieren → Laden auf der CPU, `LoadedModel.accelerator == CPU`, die Anfrage wird beantwortet. Scheitert auch die CPU → `ModelLoadFailed`. (Test in Task 2)
2. **Letzte Nachricht ist nicht vom Nutzer** (z. B. endet mit `assistant`, oder nur `system`) → 400 `last_message_must_be_user`, kein Absturz der Engine. (Tests in Task 1 und Task 2)
3. **Modelldatei verschwindet oder ist kaputt:** Datei nach dem Start gelöscht, 0 Bytes, falsches Format → 503 `model_load_failed` ohne Dateipfad in der Meldung, der Server läuft weiter. (Tests in Task 1 und Task 3)
4. **Service-Neustart durch das System** (`onStartCommand` mit `intent == null`) → der Server startet mit den gespeicherten Einstellungen, nicht mit Standardwerten. Port belegt → Benachrichtigung zeigt den Fehler, kein Absturz. (Test in Task 5)
5. **Import bricht ab oder Name kollidiert:** abgebrochene Kopie hinterlässt keine halbe `.litertlm`-Datei im Katalog; eine gleichnamige Datei wird nicht still überschrieben. (Test in Task 4)

---

## File Structure

```text
core/src/main/kotlin/com/pocketnode/server/
  engine/Types.kt                 + GenerationEvent.Started
  engine/EngineManager.kt         Started-Event, Token/s, unload bei Abbruch des Ladens
  engine/DirectoryModelCatalog.kt Modellordner als Katalog
  engine/litert/LiteRtMapping.kt  GenerationRequest → ConversationConfig + letzte Nachricht
  engine/litert/LiteRtRuntime.kt  dünne Schnittstelle über Engine/Conversation (für Tests ersetzbar)
  engine/litert/LiteRtLmBackend.kt
  hardware/CpuTopology.kt         schnelle Kerne aus Maximalfrequenzen
  api/Module.kt, Sse.kt, RequestMapper.kt, Errors.kt   Review-Punkte aus Stufe 1
  api/ServerLog.kt                Log-Schnittstelle
  DevMain.kt                      echter Modellordner per POCKETNODE_MODELS_DIR
app/src/main/java/com/pocketnode/server/
  PocketNodeApp.kt                Application + AppContainer
  settings/Settings.kt, SettingsStore.kt
  models/ModelImporter.kt
  hardware/DeviceInfo.kt          SoC, RAM, Adressen, Kernfrequenzen lesen
  service/PocketNodeService.kt, ServerController.kt, Notifications.kt
  ui/MainActivity.kt, OverviewScreen.kt, ModelsScreen.kt, SettingsScreen.kt, viewmodels, QrCode.kt
app/src/main/res/values/strings.xml, values-de/strings.xml
app/src/test/java/…               JVM-Tests für SettingsStore, ModelImporter, ServerController
docs/geraetetest-stufe-2.md       Checkliste für das Pixel 7 Pro
```

---

### Task 1: Offene Punkte aus dem Review von Stufe 1

**Files:**
- Modify: `engine/Types.kt`, `engine/EngineManager.kt`, `engine/GenerationLimits.kt`, `engine/FakeBackend.kt`
- Modify: `api/Module.kt`, `api/Sse.kt`, `api/RequestMapper.kt`, `api/Errors.kt`, `DevMain.kt`
- Create: `api/ServerLog.kt`
- Modify: `.github/workflows/ci.yml`
- Test: bestehende Testdateien erweitern

**Interfaces:**
- Produces:

```kotlin
// Types.kt: erstes Event jeder Generierung, nach dem Laden und vor dem ersten Token
data class Started(val model: LoadedModel) : GenerationEvent

// EngineState: neu
val lastTokensPerSecond: Double? = null     // der letzten abgeschlossenen Generierung

// ServerLog.kt
fun interface ServerLog {
    fun log(level: Level, message: String, error: Throwable?)
    enum class Level { INFO, WARN, ERROR }
    companion object { val Stderr: ServerLog }      // schreibt "LEVEL message" und den Stacktrace nach System.err
}
// ApiDeps: neu
val log: ServerLog = ServerLog.Stderr
```

Änderungen im Einzelnen:

| Punkt | Festlegung |
|---|---|
| Auth | Alle Routen unter `/v1` liegen in einem Block mit einem routenweiten Plugin, das bei fehlendem Key `ApiError(401, …)` **wirft**. `requireAuth` je Route entfällt. |
| Modellname | `EngineManager.generate` sendet `Started(model)` als erstes Event. `Module.kt` und `Sse.kt` nehmen den Modellnamen daraus. Der Parameter `model: () -> String` von `respondChatStream` entfällt. `withLimits` reicht `Started` unverändert durch und zählt es nicht. |
| Usage bei Kürzung | Kürzt `withLimits`, behält es die zuletzt bekannte Prompt-Token-Zahl nicht (unbekannt), setzt aber `Usage(promptTokens = 0, completionTokens = gezählte Tokens)` nur, wenn das Backend vorher kein Usage liefern konnte. Backends dürfen `Done.usage` selbst korrekt liefern; `LiteRtLmBackend` tut das in Task 2. Für `FakeBackend`: bei Kürzung `Usage(promptTokens, gezählte Tokens)` über einen neuen optionalen Parameter `promptTokens: Int?` von `withLimits`. |
| Ladefehler | `model_load_failed` antwortet mit der festen Meldung „Das Modell '<id>' konnte nicht geladen werden“. Die Ursache geht an `ServerLog`. |
| Abbruch beim Laden | Wird `backend.load` abgebrochen, ruft `EngineManager` `backend.unload()` auf (in `NonCancellable`). |
| `n` | `n < 1` → 400 `unsupported_parameter`. |
| Rolle `developer` | wird wie `system` behandelt. |
| Letzte Nachricht | Ist die letzte Nachricht nicht von `user` → 400 `last_message_must_be_user`. |
| Logging | `application.log` wird durch `deps.log` ersetzt. |
| Token/s | `EngineManager` misst Tokens und Dauer vom ersten Token bis `Done` und schreibt `lastTokensPerSecond`. Bei weniger als 2 Tokens bleibt der alte Wert. |
| `DevMain` | bindet an `127.0.0.1`, außer `POCKETNODE_HOST` ist gesetzt. |
| CI | `actions/checkout`, `setup-java`, `upload-artifact`, `gradle/actions/setup-gradle` auf die aktuelle Hauptversion. Auslöser: `push` nur auf `main`, dazu `pull_request`. Zusätzlich `:app:testDebugUnitTest`. |

- [ ] **Step 1: Tests schreiben**

```kotlin
// EngineManagerTest
@Test fun `first event is Started with the loaded model`()
@Test fun `cancelled load unloads the backend`()            // FakeBackend bekommt loadDelayMs und unloadCalls
@Test fun `lastTokensPerSecond is set after a generation`() // tokenDelayMs=10, 4 Tokens → Wert zwischen 50 und 200
// GenerationLimitsTest
@Test fun `Started passes through and is not counted`()
@Test fun `cut output reports counted completion tokens`()  // promptTokens=7, maxTokens=2 → Usage(7, 2)
// RequestMapperTest
@Test fun `n below one is rejected`()
@Test fun `developer role maps to SYSTEM`()
@Test fun `last message from assistant is rejected`()       // code last_message_must_be_user
@Test fun `only system message is rejected`()
// ModuleTest
@Test fun `every v1 route rejects a missing key`()          // geht alle registrierten Routen unter /v1 durch, erwartet 401
@Test fun `model load failure hides the cause and logs it`()// Meldung enthält weder "absichtlich" noch einen Pfad; Log hat 1 ERROR
@Test fun `response model name comes from the Started event`()
// SseTest
@Test fun `cut stream with include_usage still sends usage`()
```

`every v1 route rejects a missing key` liest die Routen aus dem Routing-Baum der Anwendung, damit eine neue Route ohne Auth den Test bricht.

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :core:test`
Expected: FAIL, die neuen Tests schlagen fehl oder kompilieren nicht

- [ ] **Step 3: Änderungen umsetzen**

- [ ] **Step 4: Tests ausführen**

Run: `./gradlew :core:test`
Expected: PASS, alle bisherigen 107 Tests plus die neuen

- [ ] **Step 5: CI-Datei anpassen und committen**

```bash
git add -A && git commit -m "fix: offene Punkte aus dem Review von Stufe 1"
```

---

### Task 2: LiteRtLmBackend

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/build.gradle.kts` (`compileOnly` und `testImplementation` für `litertlm-jvm`)
- Create: `engine/litert/LiteRtMapping.kt`, `engine/litert/LiteRtRuntime.kt`, `engine/litert/LiteRtLmBackend.kt`
- Test: `engine/litert/LiteRtMappingTest.kt`, `engine/litert/LiteRtLmBackendTest.kt`, `engine/litert/LiteRtRealModelTest.kt`

**Interfaces:**
- Consumes: `InferenceBackend`, `GenerationRequest`, `GenerationEvent`, `EngineException`
- Produces:

```kotlin
// LiteRtMapping.kt: reine Funktionen
data class ConversationPlan(
    val systemInstruction: String?,           // alle system-Nachrichten, mit "\n\n" verbunden
    val history: List<ChatMessage>,           // alle nicht-system-Nachrichten außer der letzten
    val lastUserText: String,
    val sampler: SamplerValues?,              // null = Vorgaben des Modells
    val maxOutputTokens: Int?,
)
data class SamplerValues(val topK: Int, val topP: Double, val temperature: Double, val seed: Int)
fun GenerationRequest.toConversationPlan(): ConversationPlan   // wirft IllegalArgumentException, wenn die letzte Nachricht nicht USER ist

// LiteRtRuntime.kt: die einzige Stelle, die LiteRT-LM-Klassen anfasst
interface LiteRtRuntime {
    /** Blockiert. Wirft bei Fehlern. */
    fun open(modelPath: String, accelerator: Accelerator, cpuThreads: Int, cacheDir: String?, maxTokens: Int?): LiteRtSession
}
interface LiteRtSession : AutoCloseable {
    val contextTokens: Int
    /** Kalter Flow mit Textstücken. Abbruch ruft cancelProcess(). Liefert am Ende die Token-Zahlen. */
    fun generate(plan: ConversationPlan, onDone: (promptTokens: Int?, completionTokens: Int?) -> Unit): Flow<String>
}
object RealLiteRtRuntime : LiteRtRuntime

// LiteRtLmBackend.kt
class LiteRtLmBackend(
    private val runtime: LiteRtRuntime = RealLiteRtRuntime,
    private val cacheDir: String? = null,
    private val maxTokens: Int? = null,
    private val log: ServerLog = ServerLog.Stderr,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : InferenceBackend {
    override val name = "LiteRT-LM"
    override val supported = setOf(Accelerator.CPU, Accelerator.GPU)
}
```

Regeln:

- **Sampler:** Sind `temperature`, `topP` und `topK` alle `null`, ist `sampler = null`. Sonst werden fehlende Werte ergänzt: `topK = 40`, `topP = 0.95`, `temperature = 1.0`, `seed = 0`.
- **Laden:** Erst der gewünschte Beschleuniger. Wirft `open` bei `GPU`, wird der Fehler als WARN geloggt und `CPU` versucht. Wirft auch das, fliegt die zweite Ausnahme. `NPU` wird wie `GPU` behandelt (nicht in `supported`, also zuerst GPU).
- **Entladen:** schließt die Session. Ein vorher geladenes Modell wird vor dem neuen Laden geschlossen.
- **Generieren:** Pro Anfrage eine neue Conversation, am Ende geschlossen. `Done.usage` kommt aus `onDone`, wenn beide Werte da sind, sonst `null`. `finish_reason` ist `STOP`; `LENGTH` setzt `withLimits` bzw. das Backend, wenn `completionTokens == maxOutputTokens`.
- **Kontext zu lang:** Meldet die Engine das, wird `ContextLengthExceeded(contextTokens)` geworfen. Welche Ausnahme die Engine dafür wirft, wird in Step 5 am echten Modell festgestellt und im Code vermerkt.
- **Threads:** Alle Aufrufe von `runtime` und der Flow laufen auf `dispatcher`.

- [ ] **Step 1: Tests für die Zuordnung schreiben**

```kotlin
@Test fun `single user message`()                         // system=null, history leer, lastUserText="Hi"
@Test fun `system messages are joined`()                  // zwei system → "A\n\nB"
@Test fun `history keeps order and drops the last user message`()
@Test fun `no sampling values means model defaults`()     // sampler == null
@Test fun `partial sampling is filled with defaults`()    // nur temperature=0.2 → SamplerValues(40, 0.95, 0.2, 0)
@Test fun `maxTokens is passed through`()
@Test fun `last message from assistant throws`()
```

- [ ] **Step 2: Tests für das Backend mit einer Test-Runtime schreiben**

Eine `FakeRuntime` im Testcode zeichnet `open`-Aufrufe auf und kann pro Beschleuniger werfen.

```kotlin
@Test fun `loads on GPU when it works`()                          // accelerator == GPU
@Test fun `falls back to CPU when GPU fails and logs a warning`() // Review Focus 1: open-Aufrufe [GPU, CPU], accelerator == CPU, 1 WARN
@Test fun `throws when CPU fails too`()                           // Review Focus 1
@Test fun `CPU preference never tries GPU`()
@Test fun `load closes the previous session`()
@Test fun `generate emits tokens then Done with usage`()
@Test fun `generate without usage yields Done with null usage`()
@Test fun `cancelling the flow cancels the session`()
@Test fun `generate before load throws`()
@Test fun `last message from assistant throws ApiError-compatible exception`() // Review Focus 2: IllegalArgumentException → über Task 1 schon 400
```

- [ ] **Step 3: Fehlschlag prüfen**

Run: `./gradlew :core:test --tests '*litert*'`
Expected: FAIL, Typen nicht definiert

- [ ] **Step 4: `LiteRtMapping`, `LiteRtRuntime`, `RealLiteRtRuntime` und `LiteRtLmBackend` implementieren**

`RealLiteRtRuntime` baut `EngineConfig`, `ConversationConfig` und `Message` aus dem Abschnitt „Geprüfte Fakten“. Token-Zahlen kommen aus `getBenchmarkInfo()`; wirft der Aufruf, sind beide `null`.

- [ ] **Step 5: Test mit echtem Modell**

`LiteRtRealModelTest` läuft nur, wenn `POCKETNODE_TEST_MODEL` auf eine `.litertlm`-Datei zeigt (`assumeTrue`).

```kotlin
@Test fun `real model answers on CPU`()                 // nicht leerer Text, Done am Ende, contextTokens > 0
@Test fun `real model respects maxTokens`()             // maxTokens=5 → höchstens 5 Tokens, finish LENGTH
@Test fun `real model honours a system instruction`()   // System: "Antworte nur mit dem Wort JA." → Antwort enthält "JA"
@Test fun `real model can be cancelled`()               // Abbruch nach dem ersten Token, danach neue Anfrage erfolgreich
@Test fun `real model reports usage`()                  // promptTokens > 0, completionTokens > 0, sonst im Ledger vermerken
@Test fun `corrupt model file fails to load`()          // Review Focus 3: 0-Byte-Datei → Ausnahme, kein Absturz der JVM
```

Ein frei ladbares kleines Modell von Hugging Face (`litert-community/granite-4.0-350m-litert-lm` oder kleiner) nach `/tmp` laden und die Tests damit ausführen. Die Modelldatei wird **nicht** committet.

Run: `POCKETNODE_TEST_MODEL=/tmp/model.litertlm ./gradlew :core:test --tests '*LiteRtRealModelTest'`
Expected: PASS. Schlägt ein Test fehl, weil die Bibliothek sich anders verhält als angenommen, wird der Befund im Ledger festgehalten und das Backend angepasst.

- [ ] **Step 6: Alle Tests ausführen und committen**

Run: `./gradlew :core:test`
Expected: PASS (die Tests mit echtem Modell werden ohne Variable übersprungen)

```bash
git add -A && git commit -m "feat: LiteRtLmBackend mit GPU/CPU-Rückfall"
```

---

### Task 3: Modellordner und echter Desktop-Lauf

**Files:**
- Create: `engine/DirectoryModelCatalog.kt`
- Modify: `DevMain.kt`, `core/build.gradle.kts` (`runtimeOnly` `litertlm-jvm` für den Desktop-Lauf), `README.md`
- Test: `engine/DirectoryModelCatalogTest.kt`

**Interfaces:**
- Produces:

```kotlin
class DirectoryModelCatalog(private val directory: File) : ModelCatalog
// list(): alle regulären Dateien mit Endung ".litertlm" (ohne Beachtung der Großschreibung), Größe > 0,
//         nach Name sortiert. id = Dateiname ohne Endung. Liest das Verzeichnis bei jedem Aufruf neu.
// find(id): Eintrag mit genau dieser id. Enthält id einen Pfadtrenner oder "..", ist das Ergebnis null.
```

`DevMain`: Ist `POCKETNODE_MODELS_DIR` gesetzt, laufen `DirectoryModelCatalog` und `LiteRtLmBackend` (CPU), sonst wie bisher das `FakeBackend`.

- [ ] **Step 1: Tests schreiben**

```kotlin
@Test fun `lists litertlm files sorted by name`()
@Test fun `id is the file name without extension`()
@Test fun `ignores other extensions directories and empty files`()   // Review Focus 3 und 5
@Test fun `ignores temporary import files`()                         // "x.litertlm.part"
@Test fun `missing directory yields an empty list`()
@Test fun `sees files added after construction`()
@Test fun `find rejects path traversal`()                            // "../x", "a/b" → null
@Test fun `deleted file disappears from the list`()                  // Review Focus 3
```

- [ ] **Step 2: Fehlschlag prüfen, implementieren, Tests ausführen**

Run: `./gradlew :core:test --tests '*DirectoryModelCatalogTest'`
Expected: erst FAIL (Klasse fehlt), nach der Implementierung PASS

- [ ] **Step 3: Echter Desktop-Lauf von Hand**

```bash
POCKETNODE_MODELS_DIR=/tmp/models POCKETNODE_API_KEY=dev ./gradlew :core:run &
curl -s localhost:8080/v1/models -H "Authorization: Bearer dev"
curl -sN localhost:8080/v1/chat/completions -H "Authorization: Bearer dev" \
  -d '{"stream":true,"messages":[{"role":"user","content":"Sag Hallo."}]}'
curl -s localhost:8080/health
```

Expected: `/v1/models` listet das Modell, der Stream liefert echten Text und endet mit `[DONE]`, `/health` zeigt `"status":"ok"` und `"accelerator":"cpu"`. Zusätzlich: Modelldatei im laufenden Betrieb löschen und ein anderes Modell anfragen → 404; Datei durch eine kaputte ersetzen → 503 ohne Pfad (Review Focus 3).

- [ ] **Step 4: README ergänzen und committen**

README: Abschnitt „Run a real model on a desktop“ mit `POCKETNODE_MODELS_DIR` und einem Hinweis, woher Modelle kommen.

```bash
git add -A && git commit -m "feat: Modellordner und Desktop-Lauf mit echtem Modell"
```

---

### Task 4: Einstellungen, App-Container, Geräteinfo, Import

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (LiteRT-LM Android, DataStore, Lifecycle, Navigation, ZXing, Test-Abhängigkeiten)
- Create: `core/…/hardware/CpuTopology.kt`
- Create: `app/…/PocketNodeApp.kt`, `settings/Settings.kt`, `settings/SettingsStore.kt`, `models/ModelImporter.kt`, `hardware/DeviceInfo.kt`
- Modify: `AndroidManifest.xml` (`android:name=".PocketNodeApp"`)
- Test: `core/…/hardware/CpuTopologyTest.kt`, `app/src/test/…/SettingsStoreTest.kt`, `app/src/test/…/ModelImporterTest.kt`

**Interfaces:**
- Produces:

```kotlin
// core: CpuTopology.kt
/** maxFreqKhz: Maximalfrequenz je Kern. Ergebnis: Anzahl der Kerne oberhalb der langsamsten Gruppe, mindestens 1, höchstens 4.
    Sind alle Kerne gleich schnell oder die Liste leer: min(Kernzahl, 4), mindestens 1. */
fun recommendedThreads(maxFreqKhz: List<Long>): Int
// Tensor G2: [1803000×4, 2348000×2, 2850000×2] → 4

// app: Settings.kt
enum class AcceleratorPreference { AUTO, GPU, CPU }      // AUTO = GPU mit CPU-Rückfall
data class Settings(
    val port: Int = 8080,
    val apiKey: String,                                  // beim ersten Lesen erzeugt und gespeichert
    val authDisabled: Boolean = false,
    val selectedModel: String? = null,
    val accelerator: AcceleratorPreference = AcceleratorPreference.AUTO,
    val autoSwitch: Boolean = true,
    val corsEnabled: Boolean = false,
    val serverWanted: Boolean = false,                   // true, solange der Nutzer den Server laufen lassen will
)

// SettingsStore.kt (Preferences DataStore, Datei "settings")
class SettingsStore(private val dataStore: DataStore<Preferences>, private val newKey: () -> String = ::generateApiKey) {
    val settings: Flow<Settings>
    suspend fun current(): Settings
    suspend fun update(transform: (Settings) -> Settings)
    suspend fun regenerateApiKey(): String
}
// Port außerhalb 1024..65535 wird beim Schreiben abgelehnt (IllegalArgumentException).

// ModelImporter.kt
class ModelImporter(private val modelsDir: File) {
    sealed interface Result { data class Imported(val id: String) : Result
                              data class NameTaken(val id: String) : Result
                              data class Failed(val reason: String) : Result }
    /** Kopiert nach "<name>.litertlm.part" und benennt erst nach vollständiger Kopie um.
        displayName ohne ".litertlm" → Failed. Vorhandene Datei gleichen Namens → NameTaken, nichts wird überschrieben.
        Bei Abbruch oder Fehler wird die .part-Datei gelöscht. */
    suspend fun import(displayName: String, source: InputStream, onProgress: (copiedBytes: Long) -> Unit = {}): Result
    fun delete(id: String): Boolean
}
// Der Dateiname wird bereinigt: nur der letzte Pfadbestandteil, Zeichen außerhalb [A-Za-z0-9._-] werden zu "_".

// DeviceInfo.kt
data class DeviceSnapshot(val socModel: String, val cpuCores: Int, val recommendedThreads: Int,
                          val totalRamMb: Long, val availableRamMb: Long, val addresses: List<NetworkAddress>)
data class NetworkAddress(val label: String, val ip: String)   // label: "WLAN", "Tailscale", "Ethernet", sonst der Interface-Name
class DeviceInfo(private val context: Context) { fun snapshot(): DeviceSnapshot }
// Adressen: IPv4 des aktiven Netzes über ConnectivityManager/LinkProperties, dazu IPv4 von Interfaces namens "tailscale*" oder "tun*"
//           mit Adresse in 100.64.0.0/10. Keine Loopback-, keine Mobilfunk-Adressen.

// PocketNodeApp.kt
class AppContainer(context: Context) {
    val settingsStore: SettingsStore
    val modelsDir: File                       // getExternalFilesDir(null)/models, wird angelegt
    val catalog: DirectoryModelCatalog
    val importer: ModelImporter
    val deviceInfo: DeviceInfo
    val eventLog: EventLog                    // ServerLog, hält die letzten 200 Einträge und schreibt nach Logcat
    val controller: ServerController          // Task 5
}
class PocketNodeApp : Application() { lateinit var container: AppContainer }
```

- [ ] **Step 1: Tests schreiben**

```kotlin
// CpuTopologyTest
@Test fun `tensor g2 layout recommends four threads`()
@Test fun `uniform cores are capped at four`()         // 8 gleiche → 4, 2 gleiche → 2
@Test fun `empty list recommends one`()
@Test fun `single fast core recommends one`()          // [1800×7, 3000×1] → 1
// SettingsStoreTest (DataStore mit Temp-Datei, runTest)
@Test fun `first read creates and persists an api key`()   // zwei Lesevorgänge, derselbe Key, beginnt mit "pn-"
@Test fun `update persists across instances`()
@Test fun `regenerateApiKey changes the key`()
@Test fun `rejects ports outside 1024 to 65535`()
@Test fun `defaults are safe`()                            // authDisabled=false, corsEnabled=false, serverWanted=false
// ModelImporterTest (Temp-Verzeichnis)
@Test fun `imports a file and reports progress`()
@Test fun `rejects names without litertlm extension`()
@Test fun `does not overwrite an existing model`()         // Review Focus 5: NameTaken, alter Inhalt bleibt
@Test fun `failed copy leaves no file behind`()            // Review Focus 5: InputStream wirft nach 10 Bytes
@Test fun `cancelled copy leaves no file behind`()         // Review Focus 5
@Test fun `sanitizes the file name`()                      // "../../böse name.litertlm" → "b_se_name.litertlm" im Modellordner
@Test fun `delete removes the model`()
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :core:test :app:testDebugUnitTest`
Expected: FAIL, Typen nicht definiert

- [ ] **Step 3: Implementieren**

`DeviceInfo` liest die Frequenzen aus `/sys/devices/system/cpu/cpu<N>/cpufreq/cpuinfo_max_freq` und gibt sie an `recommendedThreads`. Nicht lesbare Dateien werden übersprungen.

- [ ] **Step 4: Tests und Build**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS und `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: Einstellungen, App-Container, Geräteinfo und Modell-Import"
```

---

### Task 5: ServerController und Foreground Service

**Files:**
- Create: `service/ServerController.kt`, `service/PocketNodeService.kt`, `service/Notifications.kt`
- Modify: `AndroidManifest.xml` (Service mit `foregroundServiceType="specialUse"` und `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="local_llm_inference_server" />`), `strings.xml`
- Test: `app/src/test/…/ServerControllerTest.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `ModelCatalog`, `InferenceBackend`, `EngineManager`, `PocketNodeServer`, `ApiDeps`
- Produces:

```kotlin
sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data object Starting : ServerStatus
    data class Running(val port: Int, val engine: EngineState) : ServerStatus
    data class Failed(val reason: String) : ServerStatus
}

/** Besitzt Engine und HTTP-Server. Kennt keine Android-Klassen, damit es als JVM-Test läuft. */
class ServerController(
    private val settingsStore: SettingsStore,
    private val catalog: ModelCatalog,
    private val backendFactory: () -> InferenceBackend,
    private val cpuThreads: () -> Int,
    private val log: ServerLog,
    private val scope: CoroutineScope,
    private val host: String = "0.0.0.0",
) {
    val status: StateFlow<ServerStatus>
    suspend fun start()     // liest die Einstellungen; ohne Wirkung, wenn schon Running oder Starting
    suspend fun stop()      // stoppt den Server, entlädt das Modell
    suspend fun restart()
    suspend fun loadModel(id: String)   // nur im Zustand Running; setzt selectedModel
}
```

Regeln für `start`:

1. Status `Starting`.
2. `EngineManager` mit `EngineConfig(autoSwitch, preferred, cpuThreads)`. `AUTO` und `GPU` → `Accelerator.GPU`, `CPU` → `Accelerator.CPU`.
3. `PocketNodeServer` mit `ApiDeps(apiKey = { if (authDisabled) null else apiKey }, corsEnabled, log)`. Der Key wird bei jeder Anfrage frisch aus den Einstellungen gelesen, ein neu erzeugter Key gilt sofort.
4. Server starten. Wirft das (Port belegt), Status `Failed("Port <n> ist belegt")`, nichts bleibt offen.
5. Status `Running`. Der Zustand der Engine wird laufend in `Running.engine` gespiegelt.
6. Ist `selectedModel` gesetzt und installiert, wird es im Hintergrund geladen. Ein Fehler dabei lässt den Server laufen (`EngineState.lastError`).

Ändern sich `port`, `corsEnabled`, `accelerator` oder `autoSwitch` im Zustand `Running`, ruft die UI `restart()`.

`PocketNodeService`:

- `onStartCommand`: `ACTION_STOP` → `serverWanted = false`, `controller.stop()`, `stopSelf()`, `START_NOT_STICKY`. Sonst (auch `intent == null`): Benachrichtigungskanal anlegen, `startForeground` mit `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, Locks holen, `controller.start()`, `START_STICKY`.
- Locks: `PARTIAL_WAKE_LOCK` („PocketNode:Wake“) und `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`, „PocketNode:Wifi“). In `onDestroy` freigeben.
- Die Benachrichtigung folgt `controller.status`: „Startet …“, „Läuft auf <ip>:<port> · <Modell> · <GPU|CPU>“ (ohne Modell: „kein Modell geladen“), „Fehler: <Grund>“. Aktion „Stoppen“.
- `Notifications.kt` enthält eine reine Funktion `notificationText(status: ServerStatus, address: String?, strings: (Int) -> String): String`.

- [ ] **Step 1: Tests schreiben**

`ServerControllerTest` mit `FakeBackend`, `InMemoryModelCatalog`, `SettingsStore` auf Temp-Datei, Port 0 über die Einstellungen ist nicht erlaubt, deshalb ein freier Port aus `ServerSocket(0)`.

```kotlin
@Test fun `start runs the server and answers health`()
@Test fun `start twice keeps one server`()
@Test fun `start uses stored settings not defaults`()             // Review Focus 4: gespeicherter Port und Key gelten
@Test fun `taken port yields Failed and leaves nothing running`() // Review Focus 4
@Test fun `stop releases the port and unloads the model`()
@Test fun `selected model is loaded after start`()
@Test fun `failing selected model keeps the server running`()     // FakeBackend(failLoadFor), Status Running, lastError gesetzt
@Test fun `missing selected model is ignored`()
@Test fun `regenerated key applies without restart`()             // alter Key 401, neuer Key 200
@Test fun `authDisabled allows requests without key`()
@Test fun `restart applies a changed port`()
@Test fun `loadModel switches the model and stores the selection`()
@Test fun `status mirrors engine state`()                         // nach einer Anfrage: Running.engine.loaded != null
// NotificationsTest
@Test fun `notification text for each status`()                   // vier Zustände, mit und ohne Modell
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL, `ServerController` nicht definiert

- [ ] **Step 3: `ServerController`, `Notifications`, `PocketNodeService` und Manifest umsetzen**

- [ ] **Step 4: Tests und Build**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS und `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: ServerController und Foreground Service"
```

---

### Task 6: Oberfläche

**Files:**
- Create: `ui/MainActivity.kt` (ersetzt die bisherige), `ui/OverviewScreen.kt`, `ui/ModelsScreen.kt`, `ui/SettingsScreen.kt`, `ui/OverviewViewModel.kt`, `ui/ModelsViewModel.kt`, `ui/SettingsViewModel.kt`, `ui/QrCode.kt`
- Modify: `res/values/strings.xml`, `res/values-de/strings.xml`, `AndroidManifest.xml`
- Test: `app/src/test/…/OverviewViewModelTest.kt`, `ModelsViewModelTest.kt`, `SettingsViewModelTest.kt`, `QrCodeTest.kt`

**Interfaces:**
- Consumes: `AppContainer`, `ServerController`, `ServerStatus`, `SettingsStore`, `ModelImporter`, `DeviceInfo`
- Produces:

```kotlin
data class OverviewUiState(
    val status: ServerStatus,
    val addresses: List<String>,          // "http://<ip>:<port>", WLAN zuerst, dann Tailscale
    val modelId: String?, val accelerator: String?,      // "GPU" / "CPU", aus EngineState.loaded
    val fallbackNotice: Boolean,          // true, wenn GPU gewünscht war und CPU aktiv ist
    val tokensPerSecond: Double?, val queue: Int, val loading: String?, val lastError: String?,
    val ramAvailableMb: Long, val ramTotalMb: Long,
    val apiKey: String, val authDisabled: Boolean,
)
data class ModelRow(val id: String, val sizeMb: Long, val loaded: Boolean, val loading: Boolean)
data class ModelsUiState(val models: List<ModelRow>, val importProgressMb: Long?, val message: String?)

fun qrMatrix(text: String): Array<BooleanArray>   // ZXing QRCodeWriter, Fehlerkorrektur M
```

Bildschirme (Navigation mit unterer Leiste: Übersicht, Modelle, Einstellungen):

- **Übersicht:** Aufbau wie in Abschnitt 11 der Spec. Start/Stopp startet bzw. stoppt den Service und setzt `serverWanted`. „API-Key zeigen“ öffnet einen Dialog mit Key, „Kopieren“ und QR-Code. Bei `fallbackNotice` steht ein deutlich sichtbarer Hinweis „GPU nicht verfügbar, rechnet auf der CPU“. `RAM` wird alle 5 s aktualisiert, solange der Bildschirm sichtbar ist.
- **Modelle:** Liste aus dem Katalog mit Größe. „Importieren“ öffnet `ACTION_OPEN_DOCUMENT` (`*/*`), kopiert über `ModelImporter` und zeigt den Fortschritt. `NameTaken` und `Failed` erscheinen als Meldung. „Laden“ ruft `controller.loadModel`, „Löschen“ fragt nach und ist für das geladene Modell gesperrt. Leere Liste: Hinweis mit dem Pfad des Modellordners und `adb push`.
- **Einstellungen:** Port, Beschleuniger (Automatisch, GPU, CPU), automatischer Modellwechsel, CORS, „API-Key neu erzeugen“ (mit Rückfrage), „Auth abschalten“ (mit Warnhinweis und Rückfrage). Änderungen an Port, Beschleuniger, Modellwechsel und CORS starten einen laufenden Server neu.
- Beim ersten Start fragt die App die Berechtigung `POST_NOTIFICATIONS` an.

- [ ] **Step 1: Tests schreiben**

```kotlin
// OverviewViewModelTest
@Test fun `addresses combine ip and port with wifi first`()
@Test fun `fallback notice when GPU wanted and CPU active`()
@Test fun `no fallback notice when CPU was chosen`()
@Test fun `stopped server shows no addresses`()
// ModelsViewModelTest
@Test fun `rows mark the loaded and the loading model`()
@Test fun `delete is refused for the loaded model`()
@Test fun `import result NameTaken becomes a message`()
@Test fun `successful import refreshes the list`()
// SettingsViewModelTest
@Test fun `invalid port is rejected with a message and not saved`()   // "80", "abc", "70000"
@Test fun `changing the port restarts a running server`()
@Test fun `changing the port does not start a stopped server`()
// QrCodeTest
@Test fun `qr matrix is square and non trivial`()
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL, ViewModels nicht definiert

- [ ] **Step 3: ViewModels, Bildschirme, Texte umsetzen**

- [ ] **Step 4: Tests, Lint und Build**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
Expected: PASS, keine Lint-Fehler, `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: Übersicht, Modelle und Einstellungen"
```

---

### Task 7: Gerätetest-Checkliste und Dokumentation

**Files:**
- Create: `docs/geraetetest-stufe-2.md`
- Modify: `README.md`, `docs/superpowers/specs/2026-11-27-pocketnode-design.md` (Abschnitt 5: Backend in `:core`, `Started`-Event)

- [ ] **Step 1: Checkliste schreiben**

`docs/geraetetest-stufe-2.md`, zum Abhaken auf dem Pixel 7 Pro, jeweils mit erwartetem Ergebnis:

1. APK aus der CI installieren, App starten, Benachrichtigungen erlauben.
2. Modell per `adb push` in den Modellordner legen; es erscheint unter „Modelle“.
3. Zweites Modell über „Importieren“ hinzufügen; Fortschritt sichtbar.
4. Server starten; Benachrichtigung zeigt Adresse.
5. Modell laden; Übersicht zeigt **GPU**. Zeigt sie CPU mit Hinweis: `adb logcat -s PocketNode` sichern (Ursache des GPU-Fehlers).
6. `curl` vom PC: `/health`, `/v1/models`, Chat ohne und mit Streaming.
7. Open WebUI verbinden, drei Nachrichten in einem Chat; der Verlauf wird beachtet.
8. Stream mittendrin abbrechen; Übersicht zeigt danach keine laufende Generierung.
9. Zweites Modell per API anfragen; der Wechsel klappt.
10. Bildschirm aus, 10 Minuten warten, erneut anfragen.
11. App aus der Übersicht der letzten Apps wischen; der Server antwortet weiter.
12. Token/s und Temperaturgefühl nach 5 Minuten Dauerlast notieren.
13. „Stoppen“; der Port ist frei, die Benachrichtigung verschwindet.

Am Ende eine Tabelle zum Eintragen: Modell, Beschleuniger, Token/s, Ladezeit, Auffälligkeiten.

- [ ] **Step 2: README und Spec aktualisieren**

README: Status „Stage 2 of 5“, Installation auf dem Gerät, Modellordner, Verweis auf die Checkliste, ehrlicher Hinweis, dass Stufe 2 bis zum Gerätetest nur auf dem Desktop (CPU) mit echtem Modell geprüft ist.

- [ ] **Step 3: Gesamtlauf und Commit**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
Expected: PASS und `BUILD SUCCESSFUL`

```bash
git add -A && git commit -m "docs: Gerätetest-Checkliste und Stand Stufe 2"
```

---

## Abschluss der Stufe

- [ ] Alle JVM-Tests grün, CI grün, APK als Artefakt
- [ ] Echtes Modell antwortet auf dem Desktop (CPU)
- [ ] **Offen bis zum Gerätetest durch Jan:** GPU auf der Mali-G710, Verhalten des Foreground Service im Doze, Import über den Dateidialog. Diese Punkte lassen sich in der Entwicklungsumgebung nicht prüfen.

Danach folgt der Plan für Stufe 3 (Robustheit), in den die Ergebnisse des Gerätetests einfließen.
