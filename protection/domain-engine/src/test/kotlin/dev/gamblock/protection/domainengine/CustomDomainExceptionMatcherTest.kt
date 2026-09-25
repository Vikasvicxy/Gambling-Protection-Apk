package dev.gamblock.protection.domainengine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CustomDomainExceptionMatcherTest {

    @Test
    fun `exact normalized domain matches`() {
        assertThat(CustomDomainExceptionMatcher.matches("example.test", setOf("example.test"))).isTrue()
    }

    @Test
    fun `superdomain covers subdomain`() {
        assertThat(CustomDomainExceptionMatcher.matches("ads.example.test", setOf("example.test"))).isTrue()
    }

    @Test
    fun `unrelated domain does not match`() {
        assertThat(CustomDomainExceptionMatcher.matches("other.test", setOf("example.test"))).isFalse()
    }

    @Test
    fun `empty set never matches`() {
        assertThat(CustomDomainExceptionMatcher.matches("example.test", emptySet())).isFalse()
        assertThat(CustomDomainExceptionMatcher.matches("", setOf("example.test"))).isFalse()
    }

    @Test
    fun `subdomain exception does not cover parent`() {
        assertThat(CustomDomainExceptionMatcher.matches("example.test", setOf("ads.example.test"))).isFalse()
    }
}