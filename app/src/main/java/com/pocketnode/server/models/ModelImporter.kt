package com.pocketnode.server.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

private const val EXTENSION = ".litertlm"
private const val PART_SUFFIX = ".part"
private const val BUFFER_BYTES = 64 * 1024

/** Kopiert Modelldateien in den Modellordner. Halbe Dateien landen nie unter ihrem endgültigen Namen. */
class ModelImporter(private val modelsDir: File) {
    sealed interface Result {
        data class Imported(val id: String) : Result
        data class NameTaken(val id: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Kopiert nach `<name>.litertlm.part` und benennt erst nach vollständiger Kopie um.
     * Eine vorhandene Datei gleichen Namens wird nicht überschrieben. Bei Fehler oder Abbruch
     * wird die Teildatei gelöscht.
     */
    suspend fun import(displayName: String, source: InputStream, onProgress: (copiedBytes: Long) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            val name = sanitize(displayName)
            if (!name.endsWith(EXTENSION, ignoreCase = true) || name.length == EXTENSION.length) {
                return@withContext Result.Failed("Die Datei muss auf $EXTENSION enden")
            }
            val id = name.dropLast(EXTENSION.length)
            val target = File(modelsDir, "$id$EXTENSION")
            if (target.exists()) return@withContext Result.NameTaken(id)
            modelsDir.mkdirs()
            val part = File(modelsDir, "$id$EXTENSION$PART_SUFFIX")
            try {
                source.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied)
                        }
                    }
                }
                if (target.exists()) return@withContext Result.NameTaken(id).also { part.delete() }
                if (!part.renameTo(target)) return@withContext Result.Failed("Die Datei ließ sich nicht umbenennen").also { part.delete() }
                Result.Imported(id)
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: IOException) {
                part.delete()
                Result.Failed(e.message ?: "Lesefehler")
            } catch (e: Throwable) {
                part.delete()
                throw e
            }
        }

    /** Löscht das Modell [id]. Gibt false zurück, wenn es nicht existiert oder die id ungültig ist. */
    fun delete(id: String): Boolean {
        if (id.isEmpty() || id.contains('/') || id.contains('\\') || id.contains("..")) return false
        return File(modelsDir, "$id$EXTENSION").delete()
    }

    private fun sanitize(displayName: String): String =
        displayName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
}
