package com.pocketnode.server.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketnode.server.api.generateApiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Einstellungen der App im Preferences-DataStore. Der API-Key entsteht beim ersten Lesen. */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val newKey: () -> String = ::generateApiKey,
) {
    private object Keys {
        val port = intPreferencesKey("port")
        val apiKey = stringPreferencesKey("api_key")
        val authDisabled = booleanPreferencesKey("auth_disabled")
        val selectedModel = stringPreferencesKey("selected_model")
        val accelerator = stringPreferencesKey("accelerator")
        val autoSwitch = booleanPreferencesKey("auto_switch")
        val corsEnabled = booleanPreferencesKey("cors_enabled")
        val serverWanted = booleanPreferencesKey("server_wanted")
    }

    val settings: Flow<Settings> = flow {
        ensureApiKey()
        emitAll(dataStore.data.map { it.toSettings() })
    }

    suspend fun current(): Settings = settings.first()

    /** Wirft [IllegalArgumentException] bei einem Port außerhalb von 1024 bis 65535. Dann wird nichts gespeichert. */
    suspend fun update(transform: (Settings) -> Settings) {
        ensureApiKey()
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            require(next.port in Settings.PORT_RANGE) { "Der Port muss zwischen 1024 und 65535 liegen" }
            require(next.apiKey.isNotBlank()) { "Der API-Key darf nicht leer sein" }
            prefs[Keys.port] = next.port
            prefs[Keys.apiKey] = next.apiKey
            prefs[Keys.authDisabled] = next.authDisabled
            if (next.selectedModel != null) prefs[Keys.selectedModel] = next.selectedModel else prefs.remove(Keys.selectedModel)
            prefs[Keys.accelerator] = next.accelerator.name
            prefs[Keys.autoSwitch] = next.autoSwitch
            prefs[Keys.corsEnabled] = next.corsEnabled
            prefs[Keys.serverWanted] = next.serverWanted
        }
    }

    suspend fun regenerateApiKey(): String {
        val key = newKey()
        dataStore.edit { it[Keys.apiKey] = key }
        return key
    }

    private suspend fun ensureApiKey() {
        if (dataStore.data.first()[Keys.apiKey].isNullOrBlank()) {
            dataStore.edit { prefs -> if (prefs[Keys.apiKey].isNullOrBlank()) prefs[Keys.apiKey] = newKey() }
        }
    }

    private fun Preferences.toSettings() = Settings(
        port = this[Keys.port] ?: Settings.DEFAULT_PORT,
        apiKey = this[Keys.apiKey].orEmpty(),
        authDisabled = this[Keys.authDisabled] ?: false,
        selectedModel = this[Keys.selectedModel],
        accelerator = this[Keys.accelerator]
            ?.let { name -> AcceleratorPreference.entries.firstOrNull { it.name == name } }
            ?: AcceleratorPreference.AUTO,
        autoSwitch = this[Keys.autoSwitch] ?: true,
        corsEnabled = this[Keys.corsEnabled] ?: false,
        serverWanted = this[Keys.serverWanted] ?: false,
    )
}
