package com.pocketnode.server.engine.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.EngineException
import com.pocketnode.server.engine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Die einzige Stelle, die LiteRT-LM-Klassen anfasst. Für Tests ersetzbar. */
interface LiteRtRuntime {
    /** Lädt das Modell. Blockiert und wirft bei Fehlern. */
    fun open(modelPath: String, accelerator: Accelerator, cpuThreads: Int, cacheDir: String?, maxTokens: Int?): LiteRtSession
}

interface LiteRtSession : AutoCloseable {
    /** Größe des Kontextfensters in Tokens, 0 wenn unbekannt. */
    val contextTokens: Int

    /**
     * Kalter Flow mit Textstücken. Abbruch stoppt die Generierung in der Engine.
     * Nach dem letzten Stück wird [onDone] mit den Token-Zahlen aufgerufen (`null`, wenn unbekannt).
     */
    fun generate(plan: ConversationPlan, onDone: (promptTokens: Int?, completionTokens: Int?) -> Unit): Flow<String>
}

@OptIn(ExperimentalApi::class)
object RealLiteRtRuntime : LiteRtRuntime {
    override fun open(modelPath: String, accelerator: Accelerator, cpuThreads: Int, cacheDir: String?, maxTokens: Int?): LiteRtSession {
        // Ohne diesen Schalter liefert die Engine keine Token-Zahlen (geprüft mit 0.20.0).
        ExperimentalFlags.enableBenchmark = true
        val backend = when (accelerator) {
            Accelerator.CPU -> Backend.CPU(threadCount = cpuThreads)
            Accelerator.GPU -> Backend.GPU()
            Accelerator.NPU -> throw IllegalArgumentException("NPU wird von diesem Backend nicht unterstützt")
        }
        val modelContext = runCatching { ModelInfo.from(modelPath).use { it.maxContextTokens() } }.getOrDefault(0)
        val engine = Engine(
            EngineConfig(modelPath = modelPath, backend = backend, maxNumTokens = maxTokens, cacheDir = cacheDir),
        )
        try {
            engine.initialize()
        } catch (e: Throwable) {
            runCatching { engine.close() }
            throw e
        }
        val context = if (maxTokens != null && modelContext > 0) minOf(maxTokens, modelContext) else maxTokens ?: modelContext
        return RealSession(engine, context)
    }
}

// Token-Zahlen gibt es nur über die experimentelle Benchmark-API.
@OptIn(ExperimentalApi::class)
private class RealSession(private val engine: Engine, override val contextTokens: Int) : LiteRtSession {
    override fun generate(plan: ConversationPlan, onDone: (Int?, Int?) -> Unit): Flow<String> = flow {
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = plan.systemInstruction?.let { Contents.of(it) },
                initialMessages = plan.history.map { it.toLiteRt() },
                samplerConfig = plan.sampler?.let { SamplerConfig(it.topK, it.topP, it.temperature, it.seed) },
                maxOutputToken = plan.maxOutputTokens,
            ),
        )
        try {
            conversation.sendMessageAsync(Message.user(plan.lastUserText)).collect { message ->
                val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (text.isNotEmpty()) emit(text)
            }
            val info = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
            onDone(info?.lastPrefillTokenCount?.takeIf { it > 0 }, info?.lastDecodeTokenCount?.takeIf { it > 0 })
        } catch (e: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw e
        } catch (e: LiteRtLmJniException) {
            // 0.20.0 meldet zu lange Eingaben als "Input token ids are too long. Exceeding the maximum number of tokens …".
            if (e.message.orEmpty().contains("too long", ignoreCase = true)) {
                throw EngineException.ContextLengthExceeded(contextTokens)
            }
            throw e
        } finally {
            conversation.close()
        }
    }

    override fun close() = engine.close()
}

private fun com.pocketnode.server.engine.ChatMessage.toLiteRt(): Message = when (role) {
    Role.USER -> Message.user(text)
    Role.ASSISTANT -> Message.model(text)
    Role.SYSTEM -> Message.system(text)
}
