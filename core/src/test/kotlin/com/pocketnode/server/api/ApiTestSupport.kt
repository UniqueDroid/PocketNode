package com.pocketnode.server.api

import com.pocketnode.server.engine.AdmissionGate
import com.pocketnode.server.engine.EngineConfig
import com.pocketnode.server.engine.EngineManager
import com.pocketnode.server.engine.FakeBackend
import com.pocketnode.server.engine.InMemoryModelCatalog
import com.pocketnode.server.engine.ModelFile
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

const val TEST_KEY = "test-key"
const val CHAT_BODY = """{"model":"m1","messages":[{"role":"user","content":"Hi"}]}"""

/** Testaufbau: FakeBackend, Katalog m1/m2 und das API-Modul in einer Ktor-Testanwendung. */
class ApiFixture(
    val backend: FakeBackend = FakeBackend(),
    config: EngineConfig = EngineConfig(),
    gate: AdmissionGate = AdmissionGate.Open,
    apiKey: String? = TEST_KEY,
    cors: Boolean = false,
    streamWriteTimeoutMs: Long = 30_000,
) {
    val catalog = InMemoryModelCatalog(
        listOf(ModelFile("m1", "/models/m1.litertlm", 1), ModelFile("m2", "/models/m2.litertlm", 1)),
    )
    val engine = EngineManager(backend, catalog, config, gate)
    val deps = ApiDeps(engine, catalog, apiKey = { apiKey }, corsEnabled = { cors }, streamWriteTimeoutMs = streamWriteTimeoutMs)

    fun run(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { pocketNodeModule(deps) }
        block()
    }
}

fun HttpRequestBuilder.auth(key: String = TEST_KEY) = header(HttpHeaders.Authorization, "Bearer $key")

fun HttpRequestBuilder.chat(body: String = CHAT_BODY) {
    auth()
    header(HttpHeaders.ContentType, "application/json")
    setBody(body)
}

suspend fun HttpResponse.json(): JsonObject = ApiJson.parseToJsonElement(bodyAsText()).jsonObject

/** Prüft Status und die drei Fehlerfelder, gibt die Fehlermeldung zurück. */
suspend fun HttpResponse.assertError(status: Int, type: String, code: String): String {
    assertEquals(status, this.status.value)
    val error = json().getValue("error").jsonObject
    assertEquals(type, error.getValue("type").jsonPrimitive.content)
    assertEquals(code, error.getValue("code").jsonPrimitive.content)
    return error.getValue("message").jsonPrimitive.content
}
