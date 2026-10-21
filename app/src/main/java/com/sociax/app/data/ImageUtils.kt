package com.sociax.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Reads a picked photo, shrinks it to at most [maxSide] px and returns JPEG bytes (saves data and quota). */
suspend fun loadJpeg(context: Context, uri: Uri, maxSide: Int = 1024): ByteArray = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: error("Cannot read this photo")

    val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
    val out = if (scale < 1f)
        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    else bmp

    ByteArrayOutputStream().use { bos ->
        out.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        bos.toByteArray()
    }
}
