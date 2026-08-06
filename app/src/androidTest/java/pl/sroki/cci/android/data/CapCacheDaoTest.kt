package pl.sroki.cci.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.sroki.cci.android.data.datasource.local.CciDatabase
import pl.sroki.cci.android.data.datasource.local.dao.CapCacheDao

/**
 * Ręczny wybór producenta dla kapsla "-Multiple countries" (selected_producer_id) jest silniejszy
 * niż surowy kraj z katalogu. Wszystkie trzy upserty cache'u nadpisywały go bezwarunkowo, przez co
 * wiersz robił się wewnętrznie sprzeczny — wybrany producent obok kraju z katalogu — a
 * CollectionVerifier stawiał na takim kapslu fałszywy baner rozjazdu (UPDATED).
 */
@RunWith(AndroidJUnit4::class)
class CapCacheDaoTest {

    private companion object {
        const val CAP_ID = 150627L
        // Kraj, który katalog oddaje dla kapsla z wieloma producentami.
        const val PLACEHOLDER = "-Multiple countries"
        const val WYBRANY_KRAJ = "Belgium"
        const val WYBRANY_PRODUCENT = "Crown Cork"
        const val PRODUCER_ID = 42
    }

    private lateinit var db: CciDatabase
    private lateinit var dao: CapCacheDao

    @Before
    fun createDb() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CciDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.capCacheDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private suspend fun wybierzProducenta() =
        dao.selectProducer(CAP_ID, PRODUCER_ID, WYBRANY_PRODUCENT, WYBRANY_KRAJ)

    private suspend fun wiersz() = dao.getByIds(listOf(CAP_ID)).single()

    @Test
    fun upsertSnapshot_nieKasujeRecznegoWyboruKraju() = runBlocking {
        wybierzProducenta()

        dao.upsertSnapshot(CAP_ID, "Jupiler", PLACEHOLDER, "http://img/1.jpg", "2020-01-01", 7, "2024-05-05")

        val cc = wiersz()
        assertEquals(WYBRANY_KRAJ, cc.country)
        assertEquals(WYBRANY_PRODUCENT, cc.producer)
        assertEquals(PRODUCER_ID, cc.selectedProducerId)
        // Reszta snapshotu ma się zapisać normalnie — chroniony jest wyłącznie kraj.
        assertEquals("Jupiler", cc.name)
        assertEquals("http://img/1.jpg", cc.imageUrl)
        assertEquals("2020-01-01", cc.createdAt)
        assertEquals(7, cc.createdById)
        assertEquals("2024-05-05", cc.updatedAt)
    }

    @Test
    fun upsertFull_nieKasujeRecznegoWyboruKraju_aleAktualizujeZdjecie() = runBlocking {
        wybierzProducenta()

        dao.upsertFull(CAP_ID, PLACEHOLDER, "http://img/2.jpg")

        val cc = wiersz()
        assertEquals(WYBRANY_KRAJ, cc.country)
        assertEquals("http://img/2.jpg", cc.imageUrl)
    }

    @Test
    fun upsertCountry_nieKasujeRecznegoWyboruKraju() = runBlocking {
        wybierzProducenta()

        dao.upsertCountry(CAP_ID, PLACEHOLDER)

        assertEquals(WYBRANY_KRAJ, wiersz().country)
    }

    /** Kolejność przestaje mieć znaczenie: wybór złożony po snapshocie i tak wygrywa. */
    @Test
    fun wyborProducentaPoSnapshocieNadpisujeKraj() = runBlocking {
        dao.upsertSnapshot(CAP_ID, "Jupiler", PLACEHOLDER, "http://img/1.jpg", "2020-01-01", 7, "2024-05-05")

        wybierzProducenta()

        val cc = wiersz()
        assertEquals(WYBRANY_KRAJ, cc.country)
        assertEquals(PRODUCER_ID, cc.selectedProducerId)
    }

    /**
     * Druga strona kontraktu: bez ręcznego wyboru kraj MUSI iść za katalogiem, inaczej ochrona
     * zamroziłaby cache dla wszystkich zwykłych kapsli.
     */
    @Test
    fun bezWyboruProducentaKrajIdzieZaKatalogiem() = runBlocking {
        dao.upsertCountry(CAP_ID, "Poland")

        dao.upsertSnapshot(CAP_ID, "Jupiler", "Belgium", "http://img/1.jpg", "2020-01-01", 7, "2024-05-05")
        assertEquals("Belgium", wiersz().country)

        dao.upsertFull(CAP_ID, "Germany", "http://img/2.jpg")
        assertEquals("Germany", wiersz().country)

        dao.upsertCountry(CAP_ID, "France")
        assertEquals("France", wiersz().country)
    }
}
