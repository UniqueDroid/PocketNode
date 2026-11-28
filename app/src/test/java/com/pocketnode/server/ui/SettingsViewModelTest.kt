package com.pocketnode.server.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.pocketnode.server.engine.EngineState
import com.pocketnode.server.service.ServerStatus
import com.pocketnode.server.settings.AcceleratorPreference
import com.pocketnode.server.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @TempDir
    lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    private var restarts = 0
    private val store by lazy { SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") }) }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runBlocking {
        Dispatchers.resetMain()
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private fun viewModel() = SettingsViewModel(store, status, restart = { restarts++ })

    @Test
    fun `invalid port is rejected with a message and not saved`() = runBlocking {
        val vm = viewModel()
        for (text in listOf("80", "abc", "70000", "")) {
            vm.setPort(text).join()
            assertEquals(SettingsMessage.InvalidPort, vm.message.value)
        }
        assertEquals(8080, store.current().port)
        assertEquals(0, restarts)
    }

    @Test
    fun `changing the port restarts a running server`() = runBlocking {
        status.value = ServerStatus.Running(8080, EngineState())
        val vm = viewModel()
        vm.setPort("9090").join()
        assertEquals(9090, store.current().port)
        assertEquals(1, restarts)
        assertEquals(null, vm.message.value)
    }

    @Test
    fun `changing the port does not start a stopped server`() = runBlocking {
        val vm = viewModel()
        vm.setPort("9090").join()
        assertEquals(9090, store.current().port)
        assertEquals(0, restarts)
    }

    @Test
    fun `accelerator change restarts a running server`() = runBlocking {
        status.value = ServerStatus.Running(8080, EngineState())
        val vm = viewModel()
        vm.setAccelerator(AcceleratorPreference.CPU).join()
        assertEquals(AcceleratorPreference.CPU, store.current().accelerator)
        assertEquals(1, restarts)
    }

    @Test
    fun `new api key needs no restart`() = runBlocking {
        status.value = ServerStatus.Running(8080, EngineState())
        val old = store.current().apiKey
        viewModel().regenerateApiKey().join()
        assertNotEquals(old, store.current().apiKey)
        assertEquals(0, restarts)
    }
}
