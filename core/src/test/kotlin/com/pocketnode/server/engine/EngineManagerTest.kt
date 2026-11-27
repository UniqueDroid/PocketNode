package com.pocketnode.server.engine

import com.pocketnode.server.engine.GenerationEvent.Done
import com.pocketnode.server.engine.GenerationEvent.Token
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EngineManagerTest {
    private val catalog = InMemoryModelCatalog(
        listOf(ModelFile("m1", "/models/m1.litertlm", 1), ModelFile("m2", "/models/m2.litertlm", 1)),
    )
    private val request = GenerationRequest(listOf(ChatMessage(Role.USER, "hi")))
    private val fullText = "Hallo von PocketNode."

    private suspend fun Flow<GenerationEvent>.text(): String =
        toList().filterIsInstance<Token>().joinToString("") { it.text }

    @Test
    fun `loads first catalog model when nothing loaded and model is null`() = runTest {
        val backend = FakeBackend()
        val manager = EngineManager(backend, catalog)
        assertEquals(fullText, manager.generate(null, request).text())
        assertEquals(listOf("m1"), backend.loadCalls)
        assertEquals(LoadedModel("m1", Accelerator.GPU, 4096), manager.state.value.loaded)
    }

    @Test
    fun `uses loaded model when model is null`() = runTest {
        val backend = FakeBackend()
        val manager = EngineManager(backend, catalog)
        manager.generate("m2", request).text()
        manager.generate(null, request).text()
        assertEquals(listOf("m2"), backend.loadCalls)
    }

    @Test
    fun `unknown model throws ModelNotFound`() = runTest {
        val manager = EngineManager(FakeBackend(), catalog)
        val error = assertFailsWith<EngineException.ModelNotFound> { manager.generate("gibt-es-nicht", request).toList() }
        assertEquals("gibt-es-nicht", error.modelId)
    }

    @Test
    fun `empty catalog throws NoModelAvailable`() = runTest {
        val manager = EngineManager(FakeBackend(), InMemoryModelCatalog(emptyList()))
        assertFailsWith<EngineException.NoModelAvailable> { manager.generate(null, request).toList() }
    }

    @Test
    fun `switches model when autoSwitch is on`() = runTest {
        val backend = FakeBackend()
        val manager = EngineManager(backend, catalog)
        manager.generate("m1", request).text()
        manager.generate("m2", request).text()
        assertEquals(listOf("m1", "m2"), backend.loadCalls)
        assertEquals("m2", manager.state.value.loaded?.id)
    }

    @Test
    fun `autoSwitch off throws ModelNotLoaded for other model`() = runTest {
        val backend = FakeBackend()
        val manager = EngineManager(backend, catalog, EngineConfig(autoSwitch = false))
        manager.generate("m1", request).text()
        assertFailsWith<EngineException.ModelNotLoaded> { manager.generate("m2", request).toList() }
        assertEquals("m1", manager.state.value.loaded?.id)
        assertEquals(listOf("m1"), backend.loadCalls)
    }

    @Test
    fun `runs one generation at a time`() = runTest {
        val backend = FakeBackend(tokenDelayMs = 10)
        val manager = EngineManager(backend, catalog)
        var maxActive = 0
        val texts = (1..3).map {
            async {
                manager.generate(null, request).toList().also { maxActive = maxOf(maxActive, backend.activeGenerations) }
                    .filterIsInstance<Token>().joinToString("") { it.text }
            }
        }
        launch { repeat(20) { maxActive = maxOf(maxActive, backend.activeGenerations); advanceTimeBy(5) } }
        assertEquals(List(3) { fullText }, texts.awaitAll())
        assertEquals(1, maxActive)
    }

    @Test
    fun `serves waiting requests in arrival order`() = runTest {
        val manager = EngineManager(FakeBackend(tokenDelayMs = 10), catalog)
        val log = mutableListOf<Int>()
        val jobs = (0..2).map { index -> launch { manager.generate(null, request).collect { log += index } } }
        jobs.forEach { it.join() }
        assertEquals(List(5) { 0 } + List(5) { 1 } + List(5) { 2 }, log)
    }

    @Test
    fun `sixth concurrent request throws QueueFull`() = runTest {
        val manager = EngineManager(FakeBackend(tokenDelayMs = 100), catalog)
        val jobs = (1..5).map { async { manager.generate(null, request).text() } }
        runCurrent()
        assertEquals(4, manager.state.value.waiting)
        assertTrue(manager.state.value.running)
        val error = assertFailsWith<EngineException.QueueFull> { manager.generate(null, request).toList() }
        assertEquals(5, error.retryAfterSeconds)
        assertEquals(List(5) { fullText }, jobs.awaitAll())
        assertEquals(0, manager.state.value.waiting)
    }

    @Test
    fun `cancelled waiting request frees its slot`() = runTest {
        val manager = EngineManager(FakeBackend(tokenDelayMs = 100), catalog, EngineConfig(maxWaiting = 1))
        val running = async { manager.generate(null, request).text() }
        runCurrent()
        val waiting = launch { manager.generate(null, request).toList() }
        runCurrent()
        assertEquals(1, manager.state.value.waiting)
        waiting.cancelAndJoin()
        assertEquals(0, manager.state.value.waiting)
        val next = async { manager.generate(null, request).text() }
        assertEquals(fullText, running.await())
        assertEquals(fullText, next.await())
    }

    @Test
    fun `cancelled running request stops backend and serves next`() = runTest {
        val backend = FakeBackend(tokenDelayMs = 100)
        val manager = EngineManager(backend, catalog)
        val running = launch { manager.generate(null, request).toList() }
        runCurrent()
        val next = async { manager.generate(null, request).text() }
        runCurrent()
        assertEquals(1, backend.activeGenerations)
        running.cancelAndJoin()
        assertEquals(fullText, next.await())
        assertEquals(0, backend.activeGenerations)
        assertFalse(manager.state.value.running)
    }

    @Test
    fun `backend failure mid stream frees the queue`() = runTest {
        val backend = FakeBackend(failAfterTokens = 1)
        val manager = EngineManager(backend, catalog)
        val seen = mutableListOf<GenerationEvent>()
        assertFailsWith<IllegalStateException> { manager.generate(null, request).collect { seen += it } }
        assertEquals(listOf<GenerationEvent>(Token("Hallo")), seen)
        assertFalse(manager.state.value.running)
        backend.failAfterTokens = null
        assertEquals(fullText, manager.generate(null, request).text())
    }

    @Test
    fun `failed load leaves no model loaded and retries later`() = runTest {
        val backend = FakeBackend(failLoadFor = setOf("m2"))
        val manager = EngineManager(backend, catalog)
        manager.generate("m1", request).text()
        val error = assertFailsWith<EngineException.ModelLoadFailed> { manager.generate("m2", request).toList() }
        assertEquals("m2", error.modelId)
        assertNull(manager.state.value.loaded)
        assertNull(manager.state.value.loading)
        assertNotNull(manager.state.value.lastError)
        assertFalse(manager.state.value.running)
        assertEquals(fullText, manager.generate("m1", request).text())
        assertEquals(listOf("m1", "m2", "m1"), backend.loadCalls)
        assertNull(manager.state.value.lastError)
    }

    @Test
    fun `gate rejection throws Unavailable before queueing`() = runTest {
        val backend = FakeBackend()
        val manager = EngineManager(backend, catalog, gate = { EngineException.Unavailable("Gerät zu heiß", 30) })
        val error = assertFailsWith<EngineException.Unavailable> { manager.generate(null, request).toList() }
        assertEquals(30, error.retryAfterSeconds)
        assertEquals(0, manager.state.value.waiting)
        assertEquals(emptyList(), backend.loadCalls)
    }

    @Test
    fun `applies maxTokens and stop from request`() = runTest {
        val manager = EngineManager(FakeBackend(), catalog)
        val limited = manager.generate(null, request.copy(maxTokens = 2)).toList()
        assertEquals(listOf(Token("Hallo"), Token(" von"), Done(FinishReason.LENGTH, null)), limited)
        val stopped = manager.generate(null, request.copy(stop = listOf("Pocket"))).text()
        assertEquals("Hallo von ", stopped)
    }

    @Test
    fun `state reflects loading running and waiting`() = runTest {
        val manager = EngineManager(FakeBackend(tokenDelayMs = 10), catalog)
        val states = mutableListOf<EngineState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.state.toList(states) }
        assertEquals(EngineState(), manager.state.value)
        manager.generate("m1", request).text()
        assertTrue(states.any { it.loading == "m1" && it.loaded == null })
        assertTrue(states.any { it.running && it.loaded?.id == "m1" && it.loading == null })
        assertEquals(EngineState(loaded = LoadedModel("m1", Accelerator.GPU, 4096)), manager.state.value)
    }

    @Test
    fun `load and unload change state`() = runTest {
        val backend = FakeBackend()
        val manager = EngineManager(backend, catalog)
        manager.load("m2")
        assertEquals("m2", manager.state.value.loaded?.id)
        manager.unload()
        assertNull(manager.state.value.loaded)
        assertFailsWith<EngineException.ModelNotFound> { manager.load("fehlt") }
    }
}
