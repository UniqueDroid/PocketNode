# PocketNode – Design

Stand: 2026-11-27 · Status: Entwurf zur Freigabe durch Jan
Grundlage: `pocketnode_projektdokumentation.md` (Vorlage) plus Recherche zum Pixel 7 Pro

Kennzeichnung in diesem Dokument:
**[Jan]** = von Jan vorgegeben oder bestätigt · **[Vorschlag]** = meine Entscheidung, bitte beim Lesen prüfen

---

## 1. Absicht

PocketNode macht ein Android-Smartphone zu einem dauerhaft laufenden KI-Server im Heimnetz. Clients wie Open WebUI, Home Assistant, Continue oder eigene Skripte sprechen das Handy über eine OpenAI-kompatible HTTP-API an, im LAN oder über Tailscale. **[Jan]**

Referenzgerät ist ein **Pixel 7 Pro** (Tensor G2, Mali-G710, 12 GB RAM). **[Jan]**

Die Projektdokumentation ist Vorlage, kein fester Bauplan: Ideen ausarbeiten, Verbesserungen einbringen. **[Jan]**

## 2. Ziele, Nicht-Ziele, Anti-Ziele

### Ziele

1. OpenAI-kompatible API: `/v1/models`, `/v1/chat/completions` (mit und ohne Streaming), `/health`.
2. Dauerbetrieb am Ladekabel, der Doze, WLAN-Energiesparen und Neustarts übersteht.
3. Chat-Modelle rechnen auf der GPU, die CPU ist Rückfallebene. **[Jan]**
4. Zugriff nur mit API-Key.
5. Der Server bleibt stabil: kein Absturz durch zu große Modelle, keine Überhitzung, keine ungeschützten Parallelzugriffe auf die Engine.
6. Ein eingebauter NPU-Test klärt, ob die NPU des Geräts ohne Root erreichbar ist. Falls ja, übernimmt sie später Nebenaufgaben wie Embeddings. **[Jan]**
7. Die Backend-Schnittstelle bleibt offen für weitere Engines und für die offizielle NPU-Unterstützung neuerer Pixel.

### Nicht-Ziele (erste Version)

- MLC-LLM- oder llama.cpp-Backend
- NPU-Zugriff per Root oder Reverse Engineering
- Chat-Modelle auf der NPU
- Bild- und Audio-Eingaben, Tool Calling
- TLS in der App (dafür Tailscale oder ein Reverse Proxy)
- Mehrere Modelle gleichzeitig im Speicher
- Veröffentlichung im Play Store (Installation per Sideload)

### Anti-Ziele

Diese Dinge würden PocketNode zum Fehlschlag machen, auch wenn es technisch läuft:

- Der Server meldet „GPU“, rechnet aber still auf der CPU.
- Der Server ist ohne Zutun offen im Netz erreichbar.
- Das Handy überhitzt im Dauerbetrieb oder der Akku leidet sichtbar.
- Clients hängen ohne Fehlermeldung, weil eine Anfrage intern verloren ging.

## 3. Randbedingungen

| Punkt | Festlegung |
|---|---|
| Gerät | Pixel 7 Pro, Tensor G2 (2× Cortex-X1, 2× A78, 4× A55), Mali-G710 MP7, 12 GB RAM |
| ABI | nur `arm64-v8a` |
| Paketname | `com.pocketnode.server` **[Jan, Vorlage]** |
| Sprache, UI | Kotlin, Jetpack Compose **[Jan, Vorlage]** |
| HTTP-Server | Ktor (CIO) **[Jan, Vorlage]** |
| Engine | Google LiteRT-LM, Modelle im Format `.litertlm` **[Jan]** |
| minSdk | 31 statt 29 **[Vorschlag]**: `Build.SOC_MODEL` und die Thermal-API sind dann ohne Sonderfälle nutzbar, das Zielgerät liegt weit darüber |
| Modellgröße | realistisch 1 bis 4 Mrd. Parameter, quantisiert |
| Versionen | Alle Abhängigkeiten werden fest gepinnt (Version Catalog). Kein `latest.release`. |

### Erkenntnisse aus der Recherche

