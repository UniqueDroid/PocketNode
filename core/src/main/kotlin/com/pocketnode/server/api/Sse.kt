package com.pocketnode.server.api

import com.pocketnode.server.engine.GenerationEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString

/** Die Generierung ist gescheitert, nachdem der Stream schon begonnen hatte. */
private class GenerationFailed(cause: Throwable) : Exception(cause)

/**
 * Beantwortet eine Chat-Anfrage als SSE-Stream.
 *
 * Fehler vor dem ersten Event (Warteschlange voll, Modell fehlt) werden geworfen und ergeben
 * eine normale Fehlerantwort mit Statuscode. Fehler danach werden als `data: {"error":…}` in den
 * Stream geschrieben, der dann ohne `[DONE]` endet. Trennt der Client die Verbindung oder nimmt er
 * ein Event länger als [writeTimeoutMs] nicht ab, wird die Generierung abgebrochen.
 *
 * Der Modellname kommt aus dem [GenerationEvent.Started]-Event. Die Antwort beginnt erst mit dem
 * ersten Event danach, damit auch ein Fehler vor dem ersten Token noch einen Statuscode bekommt.
 */
suspend fun ApplicationCall.respondChatStream(
    events: Flow<GenerationEvent>,
    completionId: String,
    includeUsage: Boolean,
    writeTimeoutMs: Long = 30_000,
    log: ServerLog = ServerLog.Stderr,
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

    var modelName = "unknown"
    var first = channel.receiveCatching()
    while (true) {
        first.exceptionOrNull()?.let { throw it }
        val event = first.getOrNull()
        if (event !is GenerationEvent.Started) break
        modelName = event.model.id
        first = channel.receiveCatching()
    }

    val created = nowSeconds()
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
            suspend fun send(data: String) = withTimeout(writeTimeoutMs) {
                writeStringUtf8("data: $data\n\n")
                flush()
            }
            try {
                send(chunk(Delta(role = "assistant", content = "")))
                var event = first.getOrNull()
                while (event != null) {
                    when (event) {
                        is GenerationEvent.Started -> Unit
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
                    log.log(ServerLog.Level.ERROR, "Generierung im laufenden Stream gescheitert", cause)
                }
                send(ApiJson.encodeToString(error.toBody()))
            } catch (e: TimeoutCancellationException) {
                // Der Client liest nicht mehr. Der Stream wird aufgegeben, damit die Engine frei wird.
                log.log(ServerLog.Level.WARN, "Stream aufgegeben: Client hat $writeTimeoutMs ms lang nichts abgenommen", null)
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
