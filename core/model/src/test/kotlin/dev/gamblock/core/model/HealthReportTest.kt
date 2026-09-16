package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HealthReportTest {

    private fun comp(c: HealthComponent, s: HealthStatus) =
        ComponentHealth(component = c, status = s, message = "msg", measuredAtEpochMs = 1_700_000_000_000L)

    @Test
    fun `aggregate of no components is UNKNOWN`() {
        val report = HealthReport.aggregate(emptyList(), measuredAt = 42L)
        assertThat(report.overall).isEqualTo(HealthStatus.UNKNOWN)
        assertThat(report.components).isEmpty()
        assertThat(report.measuredAtEpochMs).isEqualTo(42L)
    }

    @Test
    fun `aggregate of healthy components is HEALTHY`() {
        val report = HealthReport.aggregate(
            listOf(comp(HealthComponent.VPN, HealthStatus.HEALTHY), comp(HealthComponent.DNS, HealthStatus.HEALTHY)),
            measuredAt = 42L,
        )
        assertThat(report.overall).isEqualTo(HealthStatus.HEALTHY)
    }

    @Test
    fun `any CRITICAL makes the report CRITICAL regardless of others`() {
        val report = HealthReport.aggregate(
            listOf(
                comp(HealthComponent.VPN, HealthStatus.CRITICAL),
                comp(HealthComponent.DNS, HealthStatus.HEALTHY),
                comp(HealthComponent.BOOT, HealthStatus.DEGRADED),
                comp(HealthComponent.UPDATE, HealthStatus.UNKNOWN),
            ),
            measuredAt = 42L,
        )
        assertThat(report.overall).isEqualTo(HealthStatus.CRITICAL)
    }

    @Test
    fun `DEGRADED without CRITICAL makes the report DEGRADED`() {
        val report = HealthReport.aggregate(
            listOf(comp(HealthComponent.DNS, HealthStatus.DEGRADED), comp(HealthComponent.VPN, HealthStatus.HEALTHY)),
            measuredAt = 42L,
        )
        assertThat(report.overall).isEqualTo(HealthStatus.DEGRADED)
    }

    @Test
    fun `UNKNOWN without worse makes the report UNKNOWN`() {
        val report = HealthReport.aggregate(
            listOf(comp(HealthComponent.DNS, HealthStatus.UNKNOWN), comp(HealthComponent.VPN, HealthStatus.HEALTHY)),
            measuredAt = 42L,
        )
        assertThat(report.overall).isEqualTo(HealthStatus.UNKNOWN)
    }

    @Test
    fun `priority order is CRITICAL greater than DEGRADED greater than UNKNOWN greater than HEALTHY`() {
        assertThat(HealthReport.aggregate(listOf(comp(HealthComponent.DNS, HealthStatus.HEALTHY)), 42L).overall)
            .isEqualTo(HealthStatus.HEALTHY)
        assertThat(HealthReport.aggregate(listOf(comp(HealthComponent.DNS, HealthStatus.UNKNOWN)), 42L).overall)
            .isEqualTo(HealthStatus.UNKNOWN)
        assertThat(HealthReport.aggregate(listOf(comp(HealthComponent.DNS, HealthStatus.DEGRADED)), 42L).overall)
            .isEqualTo(HealthStatus.DEGRADED)
        assertThat(HealthReport.aggregate(listOf(comp(HealthComponent.DNS, HealthStatus.CRITICAL)), 42L).overall)
            .isEqualTo(HealthStatus.CRITICAL)
    }

    @Test
    fun `components are sorted by ordinal regardless of input order`() {
        val report = HealthReport.aggregate(
            listOf(
                comp(HealthComponent.UPDATE, HealthStatus.HEALTHY),
                comp(HealthComponent.BOOT, HealthStatus.HEALTHY),
                comp(HealthComponent.DNS, HealthStatus.HEALTHY),
            ),
            measuredAt = 42L,
        )
        assertThat(report.components.map { it.component })
            .containsExactly(HealthComponent.DNS, HealthComponent.BOOT, HealthComponent.UPDATE)
            .inOrder()
    }

    @Test
    fun `component lookup returns the matching component or null`() {
        val report = HealthReport.aggregate(
            listOf(comp(HealthComponent.VPN, HealthStatus.CRITICAL)),
            measuredAt = 42L,
        )
        assertThat(report.component(HealthComponent.VPN)?.status).isEqualTo(HealthStatus.CRITICAL)
        assertThat(report.component(HealthComponent.DNS)).isNull()
    }
}