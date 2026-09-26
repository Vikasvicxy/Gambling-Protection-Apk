package dev.gamblock.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.gamblock.core.database.dao.ActivityEventDao
import dev.gamblock.core.database.dao.BlockAttemptGroupDao
import dev.gamblock.core.database.dao.CommitmentDao
import dev.gamblock.core.database.dao.CravingJournalDao
import dev.gamblock.core.database.dao.CustomDomainExceptionDao
import dev.gamblock.core.database.dao.DomainDao
import dev.gamblock.core.database.dao.FalsePositiveReportDao
import dev.gamblock.core.database.dao.MetaDao
import dev.gamblock.core.database.entity.ActivityEventEntity
import dev.gamblock.core.database.entity.BlockAttemptGroupEntity
import dev.gamblock.core.database.entity.CommitmentEntity
import dev.gamblock.core.database.entity.CravingJournalEntity
import dev.gamblock.core.database.entity.CustomDomainExceptionEntity
import dev.gamblock.core.database.entity.DomainEntity
import dev.gamblock.core.database.entity.FalsePositiveReportEntity
import dev.gamblock.core.database.entity.MetaEntity
import dev.gamblock.core.model.BlockStatus
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.CommitmentState
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.Operator
import dev.gamblock.core.model.ProtectionLevel
import dev.gamblock.core.model.ProtectionMode
import dev.gamblock.core.model.ReportStatus

class EnumsConverter {
    @TypeConverter fun categoryToString(value: Category): String = value.name
    @TypeConverter fun stringToCategory(value: String): Category = Category.fromStorage(value)
    @TypeConverter fun blockStatusToString(value: BlockStatus): String = value.name
    @TypeConverter fun stringToBlockStatus(value: String): BlockStatus = if (value.isBlank()) BlockStatus.ACTIVE else BlockStatus.valueOf(value)
    @TypeConverter fun confidenceToString(value: Confidence): String = value.name
    @TypeConverter fun stringToConfidence(value: String): Confidence = if (value.isBlank()) Confidence.UNKNOWN else Confidence.valueOf(value)
    @TypeConverter fun riskToString(value: dev.gamblock.core.model.RiskLevel): String = value.name
    @TypeConverter fun stringToRisk(value: String): dev.gamblock.core.model.RiskLevel =
        if (value.isBlank()) dev.gamblock.core.model.RiskLevel.UNKNOWN else dev.gamblock.core.model.RiskLevel.valueOf(value)
    @TypeConverter fun operatorToString(value: Operator): String = value.name
    @TypeConverter fun stringToOperator(value: String): Operator = if (value.isBlank()) Operator.GAMBLOCK_SEED else Operator.valueOf(value)
    @TypeConverter fun modeToString(value: ProtectionMode): String = value.name
    @TypeConverter fun stringToMode(value: String): ProtectionMode = if (value.isBlank()) ProtectionMode.SELF_PROTECTION else ProtectionMode.valueOf(value)
    @TypeConverter fun levelToString(value: ProtectionLevel): String = value.name
    @TypeConverter fun stringToLevel(value: String): ProtectionLevel = ProtectionLevel.DNS_DOMAIN_BLOCKING
    @TypeConverter fun stateToString(value: CommitmentState): String = value.name
    @TypeConverter fun stringToState(value: String): CommitmentState = if (value.isBlank()) CommitmentState.NONE else CommitmentState.valueOf(value)
    @TypeConverter fun reportStatusToString(value: ReportStatus): String = value.name
    @TypeConverter fun stringToReportStatus(value: String): ReportStatus = if (value.isBlank()) ReportStatus.QUEUED else ReportStatus.valueOf(value)
}

@Database(
    entities = [
        DomainEntity::class,
        BlockAttemptGroupEntity::class,
        ActivityEventEntity::class,
        FalsePositiveReportEntity::class,
        CommitmentEntity::class,
        MetaEntity::class,
        CustomDomainExceptionEntity::class,
        CravingJournalEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(EnumsConverter::class)
abstract class ShieldDatabase : RoomDatabase() {

    abstract fun domainDao(): DomainDao
    abstract fun blockAttemptGroupDao(): BlockAttemptGroupDao
    abstract fun activityEventDao(): ActivityEventDao
    abstract fun falsePositiveReportDao(): FalsePositiveReportDao
    abstract fun commitmentDao(): CommitmentDao
    abstract fun metaDao(): MetaDao
    abstract fun customDomainExceptionDao(): CustomDomainExceptionDao
    abstract fun cravingJournalDao(): CravingJournalDao

    companion object {

        @Volatile
        private var instance: ShieldDatabase? = null

        fun get(context: Context): ShieldDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, "shield.db").also { instance = it }
            }

        fun build(context: Context, name: String): ShieldDatabase =
            Room.databaseBuilder(context, ShieldDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // No seed here: data:blocklist owns seeding logic.
                    }
                })
                .build()

        /** In-memory database used by tests. */
        fun inMemory(context: Context): ShieldDatabase =
            Room.inMemoryDatabaseBuilder(context, ShieldDatabase::class.java)
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_domain_exceptions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`normalizedDomain` TEXT NOT NULL, " +
                        "`createdAtEpochMs` INTEGER NOT NULL, " +
                        "`expiresAtEpochMs` INTEGER, " +
                        "`note` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_domain_exceptions_normalizedDomain` " +
                        "ON `custom_domain_exceptions` (`normalizedDomain`)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `craving_journal_entries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`occurredAtEpochMs` INTEGER NOT NULL, " +
                        "`intensity` INTEGER NOT NULL, " +
                        "`triggers` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`blockedDomain` TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_craving_journal_entries_occurredAtEpochMs` " +
                        "ON `craving_journal_entries` (`occurredAtEpochMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_craving_journal_entries_intensity` " +
                        "ON `craving_journal_entries` (`intensity`)",
                )
            }
        }
    }
}