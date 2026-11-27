package com.pocketnode.server.api

import com.pocketnode.server.engine.ChatMessage
import com.pocketnode.server.engine.GenerationRequest
import com.pocketnode.server.engine.Role
import com.pocketnode.server.engine.Sampling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestMapperTest {
    private val user = """{"role":"user","content":"Hi"}"""

    private fun map(json: String): GenerationRequest =
        ApiJson.decodeFromString<ChatCompletionRequest>(json).toGenerationRequest()

    private fun assertBadRequest(json: String, code: String) {
        val error = assertFailsWith<ApiError> { map(json) }
        assertEquals(400, error.status)
        assertEquals("invalid_request_error", error.type)
        assertEquals(code, error.code)
    }

    @Test
    fun `maps roles and sampling into GenerationRequest`() {
        val result = map(
            """{"model":"m1","messages":[
                {"role":"system","content":"Sei kurz."},
                {"role":"user","content":"Hi"},
                {"role":"assistant","content":"Hallo"},
                {"role":"user","content":"Wie geht's?"}],
               "max_tokens":64,"temperature":0.7,"top_p":0.9,"top_k":40,"stop":"\n\n"}""",
        )
        assertEquals(
            GenerationRequest(
                messages = listOf(
                    ChatMessage(Role.SYSTEM, "Sei kurz."),
                    ChatMessage(Role.USER, "Hi"),
                    ChatMessage(Role.ASSISTANT, "Hallo"),
                    ChatMessage(Role.USER, "Wie geht's?"),
                ),
                maxTokens = 64,
                sampling = Sampling(temperature = 0.7, topP = 0.9, topK = 40),
                stop = listOf("\n\n"),
            ),
            result,
        )
    }

    @Test
    fun `ignores unknown fields`() {
        val result = map("""{"messages":[$user],"frequency_penalty":0.5,"user":"jan","metadata":{"a":1}}""")
        assertEquals(GenerationRequest(listOf(ChatMessage(Role.USER, "Hi"))), result)
    }

    @Test
    fun `empty messages are rejected`() = assertBadRequest("""{"messages":[]}""", "messages_required")

    @Test
    fun `missing messages are rejected`() = assertBadRequest("""{"model":"m1"}""", "messages_required")

    @Test
    fun `unknown role is rejected`() =
        assertBadRequest("""{"messages":[{"role":"tool","content":"x"}]}""", "unsupported_role")

    @Test
    fun `content array of text parts is joined with newlines`() {
        val result = map(
            """{"messages":[{"role":"user","content":[{"type":"text","text":"eins"},{"type":"text","text":"zwei"}]}]}""",
        )
        assertEquals(listOf(ChatMessage(Role.USER, "eins\nzwei")), result.messages)
    }

    @Test
    fun `image content is rejected`() = assertBadRequest(
        """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"http://x"}}]}]}""",
        "unsupported_content",
    )

    @Test
    fun `missing content is rejected`() =
        assertBadRequest("""{"messages":[{"role":"assistant"}]}""", "unsupported_content")

    @Test
    fun `numeric content is rejected`() =
        assertBadRequest("""{"messages":[{"role":"user","content":5}]}""", "unsupported_content")

    @Test
    fun `n greater than one is rejected`() =
        assertBadRequest("""{"messages":[$user],"n":2}""", "unsupported_parameter")

    @Test
    fun `n equal to one is accepted`() {
        assertEquals(1, map("""{"messages":[$user],"n":1}""").messages.size)
    }

    @Test
    fun `tools are rejected`() = assertBadRequest(
        """{"messages":[$user],"tools":[{"type":"function","function":{"name":"f"}}]}""",
        "unsupported_parameter",
    )

    @Test
    fun `empty tools array is accepted`() {
        assertEquals(1, map("""{"messages":[$user],"tools":[]}""").messages.size)
    }

    @Test
    fun `max_completion_tokens wins over max_tokens`() {
        assertEquals(7, map("""{"messages":[$user],"max_tokens":99,"max_completion_tokens":7}""").maxTokens)
    }

    @Test
    fun `negative token limit is rejected`() =
        assertBadRequest("""{"messages":[$user],"max_tokens":-1}""", "invalid_max_tokens")

    @Test
    fun `stop array is mapped and empty strings are dropped`() {
        assertEquals(listOf("a", "b"), map("""{"messages":[$user],"stop":["a","","b"]}""").stop)
    }

    @Test
    fun `more than four stop sequences are rejected`() =
        assertBadRequest("""{"messages":[$user],"stop":["a","b","c","d","e"]}""", "invalid_stop")

    @Test
    fun `non string stop is rejected`() =
        assertBadRequest("""{"messages":[$user],"stop":[1,2]}""", "invalid_stop")

    @Test
    fun `temperature out of range is rejected`() =
        assertBadRequest("""{"messages":[$user],"temperature":2.5}""", "invalid_sampling")

    @Test
    fun `top_p out of range is rejected`() =
        assertBadRequest("""{"messages":[$user],"top_p":1.5}""", "invalid_sampling")
}
