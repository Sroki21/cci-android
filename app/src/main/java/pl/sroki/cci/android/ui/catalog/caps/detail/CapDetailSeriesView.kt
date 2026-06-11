package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import pl.sroki.cci.android.model.Series

@Composable
fun CapDetailSeriesView(series: Series, capSeriesSortOrder: Int?) {
    ListItem(
        headlineContent = {
            Text(
                text = series.getDescription(capSortOrder = capSeriesSortOrder),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        },
        supportingContent = {
            series.info?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
