package com.pocketnode.server.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketnode.server.R
import com.pocketnode.server.container

@Composable
fun ModelsScreen(viewModel: ModelsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
        viewModel.import(name) { context.contentResolver.openInputStream(uri) ?: error("Datei nicht lesbar") }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tab_models), style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = state.importProgressMb == null) {
            Text(stringResource(R.string.models_import))
        }
        state.importProgressMb?.let {
            Text(stringResource(R.string.models_importing, it.toInt()))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.message?.let { message ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(messageText(message), Modifier.weight(1f))
                    TextButton(onClick = { viewModel.dismissMessage() }) { Text("OK") }
                }
            }
        }
        if (state.models.isEmpty()) {
            Text(stringResource(R.string.models_empty, context.container.modelsDir.absolutePath))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.models, key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.id, style = MaterialTheme.typography.titleMedium)
                            val detail = stringResource(R.string.models_size, row.sizeMb.toInt()) + when {
                                row.loaded -> " · " + stringResource(R.string.models_loaded)
                                row.loading -> " · " + stringResource(R.string.models_loading)
                                else -> ""
                            }
                            Text(detail, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!row.loaded && !row.loading) {
                            TextButton(onClick = { viewModel.load(row.id) }) { Text(stringResource(R.string.models_load)) }
                            TextButton(onClick = { confirmDelete = row.id }) { Text(stringResource(R.string.models_delete)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier)
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.models_delete_title, id)) },
            text = { Text(stringResource(R.string.models_delete_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(id); confirmDelete = null }) { Text(stringResource(R.string.models_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun messageText(message: ModelsMessage): String = when (message) {
    is ModelsMessage.Imported -> stringResource(R.string.msg_imported, message.id)
    is ModelsMessage.NameTaken -> stringResource(R.string.msg_name_taken, message.id)
    is ModelsMessage.ImportFailed -> stringResource(R.string.msg_import_failed, message.reason)
    is ModelsMessage.DeleteLoaded -> stringResource(R.string.msg_delete_loaded, message.id)
    is ModelsMessage.LoadFailed -> stringResource(R.string.msg_load_failed, message.id, message.reason)
    ModelsMessage.ServerStopped -> stringResource(R.string.msg_server_stopped)
}
