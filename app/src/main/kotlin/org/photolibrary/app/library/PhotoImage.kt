package org.photolibrary.app.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.photolibrary.app.R

private data class ImageState(val bitmap: Bitmap? = null, val loading: Boolean = true)

@Composable
internal fun PhotoImage(
    uri: Uri,
    maxEdge: Int,
    revision: Long,
    loadImage: suspend (Uri, Int) -> Bitmap?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    description: String? = null,
    regionPicker: (@Composable (Bitmap) -> Unit)? = null,
) {
    val state = key(uri, maxEdge, revision) {
        produceState(ImageState()) {
            value = ImageState(loadImage(uri, maxEdge), loading = false)
        }.value
    }
    val bitmap = state.bitmap
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.loading -> CircularProgressIndicator()
            bitmap != null && regionPicker != null -> regionPicker(bitmap)
            bitmap != null -> Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = description,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Text(
                stringResource(R.string.library_unavailable),
                modifier = Modifier.padding(8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}
