package pl.sroki.cci.android.ui.catalog.caps.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import pl.sroki.cci.android.model.GroupSign
import pl.sroki.cci.android.model.Sign
import pl.sroki.cci.android.model.SignGroup
import pl.sroki.cci.android.ui.theme.CCITheme
import pl.sroki.cci.android.ui.theme.ImageBackground

@Composable
fun CapDetailSignsView(signGroups: List<SignGroup>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        signGroups.map { signGroup ->
            Row(
                modifier = Modifier
                    .clip(shape = RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp)
            ) {
                signGroup.groupSigns.map { groupSign ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(groupSign.sign.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Sign #${groupSign.sign.id}}",
                        modifier = modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surface),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun CapDetailSignsViewPreview() {
    val signGroups = List(3) { index ->
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
                ),
                GroupSign(
                    id = index + 1,
                    position = null,
                    sign = Sign(
                        2,
                        imageUrl = "https://crowncaps.info/common/nofactorysign.png"
                    )
                )
            )
        )
    }
    CCITheme {
        Surface {
            CapDetailSignsView(signGroups = signGroups)
        }
    }
}
