package com.pocketnode.server.api

/** Protokoll des Servers. Auf dem Desktop geht es nach stderr, in der App nach Logcat und in die Ereignisliste. */
fun interface ServerLog {
    fun log(level: Level, message: String, error: Throwable?)

    enum class Level { INFO, WARN, ERROR }

    companion object {
        val Stderr = ServerLog { level, message, error ->
            System.err.println("$level $message")
            error?.printStackTrace()
        }
    }
}
