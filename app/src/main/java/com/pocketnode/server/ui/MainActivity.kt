package com.pocketnode.server.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketnode.server.AppContainer
import com.pocketnode.server.R
import com.pocketnode.server.container
import com.pocketnode.server.service.PocketNodeService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = container
        val factory = viewModelFactory(app)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                PocketNodeUi(factory)
            }
        }
    }

    private fun viewModelFactory(app: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            OverviewViewModel::class.java -> OverviewViewModel(
                status = app.controller.status,
                settings = app.settingsStore.settings,
                snapshot = { app.deviceInfo.snapshot() },
                startServer = {
                    app.settingsStore.update { it.copy(serverWanted = true) }
                    PocketNodeService.start(this@MainActivity)
                },
                stopServer = { PocketNodeService.stop(this@MainActivity) },
            )
            ModelsViewModel::class.java -> ModelsViewModel(
                catalog = app.catalog,
                importer = app.importer,
                status = app.controller.status,
                loadModel = { app.controller.loadModel(it) },
            )
            SettingsViewModel::class.java -> SettingsViewModel(
                store = app.settingsStore,
                status = app.controller.status,
                restart = { lifecycleScope.launch { app.controller.restart() } },
            )
            else -> error("Unbekanntes ViewModel $modelClass")
        } as T
    }
}

private enum class Tab(val label: Int, val icon: Int) {
    Overview(R.string.tab_overview, android.R.drawable.ic_menu_info_details),
    Models(R.string.tab_models, android.R.drawable.ic_menu_agenda),
    Settings(R.string.tab_settings, android.R.drawable.ic_menu_preferences),
}

@Composable
private fun PocketNodeUi(factory: ViewModelProvider.Factory) {
    var tab by remember { mutableStateOf(Tab.Overview) }
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        // Ab Android 13 braucht die Benachrichtigung des Dienstes eine Erlaubnis.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { Icon(painterResource(it.icon), contentDescription = null) },
                        label = { Text(stringResource(it.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Overview -> OverviewScreen(viewModel(factory = factory), modifier)
            Tab.Models -> ModelsScreen(viewModel(factory = factory), modifier)
            Tab.Settings -> SettingsScreen(viewModel(factory = factory), modifier)
        }
    }
}
