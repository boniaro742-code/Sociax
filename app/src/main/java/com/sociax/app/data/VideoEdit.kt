package com.sociax.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Step 81 added CUTS: parts taken out of the middle. The engine (cutSegments) says which pieces stay; each piece is a clipped item of ONE sequence, with the same effects.
// Step 80 added TEXT on the video: the C++ engine gives the font size and the place (textFontPx / textPlacement), Kotlin draws it into one transparent
// picture of the SAME size as the video frame and Media3 lays it over every frame (preview and export use the same picture, so they match).
// Step 79 added SPEED (0.25x - 4x): video by Media3 SpeedChangeEffect, sound by SonicAudioProcessor (0.5x - 2x, pitch kept) or removed (other speeds), as the engine rule audioPolicy says.
// Step 78 (plan v4.0.3 sections 3 and 6, Stage A #38): the VIDEO EDITOR's edit model, its preview / export effects, and the export itself.
// The picked video is only READ (never changed). Trim, turn, crop and colour are applied by Media3 (GPU); the C++ engine (VideoEngine)
// computes the crop window, the colour matrix and the expected size, and checks the finished file before it may be posted.
// A video with NO edits never comes through here: it keeps the existing exportShort() path.

private const val TAG = "SociaxVideoEdit"

/** Ratio presets of the Crop tab. "original" = no crop. */
object VideoCrop {
    val IDS: List<String> = listOf("original", "1:1", "4:5", "3:4", "9:16", "16:9")
    fun ratio(id: String): Pair<Int, Int>? = when (id) {
        "1:1" -> Pair(1, 1)
        "4:5" -> Pair(4, 5)
        "3:4" -> Pair(3, 4)
        "9:16" -> Pair(9, 16)
        "16:9" -> Pair(16, 9)
        else -> null
    }
}

/** Filter ids are the engine's ids; the name is also the suffix of the translated text "filter_<name>". */
object VideoFilters {
    val NAMES: List<String> = listOf("original", "warm", "cool", "vivid", "mono", "noir", "sepia", "vintage", "fade", "dramatic", "golden", "pastel", "night")
}

/** Speed choices of the Speed tab, in thousandths (1000 = normal). The engine allows 250..4000 (kSpeedMinMilli / kSpeedMaxMilli). */
object VideoSpeed {
    val PRESETS: List<Int> = listOf(250, 500, 750, 1000, 1500, 2000, 3000, 4000)
    /** Chip text for a speed; 1000 is shown with the translated word "Normal" by the screen. */
    fun label(milli: Int): String = when (milli) {
        250 -> "0.25x"; 500 -> "0.5x"; 750 -> "0.75x"; 1500 -> "1.5x"; 2000 -> "2x"; 3000 -> "3x"; 4000 -> "4x"
        else -> "1x"
    }
}

/** The Cut tab: up to this many parts can be taken out of the kept range. Times are milliseconds of the SOURCE video. */
object VideoCut { const val MAX = 8; const val MIN_MS = 100L }

/** The Text tab: one text of at most 2 short lines, shown on the whole video. Colours are ARGB. */
object VideoText {
    const val MAX_LINES = 2
    const val MAX_CHARS = 40
    val WHITE: Int = 0xFFFFFFFF.toInt()
    val COLORS: List<Int> = listOf(WHITE, 0xFF000000.toInt(), 0xFFFFD60A.toInt(), 0xFF2FE0B2.toInt(), 0xFFFF453A.toInt())
    val COLOR_NAMES: List<String> = listOf("white", "black", "yellow", "teal", "red")
    /** size presets, thousandths of the picture's short side: small / medium / large */
    val SIZES: List<Int> = listOf(40, 60, 90)
    const val DEFAULT_SIZE = 60
    const val DEFAULT_X = 500
    const val DEFAULT_Y = 800
    /** Keeps what the person typed within the limits (2 lines, 40 characters each). */
    fun clean(raw: String): String = raw.replace("\r", "").split('\n').take(MAX_LINES).joinToString("\n") { it.take(MAX_CHARS) }
}

