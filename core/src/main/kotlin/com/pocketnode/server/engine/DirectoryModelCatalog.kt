package com.pocketnode.server.engine

import java.io.File

private const val EXTENSION = ".litertlm"

/**
 * Die Modelle in einem Ordner: alle nicht leeren `.litertlm`-Dateien. Der Ordner wird bei jedem Aufruf
 * neu gelesen, damit Dateien aus `adb push` oder einem Import sofort erscheinen und gelöschte verschwinden.
 */
class DirectoryModelCatalog(private val directory: File) : ModelCatalog {
    override fun list(): List<ModelFile> =
        (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(EXTENSION, ignoreCase = true) && it.length() > 0 }
            .sortedBy { it.name }
            .map { ModelFile(it.name.dropLast(EXTENSION.length), it.absolutePath, it.length()) }

    override fun find(id: String): ModelFile? {
        if (id.isEmpty() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
        return list().firstOrNull { it.id == id }
    }
}
