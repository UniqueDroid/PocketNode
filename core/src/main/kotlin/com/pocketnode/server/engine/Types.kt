package com.pocketnode.server.engine

enum class Accelerator { CPU, GPU, NPU }

/** Eine installierte Modelldatei. [id] ist der Name, unter dem Clients das Modell ansprechen. */
data class ModelFile(val id: String, val path: String, val sizeBytes: Long)

/** Ergebnis eines Ladevorgangs. [accelerator] ist der tatsächlich aktive Beschleuniger. */
data class LoadedModel(val id: String, val accelerator: Accelerator, val contextTokens: Int)

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val text: String)

data class Sampling(val temperature: Double? = null, val topP: Double? = null, val topK: Int? = null)

data class GenerationRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int? = null,
    val sampling: Sampling = Sampling(),
    val stop: List<String> = emptyList(),
)

enum class FinishReason { STOP, LENGTH }

data class Usage(val promptTokens: Int, val completionTokens: Int)

sealed interface GenerationEvent {
    data class Token(val text: String) : GenerationEvent
    data class Done(val reason: FinishReason, val usage: Usage?) : GenerationEvent
}
