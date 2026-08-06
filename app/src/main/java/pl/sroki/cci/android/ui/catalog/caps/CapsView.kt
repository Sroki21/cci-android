package pl.sroki.cci.android.ui.catalog.caps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.paging.*
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.ui.components.FullSizeLoader
import pl.sroki.cci.android.ui.theme.CCITheme
import retrofit2.HttpException
import java.io.IOException

private const val NUMBER_OF_COLUMNS = 2

@Composable
fun CapsView(caps: LazyPagingItems<Cap>, onCapClick: (Cap) -> Unit = {}) {
    when (val state = caps.loadState.refresh) {
        is LoadState.NotLoading ->
            // Bez tego wyszukiwanie bez trafień renderowało pustą siatkę bez słowa wyjaśnienia,
            // czyli wyglądało dokładnie jak awaria.
            if (caps.itemCount == 0) CenteredMessage("Brak wyników")
        is LoadState.Loading -> {
            FullSizeLoader()
        }
        is LoadState.Error -> {
            ErrorMessage(state.error, onRetry = caps::retry)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(NUMBER_OF_COLUMNS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        when (val state = caps.loadState.prepend) {
            is LoadState.NotLoading -> Unit
            is LoadState.Loading -> {
                item(span = { GridItemSpan(NUMBER_OF_COLUMNS) }) {
                    FullSizeLoader()
                }
            }
            is LoadState.Error -> errorItem(state.error, caps::retry)
        }

        // Oficjalne itemKey/itemContentType z paging-compose. Własna wersja nadawała WSZYSTKIM
        // placeholderom klucz 0, więc dwa null-e naraz dawały „Key 0 was already used" i wywalały
        // ekran. Dziś to mina uśpiona (PagingSource-y nie zwracają itemsBefore/itemsAfter, więc
        // placeholdery nie powstają), ale itemKey generuje dla nich unikalny PagingPlaceholderKey.
        items(
            count = caps.itemCount,
            key = caps.itemKey { it.id },
            contentType = caps.itemContentType { "cap" },
        ) { index ->
            caps[index]?.let { cap ->
                CapGridCard(cap = cap, onClick = { onCapClick(cap) })
            }
        }
        when (val state = caps.loadState.append) {
            is LoadState.NotLoading -> Unit
            is LoadState.Loading -> loading()
            is LoadState.Error -> errorItem(state.error, caps::retry)
        }
    }
}

/**
 * Komunikat o błędzie ładowania. Wcześniej renderował `error.message ?: ""` — wyjątki bez
 * komunikatu (część IOException) dawały PUSTY ekran, nieodróżnialny od braku wyników, a te
 * z komunikatem wyrzucały użytkownikowi surowy tekst techniczny. Nie było też jak ponowić:
 * jedynym wyjściem było opuszczenie ekranu i wejście na niego jeszcze raz.
 */
@Composable
fun ErrorMessage(error: Throwable, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = opisBledu(error),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("Ponów") }
        }
    }
}

private fun opisBledu(error: Throwable): String = when {
    error is IOException -> "Brak połączenia z siecią"
    error is HttpException -> "Katalog odpowiedział błędem ${error.code()}"
    !error.message.isNullOrBlank() -> error.message!!
    else -> "Nie udało się pobrać kapsli"
}

@Composable
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

private fun LazyGridScope.loading() {
    item(span = { GridItemSpan(NUMBER_OF_COLUMNS) }) {
        // fillMaxWidth jest konieczne: Row o szerokości treści nie ma względem czego wyśrodkować
        // spinnera, więc doładowanie kolejnej strony pokazywało go przy lewej krawędzi.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
    }
}

// Nazwa `error` cieniowała kotlin.error() z domyślnego importu. Rozstrzygało się poprawnie na
// extension, ale usunięcie albo przeniesienie tej funkcji zamieniłoby „pokaż komunikat"
// na IllegalStateException — bez błędu kompilacji.
private fun LazyGridScope.errorItem(error: Throwable, onRetry: () -> Unit) {
    item(span = { GridItemSpan(NUMBER_OF_COLUMNS) }) {
        ErrorMessage(error = error, onRetry = onRetry)
    }
}

private class FakePagingSource(private val success: Boolean = false) : PagingSource<Int, Cap>() {
    override fun getRefreshKey(state: PagingState<Int, Cap>): Int? {
        return state.anchorPosition
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Cap> {
        return if (success) {
            delay(2000L)
            LoadResult.Page(
                data = listOf(previewCaps.first()),
                prevKey = null,
                nextKey = null
            )
        } else {
            val e = Exception("Error loading caps")
            LoadResult.Error(e)
        }
    }
}

@Preview(widthDp = 320, heightDp = 480)
@Composable
fun CapsViewPreview() {
    val caps = Pager(
        pagingSourceFactory = { FakePagingSource() },
        config = PagingConfig(pageSize = 20)
    ).flow.collectAsLazyPagingItems()
    CCITheme {
        CapsView(caps = caps)
    }
}
