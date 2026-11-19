package org.photolibrary.app.library

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.photolibrary.app.subjects.PhotoRegion

@Composable
internal fun RegionPicker(bitmap: Bitmap, region: PhotoRegion?, onRegion: ((PhotoRegion) -> Unit)? = null) {
    val onComplete by rememberUpdatedState(onRegion)
    val outline = MaterialTheme.colorScheme.primary
    var start by remember(bitmap) { mutableStateOf<Offset?>(null) }
    var end by remember(bitmap) { mutableStateOf<Offset?>(null) }
    val active = start?.let { first -> end?.let { area(first, it) } } ?: region
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth, maxHeight * ratio)
        val gesture = if (onRegion == null) Modifier else Modifier.pointerInput(bitmap) {
            fun Offset.fraction() = Offset((x / size.width).coerceIn(0f, 1f), (y / size.height).coerceIn(0f, 1f))
            detectDragGestures(
                onDragStart = { start = it.fraction(); end = null },
                onDragCancel = { start = null; end = null },
                onDragEnd = {
                    val first = start
                    val last = end
                    if (first != null && last != null) area(first, last)?.let { onComplete?.invoke(it) }
                    start = null
                    end = null
                },
            ) { change, _ ->
                change.consume()
                end = change.position.fraction()
            }
        }
        Box(Modifier.size(width, width / ratio).then(gesture)) {
            Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                if (active != null) drawRect(outline, Offset(active.left * size.width, active.top * size.height),
                    Size((active.right - active.left) * size.width, (active.bottom - active.top) * size.height), style = Stroke(3.dp.toPx()))
            }
        }
    }
}

private fun area(first: Offset, last: Offset): PhotoRegion? {
    val left = minOf(first.x, last.x)
    val top = minOf(first.y, last.y)
    val right = maxOf(first.x, last.x)
    val bottom = maxOf(first.y, last.y)
    return if (right - left >= .025f && bottom - top >= .025f) PhotoRegion(left, top, right, bottom) else null
}
