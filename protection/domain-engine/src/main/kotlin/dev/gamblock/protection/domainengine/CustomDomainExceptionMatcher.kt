package dev.gamblock.protection.domainengine

/** A user-created custom domain exception (bypass). */
object CustomDomainExceptionMatcher {

    /**
     * True when [normalizedDomain] (or a superdomain covering it, e.g. an exception
     * for `example.com` also covers `ads.example.com`) is present in [exceptions].
     */
    fun matches(normalizedDomain: String, exceptions: Set<String>): Boolean {
        if (normalizedDomain.isEmpty() || exceptions.isEmpty()) return false
        if (normalizedDomain in exceptions) return true
        return DomainNormalizer.superdomains(normalizedDomain).any { it in exceptions }
    }
}