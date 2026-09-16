package dev.gamblock.protection.domainengine

import java.net.IDN
import java.util.Locale

/**
 * Normalization result. [domain] is the clean ASCII (punycode) lowercase domain
 * without a trailing dot; [wildcard] indicates the source was a `*.` rule.
 */
data class NormalizationResult(
    val domain: String,
    val wildcard: Boolean,
)

/**
 * Strict, dependency-free domain normalizer used by the whole pipeline
 * (seed import, DNS filtering, user input, false-positive reports).
 *
 * Rules:
 *  - lowercases and trims
 *  - strips optional scheme, path, query, fragment, userinfo and numeric port
 *  - strips a single trailing DNS dot and surrounding/duplicated dots
 *  - converts IDN to ASCII punycode (STD-3 rules)
 *  - rejects IP literals, empty labels, overlong labels/names and illegal chars
 *  - understands leading `*.` wildcard markers
 */
object DomainNormalizer {

    const val MAX_NAME_LENGTH = 253
    const val MAX_LABEL_LENGTH = 63

    fun normalizeWithFlags(raw: String?): NormalizationResult? {
        if (raw.isNullOrBlank()) return null

        var s = raw.trim().lowercase(Locale.ROOT)

        val schemeIndex = s.indexOf("://")
        if (schemeIndex >= 0) {
            s = s.substring(schemeIndex + 3)
        }

        for (sep in charArrayOf('/', '?', '#')) {
            val idx = s.indexOf(sep)
            if (idx >= 0) s = s.substring(0, idx)
        }

        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)

        // IPv6 literal (contains multiple colons): not a domain.
        if (s.indexOf(':') != s.lastIndexOf(':')) return null
        if (s.startsWith('[') && s.endsWith(']')) return null

        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.substring(colon + 1).all { it.isDigit() }) {
            s = s.substring(0, colon)
        }

        var wildcard = false
        if (s.startsWith("*.")) {
            wildcard = true
            s = s.removePrefix("*.")
        } else if (s == "*") {
            return null
        }

        while (s.startsWith(".")) s = s.substring(1)
        while (s.endsWith(".")) s = s.dropLast(1)
        while (s.contains("..")) s = s.replace("..", ".")

        if (s.isEmpty()) return null

        if (s.any { it.code > 127 }) {
            s = try {
                IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES)
            } catch (_: IllegalArgumentException) {
                return null
            }
        }

        if (!isValidDomain(s)) return null
        return NormalizationResult(s, wildcard)
    }

    fun normalize(raw: String?): String? = normalizeWithFlags(raw)?.domain

    fun isValidDomain(asciiDomain: String): Boolean {
        if (asciiDomain.length > MAX_NAME_LENGTH) return false
        if (isIpv4Literal(asciiDomain)) return false
        val labels = asciiDomain.split('.')
        for (label in labels) {
            if (label.isEmpty() || label.length > MAX_LABEL_LENGTH) return false
            if (label.first() == '-' || label.last() == '-') return false
            for (c in label) {
                val ok = c in 'a'..'z' || c in '0'..'9' || c == '-'
                if (!ok) return false
            }
        }
        return labels.isNotEmpty()
    }

    /** True for a dotted-quad IPv4 literal whose octets all fit 0..255. */
    private fun isIpv4Literal(dot: String): Boolean {
        if (dot.length > 15) return false
        val octets = dot.split('.')
        if (octets.size != 4) return false
        for (octet in octets) {
            if (octet.isEmpty() || octet.length > 3) return false
            if (!octet.all { it in '0'..'9' }) return false
            val value = octet.toIntOrNull() ?: return false
            if (value !in 0..255) return false
        }
        return true
    }

    /** All superdomains of [normalized], longest first, including the domain itself. */
    fun superdomains(normalized: String): List<String> {
        val parts = normalized.split('.')
        val result = ArrayList<String>(parts.size)
        var cur = parts.last()
        result.add(cur)
        for (i in parts.lastIndex - 1 downTo 0) {
            cur = parts[i] + "." + cur
            result.add(cur)
        }
        return result.asReversed()
    }
}