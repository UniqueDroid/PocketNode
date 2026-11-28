package com.pocketnode.server.hardware

import kotlin.test.Test
import kotlin.test.assertEquals

class CpuTopologyTest {
    @Test
    fun `tensor g2 layout recommends four threads`() {
        val g2 = List(4) { 1_803_000L } + List(2) { 2_348_000L } + List(2) { 2_850_000L }
        assertEquals(4, recommendedThreads(g2))
    }

    @Test
    fun `uniform cores are capped at four`() {
        assertEquals(4, recommendedThreads(List(8) { 2_000_000L }))
        assertEquals(2, recommendedThreads(List(2) { 2_000_000L }))
    }

    @Test
    fun `empty list recommends one`() = assertEquals(1, recommendedThreads(emptyList()))

    @Test
    fun `single fast core recommends one`() =
        assertEquals(1, recommendedThreads(List(7) { 1_800_000L } + 3_000_000L))

    @Test
    fun `big little with six fast cores is capped at four`() =
        assertEquals(4, recommendedThreads(List(2) { 1_000_000L } + List(6) { 2_500_000L }))
}
