package com.pocketnode.server.engine

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectoryModelCatalogTest {
    @TempDir
    lateinit var dir: File

    private fun file(name: String, bytes: Int = 10) = File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `lists litertlm files sorted by name`() {
        file("zeta.litertlm")
        file("alpha.litertlm")
        file("Mitte.LITERTLM")
        assertEquals(listOf("Mitte", "alpha", "zeta"), DirectoryModelCatalog(dir).list().map { it.id })
    }

    @Test
    fun `id is the file name without extension`() {
        val f = file("gemma-3n-E2B-it-int4.litertlm", bytes = 42)
        assertEquals(
            listOf(ModelFile("gemma-3n-E2B-it-int4", f.absolutePath, 42)),
            DirectoryModelCatalog(dir).list(),
        )
    }

    @Test
    fun `ignores other extensions directories and empty files`() {
        file("notiz.txt")
        file("modell.task")
        file("leer.litertlm", bytes = 0)
        File(dir, "ordner.litertlm").mkdir()
        file("echt.litertlm")
        assertEquals(listOf("echt"), DirectoryModelCatalog(dir).list().map { it.id })
    }

    @Test
    fun `ignores temporary import files`() {
        file("halb.litertlm.part")
        assertEquals(emptyList(), DirectoryModelCatalog(dir).list())
    }

    @Test
    fun `missing directory yields an empty list`() {
        val catalog = DirectoryModelCatalog(File(dir, "gibt-es-nicht"))
        assertEquals(emptyList(), catalog.list())
        assertNull(catalog.find("x"))
    }

    @Test
    fun `sees files added after construction`() {
        val catalog = DirectoryModelCatalog(dir)
        assertEquals(emptyList(), catalog.list())
        file("neu.litertlm")
        assertEquals(listOf("neu"), catalog.list().map { it.id })
        assertEquals("neu", catalog.find("neu")?.id)
    }

    @Test
    fun `find rejects path traversal`() {
        file("echt.litertlm")
        File(dir.parentFile, "draussen.litertlm").writeBytes(ByteArray(10))
        val catalog = DirectoryModelCatalog(dir)
        for (id in listOf("../draussen", "a/b", "..", "", "echt/..")) assertNull(catalog.find(id), id)
    }

    @Test
    fun `deleted file disappears from the list`() {
        val f = file("weg.litertlm")
        val catalog = DirectoryModelCatalog(dir)
        assertEquals(1, catalog.list().size)
        f.delete()
        assertEquals(emptyList(), catalog.list())
        assertNull(catalog.find("weg"))
    }
}
