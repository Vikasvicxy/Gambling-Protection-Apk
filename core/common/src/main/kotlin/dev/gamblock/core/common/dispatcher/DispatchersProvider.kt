package dev.gamblock.core.common.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Coroutine dispatcher seam so production and tests can differ.
 */
interface DispatchersProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val dnsUpstream: CoroutineDispatcher
}

class DefaultDispatchersProvider : DispatchersProvider {
    override val main: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main
    override val io: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    override val default: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
    override val dnsUpstream: CoroutineDispatcher =
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
        ) { r -> Thread(r, "shield-dns-upstream") }.asCoroutineDispatcher()
}