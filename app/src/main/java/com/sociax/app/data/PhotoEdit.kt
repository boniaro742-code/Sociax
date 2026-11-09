package com.sociax.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Step 76 (plan v3.10.4 sections 1-2, Stage A #38): the edits of ONE photo. This is only a small list of choices; the picked file is
 * never touched (non-destructive). Every edit is applied to the ORIGINAL pixels once, at the end (and by the GPU in the preview),
 * so switching filters never makes the photo worse. The maths lives in [PhotoMath] (tested on a computer, tools/PhotoMathTest.java).
 */
data class PhotoEdits(
    val filterId: String = "original",
    val intensity: Float = 1f,          // filter strength, 0..1 (0..100 %)
    val brightness: Float = 0f,         // Adjust sliders, each -1..1
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val quarterTurns: Int = 0,          // clockwise turns of 90 degrees, 0..3
    val flip: Boolean = false,          // mirrored left-right (after the turn, as the person sees it)
    val aspect: String = "original",    // crop ratio id, see PhotoMath.CROP_IDS
    val panX: Float = 0f,               // where the crop window sits, -1..1 (0 = centred)
    val panY: Float = 0f
) {
    /** The ONE colour matrix of this photo (filter at its strength, then Adjust). */
    fun colorMatrix(): FloatArray = PhotoMath.compose(filterId, intensity, brightness, contrast, saturation, warmth)

    val colorChanged: Boolean get() = !PhotoMath.isIdentity(colorMatrix(), 0.0005f)
    val geometryChanged: Boolean get() = quarterTurns.mod(4) != 0 || flip || aspect != "original"
    val isDefault: Boolean get() = !colorChanged && !geometryChanged
}

/**
 * Reads [uri] UPRIGHT (the camera's EXIF turn applied) with the longer side at most [maxSide]. The file itself is only read.
 * Before Step 76 a portrait photo from the camera could be saved sideways: BitmapFactory ignores EXIF and a re-saved JPEG loses it.
 */
suspend fun decodeUpright(context: Context, uri: Uri, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Cannot read this photo")

    val opts = BitmapFactory.Options().apply { inSampleSize = PhotoMath.sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
    var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Cannot read this photo")

    val longSide = max(bmp.width, bmp.height)
    if (longSide > maxSide) {
        val k = maxSide.toFloat() / longSide
        bmp = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).roundToInt()), max(1, (bmp.height * k).roundToInt()), true)
    }

    val orientation = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val t = PhotoMath.exifTransform(orientation)
    if (t[0] != 0 || t[1] != 0) bmp = rotateFlip(bmp, t[0] / 90, t[1] != 0)
    bmp
}

/** Turns clockwise by [quarterTurns] x 90 degrees, then mirrors left-right when [flip]. Returns [src] itself when nothing changes. */
fun rotateFlip(src: Bitmap, quarterTurns: Int, flip: Boolean): Bitmap {
    val turns = quarterTurns.mod(4)
    if (turns == 0 && !flip) return src
    val m = Matrix()
    if (turns != 0) m.postRotate(90f * turns)
    if (flip) m.postScale(-1f, 1f)
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

/** Cuts the crop window (ratio + position) out of an already turned bitmap. Returns [src] itself for "original". */
fun cropBitmap(src: Bitmap, e: PhotoEdits): Bitmap {
    val ratio = PhotoMath.aspectOf(e.aspect)
    if (ratio[0] <= 0) return src
    val r = PhotoMath.cropRect(src.width, src.height, ratio[0], ratio[1], e.panX, e.panY)
    if (r[0] == 0 && r[1] == 0 && r[2] == src.width && r[3] == src.height) return src
    return Bitmap.createBitmap(src, r[0], r[1], r[2], r[3])
}

/** Draws [src] through the colour matrix into a new bitmap. Returns [src] itself when the matrix changes nothing. */
fun applyColor(src: Bitmap, matrix: FloatArray): Bitmap {
    if (PhotoMath.isIdentity(matrix, 0.0005f)) return src
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix)) }
    Canvas(out).drawBitmap(src, 0f, 0f, paint)
    return out
}

/** A small centred square of [src] (for the filter thumbnails). */
fun squareThumb(src: Bitmap, size: Int): Bitmap {
    val side = minOf(src.width, src.height)
    val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    return Bitmap.createScaledBitmap(sq, size, size, true)
}

/**
 * The final photo: reads the ORIGINAL again at [maxSide], applies turn / flip, crop, then the one colour matrix, and returns JPEG bytes.
 * Runs off the main thread. If anything fails the caller keeps the draft and the untouched original (nothing is uploaded).
 */
suspend fun loadEditedJpeg(context: Context, uri: Uri, edits: PhotoEdits, maxSide: Int = 1440): ByteArray {
    val startedAt = SystemClock.elapsedRealtime()
    val upright = decodeUpright(context, uri, maxSide)
    val jpeg = withContext(Dispatchers.Default) {
        val turned = rotateFlip(upright, edits.quarterTurns, edits.flip)
        val cropped = cropBitmap(turned, edits)
        val coloured = applyColor(cropped, edits.colorMatrix())
        ByteArrayOutputStream().use { bos ->
            check(coloured.compress(Bitmap.CompressFormat.JPEG, 85, bos)) { "Cannot save the edited photo" }
            bos.toByteArray()
        }
    }
    Perf.record(if (edits.isDefault) "photo_shrink_ms" else "photo_edit_export_ms", SystemClock.elapsedRealtime() - startedAt)
    return jpeg
}
