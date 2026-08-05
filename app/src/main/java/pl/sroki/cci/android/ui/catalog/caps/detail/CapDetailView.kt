package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.time.Clock
import pl.sroki.cci.android.model.binder.BinderView
import pl.sroki.cci.android.model.binder.BinderPageView
import pl.sroki.cci.android.model.binder.POSITIONS_PER_PAGE
import pl.sroki.cci.android.data.model.CapBinderInfo
import pl.sroki.cci.android.data.model.Country
import pl.sroki.cci.android.model.BinderSuggestion
import pl.sroki.cci.android.model.CapExtended
import pl.sroki.cci.android.model.CapProperty
import pl.sroki.cci.android.model.Category
import pl.sroki.cci.android.model.Liner
import pl.sroki.cci.android.model.PeriodUsed
import pl.sroki.cci.android.model.Producer
import pl.sroki.cci.android.model.Product
import pl.sroki.cci.android.model.Purpose
import pl.sroki.cci.android.model.Series
import pl.sroki.cci.android.model.UserPublic
import pl.sroki.cci.android.ui.theme.CCITheme
import pl.sroki.cci.android.ui.theme.ImageBackground

@Composable
fun CapDetailView(
    cap: CapExtended,
    status: CapStatus = CapStatus.MISSING,
    binderInfo: CapBinderInfo? = null,
    binders: List<BinderView> = emptyList(),
    binderPages: List<BinderPageView> = emptyList(),
    selectedBinderId: Long? = null,
    selectedPageId: Long? = null,
    selectedPosition: Int? = null,
    isSaving: Boolean = false,
    binderSuggestion: BinderSuggestion? = null,
    onBinderSelected: (Long) -> Unit = {},
    onPageSelected: (Long) -> Unit = {},
    onPositionSelected: (Int) -> Unit = {},
    onProducerClick: (String) -> Unit = {},
    onProducerSelected: (Producer) -> Unit = {},
    onCapNumberClick: (Long) -> Unit = {},
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
            // Modyfikator wywołującego należy do korzenia (Column powyżej). Użyty ponownie tutaj
            // nakładałby np. padding czy fillMaxSize podwójnie — dziś nieszkodliwe tylko dlatego,
            // że jedyny wywołujący nic nie przekazuje.
            modifier = Modifier
                .aspectRatio(1f)
                .background(ImageBackground),
            contentScale = ContentScale.Crop,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CapDetailTextView(label = "Tekst", text = cap.description)
            CapDetailCountryView(
                countryName = cap.country.name,
                producers = cap.producers,
                onProducerSelected = onProducerSelected,
            )
            CapDetailTextView(label = "Rok", text = cap.year?.toString())
            if (cap.producers.isNotEmpty()) {
                CapDetailProducersView(producers = cap.producers, onProducerClick = onProducerClick)
            }
            CapDetailInfoView(info = cap.info, onCapNumberClick = onCapNumberClick)
            if (status == CapStatus.PURCHASED || status == CapStatus.IN_COLLECTION) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Lokalizacja w klaserze",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (binderSuggestion != null && status == CapStatus.PURCHASED) {
                        Text(
                            text = "${binderSuggestion.binderName} / S.${binderSuggestion.pageNumber} / P.${binderSuggestion.nextPosition}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End
                        )
                    }
                }
                CapDetailSelectView(
                    label = "Klaser",
                    value = binders.firstOrNull { it.id == selectedBinderId }?.name,
                    options = binders.map { it.name to it.id },
                    enabled = binders.isNotEmpty() && !isSaving,
                    onSelected = onBinderSelected,
                )
                CapDetailSelectView(
                    label = "Strona",
                    value = binderPages.firstOrNull { it.id == selectedPageId }
                        ?.let { "Strona ${it.pageNumber}" },
                    options = binderPages.map { "Strona ${it.pageNumber}" to it.id },
                    enabled = selectedBinderId != null && !isSaving,
                    onSelected = onPageSelected,
                )
                CapDetailSelectView(
                    label = "Pozycja",
                    value = selectedPosition?.toString(),
                    options = (1..POSITIONS_PER_PAGE).map { it.toString() to it.toLong() },
                    enabled = selectedPageId != null && !isSaving,
                    onSelected = { onPositionSelected(it.toInt()) },
                )
                if (isSaving) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CapDetailCountryView(
    countryName: String,
    producers: List<Producer>,
    onProducerSelected: (Producer) -> Unit,
) {
    // "-Multiple countries" nie ma tłumaczenia lokalnego (wartość surowa z API) — traktujemy
    // kapsel jako wielokrajowy strukturalnie: gdy jego producenci mają różne kraje. Wtedy dane
    // do listy (nazwa browaru + kraj) są już w cap.producers, bez potrzeby dodatkowego zapytania.
    val distinctCountries = remember(producers) { producers.map { it.country.id }.distinct() }
    val clickable = distinctCountries.size > 1
    var showDialog by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { showDialog = true } else Modifier)
    ) {
        Text(
            text = "Kraj",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = countryName,
            color = if (clickable) MaterialTheme.colorScheme.primary else Color.Unspecified,
            textDecoration = if (clickable) TextDecoration.Underline else null,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Wybierz kraj/producenta") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    producers.forEach { producer ->
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onProducerSelected(producer)
                                    showDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = producer.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = producer.country.name,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Anuluj") }
            }
        )
    }
}

