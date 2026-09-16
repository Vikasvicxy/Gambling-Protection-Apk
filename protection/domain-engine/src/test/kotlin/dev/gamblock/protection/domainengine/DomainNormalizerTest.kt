package dev.gamblock.protection.domainengine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DomainNormalizerTest {

    // ---- basic cleansing ----

    @Test
    fun `normalizes case and trims whitespace`() {
        assertThat(DomainNormalizer.normalize("  BetExample.COM  ")).isEqualTo("betexample.com")
        assertThat(DomainNormalizer.normalize("POKER-Example.test")).isEqualTo("poker-example.test")
    }

    @Test
    fun `strips scheme path query fragment`() {
        assertThat(DomainNormalizer.normalize("https://www.bet-example.test")).isEqualTo("www.bet-example.test")
        assertThat(DomainNormalizer.normalize("http://bet-example.test/slots?x=1#top"))
            .isEqualTo("bet-example.test")
        assertThat(DomainNormalizer.normalize("HTTPS://CASINO-EXAMPLE.TEST/lobby"))
            .isEqualTo("casino-example.test")
    }

    @Test
    fun `strips userinfo and numeric port`() {
        assertThat(DomainNormalizer.normalize("user@bet-example.test:8080")).isEqualTo("bet-example.test")
        assertThat(DomainNormalizer.normalize("a:b@host.example:443")).isEqualTo("host.example")
    }

    @Test
    fun `strips a single trailing DNS dot`() {
        assertThat(DomainNormalizer.normalize("bet-example.test.")).isEqualTo("bet-example.test")
        assertThat(DomainNormalizer.normalize("www.bet-example.test..")).isEqualTo("www.bet-example.test")
    }

    @Test
    fun `deduplicates and trims leading dots`() {
        assertThat(DomainNormalizer.normalize(".bet-example.test")).isEqualTo("bet-example.test")
        assertThat(DomainNormalizer.normalize("...bet..example.test")).isEqualTo("bet.example.test")
    }

    @Test
    fun `converts IDN to punycode`() {
        assertThat(DomainNormalizer.normalize("bücher.example")).isEqualTo("xn--bcher-kva.example")
        assertThat(DomainNormalizer.normalize("先生.example")).isEqualTo("xn--44qr78f.example")
    }

    // ---- IP literals are rejected ----

    @Test
    fun `rejects IPv4 literals`() {
        assertThat(DomainNormalizer.normalize("1.2.3.4")).isNull()
        assertThat(DomainNormalizer.normalize("192.168.0.1:53")).isNull()
        assertThat(DomainNormalizer.normalize("10.0.0.255")).isNull()
        assertThat(DomainNormalizer.isValidDomain("1.2.3.4")).isFalse()
    }

    @Test
    fun `allows numeric labels that are not dotted quads`() {
        assertThat(DomainNormalizer.normalize("888.com")).isEqualTo("888.com")
        assertThat(DomainNormalizer.normalize("example.123")).isEqualTo("example.123")
        assertThat(DomainNormalizer.normalize("123.456.789.0")).isEqualTo("123.456.789.0")
    }

    @Test
    fun `rejects IPv6 literals`() {
        assertThat(DomainNormalizer.normalize("::1")).isNull()
        assertThat(DomainNormalizer.normalize("2001:db8::1")).isNull()
        assertThat(DomainNormalizer.normalize("[::1]")).isNull()
        assertThat(DomainNormalizer.normalize("http://[::1]/")).isNull()
    }

    // ---- malformed input ----

    @Test
    fun `rejects empty and blank input`() {
        assertThat(DomainNormalizer.normalize(null)).isNull()
        assertThat(DomainNormalizer.normalize("")).isNull()
        assertThat(DomainNormalizer.normalize("   ")).isNull()
        assertThat(DomainNormalizer.normalize(".")).isNull()
        assertThat(DomainNormalizer.normalize("...")).isNull()
    }

    @Test
    fun `rejects illegal characters`() {
        assertThat(DomainNormalizer.normalize("bet_example.test")).isNull()
        assertThat(DomainNormalizer.normalize("bet example.test")).isNull()
        assertThat(DomainNormalizer.normalize("bet+example.test")).isNull()
        assertThat(DomainNormalizer.normalize("bet!example.test")).isNull()
        assertThat(DomainNormalizer.normalize("bet-example.test:abc")).isNull()
    }

    @Test
    fun `rejects labels starting or ending with hyphens`() {
        assertThat(DomainNormalizer.normalize("-bet.example")).isNull()
        assertThat(DomainNormalizer.normalize("bet-.example")).isNull()
        assertThat(DomainNormalizer.normalize("www.-bet.example")).isNull()
    }

    @Test
    fun `rejects overlength labels and names`() {
        val longLabel = "a".repeat(64)
        assertThat(DomainNormalizer.normalize("$longLabel.example")).isNull()
        // 5x60-char labels = 304 chars; each label is valid, the aggregate name is not.
        val longName = (1..5).joinToString(".") { "a".repeat(60) }
        assertThat(longName.length).isGreaterThan(DomainNormalizer.MAX_NAME_LENGTH)
        assertThat(DomainNormalizer.normalize(longName)).isNull()
    }

    @Test
    fun `rejects bare wildcard and keeps flag for star rules`() {
        assertThat(DomainNormalizer.normalize("*")).isNull()
        val full = DomainNormalizer.normalizeWithFlags("*.ads.example.com")
        assertThat(full).isNotNull()
        assertThat(full!!.domain).isEqualTo("ads.example.com")
        assertThat(full.wildcard).isTrue()
        val plain = DomainNormalizer.normalizeWithFlags("ads.example.com")
        assertThat(plain!!.wildcard).isFalse()
    }

    @Test
    fun `normalizeWithFlags allows valid domains`() {
        val result = DomainNormalizer.normalizeWithFlags("  Www.Bet-Example.TEST ")
        assertThat(result!!.domain).isEqualTo("www.bet-example.test")
        assertThat(result.wildcard).isFalse()
    }

    // ---- validity ----

    @Test
    fun `isValidDomain accepts normal names`() {
        assertThat(DomainNormalizer.isValidDomain("bet-example.test")).isTrue()
        assertThat(DomainNormalizer.isValidDomain("com")).isTrue()
    }

    // ---- superdomains ----

    @Test
    fun `superdomains are longest-first including the domain itself`() {
        assertThat(DomainNormalizer.superdomains("www.bet.example.com"))
            .containsExactly("www.bet.example.com", "bet.example.com", "example.com", "com")
            .inOrder()
    }

    @Test
    fun `superdomains of a single label is itself`() {
        assertThat(DomainNormalizer.superdomains("com")).containsExactly("com")
    }
}