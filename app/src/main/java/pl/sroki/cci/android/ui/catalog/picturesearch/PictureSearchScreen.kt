package pl.sroki.cci.android.ui.catalog.picturesearch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import pl.sroki.cci.android.model.Cap
import pl.sroki.cci.android.ui.catalog.caps.CapsView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PictureSearch(
    onBack: () -> Unit = {},
    onCapClick: (Cap) -> Unit = {}
) {
    val viewModel = hiltViewModel<PictureSearchViewModel>()
    val context = LocalContext.current
    val caps = viewModel.caps.collectAsLazyPagingItems()

    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var cropSourceUri by remember { mutableStateOf<Uri?>(null) }

    if (cropSourceUri != null) {
        CropScreen(
            sourceUri = cropSourceUri!!,
            onConfirm = { croppedUri ->
                viewModel.onImageSelected(croppedUri)
                cropSourceUri = null
            },
            onDismiss = { cropSourceUri = null }
        )
        return
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) cameraImageUri?.let { cropSourceUri = it }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createCameraUri(context)
            cameraImageUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { cropSourceUri = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Szukaj wg zdjęcia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                expandedHeight = 48.dp
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            if (viewModel.selectedImageUri == null) {
                Text(
                    text = "Wybierz zdjęcie kapsla, by znaleźć podobne",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Galeria")
                    }
                    OutlinedButton(
                        onClick = {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                val uri = createCameraUri(context)
                                cameraImageUri = uri
                                cameraLauncher.launch(uri)
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Aparat")
                    }
                }
            } else {
                AsyncImage(
                    model = viewModel.selectedImageUri,
                    contentDescription = "Wybrane zdjęcie",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Text("Zmień zdjęcie")
                    }
                    Button(onClick = viewModel::search) {
                        Text("Szukaj")
                    }
                }
                if (viewModel.hasSearched) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Box(modifier = Modifier.weight(1f)) {
                        CapsView(caps = caps, onCapClick = onCapClick)
                    }
                }
            }
        }
    }
}

private fun createCameraUri(context: Context): Uri {
    val file = File(context.cacheDir, "camera_images/photo_${System.currentTimeMillis()}.jpg")
        .also { it.parentFile?.mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
