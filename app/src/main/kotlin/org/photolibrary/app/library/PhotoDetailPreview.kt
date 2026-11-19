package org.photolibrary.app.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.photolibrary.app.R

@Composable
internal fun PhotoDetailPreview(photo: SelectedPhoto, title: String, revision: Long, loadImage: suspend (Uri, Int) -> Bitmap?) {
    val context = LocalContext.current
    var zoomOpen by remember(photo.uri, revision) { mutableStateOf(false) }
    val zoomLabel = stringResource(R.string.photo_zoom)
    PhotoImage(photo.uri, 1600, revision, loadImage, ContentScale.Fit,
        Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(4f / 3f)
            .clickable(onClickLabel = zoomLabel) { zoomOpen = true },
        description = title,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { zoomOpen = true }) { Text(zoomLabel) }
        TextButton(onClick = { openPhotoAction(context, photo.uri, share = true) }) {
            Text(stringResource(R.string.photo_share))
        }
        TextButton(onClick = { openPhotoAction(context, photo.uri, share = false) }) {
            Text(stringResource(R.string.photo_open_with))
        }
    }
    if (zoomOpen) ZoomPhoto(photo, title, revision, loadImage) { zoomOpen = false }
}

@Composable
private fun ZoomPhoto(photo: SelectedPhoto, title: String, revision: Long, loadImage: suspend (Uri, Int) -> Bitmap?, close: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = close) { Text(stringResource(R.string.library_back)) }
                    TextButton(onClick = { scale = 1f; position = Offset.Zero }) {
                        Text(stringResource(R.string.photo_reset_zoom))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            position = Offset.Zero
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            val boundX = size.width * (scale - 1f) / 2f
                            val boundY = size.height * (scale - 1f) / 2f
                            position = Offset((position.x + pan.x).coerceIn(-boundX, boundX),
                                (position.y + pan.y).coerceIn(-boundY, boundY))
                        }
                    }) {
                    PhotoImage(photo.uri, 2560, revision, loadImage, ContentScale.Fit,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = position.x
                            translationY = position.y
                        }, description = title)
                }
            }
        }
    }
}

private fun openPhotoAction(context: Context, uri: Uri, share: Boolean) {
    try {
        val mime = context.contentResolver.getType(uri) ?: "image/*"
        val intent = if (share) Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
        } else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.clipData = ClipData.newRawUri(null, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.photo_action_unavailable, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.photo_action_unavailable, Toast.LENGTH_SHORT).show()
    }
}
