package com.pocketnode.server.api

import com.pocketnode.server.engine.EngineManager
import com.pocketnode.server.engine.FinishReason
import com.pocketnode.server.engine.GenerationEvent
import com.pocketnode.server.engine.ModelCatalog
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Alles, was die API von außen braucht. Die Lambdas werden bei jeder Anfrage neu gelesen. */
class ApiDeps(
    val engine: EngineManager,
    val catalog: ModelCatalog,
    /** `null` schaltet die Auth ab. */
    val apiKey: () -> String?,
    /** Wird beim Start des Servers gelesen. Eine Änderung braucht einen Neustart des Servers. */
    val corsEnabled: () -> Boolean = { false },
    val thermal: () -> String? = { null },
    val version: String = "0.1.0",
)

/** Größte erlaubte Anfrage in Bytes. */
const val MAX_BODY_BYTES: Long = 4L * 1024 * 1024

fun Application.pocketNodeModule(deps: ApiDeps) {
    if (deps.corsEnabled()) {
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowNonSimpleContentTypes = true
            allowMethod(HttpMethod.Options)
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            val error = cause.toApiError()
            if (error.status >= 500 && error.code == "internal_error") {
                call.application.log.error("Unerwarteter Fehler bei ${call.request.local.uri}", cause)
            }
            call.respondError(error)
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(ApiError(404, "invalid_request_error", "not_found", "Diesen Endpunkt gibt es nicht"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondError(
                ApiError(405, "invalid_request_error", "method_not_allowed", "Diese Methode ist hier nicht erlaubt"),
            )
        }
    }
    routing {
        get("/health") {
            val state = deps.engine.state.value
            val status = when {
                state.loading != null -> "loading"
                state.loaded != null -> "ok"
                else -> "no_model"
            }
            call.respondJson(
                ApiJson.encodeToString(
                    HealthResponse(
                        status = status,
                        model = state.loaded?.id ?: state.loading,
                        accelerator = state.loaded?.accelerator?.name?.lowercase(),
                        queue = state.waiting,
                        thermal = deps.thermal(),
                        version = deps.version,
                    ),
                ),
            )
        }
        route("/v1") {
            get("/models") {
                deps.requireAuth(call)
                val models = deps.catalog.list().map { ModelItem(id = it.id) }
                call.respondJson(ApiJson.encodeToString(ModelList(data = models)))
            }
            post("/chat/completions") {
                deps.requireAuth(call)
                val request = ApiJson.decodeFromString<ChatCompletionRequest>(call.readLimitedBody())
                val generation = request.toGenerationRequest()
                val modelId = request.model?.takeIf { it.isNotBlank() }
                val completionId = "chatcmpl-${UUID.randomUUID()}"
                val events = deps.engine.generate(modelId, generation)

                if (request.stream) {
                    call.respondChatStream(
                        events = events,
                        completionId = completionId,
                        model = { deps.engine.state.value.loaded?.id ?: modelId ?: "unknown" },
                        includeUsage = request.streamOptions?.includeUsage == true,
                    )
                    return@post
                }

                val text = StringBuilder()
                var done: GenerationEvent.Done? = null
                events.collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> text.append(event.text)
                        is GenerationEvent.Done -> done = event
                    }
                }
                val response = ChatCompletionResponse(
                    id = completionId,
                    created = nowSeconds(),
                    model = deps.engine.state.value.loaded?.id ?: modelId ?: "unknown",
                    choices = listOf(
                        Choice(
                            message = AssistantMessage(content = text.toString()),
                            finishReason = (done?.reason ?: FinishReason.STOP).wireName(),
                        ),
                    ),
                    usage = done?.usage?.toDto(),
                )
                call.respondJson(ApiJson.encodeToString(response))
            }
        }
    }
}

internal fun nowSeconds(): Long = System.currentTimeMillis() / 1000

private fun ApiDeps.requireAuth(call: ApplicationCall) {
    val expected = apiKey() ?: return
    if (!checkBearer(call.request.header(HttpHeaders.Authorization), expected)) {
        throw ApiError(401, "authentication_error", "invalid_api_key", "Der API-Key fehlt oder ist falsch")
    }
}

/** Liest den Body als Text und bricht bei mehr als [MAX_BODY_BYTES] mit 413 ab. */
private suspend fun ApplicationCall.readLimitedBody(): String {
    fun tooLarge(): Nothing = throw ApiError(
        413, "invalid_request_error", "request_too_large", "Die Anfrage ist größer als ${MAX_BODY_BYTES / (1024 * 1024)} MiB",
    )
    val declared = request.contentLength()
    if (declared != null && declared > MAX_BODY_BYTES) tooLarge()
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) tooLarge()
    return bytes.toString(Charsets.UTF_8)
}

internal suspend fun ApplicationCall.respondJson(json: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(json, ContentType.Application.Json, status)

internal suspend fun ApplicationCall.respondError(error: ApiError) {
    error.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    if (error.status == 401) response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    respondJson(ApiJson.encodeToString(error.toBody()), HttpStatusCode.fromValue(error.status))
}
