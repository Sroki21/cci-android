package pl.sroki.cci.android.ui.binders

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.sroki.cci.android.data.BinderPageRepository
import pl.sroki.cci.android.data.BinderRepository
import pl.sroki.cci.android.data.datasource.local.entity.Binder

@OptIn(ExperimentalCoroutinesApi::class)
class BindersViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var binderRepository: BinderRepository
    private lateinit var binderPageRepository: BinderPageRepository
    private lateinit var viewModel: BindersViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        binderRepository = mockk()
        binderPageRepository = mockk()
        every { binderRepository.getAll() } returns flowOf(emptyList())
        viewModel = BindersViewModel(binderRepository, binderPageRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `createBinder_updatesState`() = runTest {
        val binder = Binder(id = 1L, name = "Europa 1")
        every { binderRepository.getAll() } returns flowOf(listOf(binder))
        coEvery { binderRepository.create("Europa 1") } returns 1L
        viewModel = BindersViewModel(binderRepository, binderPageRepository)

        viewModel.createBinder("Europa 1")

        assertTrue(viewModel.uiState.value.binders.any { it.name == "Europa 1" })
    }

    @Test
    fun `createBinder_blankName_showsSnackbar`() = runTest {
        coEvery { binderRepository.create("") } throws IllegalArgumentException("Nazwa klasera nie może być pusta")

        viewModel.createBinder("")

        val event = withTimeout(1_000) { viewModel.events.first() }
        assertTrue(event is BindersEvent.ShowSnackbar)
    }

    @Test
    fun `deleteBinder_occupied_showsSnackbar`() = runTest {
        coEvery { binderRepository.delete(42L) } throws IllegalStateException("Klaser zawiera kapsle i nie może być usunięty")

        viewModel.requestDeleteBinder(42L)
        viewModel.confirmDeleteBinder()

        val event = withTimeout(1_000) { viewModel.events.first() }
        assertTrue(event is BindersEvent.ShowSnackbar)
    }

    @Test
    fun `addPage_atLimit_showsSnackbar`() = runTest {
        coEvery { binderPageRepository.addPage(1L) } throws IllegalStateException("Klaser może mieć maksymalnie 15 stron")

        viewModel.addPage(1L)

        val event = withTimeout(1_000) { viewModel.events.first() }
        assertTrue(event is BindersEvent.ShowSnackbar)
    }

    @Test
    fun `requestDeleteBinder_setsConfirmId`() = runTest {
        viewModel.requestDeleteBinder(42L)

        assertEquals(42L, viewModel.uiState.value.deleteBinderConfirmId)
    }
}
