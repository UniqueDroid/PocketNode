package com.pocketnode.server.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrCodeTest {
    @Test
    fun `qr matrix is square and non trivial`() {
        val matrix = qrMatrix("pn-" + "a".repeat(43))
        assertTrue(matrix.size >= 21)
        assertTrue(matrix.all { it.size == matrix.size })
        val dark = matrix.sumOf { row -> row.count { it } }
        assertTrue(dark > matrix.size * 3 && dark < matrix.size * matrix.size)
        // Suchmuster oben links: 7×7 mit dunklem Rand
        assertEquals(true, matrix[0][0])
        assertEquals(true, matrix[6][6])
        assertEquals(false, matrix[1][1])
    }
}
