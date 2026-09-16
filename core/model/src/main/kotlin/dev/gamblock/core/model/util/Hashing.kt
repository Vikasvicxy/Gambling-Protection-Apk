package dev.gamblock.core.model.util

import java.security.MessageDigest
import java.security.SecureRandom

/** Deterministic short hash used for grouping/identifiers - NOT for security integrity. */
fun stableHash(input: String): Long {
    var h = 1125899906842597L
    for (c in input) {
        h = h * 31 + c.code
    }
    return h
}

/** SHA-256 hex digest (pure JVM). */
fun sha256Hex(input: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input)
    return digest.joinToString("") { "%02x".format(it) }
}

private val secureRandom = SecureRandom()

/** Cryptographically random 128-bit ID rendered as a hex string. */
fun randomId128(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Humanized duration like "3 days" / "24 hours" / "2 weeks". */
fun formatDurationMillis(millis: Long): String {
    if (millis < 0) return "-"
    if (millis == Long.MAX_VALUE) return "forever"
    val totalMinutes = millis / 60_000L
    val minutes = totalMinutes % 60
    val hours = (totalMinutes / 60) % 24
    val days = totalMinutes / (24 * 60)
    return when {
        days >= 30 -> "${days / 30} month(s)"
        days >= 7 -> {
            val weeks = days / 7
            val remaining = days % 7
            if (remaining > 0) "$weeks wk ${remaining}d" else "$weeks week(s)"
        }
        days >= 1 -> when {
            days == 1L -> "1 day"
            hours > 0 -> "$days d $hours h"
            else -> "$days days"
        }
        hours >= 1 -> when {
            hours == 1L -> "1 hour"
            minutes > 0 -> "$hours h $minutes m"
            else -> "$hours hours"
        }
        else -> "$minutes min"
    }
}