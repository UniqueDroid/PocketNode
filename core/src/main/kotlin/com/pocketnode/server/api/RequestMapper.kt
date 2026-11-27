package com.pocketnode.server.api

import com.pocketnode.server.engine.ChatMessage
import com.pocketnode.server.engine.GenerationRequest
import com.pocketnode.server.engine.Role
import com.pocketnode.server.engine.Sampling
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_STOP_SEQUENCES = 4

/** Prüft die Anfrage und übersetzt sie für die Engine. Wirft [ApiError] mit Status 400. */
fun ChatCompletionRequest.toGenerationRequest(): GenerationRequest {
    if (messages.isEmpty()) {
        throw ApiError.badRequest("messages_required", "'messages' muss mindestens eine Nachricht enthalten")
    }
    if (n != null && n > 1) {
        throw ApiError.badRequest("unsupported_parameter", "'n' größer als 1 wird nicht unterstützt")
    }
    if (tools != null && tools !is JsonNull && !(tools is JsonArray && tools.isEmpty())) {
        throw ApiError.badRequest("unsupported_parameter", "'tools' wird nicht unterstützt")
    }
    val limit = maxCompletionTokens ?: maxTokens
    if (limit != null && limit < 0) {
        throw ApiError.badRequest("invalid_max_tokens", "Das Token-Limit darf nicht negativ sein")
    }
    if (temperature != null && temperature !in 0.0..2.0) {
        throw ApiError.badRequest("invalid_sampling", "'temperature' muss zwischen 0 und 2 liegen")
    }
    if (topP != null && topP !in 0.0..1.0) {
        throw ApiError.badRequest("invalid_sampling", "'top_p' muss zwischen 0 und 1 liegen")
    }
    if (topK != null && topK < 1) {
        throw ApiError.badRequest("invalid_sampling", "'top_k' muss mindestens 1 sein")
    }
    return GenerationRequest(
        messages = messages.map { ChatMessage(it.role.toRole(), it.content.toText()) },
        maxTokens = limit,
        sampling = Sampling(temperature, topP, topK),
        stop = stop.toStopList(),
    )
}

private fun String.toRole(): Role = when (this) {
    "system" -> Role.SYSTEM
    "user" -> Role.USER
    "assistant" -> Role.ASSISTANT
    else -> throw ApiError.badRequest("unsupported_role", "Die Rolle '$this' wird nicht unterstützt")
}

private fun JsonElement?.toText(): String {
    fun unsupported(): Nothing =
        throw ApiError.badRequest("unsupported_content", "'content' muss Text sein (String oder Text-Teile)")
    return when {
        this is JsonPrimitive && isString -> content
        this is JsonArray -> joinToString("\n") { part ->
            val obj = part as? JsonObject ?: unsupported()
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type != "text" || text == null) unsupported()
            text
        }
        else -> unsupported()
    }
}

private fun JsonElement?.toStopList(): List<String> {
    fun invalid(): Nothing = throw ApiError.badRequest(
        "invalid_stop",
        "'stop' muss ein String oder ein Array aus höchstens $MAX_STOP_SEQUENCES Strings sein",
    )
    val raw = when {
        this == null || this is JsonNull -> emptyList()
        this is JsonPrimitive && isString -> listOf(content)
        this is JsonArray -> map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() }
        else -> invalid()
    }
    if (raw.size > MAX_STOP_SEQUENCES) invalid()
    return raw.filter { it.isNotEmpty() }
}
