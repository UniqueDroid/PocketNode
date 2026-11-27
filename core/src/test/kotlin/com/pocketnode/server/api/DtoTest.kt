package com.pocketnode.server.api

import com.pocketnode.server.engine.EngineException
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DtoTest {
    @Test
    fun `chunk serializes without null fields`() {
        val chunk = ChatCompletionChunk(
            id = "chatcmpl-1",
            created = 10,
            model = "m1",
            choices = listOf(ChunkChoice(delta = Delta(content = "Hi"))),
        )
        assertEquals(
            """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":10,"model":"m1",""" +
                """"choices":[{"index":0,"delta":{"content":"Hi"}}]}""",
            ApiJson.encodeToString(chunk),
        )
    }

    @Test
    fun `response has fixed object values`() {
        val response = ChatCompletionResponse(
            id = "chatcmpl-1",
            created = 10,
            model = "m1",
            choices = listOf(Choice(message = AssistantMessage(content = "Hi"), finishReason = "stop")),
            usage = UsageDto(promptTokens = 1, completionTokens = 2, totalTokens = 3),
        )
        assertEquals(
            """{"id":"chatcmpl-1","object":"chat.completion","created":10,"model":"m1",""" +
                """"choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""",
            ApiJson.encodeToString(response),
        )
    }

    @Test
    fun `model list has fixed object values`() {
        assertEquals(
            """{"object":"list","data":[{"id":"m1","object":"model","created":0,"owned_by":"pocketnode"}]}""",
            ApiJson.encodeToString(ModelList(data = listOf(ModelItem(id = "m1")))),
        )
    }

    @Test
    fun `error body has openai shape`() {
        assertEquals(
            """{"error":{"message":"kaputt","type":"server_error","code":"internal_error"}}""",
            ApiJson.encodeToString(ApiError(500, "server_error", "internal_error", "kaputt").toBody()),
        )
    }

    private fun assertMapping(error: Throwable, status: Int, type: String, code: String, retryAfter: Int? = null) {
        val api = error.toApiError()
        assertEquals(listOf(status, type, code, retryAfter), listOf(api.status, api.type, api.code, api.retryAfterSeconds))
    }

    @Test
    fun `ApiError maps to itself`() {
        val original = ApiError(400, "invalid_request_error", "invalid_stop", "x")
        assertEquals(original, original.toApiError())
    }

    @Test
    fun `QueueFull maps to 429 with retry`() =
        assertMapping(EngineException.QueueFull(5), 429, "rate_limit_error", "queue_full", 5)

    @Test
    fun `ModelNotFound maps to 404`() =
        assertMapping(EngineException.ModelNotFound("x"), 404, "invalid_request_error", "model_not_found")

    @Test
    fun `ModelNotLoaded maps to 400`() =
        assertMapping(EngineException.ModelNotLoaded("x"), 400, "invalid_request_error", "model_not_loaded")

    @Test
    fun `NoModelAvailable maps to 503`() =
        assertMapping(EngineException.NoModelAvailable(), 503, "server_error", "no_model")

    @Test
    fun `ModelLoadFailed maps to 503`() =
        assertMapping(EngineException.ModelLoadFailed("x", IllegalStateException("oom")), 503, "server_error", "model_load_failed")

    @Test
    fun `Unavailable maps to 503 with retry`() =
        assertMapping(EngineException.Unavailable("zu heiß", 30), 503, "server_error", "unavailable", 30)

    @Test
    fun `ContextLengthExceeded maps to 400`() =
        assertMapping(EngineException.ContextLengthExceeded(4096), 400, "invalid_request_error", "context_length_exceeded")

    @Test
    fun `serialization errors map to 400 invalid_json`() {
        assertMapping(SerializationException("bad"), 400, "invalid_request_error", "invalid_json")
        assertMapping(BadRequestException("bad"), 400, "invalid_request_error", "invalid_json")
    }

    @Test
    fun `unknown errors map to 500 without leaking the cause`() {
        val api = IllegalStateException("geheimes Detail /data/x").toApiError()
        assertEquals(listOf<Any?>(500, "server_error", "internal_error"), listOf(api.status, api.type, api.code))
        assertEquals("Interner Fehler", api.message)
        assertNull(api.retryAfterSeconds)
    }
}
