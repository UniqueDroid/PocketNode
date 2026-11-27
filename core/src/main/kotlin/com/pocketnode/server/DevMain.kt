package com.pocketnode.server

import com.pocketnode.server.api.ApiDeps
import com.pocketnode.server.api.PocketNodeServer
import com.pocketnode.server.api.generateApiKey
import com.pocketnode.server.engine.EngineManager
import com.pocketnode.server.engine.FakeBackend
import com.pocketnode.server.engine.InMemoryModelCatalog
import com.pocketnode.server.engine.ModelFile

/**
 * Desktop-Lauf ohne echte Inferenz: startet die API mit dem FakeBackend,
 * damit sich Clients wie curl oder Open WebUI dagegen prüfen lassen.
 */
fun main() {
    val port = System.getenv("POCKETNODE_PORT")?.toIntOrNull() ?: 8080
    val apiKey = System.getenv("POCKETNODE_API_KEY")?.takeIf { it.isNotBlank() } ?: generateApiKey()
    val catalog = InMemoryModelCatalog(
        listOf(ModelFile("fake-small", "fake-small.litertlm", 0), ModelFile("fake-large", "fake-large.litertlm", 0)),
    )
    val engine = EngineManager(FakeBackend(tokenDelayMs = 40), catalog)
    val host = System.getenv("POCKETNODE_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    val server = PocketNodeServer(ApiDeps(engine, catalog, apiKey = { apiKey }), host = host, port = port)
    server.start()
    println("PocketNode (FakeBackend) started on http://$host:${server.boundPort}")
    println("API-Key: $apiKey")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
