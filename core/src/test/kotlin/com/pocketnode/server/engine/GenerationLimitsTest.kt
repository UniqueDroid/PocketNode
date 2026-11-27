package com.pocketnode.server.engine

import com.pocketnode.server.engine.GenerationEvent.Done
import com.pocketnode.server.engine.GenerationEvent.Token
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GenerationLimitsTest {
    private val usage = Usage(promptTokens = 3, completionTokens = 4)

    private fun source(vararg tokens: String): Flow<GenerationEvent> = flow {
        tokens.forEach { emit(Token(it)) }
        emit(Done(FinishReason.STOP, usage))
    }

    private fun List<GenerationEvent>.text() = filterIsInstance<Token>().joinToString("") { it.text }

    @Test
    fun `stops after maxTokens with LENGTH`() = runTest {
        val events = source("a", "b", "c", "d").withLimits(maxTokens = 2, stop = emptyList()).toList()
        assertEquals("ab", events.text())
        assertEquals(Done(FinishReason.LENGTH, null), events.last())
        assertEquals(1, events.count { it is Done })
    }

    @Test
    fun `passes through without limits`() = runTest {
        val events = source("a", "b", "c", "d").withLimits(maxTokens = null, stop = emptyList()).toList()
        assertEquals(listOf(Token("a"), Token("b"), Token("c"), Token("d"), Done(FinishReason.STOP, usage)), events)
    }

    @Test
    fun `cuts at stop sequence inside one token`() = runTest {
        val events = source("ab", "cXd").withLimits(maxTokens = null, stop = listOf("X")).toList()
        assertEquals("abc", events.text())
        assertEquals(Done(FinishReason.STOP, null), events.last())
    }

    @Test
    fun `cuts at stop sequence spanning two tokens`() = runTest {
        val events = source("Hal", "lo EN", "D mehr").withLimits(maxTokens = null, stop = listOf("END")).toList()
        assertEquals("Hallo ", events.text())
        assertEquals(Done(FinishReason.STOP, null), events.last())
    }

    @Test
    fun `does not hold back text forever`() = runTest {
        val events = source("Das X", "Y ist", " kein Treffer X").withLimits(maxTokens = null, stop = listOf("XYZ")).toList()
        assertEquals("Das XY ist kein Treffer X", events.text())
        assertEquals(Done(FinishReason.STOP, usage), events.last())
    }

    @Test
    fun `maxTokens zero yields only Done LENGTH`() = runTest {
        var started = false
        val upstream = flow<GenerationEvent> { started = true; emit(Token("a")) }
        val events = upstream.withLimits(maxTokens = 0, stop = emptyList()).toList()
        assertEquals(listOf<GenerationEvent>(Done(FinishReason.LENGTH, null)), events)
        assertEquals(false, started)
    }

    @Test
    fun `flushes held text when maxTokens is reached`() = runTest {
        val events = source("aX", "b").withLimits(maxTokens = 1, stop = listOf("XYZ")).toList()
        assertEquals("aX", events.text())
        assertEquals(Done(FinishReason.LENGTH, null), events.last())
    }
}
