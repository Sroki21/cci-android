package pl.sroki.cci.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import pl.sroki.cci.android.ui.theme.CCITheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    onSearch: (String) -> Unit = {},
    onRequestClose: () -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }

    fun onClearClick() {
        searchQuery = ""
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val localStyle = LocalTextStyle.current
    val mergedStyle = localStyle.merge(TextStyle(color = LocalContentColor.current))

    BasicTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .focusRequester(focusRequester),
        interactionSource = interactionSource,
        cursorBrush = SolidColor(Color.White),
        textStyle = mergedStyle,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(searchQuery) }),
        decorationBox = @Composable { innerTextField ->
            TextFieldDefaults.DecorationBox(
                value = searchQuery,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                placeholder = {
                    Text(
                        "Search",
                        color = Color.White.copy(alpha = 0.74f)
                    )
                },
                leadingIcon = {
                    IconButton(onClick = onRequestClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                },
                trailingIcon = {
                    if (searchQuery.trim().isNotEmpty()) {
                        IconButton(onClick = { onClearClick() }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }
                },
                interactionSource = interactionSource,
                contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(),
            )
        }
    )

    BackHandler(onBack = onRequestClose)
}

@Preview
@Composable
fun SearchBarPreview() {
    CCITheme {
        Surface {
            SearchBar()
        }
    }
}
