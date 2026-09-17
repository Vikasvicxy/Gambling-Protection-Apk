package dev.gamblock.core.accountability

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.DeviceReplacementRequest
import dev.gamblock.core.model.DeviceReplacementState
import dev.gamblock.core.model.ReplacementDecision
import org.junit.Test

class ReplacementServiceTest {

    private val service = ReplacementService()
    private val oldId = "aa".repeat(16)
    private val newId = "bb".repeat(16)
    private val partnerId = "cc".repeat(16)

    private fun request() = DeviceReplacementRequest(
        id = "rep-1",
        oldDeviceId = oldId,
        newDeviceId = newId,
        remainingCommitmentMillis = 487L * 24 * 60 * 60 * 1000L,
        requestedAtEpochMs = 1_700_000_000_000L,
        status = DeviceReplacementState.AWAITING_CONFIRMATION,
    )

    @Test
    fun `reasonable remaining tenure is allowed`() {
        assertThat(service.evaluate(30L * 24 * 60 * 60 * 1000L))
            .isInstanceOf(ReplacementDecision.Allowed::class.java)
    }

    @Test
    fun `huge tenure exceeding cap is denied`() {
        val cap = DeviceReplacementRequest.MAX_TENURE_TRANSFER_MILLIS
        val decision = service.evaluate(cap + 1)
        assertThat(decision).isInstanceOf(ReplacementDecision.Denied::class.java)
    }

    @Test
    fun `negative tenure is denied`() {
        assertThat(service.evaluate(-1L)).isInstanceOf(ReplacementDecision.Denied::class.java)
    }

    @Test
    fun `old device cannot confirm its own replacement`() {
        val result = service.confirm(request(), oldId, 1_700_000_100_000L)
        assertThat((result as ReplacementService.ConfirmResult.Rejected).reason)
            .contains("old device")
    }

    @Test
    fun `new device cannot confirm its own replacement`() {
        val result = service.confirm(request(), newId, 1_700_000_100_000L)
        assertThat((result as ReplacementService.ConfirmResult.Rejected).reason)
            .contains("new device")
    }

    @Test
    fun `partner confirmation completes the transfer`() {
        val result = service.confirm(request(), partnerId, 1_700_000_100_000L)
        val confirmed = (result as ReplacementService.ConfirmResult.Success).request
        assertThat(confirmed.status).isEqualTo(DeviceReplacementState.CONFIRMED)
        assertThat(confirmed.confirmedBy).containsExactly(partnerId)
        assertThat(confirmed.confirmedAtEpochMs).isEqualTo(1_700_000_100_000L)
    }

    @Test
    fun `expired request is rejected`() {
        val old = request().copy(requestedAtEpochMs = 1_700_000_000_000L - 8L * 24 * 60 * 60 * 1000L)
        val result = service.confirm(old, partnerId, 1_700_000_000_000L)
        assertThat((result as ReplacementService.ConfirmResult.Rejected).reason)
            .contains("expired")
    }

    @Test
    fun `already confirmed request is rejected`() {
        val done = request().copy(status = DeviceReplacementState.CONFIRMED)
        val result = service.confirm(done, partnerId, 1_700_000_100_000L)
        assertThat((result as ReplacementService.ConfirmResult.Rejected).reason)
            .contains("not awaiting")
    }
}