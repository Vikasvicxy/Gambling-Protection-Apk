package dev.gamblock.protection.vpn

/**
 * DNS-over-HTTPS providers used for the encrypted upstream.
 *
 * Selection is deliberately conservative: only resolvers that document a
 * filtering or malware-blocking posture are listed, and each entry states what
 * it filters so the UI can be honest about it. Nothing here is a general
 * purpose resolver, because a gambling blocker handing history to a resolver
 * that logs would defeat its own purpose.
 */
enum class DohProvider(
    val displayName: String,
    val url: String,
    val filtersMalware: Boolean,
    val filtersContent: Boolean,
) {
    QUAD9(
        displayName = "Quad9",
        url = "https://dns.quad9.net/dns-query",
        filtersMalware = true,
        filtersContent = false,
    ),
    QUAD9_UNFILTERED(
        displayName = "Quad9 (unfiltered)",
        url = "https://dns.quad9.net:5053/dns-query",
        filtersMalware = false,
        filtersContent = false,
    ),
    CLOUDFLARE_SECURITY(
        displayName = "Cloudflare Security",
        url = "https://security.cloudflare-dns.com/dns-query",
        filtersMalware = true,
        filtersContent = false,
    ),
    CLOUDFLARE(
        displayName = "Cloudflare",
        url = "https://cloudflare-dns.com/dns-query",
        filtersMalware = false,
        filtersContent = false,
    ),
    ;

    /** Every provider is DoH, so no plaintext fallback exists to leak into. */
    val isEncrypted: Boolean get() = url.startsWith("https://")

    /** Human-readable summary for the settings screen. */
    val description: String
        get() = buildString {
            append("Encrypted over HTTPS. ")
            append(
                when {
                    filtersMalware && filtersContent -> "Filters malware and adult content."
                    filtersMalware -> "Filters malware and phishing."
                    filtersContent -> "Filters adult content."
                    else -> "No filtering."
                },
            )
        }

    companion object {
        /** Default: filtered, since it costs nothing given Shield already blocks gambling. */
        val default: DohProvider = QUAD9

        fun fromNameOrDefault(name: String?): DohProvider =
            entries.firstOrNull { it.name == name } ?: default
    }
}