package pl.sroki.cci.android.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
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
