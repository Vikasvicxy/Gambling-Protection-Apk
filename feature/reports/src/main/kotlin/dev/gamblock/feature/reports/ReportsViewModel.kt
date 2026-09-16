package dev.gamblock.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.data.repository.ActivityEventRepository
import dev.gamblock.data.repository.BlockEventRepository
import dev.gamblock.data.repository.FalsePositiveReportRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val blockEventRepository: BlockEventRepository,
    private val activityEventRepository: ActivityEventRepository,
    private val falsePositiveReportRepository: FalsePositiveReportRepository,
) : ViewModel() {

    val recentBlocked: StateFlow<List<dev.gamblock.core.model.BlockAttemptGroup>> =
        blockEventRepository.observeRecent(50).stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyList(),
        )

    val totalAttempts: StateFlow<Int> =
        blockEventRepository.observeTotalAttempts().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val activity = activityEventRepository.observeRecent(30)

    val submittedReports = falsePositiveReportRepository.observeRecent(20)
}