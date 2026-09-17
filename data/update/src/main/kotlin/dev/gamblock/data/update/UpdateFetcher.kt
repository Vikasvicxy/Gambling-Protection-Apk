package dev.gamblock.data.update

/** Platform-agnostic HTTPS fetch abstraction (real impl in `data:update`; fakes in tests). */
interface UpdateFetcher {
    suspend fun fetch(config: UpdateConfig, url: String, maxBytes: Long): ByteArray
}