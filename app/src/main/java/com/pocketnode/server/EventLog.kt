package com.pocketnode.server

import android.util.Log
import com.pocketnode.server.api.ServerLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogEntry(val timeMillis: Long, val level: ServerLog.Level, val message: String, val detail: String?)

/** Server-Protokoll der App: schreibt nach Logcat (Tag "PocketNode") und hält die letzten Einträge für die Oberfläche. */
class EventLog(private val capacity: Int = 200) : ServerLog {
    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = mutableEntries.asStateFlow()

    override fun log(level: ServerLog.Level, message: String, error: Throwable?) {
        when (level) {
            ServerLog.Level.INFO -> Log.i(TAG, message, error)
            ServerLog.Level.WARN -> Log.w(TAG, message, error)
            ServerLog.Level.ERROR -> Log.e(TAG, message, error)
        }
        val entry = LogEntry(System.currentTimeMillis(), level, message, error?.let { "${it::class.simpleName}: ${it.message}" })
        mutableEntries.update { (it + entry).takeLast(capacity) }
    }

    companion object {
        const val TAG = "PocketNode"
    }
}