/** What the person chose. All defaults = nothing edited. trimEndMs 0 = to the end of the video. */
data class VideoEdits(
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val quarterTurns: Int = 0,
    val cropId: String = "original",
    val filterId: Int = 0,
    val strength: Int = 100,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val temperature: Int = 0,
    val speedMilli: Int = 1000,
    val text: String = "",
    val textColor: Int = VideoText.WHITE,
    val textSize: Int = VideoText.DEFAULT_SIZE,
    val textX: Int = VideoText.DEFAULT_X,
    val textY: Int = VideoText.DEFAULT_Y,
    /** parts taken out (fromMs, toMs), SOURCE time; clamped / merged by the engine */
    val cuts: List<Pair<Long, Long>> = emptyList()
) {
    /** A text counts only when it has a visible character. */
    val hasText: Boolean get() = text.isNotBlank()
    /** Nothing chosen. A text that was typed and erased again (only its colour / place left over) is not an edit. */
    val isDefault: Boolean get() = !hasText && copy(text = "", textColor = VideoText.WHITE, textSize = VideoText.DEFAULT_SIZE, textX = VideoText.DEFAULT_X, textY = VideoText.DEFAULT_Y) == VideoEdits()
    val hasColor: Boolean get() = filterId != 0 || brightness != 0 || contrast != 0 || saturation != 0 || temperature != 0
    /** Crop windows and colour come from the C++ engine; trim and turn do not need it. */
    val needsEngine: Boolean get() = hasColor || cropId != "original" || speedMilli != 1000 || hasText || cuts.isNotEmpty()
    val color: EngineColor get() = EngineColor(filterId, strength, brightness, contrast, saturation, temperature)
}

/** What was read from the picked video. [hasAudio] null = could not be told (then audio is not checked). */
data class VideoSource(val width: Int, val height: Int, val durationMs: Long, val hasAudio: Boolean?)

/** Reads size (rotation applied), length and audio. null when the size or length is unknown: the person then posts without the editor. */
suspend fun readVideoSource(context: Context, uri: Uri): VideoSource? {
    val duration = videoDurationAny(context, uri)
    if (duration <= 0L) return null
    return withContext(Dispatchers.IO) {
        val size = videoDisplaySize(context, uri)
        if (size.first <= 0 || size.second <= 0) null else VideoSource(size.first, size.second, duration, audioTrackPresent(context, uri))
    }
}

private fun audioTrackPresent(context: Context, uri: Uri): Boolean? {
    val ex = MediaExtractor()
    return try {
        ex.setDataSource(context, uri, null)
        var found = false
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) found = true
        }
        found
    } catch (e: Exception) {
        Log.w(TAG, "audio track probe failed (${e.javaClass.simpleName})")
        null
    } finally {
        runCatching { ex.release() }
    }
}

/** A fixed 4x4 colour matrix for the GPU (OpenGL column-major, from the engine). */
@androidx.annotation.OptIn(UnstableApi::class)
private class FixedRgbMatrix(private val matrix: FloatArray) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
}

/** The effects of an edit (turn -> crop -> colour -> size) and the size of the picture they produce. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoEffectPlan(
    val effects: List<Effect>, val outW: Int, val outH: Int, val engineClipDurationUs: Long, val audio: EngineAudio = EngineAudio.NONE,
    /** the pieces that stay (fromMs, toMs of the SOURCE), in order; empty = one piece, the plain trim */
    val segmentsMs: List<Pair<Long, Long>> = emptyList()
)

private fun evenUp(v: Int): Int = if (v % 2 == 0) v else v + 1

