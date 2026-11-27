package com.pocketnode.server.api

import com.pocketnode.server.engine.EngineException
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.SerializationException

/** Ein Fehler, wie ihn der Client sieht: HTTP-Status plus OpenAI-Fehlerfelder. */
class ApiError(
    val status: Int,
    val type: String,
    val code: String,
    message: String,
    val retryAfterSeconds: Int? = null,
) : Exception(message) {
    fun toBody(): ErrorBody = ErrorBody(ErrorDetail(message ?: "", type, code))

    companion object {
        fun badRequest(code: String, message: String) = ApiError(400, "invalid_request_error", code, message)
    }
}

private const val INVALID_REQUEST = "invalid_request_error"
private const val SERVER_ERROR = "server_error"

fun Throwable.toApiError(): ApiError = when (this) {
    is ApiError -> this
    is EngineException.QueueFull -> ApiError(429, "rate_limit_error", "queue_full", message!!, retryAfterSeconds)
    is EngineException.ModelNotFound -> ApiError(404, INVALID_REQUEST, "model_not_found", message!!)
    is EngineException.ModelNotLoaded -> ApiError(400, INVALID_REQUEST, "model_not_loaded", message!!)
    is EngineException.NoModelAvailable -> ApiError(503, SERVER_ERROR, "no_model", message!!)
    is EngineException.ModelLoadFailed -> ApiError(503, SERVER_ERROR, "model_load_failed", message!!)
    is EngineException.Unavailable -> ApiError(503, SERVER_ERROR, "unavailable", message!!, retryAfterSeconds)
    is EngineException.ContextLengthExceeded -> ApiError(400, INVALID_REQUEST, "context_length_exceeded", message!!)
    is SerializationException, is BadRequestException ->
        ApiError(400, INVALID_REQUEST, "invalid_json", "Der Request-Body ist kein gültiges JSON für diesen Endpunkt")
    // Die Ursache geht nur ins Log, nicht an den Client.
    else -> ApiError(500, SERVER_ERROR, "internal_error", "Interner Fehler")
}