@Composable
private fun CapDetailProducersView(
    producers: List<Producer>,
    onProducerClick: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Text(
            text = "Producent",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Row(modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)) {
            producers.forEachIndexed { index, producer ->
                Text(
                    text = producer.name,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onProducerClick(producer.name) }
                )
                if (index != producers.lastIndex) {
                    Text(text = ", ", textAlign = TextAlign.End)
                }
            }
        }
    }
}

// Numery kapsli w polu Info (np. "różni się od #131244") — link do CapDetail tego kapsla.
private val CAP_REFERENCE_REGEX = Regex("#(\\d+)")

@Composable
private fun CapDetailInfoView(
    info: String?,
    onCapNumberClick: (Long) -> Unit,
) {
    if (info.isNullOrBlank()) return
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(info, linkColor) {
        buildAnnotatedString {
            var lastIndex = 0
            CAP_REFERENCE_REGEX.findAll(info).forEach { match ->
                append(info.substring(lastIndex, match.range.first))
                val capId = match.groupValues[1].toLongOrNull()
                if (capId != null) {
                    withLink(
                        LinkAnnotation.Clickable(tag = "cap_$capId") { onCapNumberClick(capId) }
                    ) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(match.value)
                        }
                    }
                } else {
                    append(match.value)
                }
                lastIndex = match.range.last + 1
            }
            append(info.substring(lastIndex))
        }
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Text(
            text = "Info",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = annotated,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun CapDetailSelectView(
    label: String,
    value: String?,
    options: List<Pair<String, Long>>,
    enabled: Boolean,
    onSelected: (Long) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { showDialog = true } else Modifier)
    ) {
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value ?: "—",
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp)
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (name, id) ->
                        Text(
                            text = name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelected(id)
                                    showDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Anuluj") }
            }
        )
    }
}

@Preview(widthDp = 320, heightDp = 1600, backgroundColor = 0xFFFFFFFF)
@Composable
fun CapDetailViewPreview() {
    val country = Country(1, "USA", imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/1.f7676d1d.jpeg")
    val cap = CapExtended(
        id = 1,
        description = "Hello",
        generic = true,
        picture = true,
        rimtext = "Skirt text",
        info = "Info",
        country = country,
        product = Product(1, "Beer"),
        purpose = Purpose(1, "Bottle closure"),
        liner = Liner(1, "Plastic"),
        producers = listOf(Producer(id = 1, name = "Brewery Co", city = "Atlanta", country = country, website = "https://crowncaps.info")),
        seriesSortOrder = 0,
        series = Series(id = 1, name = "Series", info = "Series info", total = 100, year = 2020),
        periodUsed = PeriodUsed(1, "2020-2030"),
        properties = listOf(CapProperty(1, "Embossed")),
        year = 2020,
        imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/1.f7676d1d.jpeg",
        signGroups = emptyList(),
        categories = listOf(Category(1, "Animals")),
        insideImages = emptyList(),
        images = emptyList(),
        usersCount = 1,
        isInCollection = false,
        createdBy = UserPublic(id = 1, firstName = "John", lastName = "Doe",
            imageUrl = "https://ddxwnzii69fzh.cloudfront.net/caps/1.f7676d1d.jpeg",
            active = true, country = country),
        createdAt = Clock.System.now()
    )
    CCITheme {
        Surface {
            CapDetailView(cap = cap)
        }
    }
}
