package dev.gamblock.core.model

object EncryptedDnsPolicy {

    const val QUIC_UDP_PORT: Int = 443
    const val DOH_DEFAULT_PORT: Int = 443
    const val DOT_DEFAULT_PORT: Int = 853

    val DOH_BOOTSTRAP_HOSTS: Set<String> = setOf(
        "cloudflare-dns.com",
        "chrome.cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "dns.google",
        "dns.quad9.net",
        "dns.adguard-dns.com",
        "unfiltered.adguard-dns.com",
        "doh.opendns.com",
        "doh.mullvad.net",
        "dns.nextdns.io",
        "block.dns.adguard.com",
        "dot.sb",
        "sdns-1.google.com",
        "quad9-dns.dns.adguard.com",
    )

    fun normalizeHost(host: String): String =
        host.trim().trimEnd('.').lowercase()

    fun isDohBootstrapHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty()) return false
        if (normalized in DOH_BOOTSTRAP_HOSTS) return true
        return DOH_BOOTSTRAP_HOSTS.any { normalized.endsWith(".$it") }
    }

    fun isQuicDestinationPort(port: Int): Boolean = port == QUIC_UDP_PORT
}
