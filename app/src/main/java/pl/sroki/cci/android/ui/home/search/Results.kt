package pl.sroki.cci.android.ui.home.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.caps

@Composable
fun SearchResults(
    searchResults: List<Cap>,
    onCapClick: (Long) -> Unit
) {
    LazyColumn {
        itemsIndexed(searchResults) { _, cap ->
            SearchResult(cap, onCapClick)
        }
    }
}

@Composable
private fun SearchResult(
    cap: Cap,
    onCapClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier
        .clickable { onCapClick(cap.id) }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(cap.imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = cap.description,
            modifier = Modifier.size(80.dp),
            contentScale = ContentScale.Crop,
        )
        Column(verticalArrangement = Arrangement.Center,
            modifier = Modifier.height(80.dp)
                .padding(8.dp)
        ) {
            cap.description?.let { Text(text = it) }
            Text(text = cap.country, color = MaterialTheme.colorScheme.secondary)
            Text(text = cap.product, color = MaterialTheme.colorScheme.secondary)
        }
    }

}

@Composable
fun NoResults(
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .wrapContentSize()
            .padding(24.dp)
    ) {
        Text(
            text = "No results",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}


@Preview("Default")
@Composable
private fun ResultPreview() {
    MaterialTheme {
        Surface {
            SearchResult(
                cap = caps.first(),
                onCapClick = { }
            )
        }
    }
}
