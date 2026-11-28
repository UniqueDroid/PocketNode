package com.pocketnode.server.engine.litert

import com.pocketnode.server.engine.ChatMessage
import com.pocketnode.server.engine.GenerationRequest
import com.pocketnode.server.engine.Role
import com.pocketnode.server.engine.Sampling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiteRtMappingTest {
    private fun user(text: String) = ChatMessage(Role.USER, text)
    private fun system(text: String) = ChatMessage(Role.SYSTEM, text)
    private fun assistant(text: String) = ChatMessage(Role.ASSISTANT, text)

    @Test
    fun `single user message`() {
        val plan = GenerationRequest(listOf(user("Hi"))).toConversationPlan()
        assertEquals(ConversationPlan(null, emptyList(), "Hi", null, null), plan)
    }

    @Test
    fun `system messages are joined`() {
        val plan = GenerationRequest(listOf(system("A"), user("Hi"), system("B"), user("Und?"))).toConversationPlan()
        assertEquals("A\n\nB", plan.systemInstruction)
    }

    @Test
    fun `history keeps order and drops the last user message`() {
        val plan = GenerationRequest(
            listOf(system("S"), user("eins"), assistant("zwei"), user("drei"), assistant("vier"), user("fünf")),
        ).toConversationPlan()
        assertEquals(listOf(user("eins"), assistant("zwei"), user("drei"), assistant("vier")), plan.history)
        assertEquals("fünf", plan.lastUserText)
    }

    @Test
    fun `no sampling values means model defaults`() {
        assertNull(GenerationRequest(listOf(user("Hi"))).toConversationPlan().sampler)
    }

    @Test
    fun `partial sampling is filled with defaults`() {
        val plan = GenerationRequest(listOf(user("Hi")), sampling = Sampling(temperature = 0.2)).toConversationPlan { 7 }
        assertEquals(SamplerValues(topK = 40, topP = 0.95, temperature = 0.2, seed = 7), plan.sampler)
    }

    @Test
    fun `full sampling is passed through`() {
        val plan = GenerationRequest(listOf(user("Hi")), sampling = Sampling(0.7, 0.8, 12)).toConversationPlan { 7 }
        assertEquals(SamplerValues(topK = 12, topP = 0.8, temperature = 0.7, seed = 7), plan.sampler)
    }

    @Test
    fun `maxTokens is passed through`() {
        assertEquals(64, GenerationRequest(listOf(user("Hi")), maxTokens = 64).toConversationPlan().maxOutputTokens)
    }

    @Test
    fun `last message from assistant throws`() {
        assertFailsWith<IllegalArgumentException> {
            GenerationRequest(listOf(user("Hi"), assistant("Hallo"))).toConversationPlan()
        }
    }

    @Test
    fun `every request gets a fresh seed`() {
        val request = GenerationRequest(listOf(user("Hi")), sampling = Sampling(temperature = 1.0))
        val seeds = List(10) { request.toConversationPlan().sampler!!.seed }.toSet()
        assertTrue(seeds.size > 1, "immer derselbe Seed: $seeds")
    }
}
