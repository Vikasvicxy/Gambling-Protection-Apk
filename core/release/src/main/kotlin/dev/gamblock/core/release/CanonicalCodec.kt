package dev.gamblock.core.release

import kotlinx.serialization.json.Json

/**
 * Deterministic canonical encoding used as the signature input domain.
 *
 * Both the signer (CI/pipeline) and every verifier (device, tools, tests) must produce
 * byte-identical JSON for the same logical manifest. kotlinx.serialization encodes data
 * classes in declaration order with fixed formatting when configured without pretty
 * printing, which gives us that guarantee across platforms.
 */
object CanonicalCodec {
    /** Manifest is serialized WITHOUT any signature material by construction. */
    val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false

        // Keeps number encoding stable across 32/64-bit platforms.
        allowSpecialFloatingPointValues = false
    }

    fun canonicalBytes(manifest: SignedReleaseManifest): ByteArray =
        json.encodeToString(SignedReleaseManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

    fun manifestToString(manifest: SignedReleaseManifest): String =
        json.encodeToString(SignedReleaseManifest.serializer(), manifest)
}