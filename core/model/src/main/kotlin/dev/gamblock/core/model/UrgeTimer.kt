package dev.gamblock.core.model

data class UrgeTimerConfig(
    val durationMs: Long = DEFAULT_DURATION_MS,
    val pledgePhrase: String = DEFAULT_PLEDGE,
) {
    init {
        require(durationMs in 1_000L..MAX_DURATION_MS) { "durationMs out of range" }
        require(pledgePhrase.isNotBlank()) { "pledgePhrase must not be blank" }
    }

    companion object {
        const val DEFAULT_DURATION_MS: Long = 15L * 60L * 1_000L
        const val MAX_DURATION_MS: Long = 24L * 60L * 60L * 1_000L
        const val DEFAULT_PLEDGE: String = "I choose my future over a bet"
    }
}

sealed interface UrgeTimerState {

    data object Idle : UrgeTimerState

    data class Counting(
        val totalMs: Long,
        val remainingMs: Long,
        val startedAtEpochMs: Long,
    ) : UrgeTimerState {
        val progress: Float
            get() = if (totalMs <= 0L) 1f else ((totalMs - remainingMs).toFloat() / totalMs).coerceIn(0f, 1f)

        val remainingSeconds: Long
            get() = (remainingMs / 1_000L).coerceAtLeast(0L)
    }

    data class AwaitingPledge(
        val totalMs: Long,
        val expiredAtEpochMs: Long,
        val attempts: Int = 0,
        val lastPledgeAccepted: Boolean = false,
    ) : UrgeTimerState

    data object Unlocked : UrgeTimerState
}

data class UrgeTimerSnapshot(
    val state: UrgeTimerState,
    val canCancel: Boolean,
    val canSubmitPledge: Boolean,
    val locked: Boolean,
)

class UrgeTimerMachine(
    private val config: UrgeTimerConfig = UrgeTimerConfig(),
) {

    var state: UrgeTimerState = UrgeTimerState.Idle
        private set

    fun snapshot(): UrgeTimerSnapshot = UrgeTimerSnapshot(
        state = state,
        canCancel = state !is UrgeTimerState.Unlocked && state !is UrgeTimerState.Idle,
        canSubmitPledge = state is UrgeTimerState.AwaitingPledge,
        locked = state !is UrgeTimerState.Idle && state !is UrgeTimerState.Unlocked,
    )

    fun start(nowEpochMs: Long): UrgeTimerState {
        state = UrgeTimerState.Counting(
            totalMs = config.durationMs,
            remainingMs = config.durationMs,
            startedAtEpochMs = nowEpochMs,
        )
        return state
    }

    fun tick(nowEpochMs: Long): UrgeTimerState {
        val current = state
        if (current !is UrgeTimerState.Counting) return current
        val elapsed = nowEpochMs - current.startedAtEpochMs
        val remaining = (config.durationMs - elapsed).coerceAtLeast(0L)
        val next = if (remaining <= 0L) {
            UrgeTimerState.AwaitingPledge(
                totalMs = config.durationMs,
                expiredAtEpochMs = current.startedAtEpochMs + config.durationMs,
                attempts = 0,
            )
        } else {
            UrgeTimerState.Counting(
                totalMs = current.totalMs,
                remainingMs = remaining,
                startedAtEpochMs = current.startedAtEpochMs,
            )
        }
        state = next
        return next
    }

    fun submitPledge(text: String): UrgeTimerState {
        val current = state
        if (current !is UrgeTimerState.AwaitingPledge) return current
        if (!isPledgeSatisfied(text)) {
            state = current.copy(attempts = current.attempts + 1, lastPledgeAccepted = false)
            return state
        }
        state = UrgeTimerState.Unlocked
        return state
    }

    fun cancel(): UrgeTimerState {
        state = UrgeTimerState.Idle
        return state
    }

    fun reset(): UrgeTimerState {
        state = UrgeTimerState.Idle
        return state
    }

    fun isPledgeSatisfied(text: String): Boolean =
        text.trim().equals(config.pledgePhrase.trim(), ignoreCase = true)
}
