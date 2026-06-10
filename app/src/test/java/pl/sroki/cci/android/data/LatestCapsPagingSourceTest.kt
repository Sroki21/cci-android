package pl.sroki.cci.android.data

import androidx.paging.PagingSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.Page
import java.io.IOException

class LatestCapsPagingSourceTest {

    private lateinit var repository: CapsRepository
    private lateinit var pagingSource: LatestCapsPagingSource

    private val fakeCaps = listOf(
        Cap(id = 1L, country = "Netherlands", product = "Beer", liner = "Plastic",
            purpose = "Bottle closure", imageUrl = "https://example.com/1.jpg"),
        Cap(id = 2L, country = "Poland", product = "Beer", liner = "Cork",
            purpose = "Bottle closure", imageUrl = "https://example.com/2.jpg")
    )

    @Before
    fun setUp() {
        repository = mockk()
        pagingSource = LatestCapsPagingSource(repository)
    }

    @Test
    fun `pierwsza strona zwraca null jako prevKey`() = runTest {
        val apiPage = Page(data = fakeCaps, lastPage = 3, currentPage = 1, perPage = 60, total = 180)
        coEvery { repository.getLatest(1) } returns apiPage

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 60, placeholdersEnabled = false)
        )

        val page = result as PagingSource.LoadResult.Page
        assertEquals(null, page.prevKey)
        assertEquals(2, page.nextKey)
        assertEquals(fakeCaps, page.data)
    }

    @Test
    fun `ostatnia strona zwraca null jako nextKey`() = runTest {
        val apiPage = Page(data = fakeCaps, lastPage = 3, currentPage = 3, perPage = 60, total = 180)
        coEvery { repository.getLatest(3) } returns apiPage

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(key = 3, loadSize = 60, placeholdersEnabled = false)
        )

        val page = result as PagingSource.LoadResult.Page
        assertEquals(2, page.prevKey)
        assertEquals(null, page.nextKey)
        assertEquals(fakeCaps, page.data)
    }

    @Test
    fun `srodkowa strona zwraca poprawne klucze paginacji`() = runTest {
        val apiPage = Page(data = fakeCaps, lastPage = 5, currentPage = 3, perPage = 60, total = 300)
        coEvery { repository.getLatest(3) } returns apiPage

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(key = 3, loadSize = 60, placeholdersEnabled = false)
        )

        val page = result as PagingSource.LoadResult.Page
        assertEquals(2, page.prevKey)
        assertEquals(4, page.nextKey)
    }

    @Test
    fun `blad sieci zwraca LoadResult Error`() = runTest {
        coEvery { repository.getLatest(any()) } throws IOException("Brak połączenia")

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 60, placeholdersEnabled = false)
        )

        assertTrue(result is PagingSource.LoadResult.Error)
    }
}
