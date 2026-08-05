package pl.sroki.cci.android.data.datasource.remote.firestore

import com.google.firebase.firestore.DocumentSnapshot
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CapPositionMapperTest {

    private fun documentSnapshot(fields: Map<String, Any?>): DocumentSnapshot {
        val snapshot = mockk<DocumentSnapshot>()
        every { snapshot.id } returns "doc-1"
        every { snapshot.getString(any()) } answers { fields[firstArg<String>()] as? String }
        every { snapshot.getLong(any()) } answers {
            when (val v = fields[firstArg<String>()]) {
                is Long -> v
                is Int -> v.toLong()
                else -> null
            }
        }
        return snapshot
    }

    private fun baseFields() = mutableMapOf<String, Any?>(
        "binderPageFirestoreId" to "page-1",
        "position" to 3L,
        "capId" to 165627L,
        "capName" to "Testowy kapsel",
        "capCountry" to "-Multiple countries",
        "capImageUrl" to "https://example.test/165627.jpeg",
        "capCreatedAt" to "2011-04-30T15:20:54Z",
        "capCreatedById" to 2L,
        "capUpdatedAt" to "2023-06-02T22:35:21Z"
    )

    @Test
    fun `dokument sprzed synchronizacji wyboru producenta czyta sie bez wyboru`() {
        val doc = documentSnapshot(baseFields()).toCapPositionDocument()

        assertNotNull(doc)
        assertNull(doc!!.producerSelection)
        assertEquals("-Multiple countries", doc.snapshot?.country)
    }

    @Test
    fun `dokument z wyborem producenta odtwarza go razem z krajem`() {
        val fields = baseFields().apply {
            put("capSelectedProducerId", 412L)
            put("capProducer", "Bavaria")
            put("capCountry", "Holandia")
        }

        val doc = documentSnapshot(fields).toCapPositionDocument()

        assertNotNull(doc?.producerSelection)
        assertEquals(412, doc!!.producerSelection!!.producerId)
        assertEquals("Bavaria", doc.producerSelection!!.producer)
        assertEquals("Holandia", doc.producerSelection!!.country)
    }

    @Test
    fun `wybor producenta bez nazwy nie wywraca odczytu`() {
        val fields = baseFields().apply { put("capSelectedProducerId", 412L) }

        val doc = documentSnapshot(fields).toCapPositionDocument()

        assertEquals(412, doc?.producerSelection?.producerId)
        assertEquals("", doc?.producerSelection?.producer)
    }

    @Test
    fun `dokument bez zdjecia nie ma snapshotu ale zachowuje wybor producenta`() {
        val fields = baseFields().apply {
            remove("capImageUrl")
            put("capSelectedProducerId", 412L)
            put("capProducer", "Bavaria")
            put("capCountry", "Holandia")
        }

        val doc = documentSnapshot(fields).toCapPositionDocument()

        assertNull(doc?.snapshot)
        assertEquals("Holandia", doc?.producerSelection?.country)
    }
}
