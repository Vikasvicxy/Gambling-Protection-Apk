package dev.gamblock.core.model

/**
 * Search engines Shield recognises, and the hostname each one treats as its
 * SafeSearch endpoint.
 *
 * ## Why this cannot be enforcement
 *
 * It is tempting to answer a query for `www.google.com` with the address of
 * `forcesafesearch.google.com` and call the feature done. That does not work,
 * and the reason is worth writing down so nobody re-attempts it.
 *
 * A stub resolver ignores the answer's *content* and connects to whatever
 * address it was handed, then sends the original hostname in both SNI and the
 * HTTP `Host` header. The server therefore sees a request for
 * `www.google.com`, presents a certificate valid for `www.google.com`, and
 * returns ordinary unfiltered results. SafeSearch is selected by hostname, not
 * by source address, so substituting the IP changes nothing an end user can
 * observe. The only way to force it under TLS is to terminate TLS, which would
 * mean trusting a certificate we generated ourselves, and that is a far larger
 * concession than this feature is worth.
 *
 * Two further limits stack on top: encrypted DNS (DoH/DoT) never reaches a
 * DNS-level filter in the first place, and QUIC can bypass it even when it does.
 * Both are already recorded as honest limitations in the bypass matrix.
 *
 * So Shield does the part that is real and verifiable: it recognises the
 * search engine being used and offers a one-tap route to that engine's own
 * SafeSearch endpoint, which does the filtering properly and on the engine's
 * own terms.
 */
enum class SafeSearchEngine(
    val displayName: String,
    val safeSearchHost: String,
) {
    GOOGLE("Google", "forcesafesearch.google.com"),
    BING("Bing", "strict.bing.com"),
    DUCKDUCKGO("DuckDuckGo", "safe.duckduckgo.com"),
}

/** Outcome of inspecting a query name against the search-engine table. */
sealed interface SearchEngineMatch {
    /** A normal search engine hostname that does not already request SafeSearch. */
    data class Engine(val engine: SafeSearchEngine, val host: String) : SearchEngineMatch

    /** Already a SafeSearch hostname; nothing to offer. */
    data class AlreadySafe(val engine: SafeSearchEngine, val host: String) : SearchEngineMatch

    /** Not a search engine we know about. */
    data object NotSearch : SearchEngineMatch
}

object SafeSearchPolicy {

    /**
     * Registrable domains per engine, matched on the name and its parent.
     *
     * Matching is on the *base* domain and any subdomain of it, so
     * `www.google.co.in` and `images.google.com` are both recognised, while
     * `notgoogle.com` is not. A naive `contains("google")` would be trivially
     * evaded and would misfire on unrelated domains.
     */
    private val BASES: Map<SafeSearchEngine, List<String>> = mapOf(
        SafeSearchEngine.GOOGLE to listOf("google.com", "google.co.in", "google.co.uk"),
        SafeSearchEngine.BING to listOf("bing.com"),
        SafeSearchEngine.DUCKDUCKGO to listOf("duckduckgo.com"),
    )

    private val SAFE_HOSTS: Map<SafeSearchEngine, String> =
        SafeSearchEngine.entries.associateWith { it.safeSearchHost }

    fun classify(hostname: String): SearchEngineMatch {
        val host = hostname.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return SearchEngineMatch.NotSearch

        // Already-safe first, so a user who bookmarked the strict endpoint is
        // not nagged every single query.
        SAFE_HOSTS.forEach { (engine, safeHost) ->
            if (matches(host, safeHost)) return SearchEngineMatch.AlreadySafe(engine, host)
        }

        BASES.forEach { (engine, bases) ->
            for (base in bases) {
                if (matches(host, base)) return SearchEngineMatch.Engine(engine, host)
            }
        }
        return SearchEngineMatch.NotSearch
    }

    /** The SafeSearch URL to open, or null when the host is not a search engine. */
    fun safeSearchUrlFor(hostname: String): String? = when (val match = classify(hostname)) {
        is SearchEngineMatch.Engine -> "https://${match.engine.safeSearchHost}/search?q="
        is SearchEngineMatch.AlreadySafe, SearchEngineMatch.NotSearch -> null
    }

    /** Exact host or a subdomain of [base]; never a bare substring test. */
    private fun matches(host: String, base: String): Boolean =
        host == base || host.endsWith(".$base")

    /** The engine whose SafeSearch endpoint should be offered for [hostname]. */
    fun engineFor(hostname: String): SafeSearchEngine? =
        (classify(hostname) as? SearchEngineMatch.Engine)?.engine
}

/**
 * IPv6 leak policy.
 *
 * Shield's tun forwards nothing: it answers DNS and drops every other packet.
 * That has one important consequence. If a client is allowed to obtain an AAAA
 * record, it will happily open an IPv6 connection, and on a device with native
 * IPv6 that connection does not need Shield at all, so a blocked domain reached
 * over IPv6 is a bypass. It is listed as row 8.4 of the bypass matrix.
 *
 * The fix is to suppress IPv6 rather than route it. Answering every AAAA query
 * with NOERROR and zero answers forces clients down to IPv4, where Shield is
 * actually in the path. This is deliberately done for *all* domains rather than
 * only blocked ones: a client that cannot get IPv4 either will show a normal
 * connection error, which is honest, whereas answering AAAA for allowed domains
 * while dropping the packets would turn the whole tun into a black hole.
 *
 * NOERROR with an empty answer section is used rather than NXDOMAIN on purpose.
 * NXDOMAIN asserts the name does not exist, which is false and can poison a
 * client's cache for the A query that follows. An empty NOERROR says "no IPv6
 * for this name", which is exactly true, and browsers fall back to the A record
 * immediately instead of timing out.
 */
object Ipv6LeakPolicy {

    const val TYPE_A: Int = 1
    const val TYPE_AAAA: Int = 28

    /**
     * True when the query should be answered with an empty NOERROR instead of
     * being forwarded.
     *
     * Only AAAA is suppressed. Suppressing anything else would break the
     * lookup the client actually needs.
     */
    fun shouldSuppress(questionType: Int, enabled: Boolean): Boolean =
        enabled && questionType == TYPE_AAAA

    /**
     * True when the query is a type Shield must never answer from cache, used
     * to guard against accidentally suppressing non-address lookups.
     */
    fun isAddressQuery(questionType: Int): Boolean =
        questionType == TYPE_A || questionType == TYPE_AAAA
}
