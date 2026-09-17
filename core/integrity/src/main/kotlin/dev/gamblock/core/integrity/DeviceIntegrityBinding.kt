package dev.gamblock.core.integrity

/**
 * Pure binding decisions for the integrity graph, unit-testable without Hilt.
 *
 * Debug / local builds (enabled-by-default config with a placeholder cloud
 * project) always resolve to fail-closed implementations: [UnavailableStandardIntegrityClient]
 * and [UnavailableDeviceIntegrity]. A configured, available client resolves to
 * the real [PlayIntegrityDeviceIntegrity] adapter. Unavailability is a risk
 * signal downstream - never a crash.
 */
fun standardIntegrityClientFor(config: IntegrityConfig): StandardIntegrityClient {
    if (!config.enabled || config.cloudProjectNumber <= 0) return UnavailableStandardIntegrityClient()
    // The Play-Services-backed client is bound here once the Google Cloud
    // project number and the `com.google.android.play:integrity` artifact are
    // provisioned; until then fail closed rather than guess a token.
    return UnavailableStandardIntegrityClient()
}

/**
 * Resolves the active [DeviceIntegrity] implementation for a build. Safe when
 * the API is unavailable, disabled, or unconfigured; wired to the Play
 * Integrity adapter when both the config and client are usable.
 */
fun deviceIntegrityFor(
    client: StandardIntegrityClient,
    nonceProvider: NonceProvider,
    config: IntegrityConfig,
): DeviceIntegrity {
    if (!config.enabled || config.cloudProjectNumber <= 0) return UnavailableDeviceIntegrity()
    if (!client.isAvailable()) return UnavailableDeviceIntegrity()
    return PlayIntegrityDeviceIntegrity(client, nonceProvider)
}