- **Die offizielle NPU-Unterstützung fehlt.** Das Google Tensor SDK und die NPU-Modelle für LiteRT-LM gibt es nur für Tensor G5 und G6. Der EdgeTPU-Systemdienst lehnt fremde Apps ohne Freigabe ab.
- **NNAPI ist der einzige Weg ohne Root.** Die API ist seit Android 15 als veraltet markiert, die Treiberschnittstelle darunter wird aber weiter unterstützt. Ob eine normale App den Beschleuniger `google-edgetpu` auf dem G2 erreicht, ist nicht belegt und wird gemessen (Abschnitt 9).
- **Für Textgenerierung bringt die NPU nichts.** Ein Community-Projekt hat auf einem gerooteten Pixel 7 gemessen: Ein Transformer-Block lief auf der NPU rund 15 % langsamer als auf der CPU, weil die Textgenerierung an der Speicherbandbreite hängt. Stark ist die NPU bei kleinen, fest quantisierten Modellen (int8, Per-Tensor-Quantisierung).
- **GPU-Zugriff braucht Manifest-Einträge:** `libvndksupport.so`, `libOpenCL.so` und auf Pixel-Geräten `libOpenCL-pixel.so`.

Quellen:
- https://ai.google.dev/edge/litert-lm/android
- https://ai.google.dev/edge/litert/next/litert_lm_npu
- https://ai.google.dev/edge/litert/next/tensor-sdk
- https://github.com/google-ai-edge/LiteRT/issues/11251
- https://developer.android.com/ndk/guides/neuralnetworks/migration-guide
- https://github.com/qbnasasn/Google-Tensor-Chips

## 4. Betrachtete Wege

### Engine

| Weg | Beschreibung | Bewertung |
|---|---|---|
| **A: nur LiteRT-LM, sauber gebaut** | GPU über OpenCL, CPU über XNNPACK, offene Backend-Schnittstelle | **Gewählt [Jan].** Schnellster funktionierender Weg auf der Mali-GPU, kleinster Aufwand. Nachteil: nur `.litertlm`-Modelle. |
| B: llama.cpp | Fast alle GGUF-Modelle | Verworfen: auf Mali praktisch nur CPU, widerspricht dem Kern der Vorlage. |
| C: LiteRT-LM und MLC LLM von Anfang an | Zwei GPU-Backends | Verworfen für Version 1: MLC braucht pro Modell vorab kompilierte Bibliotheken. Bleibt auf der Roadmap. |

### NPU

| Weg | Bewertung |
|---|---|
| Offizielles Tensor SDK | Auf dem G2 nicht verfügbar. |
| **NNAPI-Test in der App, NPU für Nebenaufgaben** | **Gewählt [Jan].** Kein Root, überschaubarer Aufwand, liefert eine sichere Antwort für das Gerät. |
| Root und Reverse Engineering | Verworfen: hängt an Frida und einem fremden Prozess, langsamer als die CPU, bricht mit Updates. |
| AICore / Gemini Nano | Verworfen: keine eigenen Modelle. |

## 5. Architektur

```text
Clients (Open WebUI, Home Assistant, Continue, Skripte)
        │  HTTP, OpenAI-kompatibel, Bearer-Token
        ▼
┌──────────────────────────── PocketNode ────────────────────────────┐
│ api        Ktor-Routen, DTOs, SSE, Auth, Fehlerformat              │
│   │                                                                │
│ engine     EngineManager: Warteschlange, Modellwechsel, Abbruch    │
│   │          └─ InferenceBackend ◄─ LiteRtLmBackend (GPU / CPU)    │
│   │                              ◄─ FakeBackend (Tests)            │
│ models     ModelRepository: Ordner, Import, Download               │
│ hardware   HardwareProfiler, MemoryGuard, ThermalMonitor, NpuProbe │
│ service    Foreground Service, Locks, Benachrichtigung, Autostart  │
│ settings   DataStore: Port, API-Key, Modell, Beschleuniger         │
│ ui         Compose: Übersicht, Modelle, Einstellungen, Diagnose    │
└────────────────────────────────────────────────────────────────────┘
```

Zwei Gradle-Module **[Vorschlag]**: `:core` ist reines Kotlin ohne Android-Klassen und enthält `api`, die Engine-Verträge samt `EngineManager` und die Regeln aus `hardware` (RAM-Prüfung, Thermik-Stufen). `:app` enthält alles, was Android braucht. So laufen die Tests von `:core` ohne Android SDK, lokal wie in der CI.

Nachtrag Stufe 2: `LiteRtLmBackend` liegt ebenfalls in `:core`. Es wird gegen `litertlm-jvm` kompiliert, die App bringt `litertlm-android` mit; beide haben dieselbe öffentliche API (0.20.0 geprüft). So läuft das Backend auch auf dem Desktop und ist dort mit einem echten Modell testbar. LiteRT-LM 0.20.0 ist für Java 25 kompiliert, der Desktop-Lauf braucht deshalb JDK 25.

