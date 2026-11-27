package com.pocketnode.server.api

import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.util.Base64

/** Startet und stoppt den HTTP-Server. Ein Objekt lässt sich nach [stop] erneut starten. */
class PocketNodeServer(
    private val deps: ApiDeps,
    private val host: String = "0.0.0.0",
    private val port: Int = 8080,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val isRunning: Boolean
        @Synchronized get() = server != null

    /** Der tatsächlich gebundene Port. Unterscheidet sich von [port], wenn dieser 0 ist. */
    @get:Synchronized
    var boundPort: Int = port
        private set

    /** Startet den Server. Wirft, wenn der Port nicht gebunden werden kann. Ein zweiter Aufruf bleibt ohne Wirkung. */
    @Synchronized
    fun start() {
        if (server != null) return
        val created = embeddedServer(CIO, host = host, port = port) { pocketNodeModule(deps) }
        try {
            created.start(wait = false)
            boundPort = runBlocking { created.engine.resolvedConnectors().first().port }
        } catch (e: Throwable) {
            runCatching { created.stop(0, 0) }
            throw e
        }
        server = created
    }

    /** Stoppt den Server. Ohne laufenden Server ohne Wirkung. */
    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
        server = null
    }
}

/** Erzeugt einen neuen API-Key: `pn-` plus 32 zufällige Bytes als URL-sicheres Base64. */
fun generateApiKey(): String {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return "pn-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
