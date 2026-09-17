package dev.gamblock.core.accountability

import dev.gamblock.core.model.InstallIdentity

/**
 * Pseudonymous device identity / registration and rotation.
 *
 * Installation identifiers are random, unlinkable-to-hardware (no IMEI/serial/advertising
 * id). Fresh installs receive a new id. Device ID rotation is supported for privacy and
 * for replacement flows.
 */
class DeviceIdentityService {

    sealed interface RegisterResult {
        data class Registered(val identity: InstallIdentity) : RegisterResult
        data class Rejected(val reason: String) : RegisterResult
    }

    fun register(
        nowEpochMs: Long,
        randomId: () -> String = ::defaultRandomId,
        existingId: String? = null,
    ): RegisterResult {
        val id = existingId ?: randomId()
        if (id.length != 32) return RegisterResult.Rejected("install id must be 32 hex chars")
        if (!id.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return RegisterResult.Rejected("install id must be hexadecimal")
        }
        return RegisterResult.Registered(InstallIdentity(id, nowEpochMs))
    }

    fun rotate(
        oldIdentity: InstallIdentity,
        nowEpochMs: Long,
        randomId: () -> String = ::defaultRandomId,
    ): InstallIdentity = InstallIdentity(randomId(), nowEpochMs)

    companion object {
        private const val HEX = "0123456789abcdef"

        fun defaultRandomId(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { b -> HEX[(b.toInt() ushr 4) and 0xF].toString() + HEX[b.toInt() and 0xF].toString() }
        }

        fun isValid(deviceId: String): Boolean =
            deviceId.length == 32 && deviceId.all { it.lowercaseChar() in HEX }
    }
}