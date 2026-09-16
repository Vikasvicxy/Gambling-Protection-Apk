package dev.gamblock.core.common.clock

/**
 * Wall-clock time source. Explicitly NOT trusted alone for commitment accounting
 * because the user can change it. Kept as a separate seam for testing.
 */
fun interface WallClock {
    fun nowEpochMillis(): Long
}

/**
 * Monotonic time source (magic of `SystemClock.elapsedRealtime()`). Survives timezone
 * changes and manual clock changes; resets to zero across device reboots.
 */
fun interface MonotonicClock {
    fun nowElapsedMillis(): Long

    companion object {
        val SYSTEM: MonotonicClock = MonotonicClock {
            android.os.SystemClock.elapsedRealtime()
        }
    }
}

object SystemWallClock : WallClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}