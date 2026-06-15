package pl.sroki.cci.android.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.sroki.cci.android.data.datasource.local.CciDatabase

private const val TEST_DB = "cci_migration_test"

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CciDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_addsFirestoreIdColumns() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO binder (name) VALUES ('Test')")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, CciDatabase.MIGRATION_1_2)

        db.query("SELECT firestore_id, name FROM binder").use { cursor ->
            assert(cursor.moveToFirst())
            assertEquals("Test", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("firestore_id")))
        }
    }

    @Test
    fun migrate2to3_addsCountryColumnToCapPosition() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL("INSERT INTO binder (id, name, firestore_id) VALUES (1, 'B', null)")
            db.execSQL("INSERT INTO binder_page (id, binder_id, page_number, firestore_id) VALUES (1, 1, 1, null)")
            db.execSQL("INSERT INTO cap_position (id, binder_page_id, position, cap_id, firestore_id) VALUES (1, 1, 1, 10, null)")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, CciDatabase.MIGRATION_2_3)

        db.query("SELECT id, country FROM cap_position WHERE id = 1").use { cursor ->
            assert(cursor.moveToFirst())
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("country")))
        }
    }

    @Test
    fun migrate3to4_createsCapCacheAndMigratesCountryData() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL("INSERT INTO binder (id, name, firestore_id) VALUES (1, 'B', null)")
            db.execSQL("INSERT INTO binder_page (id, binder_id, page_number, firestore_id) VALUES (1, 1, 1, null)")
            db.execSQL("INSERT INTO cap_position (id, binder_page_id, position, cap_id, firestore_id, country) VALUES (1, 1, 1, 100, null, 'Poland')")
            db.execSQL("INSERT INTO cap_position (id, binder_page_id, position, cap_id, firestore_id, country) VALUES (2, 1, 2, 200, null, '')")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, CciDatabase.MIGRATION_3_4)

        db.query("SELECT country FROM cap_cache WHERE cap_id = 100").use { cursor ->
            assert(cursor.moveToFirst())
            assertEquals("Poland", cursor.getString(cursor.getColumnIndexOrThrow("country")))
        }
        db.query("SELECT cap_id FROM cap_cache WHERE cap_id = 200").use { cursor ->
            assertFalse("Wiersz z pustym country nie powinien trafić do cap_cache", cursor.moveToFirst())
        }
        db.query("SELECT * FROM cap_position").use { cursor ->
            try {
                cursor.getColumnIndexOrThrow("country")
                fail("Oczekiwano IllegalArgumentException — kolumna 'country' usunięta z cap_position w migracji 3→4")
            } catch (e: IllegalArgumentException) {
                // oczekiwane
            }
        }
    }

    @Test
    fun migrate4to5_addsImageUrlToCapCache() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL("INSERT INTO cap_cache (cap_id, country) VALUES (5, 'France')")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, CciDatabase.MIGRATION_4_5)

        db.query("SELECT image_url FROM cap_cache WHERE cap_id = 5").use { cursor ->
            assert(cursor.moveToFirst())
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("image_url")))
        }
    }

    @Test
    fun migrate5to6_createsCountryFlagTable() {
        helper.createDatabase(TEST_DB, 5).use { /* brak danych — testujemy tylko strukturę */ }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, CciDatabase.MIGRATION_5_6)

        db.execSQL("INSERT INTO country_flag (name, image_url) VALUES ('Poland', 'https://f.pl')")
        db.query("SELECT name, image_url FROM country_flag WHERE name = 'Poland'").use { cursor ->
            assert(cursor.moveToFirst())
            assertEquals("Poland", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals("https://f.pl", cursor.getString(cursor.getColumnIndexOrThrow("image_url")))
        }
        try {
            db.execSQL("INSERT INTO country_flag (name, image_url) VALUES ('Poland', 'https://f2.pl')")
            fail("Oczekiwano SQLiteConstraintException — naruszenie PRIMARY KEY na country_flag.name")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // oczekiwane
        }
    }

    @Test
    fun migrateFullChain3to7_dataAndSchemaIntact() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL("INSERT INTO binder (id, name, firestore_id) VALUES (1, 'B', null)")
            db.execSQL("INSERT INTO binder_page (id, binder_id, page_number, firestore_id) VALUES (1, 1, 1, null)")
            db.execSQL("INSERT INTO cap_position (id, binder_page_id, position, cap_id, firestore_id, country) VALUES (1, 1, 1, 42, null, 'Germany')")
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 7, true,
            CciDatabase.MIGRATION_3_4, CciDatabase.MIGRATION_4_5, CciDatabase.MIGRATION_5_6, CciDatabase.MIGRATION_6_7
        )

        db.query("SELECT country, image_url, name, catalog_status FROM cap_cache WHERE cap_id = 42").use { cursor ->
            assert(cursor.moveToFirst())
            assertEquals("Germany", cursor.getString(cursor.getColumnIndexOrThrow("country")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("image_url")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals("unknown", cursor.getString(cursor.getColumnIndexOrThrow("catalog_status")))
        }
        db.query("SELECT count(*) FROM country_flag").use { cursor ->
            assert(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT * FROM cap_position").use { cursor ->
            try {
                cursor.getColumnIndexOrThrow("country")
                fail("Oczekiwano IllegalArgumentException — kolumna 'country' usunięta w migracji 3→4")
            } catch (e: IllegalArgumentException) {
                // oczekiwane
            }
        }
    }

    @Test
    fun migrate6To7_addsSnapshotAndFingerprintColumns() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL("INSERT INTO cap_cache (cap_id, country, image_url) VALUES (5, 'Poland', 'u')")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, CciDatabase.MIGRATION_6_7)

        db.query(
            "SELECT name, created_at, created_by_id, updated_at, last_verified_at, catalog_status " +
                "FROM cap_cache WHERE cap_id = 5"
        ).use { cursor ->
            assert(cursor.moveToFirst())
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("created_at")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("updated_at")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("last_verified_at")))
            assertEquals("unknown", cursor.getString(cursor.getColumnIndexOrThrow("catalog_status")))
        }
    }
}
