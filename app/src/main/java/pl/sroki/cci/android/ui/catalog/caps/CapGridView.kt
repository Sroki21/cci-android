package pl.sroki.cci.android.ui.catalog.caps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.model.caps
import pl.sroki.cci.android.ui.theme.CCITheme
import pl.sroki.cci.android.ui.theme.ImageBackground
import pl.sroki.cci.android.ui.theme.StatusInCollection
import pl.sroki.cci.android.ui.theme.StatusPurchased

@Composable
fun CapGridCard(cap: Cap, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val assignedCapIds = LocalAssignedCapIds.current
    val border = when {
        !cap.isInCollection -> null
        cap.id in assignedCapIds -> BorderStroke(6.dp, StatusInCollection) // W kolekcji → zielona
        else -> BorderStroke(6.dp, StatusPurchased)                        // Zakupiony → niebieska
    }
    Card(
        border = border
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(cap.imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = cap.description,
            modifier = modifier
                .aspectRatio(1f)
                .background(ImageBackground)
                .clickable(onClick = onClick),
            contentScale = ContentScale.Crop,
        )
    }
}

@Preview(widthDp = 320)
@Composable
fun CapGridViewPreview() {
    CCITheme {
        LazyVerticalGrid(columns = GridCells.Fixed(2)) {
            items(caps) { cap ->
                CapGridCard(cap = cap, onClick = {})
            }
        }
    }
}