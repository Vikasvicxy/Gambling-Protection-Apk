package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EncryptedDnsPolicyTest {

    @Test
    fun `known resolver hostnames are recognised`() {
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("dns.google")).isTrue()
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("cloudflare-dns.com")).isTrue()
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("dns.adguard-dns.com")).isTrue()
    }

    @Test
    fun `subdomains of a resolver are also matched`() {
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("sub.dns.adguard-dns.com")).isTrue()
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("a.b.cloudflare-dns.com")).isTrue()
    }

    @Test
    fun `a host that merely mentions a resolver is not matched`() {
        // Guards against suffix confusion: "notdns.google" is not dns.google.
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("notdns.google")).isFalse()
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("dns.google.evil.test")).isFalse()
    }

    @Test
    fun `matching ignores case and a trailing dot`() {
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("DNS.Google.")).isTrue()
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("  dns.google  ")).isTrue()
    }

    @Test
    fun `ordinary sites are not caught by the resolver list`() {
        listOf(
            "example.com",
            "safe-site.test",
            "google.com",
            "googledns.com.evil.test",
        ).forEach { host ->
            assertThat(EncryptedDnsPolicy.isDohBootstrapHost(host)).isFalse()
        }
    }

    @Test
    fun `an empty host is not a resolver`() {
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("")).isFalse()
        assertThat(EncryptedDnsPolicy.isDohBootstrapHost("   ")).isFalse()
    }

    @Test
    fun `only udp 443 is treated as quic`() {
        assertThat(EncryptedDnsPolicy.isQuicDestinationPort(443)).isTrue()
        assertThat(EncryptedDnsPolicy.isQuicDestinationPort(80)).isFalse()
        assertThat(EncryptedDnsPolicy.isQuicDestinationPort(853)).isFalse()
    }
}
