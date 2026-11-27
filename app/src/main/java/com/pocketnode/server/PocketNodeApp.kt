package com.pocketnode.server

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.pocketnode.server.engine.DirectoryModelCatalog
import com.pocketnode.server.hardware.DeviceInfo
import com.pocketnode.server.models.ModelImporter
import com.pocketnode.server.settings.SettingsStore
import java.io.File

/** Hält die app-weiten Objekte. Service und Oberfläche teilen sich dieselben Instanzen. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(
        PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("settings") },
    )

    /** `Android/data/com.pocketnode.server/files/models`, erreichbar über `adb push`. */
    val modelsDir: File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models").apply { mkdirs() }

    /** Cache für übersetzte GPU-Shader, damit spätere Starts schneller sind. */
    val engineCacheDir: File = File(appContext.cacheDir, "litert").apply { mkdirs() }

    val catalog = DirectoryModelCatalog(modelsDir)
    val importer = ModelImporter(modelsDir)
    val deviceInfo = DeviceInfo(appContext)
    val eventLog = EventLog()
}

class PocketNodeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as PocketNodeApp).container
