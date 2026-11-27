package com.pocketnode.server.api

import com.pocketnode.server.engine.GenerationEvent
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.flow.Flow

/** Vorläufig: Streaming folgt in Task 6. */
suspend fun ApplicationCall.respondChatStream(
    events: Flow<GenerationEvent>,
    completionId: String,
    model: () -> String,
    includeUsage: Boolean,
): Unit = throw ApiError.badRequest("unsupported_parameter", "'stream' wird noch nicht unterstützt")
