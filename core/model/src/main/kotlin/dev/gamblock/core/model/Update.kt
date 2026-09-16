package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Update pipeline models. Phase 1 implements the contracts and a local/offline
 * update source only. Production signed releases, deltas, canary and rollback
 * are Phase 2 transports; no unsigned production update may ever be applied.
 */
@Serializable
data class BlocklistManifest(
    val releaseId: String,
    val version: Int,
    val previousVersion: Int? = null,
    val releasedAtEpochMs: Long,
    val entryCount: Int,
    val sha256: String,
    val signature: SignatureMetadata? = null,
    val changelogUrl: String? = null,
)

@Serializable
data class BlocklistVersion(
    val version: Int,
    val sha256: String,
)

@Serializable
data class FullUpdate(
    val manifest: BlocklistManifest,
    val payload: ByteArray,
    val hash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FullUpdate
        if (manifest != other.manifest) return false
        if (!payload.contentEquals(other.payload)) return false
        return hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int {
        var result = manifest.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }
}

@Serializable
data class DeltaUpdate(
    val baseVersion: Int,
    val manifest: BlocklistManifest,
    val payload: ByteArray,
    val hash: ByteArray,
    val deletesVersionBefore: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeltaUpdate
        if (baseVersion != other.baseVersion) return false
        if (manifest != other.manifest) return false
        if (!payload.contentEquals(other.payload)) return false
        return hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int {
        var result = baseVersion
        result = 31 * result + manifest.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }
}

@Serializable
data class SignatureMetadata(
    val algorithm: String,
    val keyId: String,
    val signatureBase64: String,
    val verifiedAtEpochMs: Long? = null,
)

/** Outcome of a (foundation) update check for diagnostics/dashboard. */
@Serializable
data class UpdateCheckResult(
    val state: UpdateState,
    val checkedAtEpochMs: Long,
    val message: String,
    val availableVersion: Int? = null,
)