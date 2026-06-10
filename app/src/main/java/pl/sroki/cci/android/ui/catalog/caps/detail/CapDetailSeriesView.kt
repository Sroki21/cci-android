package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontWeight
import pl.sroki.cci.android.model.Series

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun CapDetailSeriesView(series: Series, capSeriesSortOrder: Int?) {
    ListItem(
        text = {
            Text(
                text = series.getDescription(capSortOrder = capSeriesSortOrder),
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Bold
            )
        },
        secondaryText = {
            series.info?.let {
                CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.medium) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.subtitle2
                    )
                }
            }
        }
    )
}