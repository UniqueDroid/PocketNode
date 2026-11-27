package com.pocketnode.server.engine

import com.pocketnode.server.engine.GenerationEvent.Done
import com.pocketnode.server.engine.GenerationEvent.Token
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FakeBackendTest {
    private val m1 = ModelFile("m1", "/models/m1.litertlm", 100)
    private val request = GenerationRequest(listOf(ChatMessage(Role.USER, "eins zwei drei")))

    @Test
    fun `emits configured tokens then Done STOP`() = runTest {
        val events = FakeBackend(tokens = listOf("A", "B")).generate(request).toList()
        assertEquals(
            listOf(Token("A"), Token("B"), Done(FinishReason.STOP, Usage(promptTokens = 3, completionTokens = 2))),
            events,
        )
    }

    @Test
    fun `load records calls and reports accelerator`() = runTest {
        val backend = FakeBackend()
        assertEquals(LoadedModel("m1", Accelerator.GPU, 4096), backend.load(m1, Accelerator.GPU, 4))
        assertEquals(Accelerator.CPU, backend.load(m1, Accelerator.NPU, 4).accelerator)
        assertEquals(listOf("m1", "m1"), backend.loadCalls)
    }

    @Test
    fun `load throws for ids in failLoadFor`() = runTest {
        val backend = FakeBackend(failLoadFor = setOf("m1"))
        assertFailsWith<IllegalStateException> { backend.load(m1, Accelerator.GPU, 4) }
    }

    @Test
    fun `throws after failAfterTokens`() = runTest {
        val backend = FakeBackend(tokens = listOf("A", "B", "C"), failAfterTokens = 1)
        val seen = mutableListOf<GenerationEvent>()
        assertFailsWith<IllegalStateException> { backend.generate(request).collect { seen += it } }
        assertEquals(listOf<GenerationEvent>(Token("A")), seen)
        assertEquals(0, backend.activeGenerations)
    }

    @Test
    fun `activeGenerations returns to zero after cancel`() = runTest {
        val backend = FakeBackend(tokenDelayMs = 50)
        assertEquals(Token("Hallo"), backend.generate(request).first())
        assertEquals(0, backend.activeGenerations)
    }
}
