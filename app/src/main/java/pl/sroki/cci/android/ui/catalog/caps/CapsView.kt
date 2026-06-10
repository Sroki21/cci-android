package pl.sroki.cci.android.ui.catalog.caps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.paging.*
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.caps
import pl.sroki.cci.android.ui.components.FullSizeLoader
import pl.sroki.cci.android.ui.theme.CCITheme

private const val NUMBER_OF_COLUMNS = 2

@Composable
fun CapsView(caps: LazyPagingItems<Cap>, onCapClick: (Cap) -> Unit = {}) {
    when (val state = caps.loadState.refresh) {
        is LoadState.NotLoading -> Unit
        is LoadState.Loading -> {
            FullSizeLoader()
        }
        is LoadState.Error -> {
            ErrorMessage(state.error)
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
            is LoadState.Error -> error(state.error)
        }

        items(caps, key = { it.id }) { cap ->
            CapGridCard(cap = cap, onClick = { onCapClick(cap) })
        }
        when (val state = caps.loadState.append) {
            is LoadState.NotLoading -> Unit
            is LoadState.Loading -> loading()
            is LoadState.Error -> error(state.error)
        }
    }
}

@Composable
fun ErrorMessage(error: Throwable) {
    Row {
        Text(
            text = error.message ?: "",
            style = MaterialTheme.typography.h6,
            color = MaterialTheme.colors.error,
            textAlign = TextAlign.Center
        )
    }
}

fun <T : Any> LazyGridScope.items(
    items: LazyPagingItems<T>,
    key: ((item: T) -> Any)? = null,
    itemContent: @Composable LazyGridItemScope.(value: T) -> Unit
) {
    items(
        count = items.itemCount,
        key = if (key == null) null else { index ->
            val item = items.peek(index)
            if (item == null) {
                0
            } else {
                key(item)
            }
        }
    ) { index ->
        items[index]?.let { itemContent(it) }
    }
}

private fun LazyGridScope.loading() {
    item(span = { GridItemSpan(NUMBER_OF_COLUMNS) }) {
        Row(horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
    }
}

private fun LazyGridScope.error(
    error: Throwable
) {
    item(span = { GridItemSpan(NUMBER_OF_COLUMNS) }) {
        ErrorMessage(error = error)
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
                data = listOf(caps.first()),
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