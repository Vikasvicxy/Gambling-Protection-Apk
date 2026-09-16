package dev.gamblock.core.testing

import dev.gamblock.core.common.clock.MonotonicClock
import java.util.concurrent.atomic.AtomicLong

/** Mutable monotonic clock for tests. Monotonic = resets only via [reboot]. */
class FakeMonotonicClock(initial: Long = 0L) : MonotonicClock {
    private val now = AtomicLong(initial)
    override fun nowElapsedMillis(): Long = now.get()

    fun advance(millis: Long) {
        now.addAndGet(millis)
    }

    /** Simulates a device reboot by resetting elapsed time to 0. */
    fun reboot() {
        now.set(0L)
    }
}