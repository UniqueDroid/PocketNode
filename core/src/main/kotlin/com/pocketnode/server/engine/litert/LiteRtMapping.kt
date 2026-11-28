package com.pocketnode.server.engine.litert

import com.pocketnode.server.engine.ChatMessage
import com.pocketnode.server.engine.GenerationRequest
import com.pocketnode.server.engine.Role
import kotlin.random.Random

/** Eine Anfrage so zerlegt, wie LiteRT-LM sie braucht: Systemanweisung, Verlauf, neue Nutzernachricht. */
data class ConversationPlan(
    /** Alle System-Nachrichten, mit Leerzeile verbunden. */
    val systemInstruction: String?,
    /** Alle Nachrichten außer System-Nachrichten und der letzten Nutzernachricht, in Reihenfolge. */
    val history: List<ChatMessage>,
    val lastUserText: String,
    /** `null` = Vorgaben des Modells. */
    val sampler: SamplerValues?,
    val maxOutputTokens: Int?,
)

data class SamplerValues(val topK: Int, val topP: Double, val temperature: Double, val seed: Int)

private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.95
private const val DEFAULT_TEMPERATURE = 1.0

/**
 * Wirft [IllegalArgumentException], wenn die letzte Nachricht nicht vom Nutzer ist.
 * Jede Anfrage bekommt einen neuen Seed, sonst liefert dieselbe Frage immer dieselbe Antwort.
 */
fun GenerationRequest.toConversationPlan(nextSeed: () -> Int = { Random.nextInt(1, Int.MAX_VALUE) }): ConversationPlan {
    val last = messages.lastOrNull()
    require(last != null && last.role == Role.USER) { "Die letzte Nachricht muss die Rolle 'user' haben" }
    val system = messages.filter { it.role == Role.SYSTEM }.map { it.text }
    val history = messages.dropLast(1).filter { it.role != Role.SYSTEM }
    val sampler = with(sampling) {
        if (temperature == null && topP == null && topK == null) {
            null
        } else {
            SamplerValues(
                topK = topK ?: DEFAULT_TOP_K,
                topP = topP ?: DEFAULT_TOP_P,
                temperature = temperature ?: DEFAULT_TEMPERATURE,
                seed = nextSeed(),
            )
        }
    }
    return ConversationPlan(
        systemInstruction = system.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        history = history,
        lastUserText = last.text,
        sampler = sampler,
        maxOutputTokens = maxTokens,
    )
}
