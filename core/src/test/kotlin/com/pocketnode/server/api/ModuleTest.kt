package com.pocketnode.server.api

import com.pocketnode.server.engine.EngineConfig
import com.pocketnode.server.engine.EngineException
import com.pocketnode.server.engine.FakeBackend
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleTest {
    @Test
    fun `checkBearer compares scheme case-insensitively and token exactly`() {
        assertTrue(checkBearer("Bearer abc", "abc"))
        assertTrue(checkBearer("bearer    abc", "abc"))
        assertTrue(checkBearer("  BEARER abc  ", "abc"))
        assertFalse(checkBearer(null, "abc"))
        assertFalse(checkBearer("", "abc"))
        assertFalse(checkBearer("abc", "abc"))
        assertFalse(checkBearer("Basic abc", "abc"))
        assertFalse(checkBearer("Bearer ", "abc"))
        assertFalse(checkBearer("Bearer abcd", "abc"))
        assertFalse(checkBearer("Bearer ABC", "abc"))
        assertFalse(checkBearer("Bearer", ""))
    }

    @Test
    fun `health works without token and reports no_model`() = ApiFixture().run {
        val response = client.get("/health")
        assertEquals(200, response.status.value)
        val body = response.json()
        assertEquals("no_model", body.getValue("status").jsonPrimitive.content)
        assertEquals(0, body.getValue("queue").jsonPrimitive.int)
        assertEquals("0.1.0", body.getValue("version").jsonPrimitive.content)
        assertNull(body["model"])
    }

    @Test
    fun `health reports ok model and accelerator after first chat`() = ApiFixture().run {
        client.post("/v1/chat/completions") { chat() }
        val body = client.get("/health").json()
        assertEquals("ok", body.getValue("status").jsonPrimitive.content)
        assertEquals("m1", body.getValue("model").jsonPrimitive.content)
        assertEquals("gpu", body.getValue("accelerator").jsonPrimitive.content)
    }

    @Test
    fun `models requires token`() = ApiFixture().run {
        val response = client.get("/v1/models")
        response.assertError(401, "authentication_error", "invalid_api_key")
        assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
    }

    @Test
    fun `models lists catalog`() = ApiFixture().run {
        val body = client.get("/v1/models") { auth() }.json()
        assertEquals("list", body.getValue("object").jsonPrimitive.content)
        val data = body.getValue("data").jsonArray.map { it.jsonObject }
        assertEquals(listOf("m1", "m2"), data.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("pocketnode", "pocketnode"), data.map { it.getValue("owned_by").jsonPrimitive.content })
    }

    @Test
    fun `auth accepts lowercase scheme and extra spaces`() = ApiFixture().run {
        val response = client.get("/v1/models") { header(HttpHeaders.Authorization, "bearer   $TEST_KEY") }
        assertEquals(200, response.status.value)
    }

    @Test
    fun `auth rejects wrong scheme empty token and wrong key`() = ApiFixture().run {
        for (value in listOf("Basic $TEST_KEY", "Bearer ", "Bearer falsch", TEST_KEY, "Bearer")) {
            val response = client.get("/v1/models") { header(HttpHeaders.Authorization, value) }
            response.assertError(401, "authentication_error", "invalid_api_key")
        }
    }

    @Test
    fun `chat requires token`() = ApiFixture().run {
        val response = client.post("/v1/chat/completions") { setBody(CHAT_BODY) }
        response.assertError(401, "authentication_error", "invalid_api_key")
    }

    @Test
    fun `auth disabled when apiKey is null`() = ApiFixture(apiKey = null).run {
        assertEquals(200, client.get("/v1/models").status.value)
    }

    @Test
    fun `chat returns completion`() = ApiFixture().run {
        val response = client.post("/v1/chat/completions") { chat() }
        assertEquals(200, response.status.value)
        assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        val body = response.json()
        assertEquals("chat.completion", body.getValue("object").jsonPrimitive.content)
        assertEquals("m1", body.getValue("model").jsonPrimitive.content)
        assertTrue(body.getValue("id").jsonPrimitive.content.startsWith("chatcmpl-"))
        assertTrue(body.getValue("created").jsonPrimitive.content.toLong() > 1_700_000_000)
        val choice = body.getValue("choices").jsonArray.single().jsonObject
        assertEquals("stop", choice.getValue("finish_reason").jsonPrimitive.content)
        val message = choice.getValue("message").jsonObject
        assertEquals("assistant", message.getValue("role").jsonPrimitive.content)
        assertEquals("Hallo von PocketNode.", message.getValue("content").jsonPrimitive.content)
        val usage = body.getValue("usage").jsonObject
        assertEquals(1, usage.getValue("prompt_tokens").jsonPrimitive.int)
        assertEquals(4, usage.getValue("completion_tokens").jsonPrimitive.int)
        assertEquals(5, usage.getValue("total_tokens").jsonPrimitive.int)
    }

    @Test
    fun `chat without content type header is accepted`() = ApiFixture().run {
        val response = client.post("/v1/chat/completions") { auth(); setBody(CHAT_BODY) }
        assertEquals(200, response.status.value)
    }

    @Test
    fun `chat with max_tokens 2 returns finish_reason length`() = ApiFixture().run {
        val body = client.post("/v1/chat/completions") {
            chat("""{"messages":[{"role":"user","content":"Hi"}],"max_tokens":2}""")
        }.json()
        val choice = body.getValue("choices").jsonArray.single().jsonObject
        assertEquals("length", choice.getValue("finish_reason").jsonPrimitive.content)
        assertEquals("Hallo von", choice.getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `chat with unknown model returns 404 model_not_found`() = ApiFixture().run {
        val response = client.post("/v1/chat/completions") {
            chat("""{"model":"fehlt","messages":[{"role":"user","content":"Hi"}]}""")
        }
        val message = response.assertError(404, "invalid_request_error", "model_not_found")
        assertTrue("fehlt" in message)
    }

    @Test
    fun `invalid json returns 400 invalid_json`() = ApiFixture().run {
        client.post("/v1/chat/completions") { chat("""{"messages": [""") }
            .assertError(400, "invalid_request_error", "invalid_json")
        client.post("/v1/chat/completions") { chat("""{"messages":"kein array"}""") }
            .assertError(400, "invalid_request_error", "invalid_json")
        client.post("/v1/chat/completions") { chat("""[1,2,3]""") }
            .assertError(400, "invalid_request_error", "invalid_json")
    }

    @Test
    fun `missing messages returns 400 messages_required`() = ApiFixture().run {
        client.post("/v1/chat/completions") { chat("""{"model":"m1"}""") }
            .assertError(400, "invalid_request_error", "messages_required")
        client.post("/v1/chat/completions") { chat("""{"model":"m1","messages":[]}""") }
            .assertError(400, "invalid_request_error", "messages_required")
    }

    @Test
    fun `empty body returns 400`() = ApiFixture().run {
        client.post("/v1/chat/completions") { chat("") }
            .assertError(400, "invalid_request_error", "invalid_json")
    }

    @Test
    fun `oversized body returns 413`() = ApiFixture().run {
        val big = """{"messages":[{"role":"user","content":"${"x".repeat(4 * 1024 * 1024)}"}]}"""
        client.post("/v1/chat/completions") { chat(big) }
            .assertError(413, "invalid_request_error", "request_too_large")
    }

    @Test
    fun `queue full returns 429 with Retry-After 5`() {
        val fixture = ApiFixture(FakeBackend(tokenDelayMs = 150), EngineConfig(maxWaiting = 1))
        fixture.run {
            coroutineScope {
                val first = async { client.post("/v1/chat/completions") { chat() } }
                val second = async { client.post("/v1/chat/completions") { chat() } }
                withTimeout(5_000) {
                    while (!(fixture.engine.state.value.running && fixture.engine.state.value.waiting == 1)) delay(10)
                }
                val third = client.post("/v1/chat/completions") { chat() }
                third.assertError(429, "rate_limit_error", "queue_full")
                assertEquals("5", third.headers[HttpHeaders.RetryAfter])
                assertEquals(200, first.await().status.value)
                assertEquals(200, second.await().status.value)
            }
        }
    }

    @Test
    fun `gate rejection returns 503 with Retry-After`() =
        ApiFixture(gate = { EngineException.Unavailable("Gerät zu heiß", 30) }).run {
            val response = client.post("/v1/chat/completions") { chat() }
            val message = response.assertError(503, "server_error", "unavailable")
            assertEquals("Gerät zu heiß", message)
            assertEquals("30", response.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `backend failure returns 500 without leaking details`() =
        ApiFixture(FakeBackend(failAfterTokens = 0)).run {
            val response = client.post("/v1/chat/completions") { chat() }
            val message = response.assertError(500, "server_error", "internal_error")
            assertEquals("Interner Fehler", message)
        }

    @Test
    fun `unknown route returns 404 in error format`() = ApiFixture().run {
        client.get("/gibt/es/nicht").assertError(404, "invalid_request_error", "not_found")
    }

    @Test
    fun `wrong method returns 405 in error format`() = ApiFixture().run {
        client.get("/v1/chat/completions") { auth() }
            .assertError(405, "invalid_request_error", "method_not_allowed")
    }

    @Test
    fun `cors headers only when enabled`() {
        ApiFixture(cors = true).run {
            val response = client.get("/health") { header(HttpHeaders.Origin, "http://webui.local") }
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
            val preflight = client.options("/v1/chat/completions") {
                header(HttpHeaders.Origin, "http://webui.local")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-type")
            }
            assertEquals(200, preflight.status.value)
            assertEquals("", preflight.bodyAsText())
        }
        ApiFixture(cors = false).run {
            val response = client.get("/health") { header(HttpHeaders.Origin, "http://webui.local") }
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }
}
