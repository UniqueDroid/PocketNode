package com.pocketnode.server.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettingsStoreTest {
    @TempDir
    lateinit var dir: File
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(name: String = "settings", keys: Iterator<String> = generateSequence(1) { it + 1 }.map { "pn-key$it" }.iterator()): SettingsStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }
        return SettingsStore(dataStore) { keys.next() }
    }

    @AfterTest
    fun close() = scopes.forEach { it.cancel() }

    @Test
    fun `first read creates and persists an api key`() = runTest {
        val store = store()
        val first = store.current().apiKey
        assertEquals("pn-key1", first)
        assertEquals(first, store.current().apiKey)
    }

    @Test
    fun `update persists across instances`() = runTest {
        val a = store("geteilt")
        a.update { it.copy(port = 9090, selectedModel = "gemma", accelerator = AcceleratorPreference.CPU, serverWanted = true) }
        val key = a.current().apiKey
        // Der erste DataStore muss ganz beendet sein, sonst meldet DataStore zwei aktive Instanzen für eine Datei.
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        val b = store("geteilt")
        val read = b.current()
        assertEquals(9090, read.port)
        assertEquals("gemma", read.selectedModel)
        assertEquals(AcceleratorPreference.CPU, read.accelerator)
        assertTrue(read.serverWanted)
        assertEquals(key, read.apiKey)
    }

    @Test
    fun `selected model can be cleared`() = runTest {
        val store = store()
        store.update { it.copy(selectedModel = "gemma") }
        store.update { it.copy(selectedModel = null) }
        assertEquals(null, store.current().selectedModel)
    }

    @Test
    fun `regenerateApiKey changes the key`() = runTest {
        val store = store()
        val old = store.current().apiKey
        val new = store.regenerateApiKey()
        assertNotEquals(old, new)
        assertEquals(new, store.current().apiKey)
    }

    @Test
    fun `rejects ports outside 1024 to 65535`() = runTest {
        val store = store()
        for (port in listOf(80, 1023, 65536, 0, -1)) {
            assertFailsWith<IllegalArgumentException> { store.update { it.copy(port = port) } }
        }
        assertEquals(8080, store.current().port)
        store.update { it.copy(port = 1024) }
        store.update { it.copy(port = 65535) }
        assertEquals(65535, store.current().port)
    }

    @Test
    fun `defaults are safe`() = runTest {
        val s = store().current()
        assertFalse(s.authDisabled)
        assertFalse(s.corsEnabled)
        assertFalse(s.serverWanted)
        assertTrue(s.autoSwitch)
        assertEquals(8080, s.port)
        assertEquals(AcceleratorPreference.AUTO, s.accelerator)
        assertEquals(null, s.selectedModel)
    }
}
