package dev.gamblock.data.update

import dev.gamblock.core.release.UpdateChannel

/**
 * Transport configuration for the signed update pipeline.
 *
 * Serverless by design: artifacts are served from a static CDN (GitHub Pages /
 * jsDelivr / S3 bucket). A channel URL must be a stable https base that lists
 * `manifest.json` and the artifacts it references. Nothing here carries a secret.
 */
data class UpdateConfig(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    /** Base URL (https only) under which `manifest.json` and artifacts live. */
    val baseUrl: String = DEFAULT_STABLE_BASE_URL,
    val preferDelta: Boolean = true,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 30_000,
    val maxRedirects: Int = 5,
    /** Manifest JSON is tiny; keep a hard ceiling. */
    val maxManifestBytes: Long = 64L * 1024L,
    /** Full payloads are compressed; cap far above any plausible real size. */
    val maxArtifactBytes: Long = 128L * 1024L * 1024L,
) {
    val manifestUrl: String get() = if (baseUrl.endsWith('/')) "${baseUrl}manifest.json" else "$baseUrl/manifest.json"

    fun artifactUrl(fileName: String): String =
        if (baseUrl.endsWith('/')) "${baseUrl}${fileName.removePrefix("/")}" else "$baseUrl/${fileName.removePrefix("/")}"

    companion object {
        /**
         * Placeholder until the release CDN goes live. Https + static hosting only.
         * An unreachable base is handled gracefully (update state FAILED, protection
         * keeps the last-known-good database).
         */
        const val DEFAULT_STABLE_BASE_URL = "https://gamblock.github.io/shield-blocklist/stable/"
    }
}