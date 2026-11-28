package com.pocketnode.server.engine.litert

import com.pocketnode.server.api.ServerLog
import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.ChatMessage
import com.pocketnode.server.engine.FinishReason
import com.pocketnode.server.engine.GenerationEvent
import com.pocketnode.server.engine.GenerationEvent.Done
import com.pocketnode.server.engine.GenerationEvent.Token
import com.pocketnode.server.engine.GenerationRequest
import com.pocketnode.server.engine.LoadedModel
import com.pocketnode.server.engine.ModelFile
import com.pocketnode.server.engine.Role
import com.pocketnode.server.engine.Usage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Test-Runtime: zeichnet Aufrufe auf und kann pro Beschleuniger beim Öffnen werfen. */
private class FakeRuntime(
    val failOn: Set<Accelerator> = emptySet(),
    val chunks: List<String> = listOf("Hal", "lo"),
    val usage: Pair<Int, Int>? = 7 to 2,
    val endless: Boolean = false,
) : LiteRtRuntime {
    val opened = CopyOnWriteArrayList<Pair<String, Accelerator>>()
    val sessions = CopyOnWriteArrayList<FakeSession>()

    override fun open(modelPath: String, accelerator: Accelerator, cpuThreads: Int, cacheDir: String?, maxTokens: Int?): LiteRtSession {
        opened += modelPath to accelerator
        if (accelerator in failOn) throw IllegalStateException("$accelerator absichtlich kaputt")
        return FakeSession(this).also { sessions += it }
    }
}

private class FakeSession(private val runtime: FakeRuntime) : LiteRtSession {
    var closed = false
    var cancelled = 0
    val plans = CopyOnWriteArrayList<ConversationPlan>()
    override val contextTokens = 1280

    override fun generate(plan: ConversationPlan, onDone: (Int?, Int?) -> Unit): Flow<String> = flow {
        plans += plan
        try {
            runtime.chunks.forEach { emit(it) }
            if (runtime.endless) awaitCancellation()
            onDone(runtime.usage?.first, runtime.usage?.second)
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled++
            throw e
        }
    }

    override fun close() {
        closed = true
    }
}

class LiteRtLmBackendTest {
    private val model = ModelFile("gemma", "/models/gemma.litertlm", 1)
    private val request = GenerationRequest(listOf(ChatMessage(Role.USER, "Hi")))
    private val logs = CopyOnWriteArrayList<Pair<ServerLog.Level, String>>()
    private val log = ServerLog { level, message, _ -> logs += level to message }

    private fun backend(runtime: FakeRuntime, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        LiteRtLmBackend(runtime = runtime, log = log, dispatcher = dispatcher)

    @Test
    fun `loads on GPU when it works`() = runTest {
        val runtime = FakeRuntime()
        val loaded = backend(runtime, StandardTestDispatcher(testScheduler)).load(model, Accelerator.GPU, 4)
        assertEquals(LoadedModel("gemma", Accelerator.GPU, 1280), loaded)
        assertEquals(listOf("/models/gemma.litertlm" to Accelerator.GPU), runtime.opened.toList())
    }

    @Test
    fun `falls back to CPU when GPU fails and logs a warning`() = runTest {
        val runtime = FakeRuntime(failOn = setOf(Accelerator.GPU))
        val loaded = backend(runtime, StandardTestDispatcher(testScheduler)).load(model, Accelerator.GPU, 4)
        assertEquals(Accelerator.CPU, loaded.accelerator)
        assertEquals(listOf(Accelerator.GPU, Accelerator.CPU), runtime.opened.map { it.second })
        assertEquals(1, logs.count { it.first == ServerLog.Level.WARN })
        assertTrue("GPU" in logs.single { it.first == ServerLog.Level.WARN }.second)
    }

    @Test
    fun `NPU preference starts with GPU`() = runTest {
        val runtime = FakeRuntime()
        val loaded = backend(runtime, StandardTestDispatcher(testScheduler)).load(model, Accelerator.NPU, 4)
        assertEquals(Accelerator.GPU, loaded.accelerator)
    }

    @Test
    fun `throws when CPU fails too`() = runTest {
        val runtime = FakeRuntime(failOn = setOf(Accelerator.GPU, Accelerator.CPU))
        val error = assertFailsWith<IllegalStateException> {
            backend(runtime, StandardTestDispatcher(testScheduler)).load(model, Accelerator.GPU, 4)
        }
        assertTrue("CPU" in error.message.orEmpty())
    }

    @Test
    fun `CPU preference never tries GPU`() = runTest {
        val runtime = FakeRuntime()
        backend(runtime, StandardTestDispatcher(testScheduler)).load(model, Accelerator.CPU, 4)
        assertEquals(listOf(Accelerator.CPU), runtime.opened.map { it.second })
    }

    @Test
    fun `load closes the previous session`() = runTest {
        val runtime = FakeRuntime()
        val backend = backend(runtime, StandardTestDispatcher(testScheduler))
        backend.load(model, Accelerator.GPU, 4)
        backend.load(model.copy(id = "zwei"), Accelerator.GPU, 4)
        assertEquals(listOf(true, false), runtime.sessions.map { it.closed })
        backend.unload()
        assertEquals(listOf(true, true), runtime.sessions.map { it.closed })
    }

    @Test
    fun `generate emits tokens then Done with usage`() = runTest {
        val runtime = FakeRuntime()
        val backend = backend(runtime, StandardTestDispatcher(testScheduler))
        backend.load(model, Accelerator.GPU, 4)
        val events = backend.generate(request).toList()
        assertEquals(listOf(Token("Hal"), Token("lo"), Done(FinishReason.STOP, Usage(7, 2))), events)
        assertEquals("Hi", runtime.sessions.single().plans.single().lastUserText)
    }

    @Test
    fun `generate without usage yields Done with null usage`() = runTest {
        val runtime = FakeRuntime(usage = null)
        val backend = backend(runtime, StandardTestDispatcher(testScheduler))
        backend.load(model, Accelerator.GPU, 4)
        assertEquals(Done(FinishReason.STOP, null), backend.generate(request).toList().last())
    }

    @Test
    fun `reaching maxTokens reports LENGTH`() = runTest {
        val runtime = FakeRuntime(usage = 7 to 2)
        val backend = backend(runtime, StandardTestDispatcher(testScheduler))
        backend.load(model, Accelerator.GPU, 4)
        val last = backend.generate(request.copy(maxTokens = 2)).toList().last()
        assertEquals(Done(FinishReason.LENGTH, Usage(7, 2)), last)
    }

    @Test
    fun `cancelling the flow cancels the session`() = runTest {
        val runtime = FakeRuntime(endless = true)
        val backend = backend(runtime, StandardTestDispatcher(testScheduler))
        backend.load(model, Accelerator.GPU, 4)
        assertEquals(Token("Hal"), backend.generate(request).first())
        assertEquals(1, runtime.sessions.single().cancelled)
    }

    @Test
    fun `generate before load throws`() = runTest {
        val backend = backend(FakeRuntime(), StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalStateException> { backend.generate(request).toList() }
    }

    @Test
    fun `last message from assistant is rejected by the backend too`() = runTest {
        val backend = backend(FakeRuntime(), StandardTestDispatcher(testScheduler))
        backend.load(model, Accelerator.GPU, 4)
        val bad = GenerationRequest(listOf(ChatMessage(Role.USER, "Hi"), ChatMessage(Role.ASSISTANT, "Hallo")))
        assertFailsWith<IllegalArgumentException> { backend.generate(bad).toList<GenerationEvent>() }
    }
}
