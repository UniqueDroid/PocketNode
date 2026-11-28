package com.pocketnode.server

import com.pocketnode.server.api.ApiDeps
import com.pocketnode.server.api.PocketNodeServer
import com.pocketnode.server.api.generateApiKey
import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.DirectoryModelCatalog
import com.pocketnode.server.engine.EngineConfig
import com.pocketnode.server.engine.EngineManager
import com.pocketnode.server.engine.InferenceBackend
import com.pocketnode.server.engine.ModelCatalog
import com.pocketnode.server.engine.litert.LiteRtLmBackend
import java.io.File
import com.pocketnode.server.engine.FakeBackend
import com.pocketnode.server.engine.InMemoryModelCatalog
import com.pocketnode.server.engine.ModelFile

/**
 * Desktop-Lauf. Ohne `POCKETNODE_MODELS_DIR` mit dem FakeBackend, mit der Variable mit LiteRT-LM auf der CPU
 * und den `.litertlm`-Dateien aus diesem Ordner. LiteRT-LM 0.20.0 braucht dafür JDK 25.
 */
fun main() {
    val port = System.getenv("POCKETNODE_PORT")?.toIntOrNull() ?: 8080
    val apiKey = System.getenv("POCKETNODE_API_KEY")?.takeIf { it.isNotBlank() } ?: generateApiKey()
    val host = System.getenv("POCKETNODE_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    val modelsDir = System.getenv("POCKETNODE_MODELS_DIR")?.takeIf { it.isNotBlank() }?.let(::File)

    val catalog: ModelCatalog
    val backend: InferenceBackend
    val config: EngineConfig
    if (modelsDir != null) {
        catalog = DirectoryModelCatalog(modelsDir)
        backend = LiteRtLmBackend(cacheDir = File(System.getProperty("java.io.tmpdir"), "pocketnode-cache").apply { mkdirs() }.path)
        config = EngineConfig(preferred = Accelerator.CPU, cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
    } else {
        catalog = InMemoryModelCatalog(
            listOf(ModelFile("fake-small", "fake-small.litertlm", 0), ModelFile("fake-large", "fake-large.litertlm", 0)),
        )
        backend = FakeBackend(tokenDelayMs = 40)
        config = EngineConfig()
    }
    val engine = EngineManager(backend, catalog, config)
    val server = PocketNodeServer(ApiDeps(engine, catalog, apiKey = { apiKey }), host = host, port = port)
    server.start()
    println("PocketNode (${backend.name}) started on http://$host:${server.boundPort}")
    if (modelsDir != null) println("Models in ${modelsDir.absolutePath}: ${catalog.list().joinToString { it.id }.ifEmpty { "none" }}")
    println("API key: $apiKey")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
