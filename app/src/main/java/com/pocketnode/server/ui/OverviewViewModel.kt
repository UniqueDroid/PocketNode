package com.pocketnode.server.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.hardware.DeviceSnapshot
import com.pocketnode.server.service.ServerStatus
import com.pocketnode.server.settings.AcceleratorPreference
import com.pocketnode.server.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OverviewUiState(
    val status: ServerStatus,
    /** "http://<ip>:<port>", WLAN zuerst, dann Tailscale. Leer, solange der Server nicht läuft. */
    val addresses: List<String>,
    val modelId: String?,
    /** "GPU" oder "CPU", aus dem tatsächlich geladenen Modell. */
    val accelerator: String?,
    /** GPU war gewünscht, gerechnet wird auf der CPU. */
    val fallbackNotice: Boolean,
    val tokensPerSecond: Double?,
    val queue: Int,
    val loading: String?,
    val lastError: String?,
    val ramAvailableMb: Long,
    val ramTotalMb: Long,
    val socModel: String,
    val apiKey: String,
    val authDisabled: Boolean,
)

fun overviewState(status: ServerStatus, settings: Settings, device: DeviceSnapshot): OverviewUiState {
    val running = status as? ServerStatus.Running
    val engine = running?.engine
    val loaded = engine?.loaded
    val ordered = device.addresses.sortedBy { if (it.label == "WLAN") 0 else if (it.label == "Ethernet") 1 else 2 }
    return OverviewUiState(
        status = status,
        addresses = if (running == null) emptyList() else ordered.map { "http://${it.ip}:${running.port}" },
        modelId = loaded?.id,
        accelerator = loaded?.accelerator?.name,
        fallbackNotice = loaded?.accelerator == Accelerator.CPU && settings.accelerator != AcceleratorPreference.CPU,
        tokensPerSecond = engine?.lastTokensPerSecond,
        queue = engine?.waiting ?: 0,
        loading = engine?.loading,
        lastError = engine?.lastError,
        ramAvailableMb = device.availableRamMb,
        ramTotalMb = device.totalRamMb,
        socModel = device.socModel,
        apiKey = settings.apiKey,
        authDisabled = settings.authDisabled,
    )
}

private const val DEVICE_REFRESH_MS = 5_000L

class OverviewViewModel(
    status: StateFlow<ServerStatus>,
    settings: Flow<Settings>,
    snapshot: () -> DeviceSnapshot,
    private val startServer: suspend () -> Unit,
    private val stopServer: suspend () -> Unit,
) : ViewModel() {
    /** Geräte- und Netzwerkdaten alle 5 s, solange jemand zuschaut. */
    private val device = flow {
        while (true) {
            emit(snapshot())
            delay(DEVICE_REFRESH_MS)
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<OverviewUiState?> = combine(status, settings, device, ::overviewState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun start() = viewModelScope.launch { startServer() }

    fun stop() = viewModelScope.launch { stopServer() }
}

/** Für die Oberfläche: wartet auf den ersten Zustand. */
fun StateFlow<OverviewUiState?>.ready() = filterNotNull()
