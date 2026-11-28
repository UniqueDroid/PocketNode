package com.pocketnode.server.engine.litert

import com.pocketnode.server.api.ServerLog
import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.FinishReason
import com.pocketnode.server.engine.GenerationEvent
import com.pocketnode.server.engine.GenerationRequest
import com.pocketnode.server.engine.InferenceBackend
import com.pocketnode.server.engine.LoadedModel
import com.pocketnode.server.engine.ModelFile
import com.pocketnode.server.engine.Usage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Backend auf Basis von Google LiteRT-LM. Versucht die GPU und fällt bei einem Fehler auf die CPU zurück.
 * Alle Aufrufe in die Engine laufen auf [dispatcher], nie auf dem Aufrufer-Thread.
 */
class LiteRtLmBackend(
    private val runtime: LiteRtRuntime = RealLiteRtRuntime,
    private val cacheDir: String? = null,
    private val maxTokens: Int? = null,
    private val log: ServerLog = ServerLog.Stderr,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : InferenceBackend {
    override val name: String = "LiteRT-LM"
    override val supported: Set<Accelerator> = setOf(Accelerator.CPU, Accelerator.GPU)

    /** maxTokens geht als maxOutputToken an die Engine. */
    override val enforcesMaxTokens: Boolean = true

    @Volatile
    private var session: LiteRtSession? = null

    override suspend fun load(model: ModelFile, preferred: Accelerator, cpuThreads: Int): LoadedModel =
        withContext(dispatcher) {
            closeSession()
            val order = if (preferred == Accelerator.CPU) listOf(Accelerator.CPU) else listOf(Accelerator.GPU, Accelerator.CPU)
            var lastError: Throwable? = null
            for (accelerator in order) {
                try {
                    val opened = runtime.open(model.path, accelerator, cpuThreads, cacheDir, maxTokens)
                    session = opened
                    log.log(ServerLog.Level.INFO, "Modell '${model.id}' auf $accelerator geladen", null)
                    return@withContext LoadedModel(model.id, accelerator, opened.contextTokens)
                } catch (e: Throwable) {
                    lastError = e
                    if (accelerator != order.last()) {
                        log.log(ServerLog.Level.WARN, "$accelerator nicht nutzbar für '${model.id}', versuche CPU", e)
                    }
                }
            }
            throw IllegalStateException("Modell '${model.id}' ließ sich auch auf der CPU nicht laden", lastError)
        }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        val active = session ?: throw IllegalStateException("Kein Modell geladen")
        val plan = request.toConversationPlan()
        var usage: Usage? = null
        var chunks = 0
        active.generate(plan) { prompt, completion ->
            if (prompt != null && completion != null) usage = Usage(prompt, completion)
        }.collect {
            chunks++
            emit(GenerationEvent.Token(it))
        }
        val produced = usage?.completionTokens ?: chunks
        val limit = plan.maxOutputTokens
        val reason = if (limit != null && produced >= limit) FinishReason.LENGTH else FinishReason.STOP
        emit(GenerationEvent.Done(reason, usage))
    }.flowOn(dispatcher)

    override suspend fun unload() = withContext(dispatcher) { closeSession() }

    private fun closeSession() {
        session?.let { runCatching { it.close() } }
        session = null
    }
}
