package dev.gamblock.feature.accountability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.accountability.DeviceIdentityService
import dev.gamblock.core.model.AcceptPairingRequest
import dev.gamblock.core.model.InviteCapabilitySelection
import dev.gamblock.core.model.PairingKind
import dev.gamblock.core.model.PairingToken
import dev.gamblock.core.model.PartnerCapability
import dev.gamblock.core.model.PartnerRelationship
import dev.gamblock.core.model.SensitiveChange
import dev.gamblock.core.model.ShieldBackendClient
import dev.gamblock.data.accountability.AccountabilityRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Accountability setup / partner dashboard. */
data class AccountabilityUiState(
    val installId: String = "",
    val relationships: List<PartnerRelationship> = emptyList(),
    val activeInvitation: PairingToken? = null,
    val lastError: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class AccountabilityViewModel @Inject constructor(
    private val repository: AccountabilityRepository,
    deviceIdentity: DeviceIdentityService,
    private val backend: ShieldBackendClient,
) : ViewModel() {

    private val selfId: String = when (val result = deviceIdentity.register(nowEpochMs = System.currentTimeMillis())) {
        is DeviceIdentityService.RegisterResult.Registered -> result.identity.id
        is DeviceIdentityService.RegisterResult.Rejected -> ""
    }

    private val _uiState = MutableStateFlow(AccountabilityUiState(installId = selfId))

    val uiState: StateFlow<AccountabilityUiState> =
        combine(_uiState, repository.observeRelationships()) { base, relationships ->
            base.copy(relationships = relationships)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountabilityUiState(installId = selfId))

    fun createPartnerInvitation() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, lastError = null)
            val selection = InviteCapabilitySelection(
                kind = PairingKind.ACCOUNTABILITY_PARTNER,
                capabilities = setOf(
                    PartnerCapability.RECEIVE_PROTECTION_ALERTS,
                    PartnerCapability.RECEIVE_HEARTBEAT_ALERTS,
                    PartnerCapability.RECEIVE_GAMBLING_ATTEMPTS,
                    PartnerCapability.RECEIVE_TAMPER_ALERTS,
                    PartnerCapability.RECEIVE_REPLACEMENT_NOTICES,
                ),
                label = "My Shield partner",
            )
            val now = System.currentTimeMillis()
            when (val result = repository.createInvitation(selfId, PairingKind.ACCOUNTABILITY_PARTNER, selection, now)) {
                is AccountabilityRepository.PairingResult.Created -> {
                    _uiState.value = _uiState.value.copy(
                        activeInvitation = result.token,
                        busy = false,
                    )
                    // Register the invite's SHA-256 hash with the backend so the accepting
                    // device can validate it while the secret itself never leaves the device.
                    try {
                        backend.createPairing(
                            dev.gamblock.core.model.CreatePairingRequest(
                                deviceId = selfId,
                                kind = PairingKind.ACCOUNTABILITY_PARTNER,
                                capabilities = selection.capabilities.map { it.name },
                                ttlMillis = 10 * 60 * 1000L,
                                label = selection.label,
                                tokenId = result.token.id,
                                tokenSecretHash = dev.gamblock.core.accountability.PairingTokenService
                                    .hash(result.token.fullToken),
                            ),
                        )
                    } catch (e: Exception) {
                        // Offline invite still works locally for later handshake.
                    }
                }
                is AccountabilityRepository.PairingResult.Rejected ->
                    _uiState.value = _uiState.value.copy(
                        lastError = result.reason,
                        busy = false,
                    )
            }
        }
    }

    fun acceptInvitation(fullToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, lastError = null)
            val now = System.currentTimeMillis()
            when (val result = repository.acceptInvitation(fullToken, selfId, now)) {
                is AccountabilityRepository.AcceptOutcome.Linked ->
                    _uiState.value = _uiState.value.copy(busy = false)
                is AccountabilityRepository.AcceptOutcome.Rejected ->
                    _uiState.value = _uiState.value.copy(lastError = result.reason, busy = false)
            }
        }
    }

    fun requestDeviceReplacement(newDeviceId: String, remainingMillis: Long) {
        viewModelScope.launch {
            repository.createReplacementRequest(selfId, newDeviceId, remainingMillis, System.currentTimeMillis())
        }
    }

    fun requestAlertDisableApproval(relationshipId: String) {
        viewModelScope.launch {
            repository.requestApproval(
                relationshipId = relationshipId,
                protectedDeviceId = selfId,
                change = SensitiveChange.DISABLE_ACCOUNTABILITY_ALERTS,
                description = "Disable accountability alerts",
                nowEpochMs = System.currentTimeMillis(),
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(lastError = null)
    }
}