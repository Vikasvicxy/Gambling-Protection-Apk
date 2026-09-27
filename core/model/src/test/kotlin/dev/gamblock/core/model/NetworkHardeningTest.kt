package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Ipv6LeakPolicyTest {

    @Test
    fun `AAAA is suppressed when the feature is on`() {
        assertThat(Ipv6LeakPolicy.shouldSuppress(Ipv6LeakPolicy.TYPE_AAAA, enabled = true)).isTrue()
    }

    @Test
    fun `AAAA passes through when the feature is off`() {
        assertThat(Ipv6LeakPolicy.shouldSuppress(Ipv6LeakPolicy.TYPE_AAAA, enabled = false)).isFalse()
    }

    @Test
    fun `A is never suppressed, because that would break the lookup`() {
        assertThat(Ipv6LeakPolicy.shouldSuppress(Ipv6LeakPolicy.TYPE_A, enabled = true)).isFalse()
    }

    @Test
    fun `other record types are never suppressed`() {
        listOf(2, 5, 6, 12, 15, 16, 33, 41, 65, 255).forEach { type ->
            assertThat(Ipv6LeakPolicy.shouldSuppress(type, enabled = true)).isFalse()
        }
    }

    @Test
    fun `address query detection covers A and AAAA only`() {
        assertThat(Ipv6LeakPolicy.isAddressQuery(Ipv6LeakPolicy.TYPE_A)).isTrue()
        assertThat(Ipv6LeakPolicy.isAddressQuery(Ipv6LeakPolicy.TYPE_AAAA)).isTrue()
        assertThat(Ipv6LeakPolicy.isAddressQuery(DnsConstantsProxy.TXT)).isFalse()
    }

    @Test
    fun `the wire values match the DNS standard`() {
        // A hard-coded 28 that drifts from the codec would silently stop
        // suppressing anything.
        assertThat(Ipv6LeakPolicy.TYPE_A).isEqualTo(1)
        assertThat(Ipv6LeakPolicy.TYPE_AAAA).isEqualTo(28)
    }

    private object DnsConstantsProxy {
        const val TXT = 16
    }
}

class SafeSearchPolicyTest {

    @Test
    fun `recognises google search hosts`() {
        listOf("www.google.com", "google.com", "images.google.com", "www.google.co.in", "news.google.com")
            .forEach {
                assertThat(SafeSearchPolicy.classify(it)).isInstanceOf(SearchEngineMatch.Engine::class.java)
                assertThat(SafeSearchPolicy.engineFor(it)).isEqualTo(SafeSearchEngine.GOOGLE)
            }
    }

    @Test
    fun `recognises bing and duckduckgo`() {
        assertThat(SafeSearchPolicy.engineFor("www.bing.com")).isEqualTo(SafeSearchEngine.BING)
        assertThat(SafeSearchPolicy.engineFor("duckduckgo.com")).isEqualTo(SafeSearchEngine.DUCKDUCKGO)
        assertThat(SafeSearchPolicy.engineFor("html.duckduckgo.com"))
            .isEqualTo(SafeSearchEngine.DUCKDUCKGO)
    }

    @Test
    fun `a substring lookalike is not a search engine`() {
        // The reason matching is on the base domain rather than `contains`.
        listOf(
            "notgoogle.com",
            "google.com.evil.example",
            "mybing.com",
            "duckduckgo.com.attacker.net",
            "googleapis.com",
        ).forEach {
            assertThat(SafeSearchPolicy.classify(it)).isEqualTo(SearchEngineMatch.NotSearch)
        }
    }

    @Test
    fun `an already-safe host is recognised and not nagged`() {
        val match = SafeSearchPolicy.classify("forcesafesearch.google.com")
        assertThat(match).isInstanceOf(SearchEngineMatch.AlreadySafe::class.java)
        assertThat(SafeSearchPolicy.safeSearchUrlFor("forcesafesearch.google.com")).isNull()
    }

    @Test
    fun `safe search url points at the engine's own endpoint`() {
        assertThat(SafeSearchPolicy.safeSearchUrlFor("www.google.com"))
            .isEqualTo("https://forcesafesearch.google.com/search?q=")
        assertThat(SafeSearchPolicy.safeSearchUrlFor("www.bing.com"))
            .isEqualTo("https://strict.bing.com/search?q=")
    }

    @Test
    fun `no url is offered for a non-search host`() {
        assertThat(SafeSearchPolicy.safeSearchUrlFor("example.com")).isNull()
        assertThat(SafeSearchPolicy.safeSearchUrlFor("")).isNull()
    }

    @Test
    fun `matching ignores case and a trailing dot`() {
        assertThat(SafeSearchPolicy.engineFor("WWW.GOOGLE.COM")).isEqualTo(SafeSearchEngine.GOOGLE)
        assertThat(SafeSearchPolicy.engineFor("www.google.com.")).isEqualTo(SafeSearchEngine.GOOGLE)
        assertThat(SafeSearchPolicy.engineFor("  www.bing.com  ")).isEqualTo(SafeSearchEngine.BING)
    }

    @Test
    fun `every engine exposes a safe search host`() {
        SafeSearchEngine.entries.forEach { engine ->
            assertThat(engine.safeSearchHost).isNotEmpty()
            assertThat(engine.displayName).isNotEmpty()
            // The safe host must itself be classified as safe, not as a plain
            // engine host, or the assist would loop on itself.
            assertThat(SafeSearchPolicy.classify(engine.safeSearchHost))
                .isInstanceOf(SearchEngineMatch.AlreadySafe::class.java)
        }
    }
}
