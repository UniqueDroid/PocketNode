package com.pocketnode.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketnode.server.R
import com.pocketnode.server.settings.AcceleratorPreference

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val current = settings ?: return
    var portText by remember(current.port) { mutableStateOf(current.port.toString()) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.settings_restart_hint), style = MaterialTheme.typography.bodySmall)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.settings_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = message == SettingsMessage.InvalidPort,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { viewModel.setPort(portText) }) { Text(stringResource(R.string.settings_port_save)) }
        }
        if (message == SettingsMessage.InvalidPort) {
            Text(stringResource(R.string.settings_port_invalid), color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider()

        Text(stringResource(R.string.settings_accelerator), style = MaterialTheme.typography.titleMedium)
        listOf(
            AcceleratorPreference.AUTO to R.string.settings_acc_auto,
            AcceleratorPreference.GPU to R.string.settings_acc_gpu,
            AcceleratorPreference.CPU to R.string.settings_acc_cpu,
        ).forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = current.accelerator == value, onClick = { viewModel.setAccelerator(value) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current.accelerator == value, onClick = { viewModel.setAccelerator(value) })
                Text(stringResource(label))
            }
        }
        HorizontalDivider()

        SwitchRow(stringResource(R.string.settings_auto_switch), current.autoSwitch) { viewModel.setAutoSwitch(it) }
        SwitchRow(stringResource(R.string.settings_cors), current.corsEnabled) { viewModel.setCors(it) }
        SwitchRow(stringResource(R.string.settings_auth_off), current.authDisabled) { off ->
            if (off) confirm = Confirm.AuthOff else viewModel.setAuthDisabled(false)
        }
        HorizontalDivider()
        OutlinedButton(onClick = { confirm = Confirm.NewKey }) { Text(stringResource(R.string.settings_new_key)) }
    }

    confirm?.let { kind ->
        val (title, text) = when (kind) {
            Confirm.AuthOff -> R.string.settings_auth_off to R.string.settings_auth_off_text
            Confirm.NewKey -> R.string.settings_new_key to R.string.settings_new_key_text
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(text)) },
            confirmButton = {
                TextButton(onClick = {
                    when (kind) {
                        Confirm.AuthOff -> viewModel.setAuthDisabled(true)
                        Confirm.NewKey -> viewModel.regenerateApiKey()
                    }
                    confirm = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private enum class Confirm { AuthOff, NewKey }

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
