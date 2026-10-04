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
    /**
     * Mirrors of [baseUrl] that publish byte-identical releases, tried in order when the
     * primary is unreachable.
     *
     * This widens AVAILABILITY ONLY, never trust: every candidate response is subject to
     * the same ECDSA signature check, SHA-256 pin and forward-only version policy. A
     * mirror that serves a forged or stale manifest cannot get further than a dead primary
     * could, so the list may safely contain third-party CDNs and mirrors that are not under
     * our control.
     */
    val fallbackBaseUrls: List<String> = DEFAULT_FALLBACK_BASE_URLS,
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
  
      /**
       * Every base that may serve this channel, primary first, de-duplicated and https-only.
       *
       * Blank entries and non-https entries are dropped rather than trusted: [ReleaseDownloader]
       * would reject them anyway, but filtering here keeps the candidate list honest and lets
       * a misconfigured build fail fast instead of burning a request on it.
       */
      fun candidateBaseUrls(): List<String> =
          (listOf(baseUrl) + fallbackBaseUrls)
              .map { it.trim() }
              .filter { it.isNotEmpty() && it.startsWith("https://", ignoreCase = true) }
              .map { if (it.endsWith('/')) it else "$it/" }
              .distinct()
  
      fun manifestUrls(): List<String> = candidateBaseUrls().map { "${it}manifest.json" }
  
      fun artifactUrls(fileName: String): List<String> {
          val clean = fileName.removePrefix("/")
          return candidateBaseUrls().map { "${it}${clean}" }
      }
  
      companion object {
          /**
           * Primary CDN. Kept as the first candidate so an existing signed deployment keeps
           * working untouched; the mirrors below only matter when it is down.
           */
          const val DEFAULT_STABLE_BASE_URL = "https://gamblock.github.io/shield-blocklist/stable/"
  
          /**
           * Same `docs/blocklist/` directory, served from two further independent origins.
           *
           * The point is fault tolerance, not redundancy for its own sake: a blocklist the app
           * cannot fetch is a blocklist that silently stops improving, and the only supported way
           * to keep protection current is a successful fetch. Every one of these is still gated
           * behind signature verification, so an origin we do not control can only cause a
           * failed download, never an accepted forgery.
           */
          val DEFAULT_FALLBACK_BASE_URLS: List<String> = listOf(
              "https://cdn.jsdelivr.net/gh/Vikasvicxy/Gambling-Protection-Apk@master/docs/blocklist/",
              "https://vikasvicxy.github.io/Gambling-Protection-Apk/blocklist/",
          )
      }
  }