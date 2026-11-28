package com.pocketnode.server.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Backend ohne echte Inferenz: für Tests und den Desktop-Lauf. */
class FakeBackend(
    val tokens: List<String> = listOf("Hallo", " von", " PocketNode", "."),
    val tokenDelayMs: Long = 0,
    val loadDelayMs: Long = 0,
    val failLoadFor: Set<String> = emptySet(),
    @Volatile var failAfterTokens: Int? = null,
    override val supported: Set<Accelerator> = setOf(Accelerator.CPU, Accelerator.GPU),
    override val enforcesMaxTokens: Boolean = false,
) : InferenceBackend {
    override val name: String = "Fake"

    private val recordedLoads = CopyOnWriteArrayList<String>()
    private val active = AtomicInteger(0)
    private val unloads = AtomicInteger(0)

    /** Geladene Modell-IDs in Reihenfolge, auch fehlgeschlagene Versuche. */
    val loadCalls: List<String> get() = recordedLoads.toList()

    /** Anzahl der Aufrufe von [unload]. */
    val unloadCalls: Int get() = unloads.get()

    /** Anzahl der gerade laufenden Generierungen. */
    val activeGenerations: Int get() = active.get()

    override suspend fun load(model: ModelFile, preferred: Accelerator, cpuThreads: Int): LoadedModel {
        recordedLoads += model.id
        if (loadDelayMs > 0) delay(loadDelayMs)
        check(model.id !in failLoadFor) { "Laden von '${model.id}' schlägt im FakeBackend absichtlich fehl" }
        val accelerator = if (preferred in supported) preferred else Accelerator.CPU
        return LoadedModel(model.id, accelerator, contextTokens = 4096)
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        active.incrementAndGet()
        try {
            val limit = request.maxTokens?.takeIf { enforcesMaxTokens }
            val output = if (limit != null) tokens.take(limit) else tokens
            output.forEachIndexed { index, token ->
                check(failAfterTokens != index) { "FakeBackend bricht nach $index Tokens absichtlich ab" }
                if (tokenDelayMs > 0) delay(tokenDelayMs)
                emit(GenerationEvent.Token(token))
            }
            check(failAfterTokens != output.size) { "FakeBackend bricht nach ${output.size} Tokens absichtlich ab" }
            val promptTokens = request.messages.sumOf { it.text.split(Regex("\\s+")).count(String::isNotBlank) }
            val reason = if (limit != null && output.size < tokens.size) FinishReason.LENGTH else FinishReason.STOP
            emit(GenerationEvent.Done(reason, Usage(promptTokens, output.size)))
        } finally {
            active.decrementAndGet()
        }
    }

    override suspend fun unload() {
        unloads.incrementAndGet()
    }
}
