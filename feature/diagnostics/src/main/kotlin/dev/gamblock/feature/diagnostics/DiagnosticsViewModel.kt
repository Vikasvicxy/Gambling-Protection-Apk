package dev.gamblock.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.model.HealthReport
import dev.gamblock.core.model.OemGuidanceItem
import dev.gamblock.core.model.OemInfo
import dev.gamblock.core.model.VpnConflictInfo
import dev.gamblock.protection.health.HealthEngine
import dev.gamblock.protection.oem.OemInfoRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val report: HealthReport? = null,
    val oem: OemInfo? = null,
    val conflict: VpnConflictInfo? = null,
    val guidance: List<OemGuidanceItem> = emptyList(),
    val isBatteryOptimizationExempt: Boolean = false,
    val isCharging: Boolean = false,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val healthEngine: HealthEngine,
    private val oemInfoRepository: OemInfoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = DiagnosticsUiState(
                report = healthEngine.measure(),
                oem = oemInfoRepository.oemInfo,
                conflict = oemInfoRepository.vpnConflict,
                guidance = oemInfoRepository.guidance,
                isBatteryOptimizationExempt = oemInfoRepository.batteryStatus.isIgnoringBatteryOptimizations,
                isCharging = oemInfoRepository.batteryStatus.isCharging,
            )
        }
    }
}