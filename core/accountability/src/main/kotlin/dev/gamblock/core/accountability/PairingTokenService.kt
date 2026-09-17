package dev.gamblock.core.accountability

import dev.gamblock.core.model.InviteCapabilitySelection
import dev.gamblock.core.model.PairingKind
import dev.gamblock.core.model.PairingToken
import dev.gamblock.core.model.SensitiveChange
import dev.gamblock.core.model.PartnerCapability
import java.security.SecureRandom

/**
 * Secure pairing-token creation and validation.
 *
 * Tokens are:
 *  - short-lived (TTL enforced at creation and at accept),
 *  - single-use (consumed atomically on accept),
 *  - high-entropy (128-bit id + 128-bit secret, encoded base64url),
 *  - free of any sensitive data (id/secret are opaque; the protected user's identity is
 *    never embedded).
 *
 * Callers validate [fullToken] directly with [validateToken], or split `id.secret`.
 */
class PairingTokenService(
    private val random: SecureRandom = SecureRandom(),
    private val maxTokenTtlMillis: Long = MAX_TTL_MILLIS,
    private val tokenTtlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    sealed interface CreateResult {
        data class Created(val token: PairingToken, val tokenHash: String) : CreateResult
        data class Rejected(val reason: String) : CreateResult
    }

    sealed interface ValidateResult {
        data class Valid(val token: PairingToken, val tokenHash: String) : ValidateResult
        data class Invalid(val reason: String) : ValidateResult
    }

    fun create(
        createdByDeviceId: String,
        kind: PairingKind,
        selection: InviteCapabilitySelection,
        nowEpochMs: Long,
    ): CreateResult {
        if (createdByDeviceId.isBlank()) return CreateResult.Rejected("device id is blank")
        if (kind == PairingKind.PARENT_SUPERVISED_CHILD && selection.capabilities.isEmpty()) {
            return CreateResult.Rejected("parent invite requires at least one capability")
        }
        if (tokenTtlMillis <= 0 || tokenTtlMillis > maxTokenTtlMillis) {
            return CreateResult.Rejected("token ttl out of range")
        }
        val id = generatePart()
        val secret = generatePart()
        val expiresAt = nowEpochMs + tokenTtlMillis
        if (expiresAt <= nowEpochMs) return CreateResult.Rejected("token would expire immediately")

        val token = PairingToken(
            id = id,
            secret = secret,
            kind = kind,
            createdByDeviceId = createdByDeviceId,
            createdAtEpochMs = nowEpochMs,
            expiresAtEpochMs = expiresAt,
            label = selection.label,
        )
        return CreateResult.Created(token, tokenHash = token.fullToken)
    }

    /**
     * Validates a full `id.secret` token string against the hashed stored record. The
     * [storedTokenHash] must be the SHA-256 hex of the full token.
     */
    fun validateToken(
        fullToken: String,
        storedTokenHash: String,
        nowEpochMs: Long,
    ): ValidateResult {
        val parts = fullToken.split('.')
        if (parts.size != 2) return ValidateResult.Invalid("malformed token")
        val id = parts[0]
        val secret = parts[1]
        if (id.isBlank() || secret.isBlank()) return ValidateResult.Invalid("missing id or secret")
        if (!constantTimeEquals(hash(fullToken), storedTokenHash)) {
            return ValidateResult.Invalid("invalid token")
        }
        val token = PairingToken(
            id = id,
            secret = secret,
            kind = PairingKind.ACCOUNTABILITY_PARTNER,
            createdByDeviceId = "",
            createdAtEpochMs = 0L,
            expiresAtEpochMs = 0L,
        )
        return ValidateResult.Valid(token, tokenHash = storedTokenHash)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 10 * 60 * 1000L
        const val MAX_TTL_MILLIS: Long = 60 * 60 * 1000L
        const val ENTROPY_BITS: Int = 256

        fun hash(fullToken: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(fullToken.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var result = 0
            for (i in a.indices) result = result or (a[i].code xor b[i].code)
            return result == 0
        }
    }

    private fun generatePart(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** Mapping of which capabilities are required to approve which sensitive change. */
object SensitiveChangePolicy {
    private val requiredCapability = mapOf(
        SensitiveChange.REPLACE_PROTECTED_DEVICE to PartnerCapability.APPROVE_DEVICE_REPLACEMENT,
        SensitiveChange.CHANGE_ACCOUNTABILITY_PARTNER to PartnerCapability.APPROVE_PARTNER_CHANGES,
        SensitiveChange.DISABLE_ACCOUNTABILITY_ALERTS to PartnerCapability.APPROVE_ALERT_DISABLE,
        SensitiveChange.EXTEND_RELATIONSHIP to PartnerCapability.EXTEND_PROTECTION,
    )

    /** Returns the capability required to approve [change], or null if no approval required. */
    fun capabilityRequired(change: SensitiveChange): PartnerCapability? = requiredCapability[change]

    fun partnerMayApprove(change: SensitiveChange, capabilities: Set<PartnerCapability>): Boolean {
        val required = capabilityRequired(change) ?: return false
        return required in capabilities
    }
}