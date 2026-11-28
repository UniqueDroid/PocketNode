package com.pocketnode.server.service

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.pocketnode.server.api.ServerLog
import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.FakeBackend
import com.pocketnode.server.engine.InMemoryModelCatalog
import com.pocketnode.server.engine.LoadedModel
import com.pocketnode.server.engine.ModelFile
import com.pocketnode.server.settings.AcceleratorPreference
import com.pocketnode.server.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.pocketnode.server.engine.InferenceBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerControllerTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient.newHttpClient()
    private val catalog = InMemoryModelCatalog(listOf(ModelFile("m1", "/m1", 1), ModelFile("m2", "/m2", 1)))
    private var backend = FakeBackend()
    private val controllers = mutableListOf<ServerController>()
    private val settings by lazy {
        run {
            var first = true
            SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") }) {
                if (first) "pn-test".also { first = false } else "pn-neu"
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun controller(backendFactory: () -> InferenceBackend = { backend }): ServerController =
        ServerController(settings, catalog, backendFactory, { 2 }, ServerLog { _, _, _ -> }, scope, host = "127.0.0.1")
            .also { controllers += it }

    private fun get(port: Int, path: String, key: String? = "pn-test"): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .apply { if (key != null) header("Authorization", "Bearer $key") }
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun chat(port: Int, key: String? = "pn-test"): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
            .apply { if (key != null) header("Authorization", "Bearer $key") }
            .POST(HttpRequest.BodyPublishers.ofString("""{"messages":[{"role":"user","content":"Hi"}]}"""))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private suspend fun ServerController.awaitStatus(predicate: (ServerStatus) -> Boolean): ServerStatus =
        withTimeout(5_000) { status.first(predicate) }

    @AfterTest
    fun tearDown() = runBlocking {
        controllers.forEach { it.stop() }
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun `start runs the server and answers health`() = runBlocking {
        val port = freePort()
        settings.update { it.copy(port = port) }
        val controller = controller()
        assertEquals(ServerStatus.Stopped, controller.status.value)
        controller.start()
        assertEquals(port, (controller.status.value as ServerStatus.Running).port)
        assertEquals(200, get(port, "/health", key = null).statusCode())
    }

    @Test
    fun `start twice keeps one server`() = runBlocking {
        val port = freePort()
        settings.update { it.copy(port = port) }
        val controller = controller()
        controller.start()
        controller.start()
        assertEquals(200, get(port, "/health").statusCode())
    }

    @Test
    fun `start uses stored settings not defaults`() = runBlocking {
        val port = freePort()
        settings.update { it.copy(port = port, autoSwitch = false) }
        val controller = controller()
        controller.start()
        assertEquals(401, get(port, "/v1/models", key = "falsch").statusCode())
        assertEquals(200, get(port, "/v1/models").statusCode())
    }

    @Test
    fun `taken port yields Failed and leaves nothing running`() = runBlocking {
        ServerSocket(0).use { blocker ->
            settings.update { it.copy(port = blocker.localPort) }
            val controller = controller()
            controller.start()
            val failed = assertIs<ServerStatus.Failed>(controller.status.value)
            assertTrue(blocker.localPort.toString() in failed.reason)
        }
        assertEquals(0, backend.loadCalls.size)
    }

    @Test
    fun `stop releases the port and unloads the model`() = runBlocking {
        val port = freePort()
        settings.update { it.copy(port = port) }
        val controller = controller()
        controller.start()
        assertEquals(200, chat(port).statusCode())
        controller.stop()
        assertEquals(ServerStatus.Stopped, controller.status.value)
        assertTrue(backend.unloadCalls >= 1)
        ServerSocket(port).close()
    }

    @Test
    fun `selected model is loaded after start`() = runBlocking {
        settings.update { it.copy(port = freePort(), selectedModel = "m2") }
        val controller = controller()
        controller.start()
        val running = controller.awaitStatus { (it as? ServerStatus.Running)?.engine?.loaded != null } as ServerStatus.Running
        assertEquals(LoadedModel("m2", Accelerator.GPU, 4096), running.engine.loaded)
    }

    @Test
    fun `cpu preference loads on the cpu`() = runBlocking {
        settings.update { it.copy(port = freePort(), selectedModel = "m1", accelerator = AcceleratorPreference.CPU) }
        val controller = controller()
        controller.start()
        val running = controller.awaitStatus { (it as? ServerStatus.Running)?.engine?.loaded != null } as ServerStatus.Running
        assertEquals(Accelerator.CPU, running.engine.loaded?.accelerator)
    }

    @Test
    fun `failing selected model keeps the server running`() = runBlocking {
        backend = FakeBackend(failLoadFor = setOf("m2"))
        val port = freePort()
        settings.update { it.copy(port = port, selectedModel = "m2") }
        val controller = controller()
        controller.start()
        controller.awaitStatus { (it as? ServerStatus.Running)?.engine?.lastError != null }
        assertEquals(200, get(port, "/health").statusCode())
    }

    @Test
    fun `missing selected model is ignored`() = runBlocking {
        val port = freePort()
        settings.update { it.copy(port = port, selectedModel = "gibt-es-nicht") }
        val controller = controller()
        controller.start()
        assertIs<ServerStatus.Running>(controller.status.value)
        assertEquals(0, backend.loadCalls.size)
        assertEquals(200, chat(port).statusCode())
    }

    @Test
    fun `regenerated key applies without restart`() = runBlocking {
        val port = freePort()
        settings.update { it.copy(port = port) }
        val controller = controller()
        controller.start()
        assertEquals(200, get(port, "/v1/models", key = "pn-test").statusCode())
        val newKey = settings.regenerateApiKey()
        assertEquals(401, get(port, "/v1/models", key = "pn-test").statusCode())
        assertEquals(200, get(port, "/v1/models", key = newKey).statusCode())
    }

    @Test
    fun `authDisabled allows requests without key`() = runBlocking {
        val port = freePort()
        settings.update { it.copy(port = port, authDisabled = true) }
        controller().start()
        assertEquals(200, get(port, "/v1/models", key = null).statusCode())
    }

    @Test
    fun `restart applies a changed port`() = runBlocking {
        val first = freePort()
        settings.update { it.copy(port = first) }
        val controller = controller()
        controller.start()
        val second = freePort()
        settings.update { it.copy(port = second) }
        controller.restart()
        assertEquals(second, (controller.status.value as ServerStatus.Running).port)
        assertEquals(200, get(second, "/health").statusCode())
        ServerSocket(first).close()
    }

    @Test
    fun `loadModel switches the model and stores the selection`() = runBlocking {
        settings.update { it.copy(port = freePort()) }
        val controller = controller()
        controller.start()
        controller.loadModel("m2")
        assertEquals("m2", (controller.status.value as ServerStatus.Running).engine.loaded?.id)
        assertEquals("m2", settings.current().selectedModel)
    }

    @Test
    fun `loadModel requires a running server`() = runBlocking {
        assertFailsWith<IllegalStateException> { controller().loadModel("m1") }
    }

    @Test
    fun `status mirrors engine state`() = runBlocking {
        val port = freePort()
        settings.update { it.copy(port = port) }
        val controller = controller()
        controller.start()
        assertEquals(200, chat(port).statusCode())
        val running = controller.awaitStatus { (it as? ServerStatus.Running)?.engine?.loaded != null } as ServerStatus.Running
        assertEquals("m1", running.engine.loaded?.id)
    }

    @Test
    fun `start completes even when the caller is cancelled`() = runBlocking {
        val port = freePort()
        settings.update { it.copy(port = port) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = controller {
            entered.countDown()
            release.await()
            backend
        }
        val caller = launch(Dispatchers.Default) { controller.start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        caller.cancel()
        release.countDown()
        caller.join()
        controller.awaitStatus { it is ServerStatus.Running }
        assertEquals(200, get(port, "/health").statusCode())
        controller.stop()
        ServerSocket(port).close()
    }

    @Test
    fun `restartInBackground applies changes without a caller scope`() = runBlocking {
        val first = freePort()
        settings.update { it.copy(port = first) }
        val controller = controller()
        controller.start()
        val second = freePort()
        settings.update { it.copy(port = second) }
        controller.restartInBackground().join()
        assertEquals(second, (controller.status.value as ServerStatus.Running).port)
    }
}
