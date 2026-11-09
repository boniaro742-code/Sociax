package com.sociax.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Reads a picked photo, shrinks it to at most [maxSide] px and returns JPEG bytes (saves data and quota).
 * Step 76: the photo is read UPRIGHT (the camera's EXIF turn is applied by [decodeUpright]); before, a portrait camera photo could be saved sideways.
 */
suspend fun loadJpeg(context: Context, uri: Uri, maxSide: Int = 1024): ByteArray {
    val startedAt = SystemClock.elapsedRealtime()      // Step 52: measured, so a native (C++) version is only considered if THIS is slow
    val bmp = decodeUpright(context, uri, maxSide)
    val jpeg = withContext(Dispatchers.Default) {
        ByteArrayOutputStream().use { bos ->
            check(bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)) { "Cannot save this photo" }
            bos.toByteArray()
        }
    }
    Perf.record("photo_shrink_ms", SystemClock.elapsedRealtime() - startedAt)
    return jpeg
}