/**
 * The text as ONE transparent picture of the frame's size (w x h = the size of the finished picture), laid over every frame by Media3.
 * The engine gives the font size and the place; when the text is too wide for the picture the font is made smaller (15 % per try) until it fits.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun buildTextOverlay(edits: VideoEdits, w: Int, h: Int): EngineResult<Effect> {
    val lines = edits.text.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(VideoText.MAX_LINES).map { it.take(VideoText.MAX_CHARS) }
    if (lines.isEmpty()) return EngineResult.Failed(EngineCode.BAD_ARGUMENT)
    var fontPx = when (val r = VideoEngine.textFontPx(w, h, edits.textSize)) {
        is EngineResult.Ok -> r.value
        is EngineResult.Failed -> return r
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.color = edits.textColor
    for (attempt in 0 until 14) {
        paint.textSize = fontPx.toFloat()
        paint.setShadowLayer(fontPx * 0.08f, 0f, fontPx * 0.04f, 0xCC000000.toInt())   // a soft shadow keeps the text readable on any picture
        val fm = paint.fontMetrics
        val lineH = kotlin.math.ceil((fm.descent - fm.ascent).toDouble()).toInt().coerceAtLeast(1)
        val boxW = kotlin.math.ceil(lines.maxOf { paint.measureText(it) }.toDouble()).toInt().coerceAtLeast(1)
        val boxH = lineH * lines.size
        when (val place = VideoEngine.textPlacement(w, h, edits.textX, edits.textY, boxW, boxH)) {
            is EngineResult.Ok -> {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                lines.forEachIndexed { i, line ->
                    val x = place.value.left + (boxW - paint.measureText(line)) / 2f    // each line centred inside the box
                    canvas.drawText(line, x, place.value.top + i * lineH - fm.ascent, paint)
                }
                return EngineResult.Ok(OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(bmp))))
            }
            is EngineResult.Failed -> if (place.code != EngineCode.BAD_RANGE) return place   // BAD_RANGE = does not fit: smaller font
        }
        val smaller = (fontPx * 0.85f).toInt()
        if (smaller < 8 || smaller >= fontPx) break
        fontPx = smaller
    }
    return EngineResult.Failed(EngineCode.BAD_RANGE)
}

/** The pieces that stay after trim + cuts (engine rule). Without cuts it is the trim itself. */
fun editSegments(edits: VideoEdits, src: VideoSource): EngineResult<List<EngineSegment>> {
    val endMs = if (edits.trimEndMs > 0L) edits.trimEndMs else src.durationMs
    return VideoEngine.cutSegments(edits.trimStartMs * 1000L, endMs * 1000L, edits.cuts.map { it.first * 1000L to it.second * 1000L })
}

/** Length of the kept video BEFORE speed, in ms: the pieces added up (the plain trim length when the engine is not there). */
fun keptLengthMs(edits: VideoEdits, src: VideoSource): Long {
    val endMs = if (edits.trimEndMs > 0L) edits.trimEndMs else src.durationMs
    if (edits.cuts.isEmpty()) return endMs - edits.trimStartMs
    return when (val r = editSegments(edits, src)) {
        is EngineResult.Ok -> r.value.sumOf { (it.endUs - it.startUs) / 1000L }
        is EngineResult.Failed -> endMs - edits.trimStartMs
    }
}

/** What happens to the sound at the chosen speed (the engine's rule): NONE = the video has no sound, KEEP = kept with the normal pitch, MUTE = removed. */
fun speedAudio(edits: VideoEdits, src: VideoSource): EngineAudio {
    val clip = EngineClip(
        srcW = src.width, srcH = src.height, srcDurationUs = src.durationMs * 1000L,
        speedMilli = edits.speedMilli, hasAudio = src.hasAudio ?: (edits.speedMilli != 1000)
    )
    return VideoEngine.audioPolicy(clip)
}

