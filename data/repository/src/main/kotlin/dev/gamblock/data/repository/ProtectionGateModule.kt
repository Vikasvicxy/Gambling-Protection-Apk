package dev.gamblock.data.repository

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the narrow gate interface the shared [ProtectionGateHolder] depends on to
 * the single real implementation, so the destructive-action chain has exactly one
 * gatekeeper in the graph.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProtectionGateModule {

    @Binds
    @Singleton
    abstract fun bindProtectionGateEvaluator(
        coordinator: ProtectionCommandCoordinator,
    ): ProtectionGateEvaluator
}
