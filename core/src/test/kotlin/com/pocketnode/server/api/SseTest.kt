package com.pocketnode.server.api

import com.pocketnode.server.engine.EngineConfig
import com.pocketnode.server.engine.FakeBackend
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SseTest {
    private fun streamBody(extra: String = "") =
        """{"model":"m1","stream":true,"messages":[{"role":"user","content":"Hi"}]$extra}"""

    /** Zerlegt eine SSE-Antwort in die Nutzlasten ihrer `data:`-Zeilen. */
    private suspend fun HttpResponse.events(): List<String> {
        val text = bodyAsText()
        assertTrue(text.endsWith("\n\n"), "Stream muss mit einer Leerzeile enden")
        return text.trimEnd().split("\n\n").map {
            assertTrue(it.startsWith("data: "), "Unerwartete Zeile: $it")
            it.removePrefix("data: ")
        }
    }

    private fun List<String>.chunks(): List<JsonObject> =
        filter { it != "[DONE]" }.map { ApiJson.parseToJsonElement(it).jsonObject }

    private fun JsonObject.choice(): JsonObject = getValue("choices").jsonArray.single().jsonObject

    private fun JsonObject.delta(): JsonObject = choice().getValue("delta").jsonObject

    @Test
    fun `stream has role chunk content chunks finish chunk and DONE`() = ApiFixture().run {
        val response = client.post("/v1/chat/completions") { chat(streamBody()) }
        assertEquals(200, response.status.value)
        assertEquals("text/event-stream", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        assertEquals("no", response.headers["X-Accel-Buffering"])
        val events = response.events()
        assertEquals("[DONE]", events.last())
        val chunks = events.chunks()
        assertEquals(6, chunks.size)
        assertEquals("assistant", chunks.first().delta().getValue("role").jsonPrimitive.content)
        assertEquals("", chunks.first().delta().getValue("content").jsonPrimitive.content)
        assertNull(chunks.first().choice()["finish_reason"])
        chunks.subList(1, 5).forEach {
            assertNull(it.delta()["role"])
            assertNull(it.choice()["finish_reason"])
            assertEquals("chat.completion.chunk", it.getValue("object").jsonPrimitive.content)
        }
        assertEquals("stop", chunks.last().choice().getValue("finish_reason").jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), chunks.last().delta())
    }

    @Test
    fun `stream content concatenates to full text`() = ApiFixture().run {
        val chunks = client.post("/v1/chat/completions") { chat(streamBody()) }.events().chunks()
        val text = chunks.mapNotNull { it.delta()["content"]?.jsonPrimitive?.content }.joinToString("")
        assertEquals("Hallo von PocketNode.", text)
    }

    @Test
    fun `all chunks share id and model`() = ApiFixture().run {
        val chunks = client.post("/v1/chat/completions") { chat(streamBody()) }.events().chunks()
        val ids = chunks.map { it.getValue("id").jsonPrimitive.content }.toSet()
        assertEquals(1, ids.size)
        assertTrue(ids.single().startsWith("chatcmpl-"))
        assertEquals(setOf("m1"), chunks.map { it.getValue("model").jsonPrimitive.content }.toSet())
    }

    @Test
    fun `stream with max_tokens 2 ends with finish_reason length`() = ApiFixture().run {
        val events = client.post("/v1/chat/completions") { chat(streamBody(""","max_tokens":2""")) }.events()
        val chunks = events.chunks()
        assertEquals("length", chunks.last().choice().getValue("finish_reason").jsonPrimitive.content)
        assertEquals("Hallo von", chunks.mapNotNull { it.delta()["content"]?.jsonPrimitive?.content }.joinToString(""))
        assertEquals("[DONE]", events.last())
    }

    @Test
    fun `include_usage adds usage chunk before DONE`() = ApiFixture().run {
        val events = client.post("/v1/chat/completions") {
            chat(streamBody(""","stream_options":{"include_usage":true}"""))
        }.events()
        assertEquals("[DONE]", events.last())
        val usageChunk = ApiJson.parseToJsonElement(events[events.size - 2]).jsonObject
        assertEquals(0, usageChunk.getValue("choices").jsonArray.size)
        val usage = usageChunk.getValue("usage").jsonObject
        assertEquals(1, usage.getValue("prompt_tokens").jsonPrimitive.int)
        assertEquals(4, usage.getValue("completion_tokens").jsonPrimitive.int)
        assertEquals(5, usage.getValue("total_tokens").jsonPrimitive.int)
    }

    @Test
    fun `no usage chunk by default`() = ApiFixture().run {
        val chunks = client.post("/v1/chat/completions") { chat(streamBody()) }.events().chunks()
        assertTrue(chunks.none { it.containsKey("usage") })
    }

    @Test
    fun `queue full before stream start returns 429 json not sse`() {
        val fixture = ApiFixture(FakeBackend(tokenDelayMs = 150), EngineConfig(maxWaiting = 1))
        fixture.run {
            coroutineScope {
                val first = async { client.post("/v1/chat/completions") { chat(streamBody()) } }
                val second = async { client.post("/v1/chat/completions") { chat(streamBody()) } }
                withTimeout(5_000) {
                    while (!(fixture.engine.state.value.running && fixture.engine.state.value.waiting == 1)) delay(10)
                }
                val third = client.post("/v1/chat/completions") { chat(streamBody()) }
                third.assertError(429, "rate_limit_error", "queue_full")
                assertEquals("application/json", third.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertEquals("5", third.headers[HttpHeaders.RetryAfter])
                assertEquals("[DONE]", first.await().events().last())
                assertEquals("[DONE]", second.await().events().last())
            }
        }
    }

    @Test
    fun `unknown model before stream start returns 404 json`() = ApiFixture().run {
        val response = client.post("/v1/chat/completions") {
            chat("""{"model":"fehlt","stream":true,"messages":[{"role":"user","content":"Hi"}]}""")
        }
        response.assertError(404, "invalid_request_error", "model_not_found")
    }

    @Test
    fun `backend failure before first token returns 500 json`() =
        ApiFixture(FakeBackend(failAfterTokens = 0)).run {
            client.post("/v1/chat/completions") { chat(streamBody()) }
                .assertError(500, "server_error", "internal_error")
        }

    @Test
    fun `backend failure mid stream emits error event and no DONE`() =
        ApiFixture(FakeBackend(failAfterTokens = 2)).run {
            val response = client.post("/v1/chat/completions") { chat(streamBody()) }
            assertEquals(200, response.status.value)
            val events = response.events()
            assertFalse("[DONE]" in events)
            val chunks = events.chunks()
            assertEquals(4, chunks.size)
            val error = chunks.last().getValue("error").jsonObject
            assertEquals("server_error", error.getValue("type").jsonPrimitive.content)
            assertEquals("internal_error", error.getValue("code").jsonPrimitive.content)
            assertEquals("Interner Fehler", error.getValue("message").jsonPrimitive.content)
        }

    @Test
    fun `request after mid stream failure succeeds`() {
        val fixture = ApiFixture(FakeBackend(failAfterTokens = 2))
        fixture.run {
            client.post("/v1/chat/completions") { chat(streamBody()) }.bodyAsText()
            fixture.backend.failAfterTokens = null
            assertEquals(0, fixture.backend.activeGenerations)
            assertFalse(fixture.engine.state.value.running)
            val events = client.post("/v1/chat/completions") { chat(streamBody()) }.events()
            assertEquals("[DONE]", events.last())
        }
    }
}
