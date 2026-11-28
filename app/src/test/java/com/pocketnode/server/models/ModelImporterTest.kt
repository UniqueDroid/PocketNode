package com.pocketnode.server.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelImporterTest {
    @TempDir
    lateinit var root: File
    private val models get() = File(root, "models")

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    /** Liefert erst [okBytes] Bytes, dann einen Fehler. */
    private class BrokenStream(private val okBytes: Int) : InputStream() {
        private var read = 0
        override fun read(): Int = if (read++ < okBytes) 1 else throw IOException("Lesefehler")
    }

    @Test
    fun `imports a file and reports progress`() = runTest {
        val data = bytes(300_000)
        val progress = mutableListOf<Long>()
        val result = ModelImporter(models).import("gemma.litertlm", ByteArrayInputStream(data)) { progress += it }
        assertEquals(ModelImporter.Result.Imported("gemma"), result)
        assertTrue(File(models, "gemma.litertlm").readBytes().contentEquals(data))
        assertEquals(300_000L, progress.last())
        assertTrue(progress.size > 1)
        assertEquals(listOf("gemma.litertlm"), models.list()!!.toList())
    }

    @Test
    fun `rejects names without litertlm extension`() = runTest {
        val result = ModelImporter(models).import("modell.task", ByteArrayInputStream(bytes(10)))
        assertIs<ModelImporter.Result.Failed>(result)
        assertEquals(0, models.list()?.size ?: 0)
    }

    @Test
    fun `does not overwrite an existing model`() = runTest {
        models.mkdirs()
        File(models, "gemma.litertlm").writeText("alt")
        val result = ModelImporter(models).import("gemma.litertlm", ByteArrayInputStream(bytes(10)))
        assertEquals(ModelImporter.Result.NameTaken("gemma"), result)
        assertEquals("alt", File(models, "gemma.litertlm").readText())
        assertEquals(listOf("gemma.litertlm"), models.list()!!.toList())
    }

    @Test
    fun `failed copy leaves no file behind`() = runTest {
        val result = ModelImporter(models).import("gemma.litertlm", BrokenStream(10))
        assertIs<ModelImporter.Result.Failed>(result)
        assertEquals(emptyList(), models.list()?.toList() ?: emptyList())
    }

    @Test
    fun `cancelled copy leaves no file behind`() = runTest {
        val endless = object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { Thread.sleep(1) }
        }
        // Eigener Dispatcher: die Warteschleife unten blockiert den Test-Thread.
        val job = async(Dispatchers.IO) { ModelImporter(models).import("gemma.litertlm", endless) }
        while ((models.list()?.size ?: 0) == 0) Thread.sleep(5)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(emptyList(), models.list()?.toList() ?: emptyList())
    }

    @Test
    fun `sanitizes the file name`() = runTest {
        val result = ModelImporter(models).import("../../böse name.litertlm", ByteArrayInputStream(bytes(10)))
        assertEquals(ModelImporter.Result.Imported("b_se_name"), result)
        assertTrue(File(models, "b_se_name.litertlm").isFile)
        assertFalse(File(root.parentFile, "böse name.litertlm").exists())
    }

    @Test
    fun `delete removes the model`() = runTest {
        val importer = ModelImporter(models)
        importer.import("gemma.litertlm", ByteArrayInputStream(bytes(10)))
        assertTrue(importer.delete("gemma"))
        assertFalse(File(models, "gemma.litertlm").exists())
        assertFalse(importer.delete("gemma"))
        assertFalse(importer.delete("../x"))
    }
}
