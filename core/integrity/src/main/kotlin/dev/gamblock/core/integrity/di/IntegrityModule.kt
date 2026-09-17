package dev.gamblock.core.integrity.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gamblock.core.integrity.DeviceIntegrity
import dev.gamblock.core.integrity.IntegrityConfig
import dev.gamblock.core.integrity.IntegrityClassifier
import dev.gamblock.core.integrity.NonceProvider
import dev.gamblock.core.integrity.SecureNonceProvider
import dev.gamblock.core.integrity.StandardIntegrityClient
import dev.gamblock.core.integrity.deviceIntegrityFor
import dev.gamblock.core.integrity.standardIntegrityClientFor
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IntegrityModule {

    /**
     * Placeholder Google Cloud project number. Debug/local builds keep this, so
     * integrity stays fail-closed ([IntegrityResult.Unavailable]) - which is a
     * risk signal, not a startup crash. A real Play Integrity binding requires
     * a provisioned project number before shipping.
     */
    private const val PLACEHOLDER_CLOUD_PROJECT = -1L

    @Provides
    @Singleton
    fun provideIntegrityConfig(): IntegrityConfig =
        IntegrityConfig(cloudProjectNumber = PLACEHOLDER_CLOUD_PROJECT)

    @Provides
    @Singleton
    fun provideNonceProvider(): NonceProvider = SecureNonceProvider()

    @Provides
    @Singleton
    fun provideIntegrityClassifier(): IntegrityClassifier = IntegrityClassifier()

    @Provides
    @Singleton
    fun provideStandardIntegrityClient(config: IntegrityConfig): StandardIntegrityClient =
        standardIntegrityClientFor(config)

    @Provides
    @Singleton
    fun provideDeviceIntegrity(
        client: StandardIntegrityClient,
        nonceProvider: NonceProvider,
        config: IntegrityConfig,
    ): DeviceIntegrity = deviceIntegrityFor(client, nonceProvider, config)
}