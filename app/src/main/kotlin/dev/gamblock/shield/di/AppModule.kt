package dev.gamblock.shield.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gamblock.data.repository.BlockEventRepository
import dev.gamblock.protection.domainengine.DomainBlocker
import dev.gamblock.protection.vpn.BlockEventRecorder
import dev.gamblock.shield.ReviewerModeDomainBlocker

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindBlockEventRecorder(impl: BlockEventRepository): BlockEventRecorder

    @Binds
    abstract fun bindDomainBlocker(impl: ReviewerModeDomainBlocker): DomainBlocker
}