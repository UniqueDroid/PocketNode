package com.pocketnode.server.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketnode.server.R
import com.pocketnode.server.service.ServerStatus

@Composable
fun OverviewScreen(viewModel: OverviewViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state ?: return
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        StatusLine(current.status)

        if (current.fallbackNotice) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    stringResource(R.string.overview_fallback),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (current.authDisabled) {
            Text(stringResource(R.string.overview_auth_off), color = MaterialTheme.colorScheme.error)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (current.status is ServerStatus.Running) {
                    if (current.addresses.isEmpty()) {
                        Text(stringResource(R.string.overview_no_address))
                    } else {
                        SelectionContainer {
                            Column {
                                current.addresses.forEach {
                                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
                val model = when {
                    current.loading != null -> stringResource(R.string.overview_loading, current.loading)
                    current.modelId != null -> stringResource(R.string.overview_model, "${current.modelId}   ${current.accelerator}")
                    else -> stringResource(R.string.overview_no_model)
                }
                Text(model)
                current.tokensPerSecond?.let { Text(stringResource(R.string.overview_speed, it, current.queue)) }
                    ?: Text(stringResource(R.string.overview_queue, current.queue))
                Text(stringResource(R.string.overview_ram, current.ramAvailableMb / 1024.0, current.ramTotalMb / 1024.0))
                Text(stringResource(R.string.overview_soc, current.socModel), style = MaterialTheme.typography.bodySmall)
                current.lastError?.let {
                    Text(stringResource(R.string.overview_last_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (current.status) {
                ServerStatus.Stopped, is ServerStatus.Failed ->
                    Button(onClick = { viewModel.start() }) { Text(stringResource(R.string.action_start)) }
                else -> Button(onClick = { viewModel.stop() }) { Text(stringResource(R.string.action_stop)) }
            }
            OutlinedButton(onClick = { showKey = true }) { Text(stringResource(R.string.action_show_key)) }
        }
    }

    if (showKey) ApiKeyDialog(current.apiKey) { showKey = false }
}

@Composable
private fun StatusLine(status: ServerStatus) {
    val (text, color) = when (status) {
        is ServerStatus.Running -> stringResource(R.string.status_running) to Color(0xFF2E7D32)
        ServerStatus.Starting -> stringResource(R.string.status_starting) to MaterialTheme.colorScheme.onSurfaceVariant
        ServerStatus.Stopped -> stringResource(R.string.status_stopped) to MaterialTheme.colorScheme.onSurfaceVariant
        is ServerStatus.Failed -> stringResource(R.string.status_failed, status.reason) to MaterialTheme.colorScheme.error
    }
    Text("● $text", color = color, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ApiKeyDialog(key: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.key_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(key, fontFamily = FontFamily.Monospace) }
                QrCode(key)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PocketNode API key", key))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.action_copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun QrCode(text: String) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(Modifier.size(220.dp).background(Color.White).padding(12.dp)) {
        val cell = size.minDimension / matrix.size
        matrix.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
