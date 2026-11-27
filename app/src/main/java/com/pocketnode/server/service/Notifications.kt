package com.pocketnode.server.service

/** Texte der Benachrichtigung, aus den String-Ressourcen. Als eigener Typ, damit [notificationText] ohne Android testbar ist. */
class NotificationTexts(
    val stopped: String,
    val starting: String,
    val running: (address: String, model: String, accelerator: String) -> String,
    val noModel: String,
    val loading: (model: String) -> String,
    val failed: (reason: String) -> String,
)

fun notificationText(status: ServerStatus, address: String?, texts: NotificationTexts): String = when (status) {
    ServerStatus.Stopped -> texts.stopped
    ServerStatus.Starting -> texts.starting
    is ServerStatus.Failed -> texts.failed(status.reason)
    is ServerStatus.Running -> {
        val engine = status.engine
        val model = engine.loaded?.id ?: engine.loading?.let(texts.loading) ?: texts.noModel
        val accelerator = engine.loaded?.accelerator?.name ?: "–"
        texts.running("${address.orEmpty()}:${status.port}", model, accelerator)
    }
}
