package pl.sroki.cci.android.ui.catalog.picturesearch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import pl.sroki.cci.android.model.Category
import pl.sroki.cci.android.ui.components.FullSizeLoader
import pl.sroki.cci.android.ui.theme.CCITheme

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PictureSearch(
    onSearch: (categories: Set<Category>) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<PictureSearchViewModel>()
    val uiState = viewModel.pictureSearchUiState
    LaunchedEffect(true) {
        viewModel.getCategories()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(text = "Picture search")
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )
    },
        bottomBar = {
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                Button(
                    enabled = viewModel.selectedCategories.isNotEmpty(),
                    onClick = { onSearch(viewModel.selectedCategories) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Search")
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
        when (uiState) {
            is PictureSearchUiState.Loading -> FullSizeLoader()
            is PictureSearchUiState.Success -> LazyColumn(
                modifier = modifier.fillMaxSize()
            ) {
                itemsIndexed(uiState.categories) { _, category ->
                    ListItem(
                        trailing = {
                            Checkbox(
                                checked = viewModel.selectedCategories.contains(category),
                                onCheckedChange = {
                                    viewModel.toggleCategory(category)
                                })
                        },
                        modifier = Modifier.clickable {
                            viewModel.toggleCategory(category)
                        }) {
                        Text(text = category.name)
                    }
                }
            }

            is PictureSearchUiState.Error -> Text("Error: " + uiState.error.message)
        }
        }
    }
}


@Preview(widthDp = 320, heightDp = 400, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PictureSearchPreview() {
    CCITheme {
        PictureSearch()
    }
}