# Gerätetest Stufe 2 – Pixel 7 Pro

Stufe 2 ist in der Entwicklungsumgebung nur auf dem Desktop (CPU) mit einem echten Modell geprüft. GPU, Foreground Service und Dateidialog lassen sich nur auf dem Gerät prüfen. Diese Liste der Reihe nach abhaken und das Ergebnis in die Tabelle am Ende eintragen.

**Vorbereitung**

- APK aus dem letzten CI-Lauf (Artefakt `pocketnode-debug-apk`) oder lokal mit `./gradlew :app:assembleDebug`
- Ein Modell, das auf der GPU laufen soll, z. B. `gemma-4-E2B-it` aus der [LiteRT-Community auf Hugging Face](https://huggingface.co/litert-community) (Datei für die GPU, nicht die `_Google_Tensor_G5/G6`-Variante)
- PC im selben WLAN, `adb` verbunden
- Logcat mitschneiden: `adb logcat -s PocketNode litert`

| # | Schritt | Erwartet | ✓ |
|---|---|---|---|
| 1 | `adb install -r app-debug.apk`, App starten, Benachrichtigungen erlauben | Übersicht erscheint, „Server gestoppt“ | ☐ |
| 2 | `adb push modell.litertlm /sdcard/Android/data/com.pocketnode.server/files/models/` | Modell erscheint unter „Modelle“ (eventuell Tab wechseln) | ☐ |
| 3 | Zweites Modell über „Modell importieren“ hinzufügen | Fortschritt in MB sichtbar, danach „… importiert“ | ☐ |
| 4 | „Server starten“ | Benachrichtigung „Läuft auf 192.168.x.x:8080 · kein Modell geladen“, Übersicht zeigt die Adresse | ☐ |
| 5 | Unter „Modelle“ das GPU-Modell laden | Übersicht zeigt das Modell mit **GPU**. Bei **CPU** und rotem Hinweis: Logcat sichern, die Ursache des GPU-Fehlers steht dort als WARN | ☐ |
| 6 | Vom PC: `curl http://<ip>:8080/health`, dann `/v1/models` und ein Chat mit und ohne `"stream":true` (Key aus „API-Key zeigen“) | `/health` ohne Key, die anderen nur mit Key; Text kommt beim Streaming stückweise | ☐ |
| 7 | Open WebUI mit `http://<ip>:8080/v1` und dem Key verbinden, drei Nachrichten in einem Chat | Das Modell bezieht sich auf frühere Nachrichten | ☐ |
| 8 | Eine lange Antwort streamen und im Client abbrechen | Übersicht: Warteschlange 0, nächste Anfrage antwortet sofort | ☐ |
| 9 | Das zweite Modell per API anfragen (`"model": "<id>"`) | Wechsel klappt, Übersicht zeigt das neue Modell | ☐ |
| 10 | Bildschirm aus, 10 Minuten warten, erneut anfragen | Antwort kommt ohne Verzögerung durch Aufwachen | ☐ |
| 11 | App aus den letzten Apps wischen | Server antwortet weiter, Benachrichtigung bleibt | ☐ |
| 12 | 5 Minuten Dauerlast (z. B. Schleife mit `curl`), Token/s und Temperatur notieren | Token/s bleiben ungefähr gleich, das Gerät wird warm, nicht heiß | ☐ |
| 13 | „Stoppen“ in der Benachrichtigung | Benachrichtigung verschwindet, Port ist frei | ☐ |

**Ergebnisse**

| Modell | Beschleuniger | Token/s | Ladezeit | Auffälligkeiten |
|---|---|---|---|---|
| | | | | |
| | | | | |

Rückmeldungen aus diesem Test fließen in den Plan für Stufe 3 ein.
