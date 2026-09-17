package dev.gamblock.core.release

import dev.gamblock.protection.domainengine.DomainNormalizer

/**
 * Protected infrastructure allowlist.
 *
 * These registrable domains are treated as critical system infrastructure: OS stores,
 * identity/SSO, OS update/CDN backbones and widely relied-upon services. A rule that is
 * identical to, or a subdomain of, any entry here must NEVER be auto-activated by an
 * upstream source (a poisoned source must not be able to sever system services
 * globally). Such rules are downgraded to CANDIDATE status and require explicit human
 * review in the release pipeline before they could ever ship as ACTIVE.
 *
 * The list is intentionally conservative (Tier-1 OS/routing infrastructure only). It is
 * NOT a general ad-blocking judgement, so it stays small and auditable.
 */
object CriticalAllowlist {
    const val TIER: String = "critical-infrastructure"

    private val CRITICAL = setOf(
        // Android / Google system services (Play Store, GCM, account sync, telemetry none).
        "android.com",
        "gstatic.com",
        "google.com",
        "googleapis.com",
        "googlesource.com",
        "ggpht.com",
        "googleusercontent.com",
        "googlevideo.com",
        "gvt1.com",
        "googlefiber.net",
        // Apple identity / app store.
        "apple.com",
        "icloud.com",
        "mzstatic.com",
        "appstore.com",
        "icloud-content.com",
        // Microsoft OS update / Office / identity.
        "microsoft.com",
        "msftconnecttest.com",
        "windowsupdate.com",
        "windows.com",
        "live.com",
        "office.com",
        "office.net",
        "onedrive.com",
        "azureedge.net",
        // Amazon / AWS infrastructure.
        "amazon.com",
        "amazonaws.com",
        "awsstatic.com",
        "amazontrust.com",
        // Cloudflare backbone (DNS + CDN edge).
        "cloudflare.com",
        "cloudflare.net",
        "cloudflare-dns.com",
        // CDN / edge providers widely used by OS and identity infrastructure.
        "akamai.net",
        "akamaized.net",
        "akamaihd.net",
        "fastly.net",
        "fastlylb.net",
        "edgekey.net",
        "edgesuite.net",
        "azureedge.net.globalrouting.com",
        // DNS / time infrastructure.
        "iana.org",
        "ietf.org",
        "ntp.org",
        "time.gov",
        "registrar-servers.com",
        // Major identity providers / stores many accounts rely on.
        "facebook.com",
        "fbcdn.net",
        "whatsapp.com",
        "instagram.com",
        "twitter.com",
        "x.com",
        "appleid.apple.com",
        // Code hosting relied on by the ecosystem.
        "github.com",
        "githubusercontent.com",
        "github.io",
        "gitlab.com",
        // Wikimedia.
        "wikipedia.org",
        "wikimedia.org",
        // Mozilla.
        "mozilla.org",
        "firefox.com",
        // Linux/Unix core.
        "kernel.org",
        "debian.org",
        "ubuntu.com",
        "canonical.com",
        "f-droid.org",
        // DNS resolver infrastructure some networks depend on.
        "opendns.com",
        "nextdns.io",
        "quad9.net",
        // Certificate infrastructure.
        "digicert.com",
        "letsencrypt.org",
        "identrust.com",
    ).mapNotNull { DomainNormalizer.normalize(it) }.toSet()

    private val criticalCache = buildSet {
        for (base in CRITICAL) {
            add(base)
            // Precompute the suffix forms is not possible statically; check at runtime.
        }
    }

    /** True when [normalized] equals a critical base or lives beneath one. */
    fun isProtected(normalizedDomain: String): Boolean {
        if (normalizedDomain in criticalCache) return true
        for (base in CRITICAL) {
            if (normalizedDomain.endsWith(".$base")) return true
        }
        return false
    }

    /** Exposed for diagnostics/tests. */
    fun entries(): Set<String> = CRITICAL
}