package com.pocketnode.server.engine

sealed class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class QueueFull(val retryAfterSeconds: Int) :
        EngineException("Die Warteschlange ist voll")

    class ModelNotFound(val modelId: String) :
        EngineException("Das Modell '$modelId' ist nicht installiert")

    /** Ein anderes Modell wurde verlangt, der automatische Wechsel ist aber abgeschaltet. */
    class ModelNotLoaded(val modelId: String) :
        EngineException("Das Modell '$modelId' ist nicht geladen und der automatische Modellwechsel ist abgeschaltet")

    class NoModelAvailable :
        EngineException("Es ist kein Modell geladen und keines installiert")

    class ModelLoadFailed(val modelId: String, cause: Throwable) :
        EngineException("Das Modell '$modelId' konnte nicht geladen werden: ${cause.message}", cause)

    class Unavailable(val reason: String, val retryAfterSeconds: Int) :
        EngineException(reason)

    class ContextLengthExceeded(val limitTokens: Int) :
        EngineException("Die Eingabe ist länger als das Kontextfenster von $limitTokens Tokens")
}
