package com.pocketnode.server.ui

import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.EngineState
import com.pocketnode.server.engine.LoadedModel
import com.pocketnode.server.hardware.DeviceSnapshot
import com.pocketnode.server.hardware.NetworkAddress
import com.pocketnode.server.service.ServerStatus
import com.pocketnode.server.settings.AcceleratorPreference
import com.pocketnode.server.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverviewViewModelTest {
    private val settings = Settings(apiKey = "pn-key")
    private val device = DeviceSnapshot(
        socModel = "Tensor G2", cpuCores = 8, recommendedThreads = 4, totalRamMb = 11_500, availableRamMb = 5_100,
        addresses = listOf(NetworkAddress("Tailscale", "100.101.102.103"), NetworkAddress("WLAN", "192.168.1.5")),
    )
    private fun running(accelerator: Accelerator?) = ServerStatus.Running(
        8080,
        EngineState(loaded = accelerator?.let { LoadedModel("gemma", it, 4096) }, waiting = 2, lastTokensPerSecond = 14.2),
    )

    @Test
    fun `addresses combine ip and port with wifi first`() {
        val state = overviewState(running(Accelerator.GPU), settings, device)
        assertEquals(listOf("http://192.168.1.5:8080", "http://100.101.102.103:8080"), state.addresses)
        assertEquals("gemma", state.modelId)
        assertEquals("GPU", state.accelerator)
        assertEquals(14.2, state.tokensPerSecond)
        assertEquals(2, state.queue)
        assertEquals(5_100, state.ramAvailableMb)
        assertEquals("pn-key", state.apiKey)
    }

    @Test
    fun `fallback notice when GPU wanted and CPU active`() {
        assertTrue(overviewState(running(Accelerator.CPU), settings, device).fallbackNotice)
        assertTrue(overviewState(running(Accelerator.CPU), settings.copy(accelerator = AcceleratorPreference.GPU), device).fallbackNotice)
        assertFalse(overviewState(running(Accelerator.GPU), settings, device).fallbackNotice)
        assertFalse(overviewState(running(null), settings, device).fallbackNotice)
    }

    @Test
    fun `no fallback notice when CPU was chosen`() {
        val state = overviewState(running(Accelerator.CPU), settings.copy(accelerator = AcceleratorPreference.CPU), device)
        assertFalse(state.fallbackNotice)
    }

    @Test
    fun `stopped server shows no addresses`() {
        val state = overviewState(ServerStatus.Stopped, settings, device)
        assertEquals(emptyList(), state.addresses)
        assertEquals(null, state.modelId)
        assertEquals(0, state.queue)
    }
}
