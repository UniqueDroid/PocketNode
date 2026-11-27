package com.pocketnode.server.api

import com.pocketnode.server.engine.FakeBackend
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PocketNodeServerTest {
    private val servers = mutableListOf<PocketNodeServer>()
    private val http = HttpClient.newHttpClient()

    private fun server(fixture: ApiFixture = ApiFixture(), port: Int = 0) =
        PocketNodeServer(fixture.deps, host = "127.0.0.1", port = port).also { servers += it }

    private fun health(port: Int): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @AfterTest
    fun stopAll() = servers.forEach { it.stop() }

    @Test
    fun `starts on free port and answers health`() {
        val server = server()
        assertFalse(server.isRunning)
        server.start()
        assertTrue(server.isRunning)
        assertTrue(server.boundPort > 0)
        val response = health(server.boundPort)
        assertEquals(200, response.statusCode())
        assertTrue("\"status\":\"no_model\"" in response.body())
    }

    @Test
    fun `start twice is a no-op`() {
        val server = server()
        server.start()
        val port = server.boundPort
        server.start()
        assertEquals(port, server.boundPort)
        assertEquals(200, health(port).statusCode())
    }

    @Test
    fun `stop releases the port`() {
        val first = server()
        first.start()
        val port = first.boundPort
        first.stop()
        assertFalse(first.isRunning)
        first.stop()
        val second = server(port = port)
        second.start()
        assertEquals(200, health(port).statusCode())
    }

    @Test
    fun `start fails clearly when the port is taken`() {
        val first = server()
        first.start()
        val second = server(port = first.boundPort)
        runCatching { second.start() }
        assertFalse(second.isRunning)
        assertEquals(200, health(first.boundPort).statusCode())
    }

    @Test
    fun `client disconnect stops generation`() {
        val fixture = ApiFixture(FakeBackend(tokens = List(50) { "x" }, tokenDelayMs = 100))
        val server = server(fixture)
        server.start()
        val body = """{"stream":true,"messages":[{"role":"user","content":"Hi"}]}"""
        Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.getOutputStream().write(
                (
                    "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" +
                        "Authorization: Bearer $TEST_KEY\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
                    ).toByteArray(),
            )
            val reader = socket.getInputStream().bufferedReader()
            val seen = StringBuilder()
            while ("\"content\":\"x\"" !in seen) seen.append(reader.readLine() ?: break).append('\n')
            assertTrue(seen.startsWith("HTTP/1.1 200"), seen.toString())
            assertEquals(1, fixture.backend.activeGenerations)
        }
        // Die Generierung würde noch rund 5 s laufen. Nach dem Trennen muss sie zügig enden.
        val deadline = System.currentTimeMillis() + 2_000
        while (fixture.backend.activeGenerations > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(0, fixture.backend.activeGenerations)
        assertFalse(fixture.engine.state.value.running)
    }

    @Test
    fun `generateApiKey has prefix and is unique`() {
        val a = generateApiKey()
        val b = generateApiKey()
        assertTrue(a.startsWith("pn-"))
        assertTrue(a.length >= 40)
        assertTrue(a.matches(Regex("pn-[A-Za-z0-9_-]+")))
        assertNotEquals(a, b)
    }
}
