package pl.sroki.cci.android.ui.catalog.caps.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.time.Clock
import pl.sroki.cci.android.R
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.*
import pl.sroki.cci.android.ui.theme.CCITheme
import pl.sroki.cci.android.ui.theme.ImageBackground


@Composable
fun CapDetailView(
    cap: CapExtended,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(cap.imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = cap.description,
            modifier = modifier
                .aspectRatio(1f)
                .background(ImageBackground),
            contentScale = ContentScale.Crop,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CapDetailTextView(label = "Text on the crown cap", text = cap.description)
            CapDetailTextView(label = "Country", text = cap.country.name)
            CapDetailTextView(label = "Product", text = cap.product.name)
            CapDetailTextView(label = "Liner", text = cap.liner.name)
            CapDetailTextView(label = "Type", text = cap.purpose.name)
            if (cap.generic) {
                CapDetailTextView(label = "Generic", text = "Yes")
            }
            CapDetailTextView(label = "Issued", text = cap.year?.toString())
            CapDetailTextView(label = "Period used", text = cap.periodUsed?.name)
            CapDetailTextView(label = "Skirt text", text = cap.rimtext)
            CapDetailTextView(label = "Info", text = cap.info)
            if (cap.properties.isNotEmpty()) {
                CapDetailTextView(
                    label = "Properties",
                    text = cap.properties.joinToString { it.name })
            }
        }


        cap.series?.let {
            Column {
                SectionHeader(text = "Series")
                CapDetailSeriesView(series = it, capSeriesSortOrder = cap.seriesSortOrder)
            }
        }

        // TODO additionalImages
        // TODO inside images

        if (cap.producers.isNotEmpty()) {
            Column {
                SectionHeader(text = "Producers")
                cap.producers.map {
                    CapDetailProducerView(producer = it, onWebsiteClick = {
                        openProducerUrl(context = context, producer = it)
                    }, modifier = Modifier.clickable {
                        println(it)
                    })
                }
            }
        }

        if (cap.signGroups.isNotEmpty()) {
            Column {
                SectionHeader(text = "Signs")
                CapDetailSignsView(signGroups = cap.signGroups)
            }
        }

        // TODO who has this one?
    }

}

private fun shareCap(context: Context, subject: String, summary: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, summary)
    }

    context.startActivity(
        Intent.createChooser(
            intent,
            context.getString(R.string.app_name)
        )
    )
}

private fun openProducerUrl(context: Context, producer: Producer) {
    Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"))
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(producer.website))

    context.startActivity(
        Intent.createChooser(
            intent,
            context.getString(R.string.app_name)
        )
    )
}

@Preview(widthDp = 320, heightDp = 1600, backgroundColor = 0xFFFFFFFF)
@Composable
fun CapDetailViewPreview() {
    val country = Country(
        1,
        "USA",
        imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/1.f7676d1d.jpeg"
    )

    val cap = CapExtended(
        id = 1,
        description = "Hello",
        generic = true,
        picture = true,
        rimtext = "Skirt text",
        info = "Info about the cap. Can be pretty long",
        country = country,
        product = Product(1, "Beer"),
        purpose = Purpose(1, "Bottle closure"),
        liner = Liner(1, "Plastic"),
        producers = listOf(
            Producer(
                id = 1,
                name = "Brewery Co",
                city = "Atlanta",
                country = country,
                website = "https://crowncaps.info"
            ),
            Producer(
                id = 2,
                name = "Another Brewery Co",
                city = "Atlanta",
                country = country,
                website = "https://crowncaps.info"
            )
        ),
        seriesSortOrder = 0,
        series = Series(
            id = 1,
            name = "Series",
            info = "Series info",
            total = 100,
            year = 2020
        ),
        periodUsed = PeriodUsed(1, "2020-2030"),
        properties = listOf(
            CapProperty(1, "Embossed"),
            CapProperty(2, "Big size"),
        ),
        year = 2020,
        imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/1.f7676d1d.jpeg",
        signGroups = List(10) { index ->
            SignGroup(
                id = index,
                groupSigns = listOf(
                    GroupSign(
                        id = index,
                        position = null,
                        sign = Sign(
                            1,
                            imageUrl = "https://crowncaps.info/common/nofactorysign.png"
                        )
                    )
                )
            )
        },
        categories = listOf(
            Category(1, "Animals"),
            Category(2, "Color: red"),
        ),
        insideImages = listOf(
            InsideImage(
                id = 1,
                imageUrl = "https://ddxwnzii69fzh.cloudfront.net/inside-images/504.67d9dc86.jpeg"
            )
        ),
        images = listOf(
            AdditionalImage(
                id = 1,
                imageUrl = "https://ddxwnzii69fzh.cloudfront.net/images/dca62208-173b-49dd-94b5-31a02c18e357.jpeg",
                thumbnailImageUrl = "https://ddxwnzii69fzh.cloudfront.net/images/thumbnails/dca62208-173b-49dd-94b5-31a02c18e357.jpeg",
                width = 312,
                height = 595
            )
        ),
        usersCount = 1,
        isInCollection = false,
        createdBy = UserPublic(
            id = 1,
            firstName = "John",
            lastName = "Doe",
            imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/1.f7676d1d.jpeg",
            active = true,
            country = country,
        ),
        createdAt = Clock.System.now()
    )
    CCITheme {
        Surface {
            CapDetailView(cap = cap)
        }
    }
}