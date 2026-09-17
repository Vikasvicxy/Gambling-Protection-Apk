package dev.gamblock.feature.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.accountability.DeviceIdentityService
import dev.gamblock.core.accountability.ParentAuthorization
import dev.gamblock.core.model.InviteCapabilitySelection
import dev.gamblock.core.model.PairingKind
import dev.gamblock.core.model.PairingToken
import dev.gamblock.core.model.PartnerCapability
import dev.gamblock.core.model.PartnerRelationship
import dev.gamblock.core.model.SupervisedChildDevice
import dev.gamblock.data.accountability.AccountabilityRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Parent/Guardian dashboard. */
data class ParentUiState(
    val parentId: String = "",
    val children: List<SupervisedChildDevice> = emptyList(),
    val relationships: List<PartnerRelationship> = emptyList(),
    val activeInvitation: PairingToken? = null,
    val pendingApprovalCount: Int = 0,
    val lastError: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class ParentViewModel @Inject constructor(
    private val repository: AccountabilityRepository,
    private val parentAuthz: ParentAuthorization,
    deviceIdentity: DeviceIdentityService,
) : ViewModel() {

    private val parentId: String = when (val result = deviceIdentity.register(nowEpochMs = System.currentTimeMillis())) {
        is DeviceIdentityService.RegisterResult.Registered -> result.identity.id
        is DeviceIdentityService.RegisterResult.Rejected -> ""
    }

    private val _uiState = MutableStateFlow(ParentUiState(parentId = parentId))

    val uiState: StateFlow<ParentUiState> =
        combine(_uiState, repository.observeRelationships(), repository.observeApprovals()) { base, rels, approvals ->
            base.copy(
                relationships = rels.filter { it.partnerDeviceId == parentId },
                pendingApprovalCount = approvals.count { it.status == dev.gamblock.core.model.ApprovalStatus.PENDING },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ParentUiState(parentId = parentId))

    fun createChildInvitation() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, lastError = null)
            val selection = InviteCapabilitySelection(
                kind = PairingKind.PARENT_SUPERVISED_CHILD,
                capabilities = setOf(
                    PartnerCapability.PARENT_APPROVE_CONFIG_CHANGES,
                    PartnerCapability.PARENT_RECEIVE_STATS,
                    PartnerCapability.RECEIVE_TAMPER_ALERTS,
                    PartnerCapability.RECEIVE_HEARTBEAT_ALERTS,
                ),
                label = "Supervisor for my family",
            )
            when (val result = repository.createInvitation(parentId, PairingKind.PARENT_SUPERVISED_CHILD, selection, System.currentTimeMillis())) {
                is AccountabilityRepository.PairingResult.Created ->
                    _uiState.value = _uiState.value.copy(activeInvitation = result.token, busy = false)
                is AccountabilityRepository.PairingResult.Rejected ->
                    _uiState.value = _uiState.value.copy(lastError = result.reason, busy = false)
            }
        }
    }

    fun acceptChildInvitation(fullToken: String) {
        viewModelScope.launch {
            when (val result = repository.acceptInvitation(fullToken, parentId, System.currentTimeMillis())) {
                is AccountabilityRepository.AcceptOutcome.Linked ->
                    _uiState.value = _uiState.value.copy(busy = false)
                is AccountabilityRepository.AcceptOutcome.Rejected ->
                    _uiState.value = _uiState.value.copy(lastError = result.reason, busy = false)
            }
        }
    }

    fun decideApproval(requestId: String, approved: Boolean) {
        viewModelScope.launch {
            repository.decideApproval(requestId, parentId, approved, System.currentTimeMillis())
        }
    }

    fun childScope(childDeviceId: String): ParentAuthorization.Result =
        parentAuthz.authorize(parentId, parentId, childDeviceId, childDeviceId)

    fun clearError() {
        _uiState.value = _uiState.value.copy(lastError = null)
    }
}