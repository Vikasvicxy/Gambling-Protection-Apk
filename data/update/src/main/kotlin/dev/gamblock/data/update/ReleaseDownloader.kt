package dev.gamblock.data.update

import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

class DownloadException(message: String) : Exception(message)

/**
 * Minimal HTTPS fetcher (platform HttpURLConnection; no extra deps). Hard size caps,
 * bounded redirects and timeouts. Never reads a private key, never caches the response.
 */
@Singleton
class ReleaseDownloader @Inject constructor(
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) : UpdateFetcher {

    override suspend fun fetch(config: UpdateConfig, url: String, maxBytes: Long): ByteArray =
        withContext(dispatchers.io) {
            val parsed = runCatching { URL(url) }.getOrNull()
                ?: throw DownloadException("invalid url '$url'")
            if (!parsed.protocol.equals("https", ignoreCase = true)) {
                throw DownloadException("refusing non-https url '$url'")
            }
            if (parsed.host == null) throw DownloadException("url with no host: '$url'")

            var current: URL = parsed
            var redirects = 0
            while (true) {
                val conn = (current.openConnection() as? HttpURLConnection)
                    ?: throw DownloadException("unsupported protocol on '${current.toExternalForm()}'")
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = config.connectTimeoutMs
                    conn.readTimeout = config.readTimeoutMs
                    conn.setRequestProperty("Accept-Encoding", "identity")
                    conn.setRequestProperty("User-Agent", "Shield-Update/1 (+signed-blocklist)")
                    val code = conn.responseCode
                    when {
                        code in 300..399 -> {
                            if (redirects >= config.maxRedirects) {
                                throw DownloadException("too many redirects for '$url'")
                            }
                            val location = conn.getHeaderField("Location")
                                ?: throw DownloadException("redirect without Location")
                            current = URL(current, location)
                            redirects++
                            continue
                        }
                        code !in 200..299 -> throw DownloadException("http $code from ${current.toExternalForm()}")
                        else -> {
                            val declaredLength = conn.contentLengthLong
                            if (declaredLength > maxBytes) {
                                throw DownloadException("content-length ${declaredLength}B exceeds cap ${maxBytes}B")
                            }
                            return@withContext conn.inputStream.use { input ->
                                val out = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    if (total > maxBytes) {
                                        throw DownloadException("stream exceeds cap ${maxBytes}B")
                                    }
                                    out.write(buffer, 0, read)
                                }
                                out.toByteArray()
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            error("unreachable: fetch loop must return or throw")
        }
}