package com.pocketnode.server.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.server.engine.EngineState
import com.pocketnode.server.engine.ModelCatalog
import com.pocketnode.server.engine.ModelFile
import com.pocketnode.server.models.ModelImporter
import com.pocketnode.server.service.ServerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

data class ModelRow(val id: String, val sizeMb: Long, val loaded: Boolean, val loading: Boolean)

sealed interface ModelsMessage {
    data class Imported(val id: String) : ModelsMessage
    data class NameTaken(val id: String) : ModelsMessage
    data class ImportFailed(val reason: String) : ModelsMessage
    data class DeleteLoaded(val id: String) : ModelsMessage
    data class LoadFailed(val id: String, val reason: String) : ModelsMessage
    data object ServerStopped : ModelsMessage
}

data class ModelsUiState(
    val models: List<ModelRow> = emptyList(),
    /** Bisher kopierte MB während eines Imports, sonst `null`. */
    val importProgressMb: Long? = null,
    val message: ModelsMessage? = null,
)

private const val MB = 1024L * 1024L

fun modelRows(files: List<ModelFile>, engine: EngineState?): List<ModelRow> = files.map {
    ModelRow(it.id, it.sizeBytes / MB, loaded = engine?.loaded?.id == it.id, loading = engine?.loading == it.id)
}

class ModelsViewModel(
    private val catalog: ModelCatalog,
    private val importer: ModelImporter,
    private val status: StateFlow<ServerStatus>,
    private val loadModel: suspend (String) -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { status.collect { refresh() } }
    }

    fun refresh() {
        val engine = (status.value as? ServerStatus.Running)?.engine
        mutableState.update { it.copy(models = modelRows(catalog.list(), engine)) }
    }

    fun import(displayName: String, open: () -> InputStream): Job = viewModelScope.launch {
        mutableState.update { it.copy(importProgressMb = 0, message = null) }
        val result = try {
            importer.import(displayName, open()) { copied -> mutableState.update { s -> s.copy(importProgressMb = copied / MB) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // z. B. SecurityException, wenn der Zugriff auf die Datei entzogen wurde
            ModelImporter.Result.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
        val message = when (result) {
            is ModelImporter.Result.Imported -> ModelsMessage.Imported(result.id)
            is ModelImporter.Result.NameTaken -> ModelsMessage.NameTaken(result.id)
            is ModelImporter.Result.Failed -> ModelsMessage.ImportFailed(result.reason)
        }
        mutableState.update { it.copy(importProgressMb = null, message = message) }
        refresh()
    }

    fun delete(id: String) {
        val engine = (status.value as? ServerStatus.Running)?.engine
        if (engine?.loaded?.id == id || engine?.loading == id) {
            mutableState.update { it.copy(message = ModelsMessage.DeleteLoaded(id)) }
            return
        }
        importer.delete(id)
        refresh()
    }

    fun load(id: String) {
        if (status.value !is ServerStatus.Running) {
            mutableState.update { it.copy(message = ModelsMessage.ServerStopped) }
            return
        }
        viewModelScope.launch {
            runCatching { loadModel(id) }
                .onFailure { e -> mutableState.update { it.copy(message = ModelsMessage.LoadFailed(id, e.message.orEmpty())) } }
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }
}
