package com.sociax.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Length of a video in milliseconds (0 if it cannot be read). Call off the main thread. */
fun videoDurationMs(context: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        runCatching { r.release() }
    }
}

/** Width x height as the person sees it (rotation applied). (0, 0) if unknown. */
private fun videoDisplaySize(context: Context, uri: Uri): Pair<Int, Int> {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) h to w else w to h
    } catch (e: Exception) {
        0 to 0
    } finally {
        runCatching { r.release() }
    }
}

/** Copies a picked/recorded video into the app cache so it can be uploaded as a normal file. */
suspend fun copyVideoToCache(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val f = File(context.cacheDir, "short_src_${System.nanoTime()}.mp4")
    context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        ?: error("Cannot read this video")
    f
}

private fun even(v: Int) = if (v % 2 == 0) v else v + 1

/**
 * Cuts [startMs]..[endMs] out of the video and compresses it on the phone according to the
 * upload-quality setting ("low" 480p, "standard" 720p, "high" 1080p). Never upscales.
 * Must be called from the main thread (Media3 Transformer requirement); returns the new file.
 */
@androidx.annotation.OptIn(UnstableApi::class)
suspend fun exportShort(context: Context, uri: Uri, startMs: Long, endMs: Long?, quality: String): File {
    val (w, h) = withContext(Dispatchers.IO) { videoDisplaySize(context, uri) }
    val targetShortSide = when (quality) { "low" -> 480; "high" -> 1080; else -> 720 }
    val bitrate = when (quality) { "low" -> 1_200_000; "high" -> 5_000_000; else -> 2_500_000 }
    val out = File(context.cacheDir, "short_out_${System.nanoTime()}.mp4")

    val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).apply {
        if (endMs != null) setEndPositionMs(endMs)
    }.build()
    val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()

    val videoEffects = mutableListOf<Effect>()
    val shortSide = minOf(w, h)
    if (shortSide > targetShortSide) {
        val scale = targetShortSide.toFloat() / shortSide
        videoEffects += Presentation.createForWidthAndHeight(
            even((w * scale).toInt()), even((h * scale).toInt()), Presentation.LAYOUT_SCALE_TO_FIT
        )
    }
    val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), videoEffects)).build()

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<File> { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build()
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(out)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        out.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel(); out.delete() } }
            transformer.start(edited, out.absolutePath)
        }
    }
}
