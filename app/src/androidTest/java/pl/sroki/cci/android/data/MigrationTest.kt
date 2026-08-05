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

        helper.runMigrationsAndValidate(TEST_DB, 2, true, CciDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT firestore_id, name FROM binder").use { cursor ->
                assert(cursor.moveToFirst())
                assertEquals("Test", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertNull(cursor.getString(cursor.getColumnIndexOrThrow("firestore_id")))
            }
        }
    }

    @Test
    fun migrate2to3_addsCountryColumnToCapPosition() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL("INSERT INTO binder (id, name, firestore_id) VALUES (1, 'B', null)")
            db.execSQL("INSERT INTO binder_page (id, binder_id, page_number, firestore_id) VALUES (1, 1, 1, null)")
            db.execSQL("INSERT INTO cap_position (id, binder_page_id, position, cap_id, firestore_id) VALUES (1, 1, 1, 10, null)")
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, CciDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT id, country FROM cap_position WHERE id = 1").use { cursor ->
                assert(cursor.moveToFirst())
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("country")))
            }
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

        helper.runMigrationsAndValidate(TEST_DB, 4, true, CciDatabase.MIGRATION_3_4).use { db ->
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
    }

    @Test
    fun migrate4to5_addsImageUrlToCapCache() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL("INSERT INTO cap_cache (cap_id, country) VALUES (5, 'France')")
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, CciDatabase.MIGRATION_4_5).use { db ->
            db.query("SELECT image_url FROM cap_cache WHERE cap_id = 5").use { cursor ->
                assert(cursor.moveToFirst())
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("image_url")))
            }
        }
    }

    @Test
    fun migrate5to6_createsCountryFlagTable() {
        helper.createDatabase(TEST_DB, 5).use { /* brak danych — testujemy tylko strukturę */ }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, CciDatabase.MIGRATION_5_6).use { db ->
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
    }

    @Test
    fun migrateFullChain3to9_dataAndSchemaIntact() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL("INSERT INTO binder (id, name, firestore_id) VALUES (1, 'B', null)")
            db.execSQL("INSERT INTO binder_page (id, binder_id, page_number, firestore_id) VALUES (1, 1, 1, null)")
            db.execSQL("INSERT INTO cap_position (id, binder_page_id, position, cap_id, firestore_id, country) VALUES (1, 1, 1, 42, null, 'Germany')")
        }

        // Łańcuch musi sięgać aktualnej wersji bazy — inaczej ostatnia migracja jest
        // sprawdzana wyłącznie w izolacji, nigdy po przejściu przez wszystkie poprzednie.
        helper.runMigrationsAndValidate(
            TEST_DB, 9, true,
            CciDatabase.MIGRATION_3_4, CciDatabase.MIGRATION_4_5, CciDatabase.MIGRATION_5_6,
            CciDatabase.MIGRATION_6_7, CciDatabase.MIGRATION_7_8, CciDatabase.MIGRATION_8_9
        ).use { db ->
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
            // Kolumny z migracji 7→8 i 8→9 — obecne także po przejściu całego łańcucha.
            db.query("SELECT selected_producer_id, producer, image_unavailable FROM cap_cache WHERE cap_id = 42").use { cursor ->
                assert(cursor.moveToFirst())
                assert(cursor.isNull(cursor.getColumnIndexOrThrow("selected_producer_id")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("producer")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("image_unavailable")))
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
    }

    @Test
    fun migrate6To7_addsSnapshotAndFingerprintColumns() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL("INSERT INTO cap_cache (cap_id, country, image_url) VALUES (5, 'Poland', 'u')")
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, CciDatabase.MIGRATION_6_7).use { db ->
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

    @Test
    fun migrate7To8_addsSelectedProducerColumns() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            // W schemacie 7 name i catalog_status są NOT NULL bez DEFAULT — DEFAULT istnieje
            // wyłącznie w SQL migracji 6→7, a createDatabase odtwarza tabelę z 7.json.
            db.execSQL(
                "INSERT INTO cap_cache (cap_id, country, image_url, name, catalog_status) " +
                    "VALUES (9, 'Poland', 'u', '', 'unknown')"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 8, true, CciDatabase.MIGRATION_7_8).use { db ->
            db.query("SELECT selected_producer_id, producer FROM cap_cache WHERE cap_id = 9").use { cursor ->
                assert(cursor.moveToFirst())
                assertNull(cursor.getString(cursor.getColumnIndexOrThrow("selected_producer_id")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("producer")))
            }
        }
    }

    @Test
    fun migrate8To9_addsImageUnavailableDefaultingToFalse() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO cap_cache (cap_id, country, image_url, name, catalog_status, producer) " +
                    "VALUES (11, 'Poland', '', '', 'unknown', '')"
            )
        }

        // Istniejący wpis bez zdjęcia musi wyjść z migracji jako "jeszcze nie pytaliśmy" (0),
        // a nie jako "katalog zdjęcia nie ma" — inaczej migracja zamroziłaby brak zdjęcia.
        helper.runMigrationsAndValidate(TEST_DB, 9, true, CciDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT image_unavailable FROM cap_cache WHERE cap_id = 11").use { cursor ->
                assert(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("image_unavailable")))
            }
        }
    }
}