Der Zustand liegt in einem App-weiten Container statt in einer statischen Variable im Service wie in der Vorlage. Service und UI lesen denselben `StateFlow` mit dem Serverstatus.

### Backend-Schnittstelle

```kotlin
enum class Accelerator { CPU, GPU, NPU }

data class LoadedModel(val id: String, val accelerator: Accelerator, val contextTokens: Int)

interface InferenceBackend {
    val name: String
    val supported: Set<Accelerator>
    suspend fun load(model: ModelFile, preferred: Accelerator, cpuThreads: Int): LoadedModel
    fun generate(request: GenerationRequest): Flow<GenerationEvent>   // Token, Ende, Nutzung
    suspend fun unload()
}
```

- Jede Generierung über den `EngineManager` beginnt mit `GenerationEvent.Started(model)`. Daraus kommt der Modellname der Antwort.
- Es gibt nur noch den Streaming-Weg. Die nicht gestreamte Antwort sammelt denselben Flow ein. Damit entfällt der doppelte Code der Vorlage.
- `load` gibt zurück, welcher Beschleuniger **tatsächlich** aktiv ist. Schlägt die GPU fehl, lädt das Backend auf der CPU und meldet das. UI, Benachrichtigung und `/health` zeigen den echten Wert.
- `Accelerator.NPU` existiert von Anfang an, wird von `LiteRtLmBackend` auf dem G2 aber nicht angeboten.

### Verbesserungen gegenüber der Vorlage

| Vorlage | Design |
|---|---|
| Nachrichten werden zu `USER: …`-Text verklebt | System-Prompt, Verlauf und letzte Nachricht werden getrennt an LiteRT-LM übergeben, damit das Chat-Template des Modells greift |
| `max_tokens` wird ignoriert | wird durchgesetzt, `finish_reason` ist dann `length` |
| Keine Warteschlange | Eine Generierung zur Zeit, begrenzte Warteschlange |
| Kein Abbruch | Trennt der Client die Verbindung, stoppt die Generierung |
| Offen auf `0.0.0.0` | Bearer-Token Pflicht |
| Modellpfad und Port kommen per Intent | Konfiguration liegt im DataStore. Ein vom System neu gestarteter Service findet sie wieder. |
| IP per erstem Netzwerk-Interface | IP des aktiven WLANs über `ConnectivityManager`, Tailscale-Adresse zusätzlich |
| Threads pauschal nach Kernzahl | Schnelle Kerne werden über die Maximalfrequenz in `/sys` erkannt |
| Kein Subtyp für `specialUse` | Manifest-Property für den Service-Subtyp ergänzt |
| Berechtigung für Benachrichtigungen fehlt zur Laufzeit | wird beim ersten Start angefragt |
| `latest.release`, alte Compose-Konfiguration | gepinnte Versionen, Compose-Compiler-Plugin für Kotlin 2.x |

## 6. API

### Endpunkte

| Endpunkt | Auth | Verhalten |
|---|---|---|
| `GET /health` | nein | JSON: Status, geladenes Modell, aktiver Beschleuniger, Thermik-Stufe, Länge der Warteschlange |
| `GET /v1/models` | ja | Alle installierten Modelle |
| `POST /v1/chat/completions` | ja | Antwort als JSON oder als SSE-Stream |

### Chat Completions

- **Unterstützt:** `model`, `messages` (Rollen `system`, `user`, `assistant`), `stream`, `max_tokens` und `max_completion_tokens`, `temperature`, `top_p`, `stop`, `stream_options.include_usage`. Zusätzlich `top_k` als Erweiterung.
- **Ignoriert:** unbekannte Felder, damit Clients nicht scheitern.
- **Abgelehnt mit 400:** `n > 1`, `tools`, Inhalte, die kein Text sind.
- **Streaming:** Der erste Chunk enthält `delta.role`, danach folgen Text-Deltas, dann ein Chunk mit `finish_reason` (`stop` oder `length`), am Ende `data: [DONE]`. Alle Chunks werden über die Serialisierung erzeugt, nicht als zusammengesetzter String.
- **`usage`:** Token-Zahlen aus der Engine, soweit sie diese liefert.
- **Fehler** haben das OpenAI-Format `{"error": {"message", "type", "code"}}`.

