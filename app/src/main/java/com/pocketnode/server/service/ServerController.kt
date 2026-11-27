package com.pocketnode.server.service

import com.pocketnode.server.api.ApiDeps
import com.pocketnode.server.api.PocketNodeServer
import com.pocketnode.server.api.ServerLog
import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.EngineConfig
import com.pocketnode.server.engine.EngineManager
import com.pocketnode.server.engine.EngineState
import com.pocketnode.server.engine.InferenceBackend
import com.pocketnode.server.engine.ModelCatalog
import com.pocketnode.server.settings.AcceleratorPreference
import com.pocketnode.server.settings.Settings
import com.pocketnode.server.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.BindException

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
    private val mutableStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val status: StateFlow<ServerStatus> = mutableStatus.asStateFlow()

    private val lifecycle = Mutex()
    private var server: PocketNodeServer? = null
    private var engine: EngineManager? = null
    private var jobs: List<Job> = emptyList()

    /** Liest die gespeicherten Einstellungen und startet. Ohne Wirkung, wenn der Server schon läuft. */
    suspend fun start() = lifecycle.withLock {
        if (server != null) return@withLock
        mutableStatus.value = ServerStatus.Starting
        val settings = settingsStore.current()
        val newEngine = EngineManager(
            backend = backendFactory(),
            catalog = catalog,
            config = EngineConfig(
                autoSwitch = settings.autoSwitch,
                preferred = settings.accelerator.toAccelerator(),
                cpuThreads = cpuThreads(),
            ),
        )
        // Key und Auth-Schalter werden bei jeder Anfrage frisch gelesen: ein neuer Key gilt sofort.
        var current: Settings = settings
        val settingsJob = scope.launch { settingsStore.settings.collect { current = it } }
        val newServer = PocketNodeServer(
            ApiDeps(
                engine = newEngine,
                catalog = catalog,
                apiKey = { current.let { if (it.authDisabled) null else it.apiKey } },
                corsEnabled = { settings.corsEnabled },
                log = log,
            ),
            host = host,
            port = settings.port,
        )
        try {
            withContext(Dispatchers.IO) { newServer.start() }
        } catch (e: Throwable) {
            settingsJob.cancel()
            val reason = if (e is BindException || e.cause is BindException) "Port ${settings.port} ist belegt" else (e.message ?: e::class.simpleName.orEmpty())
            log.log(ServerLog.Level.ERROR, "Server konnte nicht starten: $reason", e)
            mutableStatus.value = ServerStatus.Failed(reason)
            return@withLock
        }
        server = newServer
        engine = newEngine
        val port = newServer.boundPort
        mutableStatus.value = ServerStatus.Running(port, newEngine.state.value)
        val mirrorJob = scope.launch {
            newEngine.state.collect { state ->
                if (mutableStatus.value is ServerStatus.Running) mutableStatus.value = ServerStatus.Running(port, state)
            }
        }
        jobs = listOf(settingsJob, mirrorJob)
        log.log(ServerLog.Level.INFO, "Server läuft auf Port $port", null)

        val selected = settings.selectedModel
        if (selected != null && catalog.find(selected) != null) {
            jobs = jobs + scope.launch {
                // Ein Fehler beim Laden lässt den Server laufen. Er steht dann in EngineState.lastError.
                runCatching { newEngine.load(selected) }
                    .onFailure { log.log(ServerLog.Level.WARN, "Ausgewähltes Modell '$selected' nicht geladen", it) }
            }
        }
    }

    /** Stoppt den Server und entlädt das Modell. */
    suspend fun stop() = lifecycle.withLock {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        server?.let { withContext(Dispatchers.IO) { it.stop() } }
        engine?.let { runCatching { it.unload() } }
        server = null
        engine = null
        mutableStatus.value = ServerStatus.Stopped
    }

    suspend fun restart() {
        stop()
        start()
    }

    /** Lädt ein Modell und merkt es sich für den nächsten Start. Nur bei laufendem Server. */
    suspend fun loadModel(id: String) {
        val active = lifecycle.withLock { engine } ?: throw IllegalStateException("Der Server läuft nicht")
        active.load(id)
        settingsStore.update { it.copy(selectedModel = id) }
    }

    private fun AcceleratorPreference.toAccelerator(): Accelerator = when (this) {
        AcceleratorPreference.AUTO, AcceleratorPreference.GPU -> Accelerator.GPU
        AcceleratorPreference.CPU -> Accelerator.CPU
    }
}
