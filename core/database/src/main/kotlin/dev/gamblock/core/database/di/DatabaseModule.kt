package dev.gamblock.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.dao.ActivityEventDao
import dev.gamblock.core.database.dao.BlockAttemptGroupDao
import dev.gamblock.core.database.dao.CommitmentDao
import dev.gamblock.core.database.dao.CustomDomainExceptionDao
import dev.gamblock.core.database.dao.DomainDao
import dev.gamblock.core.database.dao.FalsePositiveReportDao
import dev.gamblock.core.database.dao.MetaDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ShieldDatabase =
        ShieldDatabase.get(context)

    @Provides fun provideDomainDao(db: ShieldDatabase): DomainDao = db.domainDao()
    @Provides fun provideBlockAttemptDao(db: ShieldDatabase): BlockAttemptGroupDao = db.blockAttemptGroupDao()
    @Provides fun provideActivityDao(db: ShieldDatabase): ActivityEventDao = db.activityEventDao()
    @Provides fun provideReportDao(db: ShieldDatabase): FalsePositiveReportDao = db.falsePositiveReportDao()
    @Provides fun provideCommitmentDao(db: ShieldDatabase): CommitmentDao = db.commitmentDao()
    @Provides fun provideMetaDao(db: ShieldDatabase): MetaDao = db.metaDao()
    @Provides fun provideCustomDomainExceptionDao(db: ShieldDatabase): CustomDomainExceptionDao = db.customDomainExceptionDao()
}