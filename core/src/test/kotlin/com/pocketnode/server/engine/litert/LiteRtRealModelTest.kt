package com.pocketnode.server.engine.litert

import com.pocketnode.server.api.ServerLog
import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.ChatMessage
import com.pocketnode.server.engine.EngineException
import com.pocketnode.server.engine.FinishReason
import com.pocketnode.server.engine.GenerationEvent
import com.pocketnode.server.engine.GenerationRequest
import com.pocketnode.server.engine.ModelFile
import com.pocketnode.server.engine.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Läuft nur mit `POCKETNODE_TEST_MODEL=/pfad/zum/modell.litertlm`. Prüft das Backend gegen die echte Engine auf der CPU.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiteRtRealModelTest {
    private val path: String? = System.getenv("POCKETNODE_TEST_MODEL")
    private lateinit var backend: LiteRtLmBackend
    private var contextTokens = 0

    @BeforeAll
    fun loadModel() {
        assumeTrue(path != null && File(path).isFile, "POCKETNODE_TEST_MODEL nicht gesetzt")
        backend = LiteRtLmBackend(cacheDir = System.getProperty("java.io.tmpdir"), log = ServerLog.Stderr)
        runBlocking {
            val loaded = backend.load(ModelFile("test", path!!, File(path).length()), Accelerator.CPU, 4)
            assertEquals(Accelerator.CPU, loaded.accelerator)
            contextTokens = loaded.contextTokens
        }
    }

    @AfterAll
    fun unload() {
        if (::backend.isInitialized) runBlocking { backend.unload() }
    }

    private fun ask(vararg messages: ChatMessage, maxTokens: Int? = 48) =
        GenerationRequest(messages.toList(), maxTokens = maxTokens)

    private fun List<GenerationEvent>.text() =
        filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }

    @Test
    fun `real model answers on CPU`() = runBlocking {
        val events = backend.generate(ask(ChatMessage(Role.USER, "Say hello in one short sentence."))).toList()
        println("Antwort: ${events.text()}")
        assertTrue(events.text().isNotBlank())
        assertTrue(events.last() is GenerationEvent.Done)
        assertTrue(contextTokens > 0, "contextTokens = $contextTokens")
    }

    @Test
    fun `real model respects maxTokens`() = runBlocking {
        val events = backend.generate(ask(ChatMessage(Role.USER, "Count from one to one hundred."), maxTokens = 5)).toList()
        val done = events.last() as GenerationEvent.Done
        println("Gekürzt: '${events.text()}' usage=${done.usage}")
        assertEquals(FinishReason.LENGTH, done.reason)
        assertTrue((done.usage?.completionTokens ?: 0) <= 5)
    }

    @Test
    fun `real model receives the system instruction`() = runBlocking {
        // Ein sehr kleines Testmodell befolgt Anweisungen nicht zuverlässig. Geprüft wird deshalb, dass die
        // Systemanweisung in den Prompt eingeht. Der Vergleich ohne Systemanweisung taugt nicht: Manche
        // Chat-Templates setzen dann eine eigene Standardanweisung ein. Verglichen werden zwei Anweisungen
        // unterschiedlicher Länge.
        suspend fun promptTokens(system: String) = (
            backend.generate(
                ask(ChatMessage(Role.SYSTEM, system), ChatMessage(Role.USER, "Is the sky blue?"), maxTokens = 4),
            ).toList().last() as GenerationEvent.Done
            ).usage!!.promptTokens
        val short = promptTokens("Answer briefly.")
        val long = promptTokens("Answer briefly. " + "Be precise, friendly and calm in every reply. ".repeat(5))
        println("Prompt-Tokens kurze/lange Systemanweisung: $short/$long")
        assertTrue(long >= short + 30, "kurz=$short lang=$long")
    }

    @Test
    fun `too long input fails with ContextLengthExceeded`() = runBlocking {
        val error = assertFailsWith<EngineException.ContextLengthExceeded> {
            backend.generate(ask(ChatMessage(Role.USER, "word ".repeat(contextTokens + 200)))).toList()
        }
        assertEquals(contextTokens, error.limitTokens)
    }

    @Test
    fun `real model uses the history`() = runBlocking {
        val events = backend.generate(
            ask(
                ChatMessage(Role.USER, "My name is Jan. Please remember it."),
                ChatMessage(Role.ASSISTANT, "Nice to meet you, Jan. I will remember your name."),
                ChatMessage(Role.USER, "What is my name? Answer with the name only."),
                maxTokens = 8,
            ),
        ).toList()
        println("Verlauf: ${events.text()}")
        assertTrue("jan" in events.text().lowercase())
    }

    @Test
    fun `real model can be cancelled`() = runBlocking {
        val first = backend.generate(ask(ChatMessage(Role.USER, "Write a long story about a fox."), maxTokens = 400))
            .first { it is GenerationEvent.Token }
        assertTrue(first is GenerationEvent.Token)
        val next = backend.generate(ask(ChatMessage(Role.USER, "Say OK."), maxTokens = 8)).toList()
        assertTrue(next.last() is GenerationEvent.Done)
    }

    @Test
    fun `real model reports usage`() = runBlocking {
        val done = backend.generate(ask(ChatMessage(Role.USER, "Say OK."), maxTokens = 8)).toList().last() as GenerationEvent.Done
        println("Usage: ${done.usage}")
        val usage = done.usage
        assertTrue(usage != null && usage.promptTokens > 0 && usage.completionTokens > 0, "usage = $usage")
    }

    @Test
    fun `corrupt model file fails to load`() = runBlocking {
        val broken = File.createTempFile("kaputt", ".litertlm").apply { writeText("kein modell") }
        val empty = File.createTempFile("leer", ".litertlm")
        val other = LiteRtLmBackend(log = ServerLog { _, _, _ -> })
        for (file in listOf(broken, empty)) {
            assertFailsWith<IllegalStateException> { other.load(ModelFile("kaputt", file.path, file.length()), Accelerator.CPU, 2) }
        }
        broken.delete()
        empty.delete()
    }
}