| Situation | Antwort |
|---|---|
| Token fehlt oder ist falsch | 401 |
| Modell nicht installiert | 404 `model_not_found` |
| Warteschlange voll | 429 mit `Retry-After` |
| Gerät zu heiß | 503 mit `Retry-After` |
| Kein Modell geladen, Laden schlägt fehl | 503 mit Grund |
| Eingabe länger als das Kontextfenster | 400 `context_length_exceeded` |

### Modellwahl

`/v1/models` listet alle installierten Modelle. Nennt eine Anfrage ein anderes installiertes Modell als das geladene, wechselt der Server das Modell. Der Wechsel läuft durch dieselbe Warteschlange. Fehlt `model`, antwortet das geladene Modell. **[Vorschlag]** Der automatische Wechsel ist in den Einstellungen abschaltbar, weil er je nach Modell viele Sekunden dauert.

### Warteschlange

Es läuft genau eine Generierung. Bis zu vier weitere Anfragen warten **[Vorschlag]**, danach kommt 429. Wartende und laufende Anfragen enden sofort, wenn der Client die Verbindung trennt.

### Zugriffsschutz

- Beim ersten Start erzeugt die App einen zufälligen API-Key. Die UI zeigt ihn zum Kopieren und als QR-Code.
- Der Vergleich läuft in konstanter Zeit.
- Der Key lässt sich neu erzeugen. Abschalten geht nur über einen bewusst gesetzten Schalter mit Warnhinweis.
- CORS ist aus und lässt sich für Browser-Clients einschalten.

## 7. Dauerbetrieb

- **Foreground Service** vom Typ `specialUse` mit Subtyp-Property, `START_STICKY`. Nach einem Neustart durch das System liest der Service seine Konfiguration aus dem DataStore.
- **Locks:** `PARTIAL_WAKE_LOCK` und WLAN-Lock, solange der Server läuft.
- **Akku-Optimierung:** Die App fragt die Ausnahme beim ersten Start an und zeigt in der Übersicht, ob sie gesetzt ist.
- **Autostart nach dem Booten** als Option, standardmäßig aus. **[Vorschlag]**
- **Benachrichtigung:** Adresse, Modell, echter Beschleuniger, Thermik-Stufe, Aktion „Stoppen“.
- **Hinweis in der UI:** Ladebegrenzung auf 80 % für den Dauerbetrieb am Kabel.

## 8. Stabilität

### RAM-Prüfung

Vor dem Laden vergleicht `MemoryGuard` die Dateigröße des Modells (mit Aufschlag für Kontext und Laufzeit) mit dem freien Speicher. Reicht er nicht, lädt die App nicht und nennt die Zahlen. Meldet Android Speicherdruck, wird das in Log und `/health` sichtbar.

### Thermik

Die Vorlage wollte `max_tokens` bei Hitze kürzen. Das kühlt kaum und verfälscht Antworten. Stattdessen **[Vorschlag]**:

| Stufe (`PowerManager`) | Verhalten |
|---|---|
| bis `MODERATE` | normal |
| `SEVERE` | Die laufende Generierung endet regulär, neue Anfragen erhalten 503 mit `Retry-After`, bis die Stufe wieder sinkt |
| ab `CRITICAL` | Zusätzlich wird die laufende Generierung abgebrochen |

Die Akkutemperatur wird mit angezeigt.

### Leerlauf

Optional entlädt die App das Modell nach einer einstellbaren Zeit ohne Anfragen und lädt es bei der nächsten Anfrage neu. Standardmäßig aus. **[Vorschlag]**

### GPU-Start

Das erste Laden auf der GPU dauert länger, weil Shader übersetzt werden. Die Engine bekommt ein Cache-Verzeichnis, damit spätere Starts schneller sind.

## 9. NPU-Test

Ein Bildschirm „Diagnose“ beantwortet für das konkrete Gerät drei Fragen:

1. Listet NNAPI den Beschleuniger `google-edgetpu` für diese App auf?
2. Lässt sich ein kleines int8-Testmodell (Per-Tensor-Quantisierung, mit der App ausgeliefert) darauf ausführen?
3. Wie schnell ist es im Vergleich zu CPU und GPU?

Das Ergebnis steht in der UI und lässt sich als Text exportieren. Der Test gilt als Experiment: wenig Tests, kein Anspruch auf andere Geräte.

**Entscheidung nach dem Test:**

- **NPU erreichbar und schneller:** `/v1/embeddings` wird mit einem int8-Embedding-Modell auf der NPU gebaut. Die GPU bleibt frei für den Chat.
- **Nicht erreichbar oder langsamer:** Das Ergebnis wird in der README festgehalten. Ob Embeddings auf der CPU kommen, entscheidet Jan dann neu.

