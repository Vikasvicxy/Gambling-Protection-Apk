package dev.gamblock.core.testing

import dev.gamblock.core.common.clock.WallClock
import java.util.concurrent.atomic.AtomicLong

/** Mutable wall clock for tests. */
class FakeWallClock(initial: Long = 1_000_000_000L) : WallClock {
    private val now = AtomicLong(initial)
    override fun nowEpochMillis(): Long = now.get()

    fun advance(millis: Long) {
        now.addAndGet(millis)
    }

    fun set(millis: Long) {
        now.set(millis)
    }
}