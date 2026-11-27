package com.pocketnode.server.ui

import com.pocketnode.server.engine.Accelerator
import com.pocketnode.server.engine.DirectoryModelCatalog
import com.pocketnode.server.engine.EngineState
import com.pocketnode.server.engine.LoadedModel
import com.pocketnode.server.engine.ModelFile
import com.pocketnode.server.models.ModelImporter
import com.pocketnode.server.service.ServerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
    @TempDir
    lateinit var dir: File
    private val status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    private val loaded = mutableListOf<String>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun file(name: String, bytes: Int = 3 * 1024 * 1024) = File(dir, name).writeBytes(ByteArray(bytes))

    private fun viewModel() = ModelsViewModel(
        catalog = DirectoryModelCatalog(dir),
        importer = ModelImporter(dir),
        status = status,
        loadModel = { loaded += it },
    )

    @Test
    fun `rows mark the loaded and the loading model`() {
        val files = listOf(ModelFile("a", "/a", 2 * 1024 * 1024), ModelFile("b", "/b", 1), ModelFile("c", "/c", 1))
        val engine = EngineState(loaded = LoadedModel("a", Accelerator.GPU, 1), loading = "b")
        assertEquals(
            listOf(ModelRow("a", 2, loaded = true, loading = false), ModelRow("b", 0, false, true), ModelRow("c", 0, false, false)),
            modelRows(files, engine),
        )
        assertEquals(listOf(false), modelRows(listOf(ModelFile("a", "/a", 1)), null).map { it.loaded })
    }

    @Test
    fun `list reflects the models folder`() {
        file("eins.litertlm")
        val vm = viewModel()
        assertEquals(listOf("eins"), vm.state.value.models.map { it.id })
        assertEquals(3, vm.state.value.models.single().sizeMb)
    }

    @Test
    fun `delete is refused for the loaded model`() {
        file("eins.litertlm")
        status.value = ServerStatus.Running(8080, EngineState(loaded = LoadedModel("eins", Accelerator.GPU, 1)))
        val vm = viewModel()
        vm.delete("eins")
        assertEquals(ModelsMessage.DeleteLoaded("eins"), vm.state.value.message)
        assertTrue(File(dir, "eins.litertlm").exists())
    }

    @Test
    fun `delete removes an unloaded model`() {
        file("eins.litertlm")
        val vm = viewModel()
        vm.delete("eins")
        assertEquals(emptyList(), vm.state.value.models)
    }

    @Test
    fun `import result NameTaken becomes a message`() = runTest {
        file("eins.litertlm")
        val vm = viewModel()
        vm.import("eins.litertlm") { ByteArrayInputStream(ByteArray(10)) }.join()
        assertEquals(ModelsMessage.NameTaken("eins"), vm.state.value.message)
        assertEquals(null, vm.state.value.importProgressMb)
    }

    @Test
    fun `successful import refreshes the list`() = runTest {
        val vm = viewModel()
        vm.import("neu.litertlm") { ByteArrayInputStream(ByteArray(2 * 1024 * 1024)) }.join()
        assertEquals(listOf("neu"), vm.state.value.models.map { it.id })
        assertEquals(ModelsMessage.Imported("neu"), vm.state.value.message)
        assertEquals(null, vm.state.value.importProgressMb)
    }

    @Test
    fun `load needs a running server`() {
        file("eins.litertlm")
        val vm = viewModel()
        vm.load("eins")
        assertEquals(ModelsMessage.ServerStopped, vm.state.value.message)
        status.value = ServerStatus.Running(8080, EngineState())
        vm.load("eins")
        assertEquals(listOf("eins"), loaded)
    }
}
