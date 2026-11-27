package com.pocketnode.server.api

import com.pocketnode.server.engine.GenerationEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Die Generierung ist gescheitert, nachdem der Stream schon begonnen hatte. */
private class GenerationFailed(cause: Throwable) : Exception(cause)

/**
 * Beantwortet eine Chat-Anfrage als SSE-Stream.
 *
 * Fehler vor dem ersten Event (Warteschlange voll, Modell fehlt) werden geworfen und ergeben
 * eine normale Fehlerantwort mit Statuscode. Fehler danach werden als `data: {"error":…}` in den
 * Stream geschrieben, der dann ohne `[DONE]` endet. Trennt der Client die Verbindung, wird die
 * Generierung abgebrochen.
 *
 * [model] wird erst nach dem ersten Event gelesen, weil das Modell davor noch geladen werden kann.
 */
suspend fun ApplicationCall.respondChatStream(
    events: Flow<GenerationEvent>,
    completionId: String,
    model: () -> String,
    includeUsage: Boolean,
): Unit = coroutineScope {
    val channel = Channel<GenerationEvent>(Channel.RENDEZVOUS)
    val producer = launch {
        try {
            events.collect { channel.send(it) }
            channel.close()
        } catch (e: CancellationException) {
            channel.close(e)
            throw e
        } catch (e: Throwable) {
            channel.close(e)
        }
    }

    val first = channel.receiveCatching()
    first.exceptionOrNull()?.let { throw it }

    val created = nowSeconds()
    val modelName = model()
    fun chunk(delta: Delta, finishReason: String? = null) = ApiJson.encodeToString(
        ChatCompletionChunk(
            id = completionId,
            created = created,
            model = modelName,
            choices = listOf(ChunkChoice(delta = delta, finishReason = finishReason)),
        ),
    )

    response.header(HttpHeaders.CacheControl, "no-cache")
    response.header("X-Accel-Buffering", "no")
    try {
        respondBytesWriter(contentType = ContentType.Text.EventStream) {
            try {
                send(chunk(Delta(role = "assistant", content = "")))
                var event = first.getOrNull()
                while (event != null) {
                    when (event) {
                        is GenerationEvent.Token -> send(chunk(Delta(content = event.text)))
                        is GenerationEvent.Done -> {
                            send(chunk(Delta(), event.reason.wireName()))
                            val usage = event.usage
                            if (includeUsage && usage != null) {
                                send(
                                    ApiJson.encodeToString(
                                        ChatCompletionChunk(
                                            id = completionId,
                                            created = created,
                                            model = modelName,
                                            choices = emptyList(),
                                            usage = usage.toDto(),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                    val next = channel.receiveCatching()
                    next.exceptionOrNull()?.let { throw GenerationFailed(it) }
                    event = next.getOrNull()
                }
                send("[DONE]")
            } catch (e: GenerationFailed) {
                val cause = e.cause!!
                if (cause is CancellationException) throw cause
                val error = cause.toApiError()
                if (error.code == "internal_error") {
                    application.log.error("Generierung im laufenden Stream gescheitert", cause)
                }
                send(ApiJson.encodeToString(error.toBody()))
            } finally {
                // Greift auch, wenn das Schreiben scheitert, weil der Client die Verbindung getrennt hat.
                producer.cancel()
            }
        }
    } catch (e: Throwable) {
        // Der Writer läuft je nach Engine erst nach der Rückkehr von respondBytesWriter.
        // Deshalb wird der Producer hier nur im Fehlerfall beendet, sonst im Writer selbst.
        producer.cancel()
        throw e
    }
}

private suspend fun ByteWriteChannel.send(data: String) {
    writeStringUtf8("data: $data\n\n")
    flush()
}
