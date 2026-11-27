package com.pocketnode.server.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.server.service.ServerStatus
import com.pocketnode.server.settings.AcceleratorPreference
import com.pocketnode.server.settings.Settings
import com.pocketnode.server.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SettingsMessage {
    data object InvalidPort : SettingsMessage
}

/**
 * Änderungen an Port, Beschleuniger, Modellwechsel und CORS starten einen laufenden Server neu.
 * API-Key und Auth-Schalter gelten ohne Neustart.
 */
class SettingsViewModel(
    private val store: SettingsStore,
    private val status: StateFlow<ServerStatus>,
    private val restart: suspend () -> Unit,
) : ViewModel() {
    val settings: StateFlow<Settings?> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val mutableMessage = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = mutableMessage.asStateFlow()

    fun setPort(text: String): Job = viewModelScope.launch {
        val port = text.trim().toIntOrNull()
        if (port == null || port !in Settings.PORT_RANGE) {
            mutableMessage.value = SettingsMessage.InvalidPort
            return@launch
        }
        mutableMessage.value = null
        changeAndRestart { it.copy(port = port) }
    }

    fun setAccelerator(value: AcceleratorPreference): Job = viewModelScope.launch { changeAndRestart { it.copy(accelerator = value) } }

    fun setAutoSwitch(value: Boolean): Job = viewModelScope.launch { changeAndRestart { it.copy(autoSwitch = value) } }

    fun setCors(value: Boolean): Job = viewModelScope.launch { changeAndRestart { it.copy(corsEnabled = value) } }

    fun setAuthDisabled(value: Boolean): Job = viewModelScope.launch { store.update { it.copy(authDisabled = value) } }

    fun regenerateApiKey(): Job = viewModelScope.launch { store.regenerateApiKey() }

    private suspend fun changeAndRestart(transform: (Settings) -> Settings) {
        val before = store.current()
        store.update(transform)
        if (store.current() != before && status.value is ServerStatus.Running) restart()
    }
}