## 10. Modelle

- **Ablage:** `Android/data/com.pocketnode.server/files/models/`. Dateien dort erscheinen automatisch in der App, auch nach `adb push`.
- **Import:** Auswahl einer `.litertlm`-Datei über den Dateidialog, die App kopiert sie in den Modellordner.
- **Download:** Eingabe einer URL (Hugging Face), mit Fortsetzen nach Abbruch, Prüfung des freien Speicherplatzes und optionalem Hugging-Face-Token für zugangsbeschränkte Modelle wie Gemma. **[Vorschlag]**
- **Empfehlung für den Start:** ein Gemma-Modell der 1B- oder E2B-Klasse aus der LiteRT-Community. Welche Dateien auf der Mali-GPU laufen, wird beim ersten Gerätetest geprüft und in der README festgehalten.

## 11. Oberfläche

Vier Bildschirme, Texte auf Deutsch und Englisch **[Vorschlag]**:

```text
┌ Übersicht ────────────────────────┐
│ ● Server läuft                    │
│ http://192.168.x.x:8080           │
│ Tailscale: 100.x.x.x              │
│ Modell: gemma-3n-e2b   GPU        │
│ 14,2 Token/s · Warteschlange 0    │
│ Thermik: normal · Akku 31 °C      │
│ RAM frei: 5,1 / 11,4 GB           │
│ [ Stoppen ]   [ API-Key zeigen ]  │
└───────────────────────────────────┘
```

- **Modelle:** Liste, Import, Download, Laden, Löschen
- **Einstellungen:** Port, API-Key, Beschleuniger (automatisch, GPU, CPU), Autostart, Leerlauf-Entladen, automatischer Modellwechsel, CORS
- **Diagnose:** Hardwareprofil, NPU-Test, Protokoll der letzten Ereignisse

## 12. Tests

- **JVM-Tests** für die API mit Ktor-Testumgebung und `FakeBackend`: SSE-Format, Auth, Fehlerformat, Warteschlange, Abbruch, Modellwechsel.
- **JVM-Tests** für die Regeln: Nachrichten-Zuordnung, RAM-Prüfung, Thermik-Stufen, Erkennung der schnellen Kerne.
- **Gerätetest als Checkliste** auf dem Pixel 7 Pro: `curl` mit und ohne Streaming, Open WebUI, Abbruch mitten in der Antwort, 24 Stunden Dauerlauf, Neustart des Geräts.
- **CI:** GitHub Actions baut, führt die JVM-Tests aus und legt ein Debug-APK ab.

## 13. Ausbaustufen

| Stufe | Inhalt | Ergebnis |
|---|---|---|
| 1 Fundament | Projektgerüst, API mit `FakeBackend`, Auth, Fehlerformat, Tests, CI | API verhält sich korrekt, ohne Gerät prüfbar |
| 2 Engine | `LiteRtLmBackend` (GPU, CPU), Modellordner und Import, Service, Übersicht | Erster echter Chat vom Pixel |
| 3 Robustheit | RAM-Prüfung, Thermik, Akku-Ausnahme, Autostart, Download, Protokoll | Dauerbetrieb |
| 4 NPU-Test | Diagnose-Bildschirm mit Messung | Antwort für den G2 |
| 5 nach Ergebnis | `/v1/embeddings` | Nebenaufgaben auf der NPU |

Stufe 4 hängt nur von Stufe 1 ab und kann vorgezogen werden.

**Später:** MLC-LLM- oder llama.cpp-Backend, Tool Calling, Bild-Eingaben, `/metrics`, Wiederverwendung des Kontext-Caches bei fortgesetzten Chats, mDNS-Ankündigung im LAN.

## 14. Dem Umsetzer überlassen

- Genaue Versionen aller Abhängigkeiten zum Zeitpunkt der Umsetzung
- Exakte Aufrufe der LiteRT-LM-API in der gepinnten Version: Übergabe von System-Prompt und Verlauf, Sampler-Werte, Abbruch, Token-Zahlen. Bietet die API etwas nicht, wird die Lücke im Code und in der README benannt statt überspielt.
- Technik des NPU-Tests: NNAPI-Delegate von LiteRT oder direkt die NDK-API, je nachdem, was die aktuelle Bibliothek noch anbietet
- Art der Abhängigkeitsverwaltung im Code, Feinlayout der Bildschirme
- Aufschlag-Faktor der RAM-Prüfung, nach Messung auf dem Gerät
