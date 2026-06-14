package pl.sroki.cci.android.data.datasource.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import pl.sroki.cci.android.data.datasource.local.dao.BinderDao
import pl.sroki.cci.android.data.datasource.local.dao.BinderPageDao
import pl.sroki.cci.android.data.datasource.local.dao.CapCacheDao
import pl.sroki.cci.android.data.datasource.local.dao.CapPositionDao
import pl.sroki.cci.android.data.datasource.local.dao.CountryFlagDao
import pl.sroki.cci.android.data.datasource.local.dao.PendingCapDao
import pl.sroki.cci.android.data.datasource.local.entity.Binder
import pl.sroki.cci.android.data.datasource.local.entity.BinderPage
import pl.sroki.cci.android.data.datasource.local.entity.CapCache
import pl.sroki.cci.android.data.datasource.local.entity.CapPosition
import pl.sroki.cci.android.data.datasource.local.entity.CountryFlag
import pl.sroki.cci.android.data.datasource.local.entity.PendingCap

@Database(
    entities = [PendingCap::class, Binder::class, BinderPage::class, CapPosition::class, CapCache::class, CountryFlag::class],
    version = 7,
    exportSchema = false
)
abstract class CciDatabase : RoomDatabase() {
    abstract fun pendingCapDao(): PendingCapDao
    abstract fun binderDao(): BinderDao
    abstract fun binderPageDao(): BinderPageDao
    abstract fun capPositionDao(): CapPositionDao
    abstract fun capCacheDao(): CapCacheDao
    abstract fun countryFlagDao(): CountryFlagDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE binder ADD COLUMN firestore_id TEXT")
                db.execSQL("ALTER TABLE binder_page ADD COLUMN firestore_id TEXT")
                db.execSQL("ALTER TABLE cap_position ADD COLUMN firestore_id TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cap_position ADD COLUMN country TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nowa tabela na metadane API
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `cap_cache` (
                        `cap_id` INTEGER PRIMARY KEY NOT NULL,
                        `country` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())

                // Migracja istniejących danych kraju z cap_position
                db.execSQL("""
                    INSERT OR IGNORE INTO `cap_cache` (`cap_id`, `country`)
                    SELECT `cap_id`, `country` FROM `cap_position` WHERE `country` != ''
                """.trimIndent())

                // Przebudowa cap_position bez kolumny country (SQLite nie obsługuje DROP COLUMN w starszych wersjach)
                db.execSQL("""
                    CREATE TABLE `cap_position_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `binder_page_id` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `cap_id` INTEGER NOT NULL,
                        `firestore_id` TEXT,
                        FOREIGN KEY(`binder_page_id`) REFERENCES `binder_page`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `cap_position_new` (`id`, `binder_page_id`, `position`, `cap_id`, `firestore_id`)
                    SELECT `id`, `binder_page_id`, `position`, `cap_id`, `firestore_id` FROM `cap_position`
                """.trimIndent())
                db.execSQL("DROP TABLE `cap_position`")
                db.execSQL("ALTER TABLE `cap_position_new` RENAME TO `cap_position`")
                db.execSQL("CREATE UNIQUE INDEX `index_cap_position_binder_page_id_position` ON `cap_position` (`binder_page_id`, `position`)")
                db.execSQL("CREATE INDEX `index_cap_position_binder_page_id` ON `cap_position` (`binder_page_id`)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Cache zaczyna trzymać też URL zdjęcia kapsla, żeby zakładka Klasery
                // nie dociągała go z API przy każdym wejściu.
                db.execSQL("ALTER TABLE `cap_cache` ADD COLUMN `image_url` TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Trwały cache flag krajów (nazwa -> URL flagi), żeby zakładka Kraje
                // nie pobierała flag z API przy każdym wejściu.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `country_flag` (
                        `name` TEXT PRIMARY KEY NOT NULL,
                        `image_url` TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Snapshot identyfikujący kapsel + fingerprint + stan weryfikacji
                // (odporność na zmiany w katalogu crowncaps).
                db.execSQL("ALTER TABLE `cap_cache` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `cap_cache` ADD COLUMN `created_at` TEXT")
                db.execSQL("ALTER TABLE `cap_cache` ADD COLUMN `created_by_id` INTEGER")
                db.execSQL("ALTER TABLE `cap_cache` ADD COLUMN `updated_at` TEXT")
                db.execSQL("ALTER TABLE `cap_cache` ADD COLUMN `last_verified_at` INTEGER")
                db.execSQL("ALTER TABLE `cap_cache` ADD COLUMN `catalog_status` TEXT NOT NULL DEFAULT 'unknown'")
            }
        }
    }
}
