package pl.sroki.cci.android.ui.catalog.caps.detail

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.Producer
import pl.sroki.cci.android.ui.theme.CCITheme


@Composable
fun CapDetailProducerView(
    producer: Producer,
    modifier: Modifier = Modifier,
    onWebsiteClick: () -> Unit = {},
) {
    Row(
        modifier = modifier.padding(
            horizontal = 16.dp,
            vertical = 12.dp,
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = producer.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = producer.getLocation(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        producer.website?.let {
            IconButton(onClick = onWebsiteClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Open URL",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Preview()
@Preview("dark theme", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CapDetailProducerViewPreview() {
    val country = Country(
        1,
        "USA",
        imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/1.f7676d1d.jpeg"
    )

    val producer = Producer(
        id = 1,
        name = "Brewery Co",
        city = "Atlanta",
        country = country,
        website = "https://crowncaps.info"
    )

    CCITheme {
        Surface {
            CapDetailProducerView(producer = producer)
        }
    }
}
