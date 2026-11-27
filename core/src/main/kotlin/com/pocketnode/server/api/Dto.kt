package com.pocketnode.server.api

import com.pocketnode.server.engine.FinishReason
import com.pocketnode.server.engine.Usage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Gemeinsame JSON-Konfiguration der API: unbekannte Felder ignorieren, `null`-Felder weglassen. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// ---- Anfrage ----

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<MessageDto> = emptyList(),
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    /** String oder Array von Strings. */
    val stop: JsonElement? = null,
    val n: Int? = null,
    val tools: JsonElement? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

@Serializable
data class MessageDto(val role: String, val content: JsonElement? = null)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = false)

// ---- Antwort ----

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: UsageDto? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: AssistantMessage,
    @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class AssistantMessage(val role: String = "assistant", val content: String)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    val usage: UsageDto? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

@Serializable
data class ModelList(val `object`: String = "list", val data: List<ModelItem>)

@Serializable
data class ModelItem(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0,
    @SerialName("owned_by") val ownedBy: String = "pocketnode",
)

@Serializable
data class HealthResponse(
    /** `ok`, `loading`, `no_model` oder `unavailable`. */
    val status: String,
    val model: String? = null,
    val accelerator: String? = null,
    val queue: Int = 0,
    val thermal: String? = null,
    val version: String,
)

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val message: String, val type: String, val code: String)

// ---- Umrechnung ----

fun FinishReason.wireName(): String = when (this) {
    FinishReason.STOP -> "stop"
    FinishReason.LENGTH -> "length"
}

fun Usage.toDto(): UsageDto = UsageDto(promptTokens, completionTokens, promptTokens + completionTokens)