/**
 * Builds the effects. [targetShortSide] limits the short side (never upscales); null = no limit.
 * [withSpeed] false = the live preview (it changes the player's speed instead, so picture and sound stay in step).
 * Failed(UNSUPPORTED) when a crop or a colour is chosen but the native engine is not available on this phone.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun planVideoEffects(edits: VideoEdits, src: VideoSource, targetShortSide: Int?, withSpeed: Boolean = true): EngineResult<VideoEffectPlan> {
    val turns = ((edits.quarterTurns % 4) + 4) % 4
    val effects = ArrayList<Effect>()
    // Step 79: the picture's speed (frames come sooner / later). The sound follows in exportEdited (Sonic, or removed).
    if (withSpeed && edits.speedMilli != 1000) effects.add(SpeedChangeEffect(edits.speedMilli / 1000f))
    if (turns != 0) {
        // Media3 turns counter-clockwise, the engine and the screen turn clockwise
        effects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(((4 - turns) * 90).toFloat()).build())
    }

    // the size the engine promises after turn + crop (and the crop window)
    var crop = EngineCrop(0, 0, 10000, 10000)
    val ratio = VideoCrop.ratio(edits.cropId)
    val turnedW = if (turns % 2 == 1) src.height else src.width
    val turnedH = if (turns % 2 == 1) src.width else src.height
    if (ratio != null) {
        when (val r = VideoEngine.cropForRatio(turnedW, turnedH, ratio.first, ratio.second)) {
            is EngineResult.Ok -> crop = r.value
            is EngineResult.Failed -> return r
        }
    }
    val endMs = if (edits.trimEndMs > 0L) edits.trimEndMs else src.durationMs
    val clip = EngineClip(
        srcW = src.width, srcH = src.height, srcDurationUs = src.durationMs * 1000L,
        turn = turns * 90, cropX = crop.x, cropY = crop.y, cropW = crop.w, cropH = crop.h,
        trimStartUs = edits.trimStartMs * 1000L, trimEndUs = endMs * 1000L, speedMilli = edits.speedMilli,
        hasAudio = src.hasAudio ?: (edits.speedMilli != 1000)   // sound unknown + a speed change: assume sound, so it is never left at the wrong speed
    )
    val out = when (val r = VideoEngine.clipOutput(clip)) {
        is EngineResult.Ok -> r.value
        is EngineResult.Failed -> return r
    }
    // Step 81: with cuts the finished length is the sum of the pieces (each one after speed), asked from the engine piece by piece
    var totalUs = out.durationUs
    var segmentsMs: List<Pair<Long, Long>> = emptyList()
    if (edits.cuts.isNotEmpty()) {
        val segs = when (val r = editSegments(edits, src)) {
            is EngineResult.Ok -> r.value
            is EngineResult.Failed -> return r
        }
        totalUs = 0L
        for (g in segs) {
            when (val r = VideoEngine.clipOutput(clip.copy(trimStartUs = g.startUs, trimEndUs = g.endUs))) {
                is EngineResult.Ok -> totalUs += r.value.durationUs
                is EngineResult.Failed -> return r
            }
        }
        segmentsMs = segs.map { (it.startUs / 1000L) to (it.endUs / 1000L) }
    }

    if (crop.w != 10000 || crop.h != 10000) {
        val left = -1f + 2f * crop.x / 10000f
        val right = -1f + 2f * (crop.x + crop.w) / 10000f
        val top = 1f - 2f * crop.y / 10000f
        val bottom = 1f - 2f * (crop.y + crop.h) / 10000f
        effects.add(Crop(left, right, bottom, top))
    }
    if (edits.hasColor) {
        when (val r = VideoEngine.colorMatrix(edits.color)) {
            is EngineResult.Ok -> effects.add(FixedRgbMatrix(r.value))
            is EngineResult.Failed -> return r
        }
    }

    var finalW = out.width
    var finalH = out.height
    val shortSide = minOf(out.width, out.height)
    if (targetShortSide != null && shortSide > targetShortSide) {
        val scale = targetShortSide.toFloat() / shortSide
        finalW = evenUp((out.width * scale).toInt())
        finalH = evenUp((out.height * scale).toInt())
    }
    // always state the exact (even) size when something changed the picture's shape or size, so Crop rounding can not give an odd size
    if (turns != 0 || crop.w != 10000 || crop.h != 10000 || finalW != out.width || finalH != out.height) {
        effects.add(Presentation.createForWidthAndHeight(finalW, finalH, Presentation.LAYOUT_STRETCH_TO_FIT))
    }
    // Step 80: the text goes LAST, on the finished picture, so it is where the person put it and as big as they chose
    if (edits.hasText) {
        when (val o = buildTextOverlay(edits, finalW, finalH)) {
            is EngineResult.Ok -> effects.add(o.value)
            is EngineResult.Failed -> return o
        }
    }
    return EngineResult.Ok(VideoEffectPlan(effects, finalW, finalH, totalUs, VideoEngine.audioPolicy(clip), segmentsMs))
}

/** The export failed or the finished file did not pass the check. [code] is an EngineCode, [problems] the EngineProblem flags. */
class VideoEditException(val code: Int, val problems: Int = 0) : Exception("video edit failed: ${EngineCode.name(code)} flags=$problems")

private fun readOutputInfo(file: File): EngineFileInfo {
    val r = MediaMetadataRetriever()
    var width = 0
    var height = 0
    var duration = 0L
    var mp4 = false
    var audio = false
    var playable = false
    try {
        r.setDataSource(file.absolutePath)
        duration = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { width = h; height = w } else { width = w; height = h }
        mp4 = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) == "video/mp4"
        audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        val frame = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        playable = frame != null
        frame?.recycle()
    } catch (e: Exception) {
        Log.w(TAG, "output probe failed (${e.javaClass.simpleName})")
    } finally {
        runCatching { r.release() }
    }
    var codecOk = false
    val ex = MediaExtractor()
    try {
        ex.setDataSource(file.absolutePath)
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265) codecOk = true
        }
    } catch (e: Exception) {
        Log.w(TAG, "output codec probe failed (${e.javaClass.simpleName})")
    } finally {
        runCatching { ex.release() }
    }
    return EngineFileInfo(duration, width, height, file.length(), mp4, codecOk, audio, playable)
}

