package com.pocketnode.server.engine

import com.pocketnode.server.engine.GenerationEvent.Done
import com.pocketnode.server.engine.GenerationEvent.Token
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/**
 * Setzt `max_tokens` und Stop-Sequenzen durch. Jedes [Token]-Event zählt als ein Token.
 * Text, der der Anfang einer Stop-Sequenz sein könnte, wird zurückgehalten, bis das geklärt ist.
 */
fun Flow<GenerationEvent>.withLimits(maxTokens: Int?, stop: List<String>): Flow<GenerationEvent> {
    val stops = stop.filter { it.isNotEmpty() }
    if (maxTokens == null && stops.isEmpty()) return this
    val upstream = this
    return flow {
        if (maxTokens != null && maxTokens <= 0) {
            emit(Done(FinishReason.LENGTH, null))
            return@flow
        }
        val held = StringBuilder()
        var count = 0
        emitAll(
            upstream.transformWhile { event ->
                when (event) {
                    is GenerationEvent.Started -> {
                        emit(event)
                        true
                    }
                    is Done -> {
                        if (held.isNotEmpty()) emit(Token(held.toString()))
                        emit(event)
                        false
                    }
                    is Token -> {
                        count++
                        held.append(event.text)
                        val hit = stops.map { held.indexOf(it) }.filter { it >= 0 }.minOrNull()
                        when {
                            hit != null -> {
                                if (hit > 0) emit(Token(held.substring(0, hit)))
                                emit(Done(FinishReason.STOP, null))
                                false
                            }
                            maxTokens != null && count >= maxTokens -> {
                                if (held.isNotEmpty()) emit(Token(held.toString()))
                                emit(Done(FinishReason.LENGTH, null))
                                false
                            }
                            else -> {
                                val safe = held.length - possibleStopPrefix(held, stops)
                                if (safe > 0) {
                                    emit(Token(held.substring(0, safe)))
                                    held.delete(0, safe)
                                }
                                true
                            }
                        }
                    }
                }
            },
        )
    }
}

/** Länge des längsten Text-Endes, das der Anfang einer Stop-Sequenz ist. */
private fun possibleStopPrefix(text: CharSequence, stops: List<String>): Int {
    var longest = 0
    for (stop in stops) {
        val max = minOf(stop.length - 1, text.length)
        for (k in max downTo longest + 1) {
            if (text.endsWith(stop.substring(0, k))) {
                longest = k
                break
            }
        }
    }
    return longest
}
