package com.pocketnode.server.api

import java.security.MessageDigest

/**
 * Prüft einen `Authorization`-Header gegen den erwarteten API-Key.
 * Das Schema `Bearer` wird ohne Beachtung der Großschreibung erkannt,
 * der Key selbst wird exakt und in konstanter Zeit verglichen.
 */
fun checkBearer(header: String?, expected: String): Boolean {
    if (header == null || expected.isEmpty()) return false
    val parts = header.trim().split(Regex("\\s+"), limit = 2)
    if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return false
    return MessageDigest.isEqual(parts[1].toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}