/**
 * Applies the edits and compresses on the phone like exportShort (same quality setting, never upscales), then CHECKS the new file with the
 * native engine (playable, mp4, H.264/HEVC, length, size, sound) before returning it. Throws VideoEditException when it can not be made or
 * did not pass the check; the new file is deleted then and the original is untouched. Must be called from the main thread (Media3).
 */
suspend fun exportEdited(context: Context, uri: Uri, edits: VideoEdits, src: VideoSource, quality: String): File {
    val startedAt = SystemClock.elapsedRealtime()
    val file = exportEditedImpl(context, uri, edits, src, quality)
    Perf.record("video_edit_export_ms", SystemClock.elapsedRealtime() - startedAt)
    return file
}

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun exportEditedImpl(context: Context, uri: Uri, edits: VideoEdits, src: VideoSource, quality: String): File {
    val targetShortSide = when (quality) { "low" -> 480; "high" -> 1080; else -> 720 }
    val bitrate = when (quality) { "low" -> 1_200_000; "high" -> 5_000_000; else -> 2_500_000 }

    val plan = when (val r = planVideoEffects(edits, src, targetShortSide)) {
        is EngineResult.Ok -> r.value
        is EngineResult.Failed -> throw VideoEditException(r.code)
    }
    val out = File(context.cacheDir, "short_edit_${System.nanoTime()}.mp4")

    // Step 81: one item per piece that stays (the plain trim = one piece). Every piece gets the same effects; the sound processor is made fresh for each.
    val pieces: List<Pair<Long, Long>> = plan.segmentsMs.ifEmpty { listOf(edits.trimStartMs to (if (edits.trimEndMs > 0L) edits.trimEndMs else 0L)) }
    // Step 79: sound at the chosen speed. KEEP (0.5x-2x) = Sonic changes the speed and keeps the pitch; MUTE (other speeds) = the sound is removed;
    // NONE = nothing to do. The check below expects exactly this, so a file with the wrong sound is refused.
    val speed = edits.speedMilli / 1000f
    val editedItems = pieces.map { (fromMs, toMs) ->
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(fromMs).apply {
            if (toMs > 0L) setEndPositionMs(toMs)
        }.build()
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()
        val audioProcessors: List<AudioProcessor> =
            if (plan.audio == EngineAudio.KEEP && edits.speedMilli != 1000) listOf(SonicAudioProcessor().apply { setSpeed(speed) }) else emptyList()
        EditedMediaItem.Builder(item)
            .setEffects(Effects(audioProcessors, plan.effects))
            .setRemoveAudio(plan.audio == EngineAudio.MUTE)
            .build()
    }

    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
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
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        out.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel(); out.delete() } }
            if (editedItems.size == 1) transformer.start(editedItems[0], out.absolutePath)
            else transformer.start(Composition.Builder(EditedMediaItemSequence(editedItems)).build(), out.absolutePath)
        }
    }

    // the check of the finished file (plan section 6): a file that does not match what the person chose is never called "exported"
    val info = withContext(Dispatchers.IO) { readOutputInfo(out) }
    val wantW = if (kotlin.math.abs(info.width - plan.outW) <= 2) info.width else plan.outW      // an encoder may differ by one or two pixels
    val wantH = if (kotlin.math.abs(info.height - plan.outH) <= 2) info.height else plan.outH
    val soundExpected = (src.hasAudio ?: info.hasAudio) && plan.audio != EngineAudio.MUTE   // Step 79: removed on purpose at fast / slow speeds
    val expect = EngineFileExpect(plan.engineClipDurationUs, wantW, wantH, 0L, soundExpected)
    when (val check = VideoEngine.validateOutput(info, expect)) {
        is EngineResult.Ok -> Unit
        is EngineResult.Failed -> {
            if (check.code == EngineCode.OUTPUT_MISMATCH) {
                Log.w(TAG, "edited file refused by the check, flags=${check.detail}")
                out.delete()
                throw VideoEditException(check.code, check.detail)
            }
            // the engine could not run the check (unavailable / internal): keep the file only if it is at least a real, non-empty file
            if (!out.exists() || out.length() <= 0L) { out.delete(); throw VideoEditException(EngineCode.OUTPUT_MISMATCH, EngineProblem.FILE_SIZE) }
        }
    }
    return out
}
