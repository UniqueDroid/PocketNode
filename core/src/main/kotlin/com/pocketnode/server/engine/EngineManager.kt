package com.pocketnode.server.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** Entscheidet vor dem Einreihen, ob eine Anfrage angenommen wird (z. B. Thermik in Stufe 3). */
fun interface AdmissionGate {
    /** `null` lässt die Anfrage zu. */
    fun rejection(): EngineException.Unavailable?

    companion object {
        val Open = AdmissionGate { null }
    }
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
    /** Modell-ID, solange ein Ladevorgang läuft. */
    val loading: String? = null,
    val running: Boolean = false,
    val waiting: Int = 0,
    val lastError: String? = null,
    /** Tokens pro Sekunde der letzten abgeschlossenen Generierung, gemessen ab dem ersten Token. */
    val lastTokensPerSecond: Double? = null,
)

/**
 * Einziger Zugang zum Backend. Lässt genau eine Generierung zur Zeit laufen,
 * reiht weitere in Ankunftsreihenfolge ein und wechselt bei Bedarf das Modell.
 */
class EngineManager(
    private val backend: InferenceBackend,
    private val catalog: ModelCatalog,
    private val config: EngineConfig = EngineConfig(),
    private val gate: AdmissionGate = AdmissionGate.Open,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val mutableState = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = mutableState.asStateFlow()

    /** Kotlins Mutex ist fair: Wartende kommen in Ankunftsreihenfolge dran. */
    private val engineLock = Mutex()

    /** Laufende plus wartende Generierungen. */
    private val admitted = AtomicInteger(0)

    /**
     * Kalter Flow. Alle Prüfungen laufen beim Einsammeln. Wirft [EngineException],
     * Fehler des Backends werden unverändert durchgereicht.
     */
    fun generate(modelId: String?, request: GenerationRequest): Flow<GenerationEvent> = flow {
        gate.rejection()?.let { throw it }
        if (admitted.incrementAndGet() > 1 + config.maxWaiting) {
            admitted.decrementAndGet()
            throw EngineException.QueueFull(config.queueRetryAfterSeconds)
        }
        try {
            mutableState.update { it.copy(waiting = it.waiting + 1) }
            try {
                engineLock.lock()
            } finally {
                mutableState.update { it.copy(waiting = it.waiting - 1) }
            }
            try {
                mutableState.update { it.copy(running = true) }
                val model = ensureLoaded(modelId)
                emit(GenerationEvent.Started(model))
                var tokens = 0
                var firstTokenAt = 0L
                backend.generate(request).withLimits(request.maxTokens, request.stop).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> if (++tokens == 1) firstTokenAt = nanoTime()
                        is GenerationEvent.Done -> recordSpeed(tokens, nanoTime() - firstTokenAt)
                        is GenerationEvent.Started -> return@collect
                    }
                    emit(event)
                }
            } finally {
                mutableState.update { it.copy(running = false) }
                engineLock.unlock()
            }
        } finally {
            admitted.decrementAndGet()
        }
    }

    suspend fun load(modelId: String): LoadedModel = engineLock.withLock {
        val file = catalog.find(modelId) ?: throw EngineException.ModelNotFound(modelId)
        loadLocked(file)
    }

    suspend fun unload() = engineLock.withLock {
        backend.unload()
        mutableState.update { it.copy(loaded = null) }
    }

    private fun recordSpeed(tokens: Int, nanosSinceFirstToken: Long) {
        if (tokens < 2 || nanosSinceFirstToken <= 0) return
        val perSecond = (tokens - 1) * 1_000_000_000.0 / nanosSinceFirstToken
        mutableState.update { it.copy(lastTokensPerSecond = perSecond) }
    }

    private suspend fun ensureLoaded(modelId: String?): LoadedModel {
        val loaded = mutableState.value.loaded
        val target = modelId
            ?: loaded?.id
            ?: catalog.list().firstOrNull()?.id
            ?: throw EngineException.NoModelAvailable()
        if (loaded != null && loaded.id == target) return loaded
        val file = catalog.find(target) ?: throw EngineException.ModelNotFound(target)
        if (loaded != null && !config.autoSwitch) throw EngineException.ModelNotLoaded(target)
        return loadLocked(file)
    }

    /** Nur mit gehaltenem [engineLock] aufrufen. */
    private suspend fun loadLocked(file: ModelFile): LoadedModel {
        try {
            if (mutableState.value.loaded != null) backend.unload()
            mutableState.update { it.copy(loaded = null, loading = file.id) }
            val model = backend.load(file, config.preferred, config.cpuThreads)
            mutableState.update { it.copy(loaded = model, loading = null, lastError = null) }
            return model
        } catch (e: CancellationException) {
            // Ein abgebrochenes Laden kann ein halb geladenes Modell hinterlassen.
            withContext(NonCancellable) { runCatching { backend.unload() } }
            mutableState.update { it.copy(loaded = null, loading = null) }
            throw e
        } catch (e: Throwable) {
            runCatching { backend.unload() }
            val error = EngineException.ModelLoadFailed(file.id, e)
            mutableState.update {
                it.copy(loaded = null, loading = null, lastError = "${error.message}: ${e.message ?: e::class.simpleName}")
            }
            throw error
        }
    }
}
