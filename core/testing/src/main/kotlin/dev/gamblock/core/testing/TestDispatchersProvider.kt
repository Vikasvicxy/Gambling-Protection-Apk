package dev.gamblock.core.testing

import dev.gamblock.core.common.dispatcher.DispatchersProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatchersProvider(
    private val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : DispatchersProvider {
    override val main: CoroutineDispatcher get() = testDispatcher
    override val io: CoroutineDispatcher get() = testDispatcher
    override val default: CoroutineDispatcher get() = testDispatcher
    override val dnsUpstream: CoroutineDispatcher get() = UnconfinedTestDispatcher()
}