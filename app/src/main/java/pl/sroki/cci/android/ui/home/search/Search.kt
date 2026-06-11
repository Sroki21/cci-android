package pl.sroki.cci.android.ui.home.search

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import pl.sroki.cci.android.R
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.SearchRepo

@Composable
fun Search(
    onCapClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    state: SearchState = rememberSearchState()
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        Column {
            Spacer(modifier = Modifier.statusBarsPadding())
            SearchBar(
                query = state.query,
                onQueryChange = { state.query = it },
                onSearchFocusChange = { state.focused = it },
                searching = state.searching
            )

            LaunchedEffect(state.query.text) {
                state.searching = true
                state.searchResults = SearchRepo.search(state.query.text)
                state.searching = false
            }
            when (state.searchDisplay) {
                SearchDisplay.Results -> SearchResults(
                    state.searchResults,
                    onCapClick
                )
                SearchDisplay.NoResults -> NoResults()
            }
        }
    }
}

enum class SearchDisplay {
    Results, NoResults
}

@Composable
private fun rememberSearchState(
    query: TextFieldValue = TextFieldValue(""),
    focused: Boolean = false,
    searching: Boolean = false,
    searchResults: List<Cap> = emptyList()
): SearchState {
    return remember {
        SearchState(
            query = query,
            focused = focused,
            searching = searching,
            searchResults = searchResults
        )
    }
}

@Stable
class SearchState(
    query: TextFieldValue,
    focused: Boolean,
    searching: Boolean,
    searchResults: List<Cap>
) {
    var query by mutableStateOf(query)
    var focused by mutableStateOf(focused)
    var searching by mutableStateOf(searching)
    var searchResults by mutableStateOf(searchResults)
    val searchDisplay: SearchDisplay
        get() = when {
            searchResults.isEmpty() -> SearchDisplay.NoResults
            else -> SearchDisplay.Results
        }
}

@Composable
private fun SearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    searching: Boolean,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = {
            if (searching) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(24.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null
                )
            }
        },
        placeholder = {
            Text(stringResource(id = R.string.catalog_quick_search_733))
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .onFocusChanged {
                onSearchFocusChange(it.isFocused)
            }
    )
}

@Preview("default")
@Preview("dark theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview("large font", fontScale = 2f)
@Composable
private fun SearchBarPreview() {
    MaterialTheme {
        Surface {
            SearchBar(
                query = TextFieldValue(""),
                onQueryChange = { },
                onSearchFocusChange = { },
                searching = true
            )
        }
    }
}
