package com.pocketnode.server.service

import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.EngineState
import com.pocketnode.server.engine.LoadedModel
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationsTest {
    private val texts = NotificationTexts(
        stopped = "Gestoppt",
        starting = "Startet …",
        running = { address, model, accelerator -> "Läuft auf $address · $model · $accelerator" },
        noModel = "kein Modell geladen",
        loading = { model -> "lädt $model" },
        failed = { reason -> "Fehler: $reason" },
    )

    @Test
    fun `notification text for each status`() {
        assertEquals("Gestoppt", notificationText(ServerStatus.Stopped, null, texts))
        assertEquals("Startet …", notificationText(ServerStatus.Starting, null, texts))
        assertEquals("Fehler: Port 8080 ist belegt", notificationText(ServerStatus.Failed("Port 8080 ist belegt"), null, texts))
        val loaded = EngineState(loaded = LoadedModel("gemma", Accelerator.GPU, 4096))
        assertEquals(
            "Läuft auf 192.168.1.5:8080 · gemma · GPU",
            notificationText(ServerStatus.Running(8080, loaded), "192.168.1.5", texts),
        )
        assertEquals(
            "Läuft auf 192.168.1.5:8080 · kein Modell geladen · –",
            notificationText(ServerStatus.Running(8080, EngineState()), "192.168.1.5", texts),
        )
        assertEquals(
            "Läuft auf 192.168.1.5:8080 · lädt gemma · –",
            notificationText(ServerStatus.Running(8080, EngineState(loading = "gemma")), "192.168.1.5", texts),
        )
    }

    @Test
    fun `missing address falls back to the port only`() {
        assertEquals(
            "Läuft auf :8080 · kein Modell geladen · –",
            notificationText(ServerStatus.Running(8080, EngineState()), null, texts),
        )
    }
}
