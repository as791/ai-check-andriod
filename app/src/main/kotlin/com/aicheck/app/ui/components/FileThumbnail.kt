package com.aicheck.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small history-row thumbnail loaded straight from app-private storage. No image
 * loading library — these files are tiny (<=256px) and decoding them is cheap, so a
 * dedicated cache/pipeline (Coil, Glide) would be unjustified complexity here.
 */
@Composable
fun FileThumbnail(path: String?, contentDescription: String?, modifier: Modifier = Modifier) {
    val bitmap = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, path) {
        value = path?.let {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull()
            }
        }
    }.value

    val shape = RoundedCornerShape(12.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(56.dp).clip(shape),
        )
    } else {
        Box(modifier = modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
