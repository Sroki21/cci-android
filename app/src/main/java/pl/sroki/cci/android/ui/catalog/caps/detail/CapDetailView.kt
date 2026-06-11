package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.time.Clock
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.*
import pl.sroki.cci.android.ui.theme.CCITheme
import pl.sroki.cci.android.ui.theme.ImageBackground


@Composable
fun CapDetailView(
    cap: CapExtended,
    binderInfo: CapBinderInfo? = null,
    modifier: Modifier = Modifier
) {
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
            CapDetailTextView(label = "Tekst", text = cap.description)
            CapDetailTextView(label = "Kraj", text = cap.country.name)
            CapDetailTextView(label = "Rok", text = cap.year?.toString())
            if (cap.producers.isNotEmpty()) {
                CapDetailTextView(label = "Producent", text = cap.producers.joinToString { it.name })
            }
            if (binderInfo != null) {
                CapDetailTextView(label = "Klaser", text = binderInfo.binderName)
                CapDetailTextView(label = "Strona", text = binderInfo.pageNumber.toString())
                CapDetailTextView(label = "Pozycja", text = binderInfo.position.toString())
            }
        }
    }

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