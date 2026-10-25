package com.sociax.app.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SociaxVideo"

private fun retrieverDuration(how: String, setup: (MediaMetadataRetriever) -> Unit): Long {
    val r = MediaMetadataRetriever()
    return try {
        setup(r)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        // diagnostic only: the way that failed and the kind of error, never a file name or address
        Log.w(TAG, "duration: $how failed (${e.javaClass.simpleName})")
        0L
    } finally {
        runCatching { r.release() }
    }
}

/**
 * Length of a video in milliseconds (0 if it cannot be read). Call off the main thread.
 * Plan v3.6 section 2: a picked video is only a content address (not always a file path), and phones differ, so four independent
 * ways are tried in turn, and the first one that gives a length wins:
 *  1. MediaMetadataRetriever on the content address, 2. the same on a file descriptor from ContentResolver,
 *  3. MediaExtractor (reads the track headers), 4. a temporary copy in the app cache read as a normal file.
 * Every way is released properly. A failure is written to the log (way + error kind only) and never crashes.
 */
fun videoDurationMs(context: Context, uri: Uri): Long {
    retrieverDuration("retriever(uri)") { it.setDataSource(context, uri) }.let { if (it > 0) { Log.i(TAG, "duration read by retriever(uri)"); return it } }

    val viaFd = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            retrieverDuration("retriever(fd)") { it.setDataSource(pfd.fileDescriptor) }
        } ?: 0L
    } catch (e: Exception) { Log.w(TAG, "duration: openFileDescriptor failed (${e.javaClass.simpleName})"); 0L }
    if (viaFd > 0) { Log.i(TAG, "duration read by retriever(fd)"); return viaFd }

    val ex = MediaExtractor()
    val viaExtractor = try {
        ex.setDataSource(context, uri, null)
        var best = 0L
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.containsKey(MediaFormat.KEY_DURATION)) best = maxOf(best, f.getLong(MediaFormat.KEY_DURATION) / 1000)
        }
        best
    } catch (e: Exception) { Log.w(TAG, "duration: extractor failed (${e.javaClass.simpleName})"); 0L }
    finally { runCatching { ex.release() } }
    if (viaExtractor > 0) { Log.i(TAG, "duration read by extractor"); return viaExtractor }

    // last resort: a temporary copy (only when the size is known to be reasonable, so a huge file does not fill the phone)
    val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
    if (size in 0..(400L * 1024 * 1024)) {
        val tmp = File(context.cacheDir, "probe_${System.nanoTime()}.bin")
        try {
            context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val viaCopy = retrieverDuration("retriever(copy)") { it.setDataSource(tmp.absolutePath) }
            if (viaCopy > 0) { Log.i(TAG, "duration read by retriever(copy)"); return viaCopy }
        } catch (e: Exception) {
            Log.w(TAG, "duration: copy failed (${e.javaClass.simpleName})")
        } finally { tmp.delete() }
    }
    Log.w(TAG, "duration: unreadable by the four quick ways (size known: ${size >= 0})")
    return 0L
}

/**
 * Fifth way, for the rare video the quick ways cannot read: let the video player open it and report its length
 * (it understands more formats). Runs on the main thread, gives up after 8 seconds, always releases the player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
suspend fun videoDurationViaPlayer(context: Context, uri: Uri): Long = withContext(Dispatchers.Main) {
    val player = ExoPlayer.Builder(context).build()
    try {
        withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine<Long> { cont ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && cont.isActive) {
                            val d = player.duration
                            cont.resume(if (d == C.TIME_UNSET || d < 0) 0L else d)
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "duration: player failed (code ${error.errorCodeName})")
                        if (cont.isActive) cont.resume(0L)
                    }
                })
                player.volume = 0f
                player.setMediaItem(MediaItem.fromUri(uri))
                player.playWhenReady = false
                player.prepare()
            }
        } ?: 0L
    } catch (e: Exception) {
        Log.w(TAG, "duration: player way failed (${e.javaClass.simpleName})"); 0L
    } finally {
        runCatching { player.release() }
    }
}

/** The quick ways first (off the main thread), then the player. 0 only when every way failed (already written to the log). */
suspend fun videoDurationAny(context: Context, uri: Uri): Long {
    val quick = withContext(Dispatchers.IO) { videoDurationMs(context, uri) }
    if (quick > 0) return quick
    val viaPlayer = videoDurationViaPlayer(context, uri)
    if (viaPlayer > 0) Log.i(TAG, "duration read by player")
    return viaPlayer
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
suspend fun exportShort(context: Context, uri: Uri, startMs: Long, endMs: Long?, quality: String): File {
    val startedAt = android.os.SystemClock.elapsedRealtime()      // Step 52: measured before anyone talks about native (C++) video code
    val file = exportShortImpl(context, uri, startMs, endMs, quality)
    val took = android.os.SystemClock.elapsedRealtime() - startedAt
    Perf.record("video_export_ms", took)
    val clipMs = if (endMs != null) endMs - startMs else 0L
    if (clipMs >= 1_000L) Perf.record("video_export_ms_per_10s_of_video", took * 10_000L / clipMs)
    return file
}

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun exportShortImpl(context: Context, uri: Uri, startMs: Long, endMs: Long?, quality: String): File {
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
