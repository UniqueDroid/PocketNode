package com.pocketnode.server.hardware

private const val MAX_THREADS = 4

/**
 * Empfohlene Thread-Zahl für die CPU-Inferenz aus den Maximalfrequenzen der Kerne (kHz).
 * Gezählt werden die Kerne oberhalb der langsamsten Gruppe, damit die Effizienzkerne die schnellen
 * nicht ausbremsen. Ergebnis zwischen 1 und 4.
 */
fun recommendedThreads(maxFreqKhz: List<Long>): Int {
    if (maxFreqKhz.isEmpty()) return 1
    val slowest = maxFreqKhz.min()
    val fast = maxFreqKhz.count { it > slowest }
    val count = if (fast == 0) maxFreqKhz.size else fast
    return count.coerceIn(1, MAX_THREADS)
}
