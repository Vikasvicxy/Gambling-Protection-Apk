package dev.gamblock.core.accountability

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.AccountabilityEventType
import dev.gamblock.core.model.AccountabilitySeverity
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.EventGroupResult
import dev.gamblock.core.model.GroupingConfig
import org.junit.Test

class EventGrouperTest {

    private var now = 1_700_000_000_000L
    private val config = GroupingConfig(cooldownWindowMillis = 60_000L, repeatedThreshold = 3)
    private val grouper = EventGrouper(config, { now })

    private val device = "aa".repeat(16)

    @Test
    fun `first occurrence emits immediately at LOW severity`() {
        val result = grouper.aggregate(null, device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING, now)
        assertThat(result).isInstanceOf(EventGroupResult.Emitted::class.java)
        val emitted = result as EventGroupResult.Emitted
        assertThat(emitted.event.count).isEqualTo(1)
        assertThat(emitted.event.severity).isEqualTo(AccountabilitySeverity.LOW)
        assertThat(emitted.event.type).isEqualTo(AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED)
    }

    @Test
    fun `repeated attempts within cooldown are suppressed and grouped`() {
        var state: dev.gamblock.core.model.EventGroupingState? = null

        val first = grouper.aggregate(null, device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING, now)
        state = (first as EventGroupResult.Emitted).state

        val second = grouper.aggregate(state, device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING, now + 100L)
        assertThat(second).isInstanceOf(EventGroupResult.Suppressed::class.java)
        state = (second as EventGroupResult.Suppressed).state
        assertThat(state!!.count).isEqualTo(2)

        val third = grouper.aggregate(state, device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING, now + 200L)
        assertThat(third).isInstanceOf(EventGroupResult.Emitted::class.java)
        val grouped = (third as EventGroupResult.Emitted).event
        assertThat(grouped.type).isEqualTo(AccountabilityEventType.REPEATED_GAMBLING_ATTEMPTS_BLOCKED)
        assertThat(grouped.severity).isEqualTo(AccountabilitySeverity.MEDIUM)
        assertThat(grouped.count).isEqualTo(3)
        assertThat(grouped.grouped).isTrue()
    }

    @Test
    fun `ten retries collapse into one emitted event`() {
        var state: dev.gamblock.core.model.EventGroupingState? = null
        var emissions = 0
        repeat(10) { i ->
            val r = grouper.aggregate(state, device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING, now + i * 10L)
            when (r) {
                is EventGroupResult.Emitted -> {
                    emissions++
                    state = r.state
                }
                is EventGroupResult.Suppressed -> state = r.state
                is EventGroupResult.ResetThenSuppressed -> state = r.state
            }
        }
        assertThat(emissions).isEqualTo(2) // first + repeated-threshold event
    }

    @Test
    fun `cooldown elapsed resets and suppresses single occurrence`() {
        val first = grouper.aggregate(null, device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING, now)
        val state = (first as EventGroupResult.Emitted).state

        val later = grouper.aggregate(state, device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING, now + 120_000L)
        assertThat(later).isInstanceOf(EventGroupResult.ResetThenSuppressed::class.java)
    }

    @Test
    fun `distinct categories group independently`() {
        val casino = grouper.aggregate(null, device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.CASINO, now)
        val sports = grouper.aggregate(null, device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.SPORTSBOOK, now)
        assertThat(casino).isInstanceOf(EventGroupResult.Emitted::class.java)
        assertThat(sports).isInstanceOf(EventGroupResult.Emitted::class.java)
    }

    @Test
    fun `grouping key is stable across device type and category`() {
        val a = grouper.groupKey(device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING)
        val b = grouper.groupKey(device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING)
        val c = grouper.groupKey(device, AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.CASINO)
        val other = grouper.groupKey("bb".repeat(16), AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, Category.GAMBLING)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a).isNotEqualTo(other)
    }
}

class SeverityCalculatorTest {

    private val calc = SeverityCalculator()

    @Test
    fun `single gambling attempt is LOW`() {
        assertThat(calc.forEvent(AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, 1)).isEqualTo(AccountabilitySeverity.LOW)
    }

    @Test
    fun `repeated attempts are MEDIUM`() {
        assertThat(calc.forEvent(AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED, 5)).isEqualTo(AccountabilitySeverity.MEDIUM)
        assertThat(calc.forEvent(AccountabilityEventType.REPEATED_GAMBLING_ATTEMPTS_BLOCKED, 5)).isEqualTo(AccountabilitySeverity.MEDIUM)
    }

    @Test
    fun `gambling app attempt is MEDIUM`() {
        assertThat(calc.forEvent(AccountabilityEventType.GAMBLING_APP_ATTEMPT_BLOCKED, 1)).isEqualTo(AccountabilitySeverity.MEDIUM)
    }

    @Test
    fun `vpn unexpectedly inactive is HIGH`() {
        assertThat(calc.forEvent(AccountabilityEventType.VPN_UNEXPECTEDLY_INACTIVE, 1)).isEqualTo(AccountabilitySeverity.HIGH)
        assertThat(calc.forEvent(AccountabilityEventType.REQUIRED_PERMISSION_REMOVED, 1)).isEqualTo(AccountabilitySeverity.HIGH)
    }

    @Test
    fun `heartbeat lost is CRITICAL and prompts immediately`() {
        assertThat(calc.forEvent(AccountabilityEventType.HEARTBEAT_LOST, 1)).isEqualTo(AccountabilitySeverity.CRITICAL)
        assertThat(calc.needsImmediatePrompt(AccountabilityEventType.HEARTBEAT_LOST)).isTrue()
        assertThat(calc.needsImmediatePrompt(AccountabilityEventType.TAMPER_EVIDENCE_GENERATED)).isTrue()
        assertThat(calc.needsImmediatePrompt(AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED)).isFalse()
    }
}