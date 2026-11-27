package com.pocketnode.server.settings

/** AUTO = GPU mit Rückfall auf die CPU. */
enum class AcceleratorPreference { AUTO, GPU, CPU }

data class Settings(
    val port: Int = DEFAULT_PORT,
    /** Wird beim ersten Lesen erzeugt und gespeichert. */
    val apiKey: String,
    val authDisabled: Boolean = false,
    val selectedModel: String? = null,
    val accelerator: AcceleratorPreference = AcceleratorPreference.AUTO,
    val autoSwitch: Boolean = true,
    val corsEnabled: Boolean = false,
    /** true, solange der Nutzer den Server laufen lassen will. */
    val serverWanted: Boolean = false,
) {
    companion object {
        const val DEFAULT_PORT = 8080
        val PORT_RANGE = 1024..65535
    }
}
