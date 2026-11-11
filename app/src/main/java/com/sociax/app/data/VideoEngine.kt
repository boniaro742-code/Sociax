package com.sociax.app.data

import android.util.Log
import java.nio.ByteBuffer

// Step 77 (plan v4.0.3): Kotlin side of the native C++ video-editing engine.
// Kotlin keeps the screens, the project state and the permissions; the C++ engine (app/src/main/cpp) does the maths and the pixel work.
// Rules (plan section 6): the library is loaded ONLY when the editor asks for it (never at app start, never for login, Home, chat or
// notifications). If it can not load, or answers with another API number, every call returns Failed(unsupported) and the app keeps using
// the existing Media3 trim / compress path. A native problem is a result code, never a crash. Nothing here touches the user's media.

private const val TAG = "SociaxEngine"

/** The only class with `external` functions. Layout of every array is the contract with app/src/main/cpp/sociax_jni.cpp. */
object NativeVideoEngine {
    @JvmStatic external fun nativeApiVersion(): Int
    /** spec(14) -> [code, outW, outH, durationUs] */
    @JvmStatic external fun nativeClipOutput(spec: LongArray): LongArray
    /** spec(14), atSourceUs -> [code, left(14), right(14)] */
    @JvmStatic external fun nativeSplit(spec: LongArray, atUs: Long): LongArray
    /** specs(count*14) -> [code, failedClip, canvasW, canvasH, totalUs, presetUsed, downgraded, count, count*(x,y,w,h)] */
    @JvmStatic external fun nativeExportPlan(specs: LongArray, count: Int, preset: Int, maxSide: Long, supports1080: Boolean, supports4k: Boolean): LongArray
    @JvmStatic external fun nativeAvailablePresets(shortSide: Long, maxSide: Long, supports1080: Boolean, supports4k: Boolean): Int
    /** filterId, strength, brightness, contrast, saturation, temperature -> 16 floats (4x4 OpenGL column-major), EMPTY on error */
    @JvmStatic external fun nativeColorMatrix(filterId: Int, strength: Int, brightness: Int, contrast: Int, saturation: Int, temperature: Int): FloatArray
    /** displayW, displayH, ratioW, ratioH -> [code, x, y, w, h] (1/10000 of the turned picture) */
    @JvmStatic external fun nativeCropForRatio(w: Int, h: Int, ratioW: Int, ratioH: Int): LongArray
    /** trimStartUs, trimEndUs, cuts (fromUs, toUs pairs) -> [code, count, start0, end0, start1, end1, ...] (the pieces that stay) */
    @JvmStatic external fun nativeCutSegments(trimStartUs: Long, trimEndUs: Long, cuts: LongArray): LongArray
    /** frameW, frameH, sizePermille (of the short side) -> [code, fontPx] */
    @JvmStatic external fun nativeTextFontPx(w: Int, h: Int, sizePermille: Int): LongArray
    /** frameW, frameH, centreX, centreY (0..1000), boxW, boxH -> [code, left, top] (the box kept inside the margins) */
    @JvmStatic external fun nativeTextPlacement(w: Int, h: Int, cx: Int, cy: Int, boxW: Int, boxH: Int): LongArray
    @JvmStatic external fun nativeAudioPolicy(spec: LongArray): Int
    @JvmStatic external fun nativeSessionCreate(): Long
    @JvmStatic external fun nativeSessionCancel(id: Long)
    @JvmStatic external fun nativeSessionProgress(id: Long): Int
    @JvmStatic external fun nativeSessionDestroy(id: Long)
    /** Direct ByteBuffers (RGBA_8888). outSize receives width, height. */
    @JvmStatic external fun nativeTransformFrame(
        sessionId: Long, src: ByteBuffer, w: Int, h: Int, rotation: Int, flipH: Boolean,
        cropX: Int, cropY: Int, cropW: Int, cropH: Int, dst: ByteBuffer, outSize: IntArray
    ): Int
    @JvmStatic external fun nativeApplyColor(
        sessionId: Long, buf: ByteBuffer, w: Int, h: Int, filterId: Int, strength: Int,
        brightness: Int, contrast: Int, saturation: Int, temperature: Int
    ): Int
    /** info(8): durationUs, w, h, sizeBytes, container, codec, hasAudio, playable; expect(5): durationUs, w, h, maxBytes, expectAudio -> [code, problemFlags] */
    @JvmStatic external fun nativeValidateOutput(info: LongArray, expect: LongArray): LongArray
}

/** Same numbers as engine.h `Code`. */
object EngineCode {
    const val OK = 0
    const val BAD_ARGUMENT = 1
    const val BAD_RANGE = 2
    const val BAD_SIZE = 3
    const val CANCELLED = 4
    const val UNSUPPORTED = 5
    const val BAD_BUFFER = 6
    const val INTERNAL = 7
    const val OUTPUT_MISMATCH = 8

    fun name(code: Int): String = when (code) {
        OK -> "ok"
        BAD_ARGUMENT -> "bad_argument"
        BAD_RANGE -> "bad_range"
        BAD_SIZE -> "bad_size"
        CANCELLED -> "cancelled"
        UNSUPPORTED -> "unsupported"
        BAD_BUFFER -> "bad_buffer"
        INTERNAL -> "internal"
        OUTPUT_MISMATCH -> "output_mismatch"
        else -> "unknown"
    }
}

sealed class EngineResult<out T> {
    data class Ok<out T>(val value: T) : EngineResult<T>()
    /** [detail] is the index of the clip that caused it (export plan), or -1. */
    data class Failed(val code: Int, val detail: Int = -1) : EngineResult<Nothing>() {
        val name: String get() = EngineCode.name(code)
    }
}

/** One clip of the timeline. Times in microseconds of SOURCE time. Crop numbers are 1/10000 of the (turned) picture. */
data class EngineClip(
    val srcW: Int, val srcH: Int, val srcDurationUs: Long,
    val metaRotation: Int = 0, val turn: Int = 0, val flipH: Boolean = false,
    val cropX: Int = 0, val cropY: Int = 0, val cropW: Int = 10000, val cropH: Int = 10000,
    val trimStartUs: Long = 0L, val trimEndUs: Long = srcDurationUs,
    val speedMilli: Int = 1000, val hasAudio: Boolean = true
) {
    fun toLongs(): LongArray = longArrayOf(
        srcW.toLong(), srcH.toLong(), metaRotation.toLong(), turn.toLong(), if (flipH) 1L else 0L,
        cropX.toLong(), cropY.toLong(), cropW.toLong(), cropH.toLong(),
        srcDurationUs, trimStartUs, trimEndUs, speedMilli.toLong(), if (hasAudio) 1L else 0L
    )

    companion object {
        const val LONGS = 14
        fun fromLongs(a: LongArray, offset: Int = 0): EngineClip = EngineClip(
            srcW = a[offset].toInt(), srcH = a[offset + 1].toInt(), metaRotation = a[offset + 2].toInt(), turn = a[offset + 3].toInt(),
            flipH = a[offset + 4] == 1L, cropX = a[offset + 5].toInt(), cropY = a[offset + 6].toInt(), cropW = a[offset + 7].toInt(),
            cropH = a[offset + 8].toInt(), srcDurationUs = a[offset + 9], trimStartUs = a[offset + 10], trimEndUs = a[offset + 11],
            speedMilli = a[offset + 12].toInt(), hasAudio = a[offset + 13] == 1L
        )
    }
}

data class EngineClipOut(val width: Int, val height: Int, val durationUs: Long)
data class EngineRect(val x: Int, val y: Int, val w: Int, val h: Int)
data class EnginePlan(
    val canvasW: Int, val canvasH: Int, val totalUs: Long,
    /** 0 = original size, else the short side in pixels that was really used */
    val presetUsed: Int,
    /** true when the chosen quality was not possible and a lower one was used: show this to the person */
    val downgraded: Boolean,
    val rects: List<EngineRect>
)

data class EngineColor(
    val filterId: Int = 0, val strength: Int = 100,
    val brightness: Int = 0, val contrast: Int = 0, val saturation: Int = 0, val temperature: Int = 0
)

enum class EngineAudio { NONE, KEEP, MUTE }

/** A crop of the (turned) picture, in 1/10000 of its width / height. */
data class EngineCrop(val x: Int, val y: Int, val w: Int, val h: Int)

/** One piece of the video that stays (SOURCE time, microseconds). */
data class EngineSegment(val startUs: Long, val endUs: Long)

/** The top-left corner of a text box on the picture, in pixels. */
data class EnginePoint(val left: Int, val top: Int)

/** What a finished file looks like (read it back with MediaMetadataRetriever BEFORE calling it exported). */
data class EngineFileInfo(val durationUs: Long, val width: Int, val height: Int, val sizeBytes: Long, val isMp4: Boolean, val isH264OrHevc: Boolean, val hasAudio: Boolean, val playable: Boolean)
data class EngineFileExpect(val durationUs: Long, val width: Int, val height: Int, val maxBytes: Long, val expectAudio: Boolean)

/** Problem flags from validateOutput (same bits as engine.h `OutputProblem`). */
object EngineProblem {
    const val NOT_PLAYABLE = 1
    const val CONTAINER = 2
    const val CODEC = 4
    const val DURATION = 8
    const val SIZE = 16
    const val FILE_SIZE = 32
    const val AUDIO = 64
}

/** A handle for one long job: lets the main thread cancel and read progress while a worker thread processes frames. Always close(). */
class EngineSession internal constructor(private val id: Long) : AutoCloseable {
    fun cancel() { try { NativeVideoEngine.nativeSessionCancel(id) } catch (t: Throwable) { Log.w(TAG, "cancel failed: ${t.javaClass.simpleName}") } }
    /** 0..1000, or -1 when the session is gone */
    val progressPermille: Int get() = try { NativeVideoEngine.nativeSessionProgress(id) } catch (t: Throwable) { -1 }
    override fun close() { try { NativeVideoEngine.nativeSessionDestroy(id) } catch (t: Throwable) { Log.w(TAG, "close failed: ${t.javaClass.simpleName}") } }
    internal val handle: Long get() = id
}

object VideoEngine {
    /** Must equal kApiVersion in engine.h. If the two ever differ, the engine is not used. */
    const val EXPECTED_API = 4
    const val PRESET_ORIGINAL = 0
    const val PRESET_720 = 720
    const val PRESET_1080 = 1080
    const val PRESET_2160 = 2160

    private var state = 0          // 0 = not tried yet, 1 = ready, 2 = unavailable
    @Volatile var unavailableReason: String? = null
        private set

    /** Loads the native library the first time it is asked (only the editor asks). Never throws. */
    @Synchronized
    fun isAvailable(): Boolean {
        if (state == 0) {
            try {
                System.loadLibrary("sociax_engine")
                val v = NativeVideoEngine.nativeApiVersion()
                if (v == EXPECTED_API) {
                    state = 1
                } else {
                    state = 2
                    unavailableReason = "api_mismatch_$v"
                }
            } catch (t: Throwable) {
                state = 2
                unavailableReason = t.javaClass.simpleName
            }
            if (state == 2) Log.w(TAG, "Native video engine not available: $unavailableReason (the Media3 path is used instead)")
        }
        return state == 1
    }

    private fun <T> guarded(block: () -> EngineResult<T>): EngineResult<T> {
        if (!isAvailable()) return EngineResult.Failed(EngineCode.UNSUPPORTED)
        return try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "native call failed: ${t.javaClass.simpleName}")
            EngineResult.Failed(EngineCode.INTERNAL)
        }
    }

    fun clipOutput(c: EngineClip): EngineResult<EngineClipOut> = guarded {
        val r = NativeVideoEngine.nativeClipOutput(c.toLongs())
        if (r.isEmpty() || r[0].toInt() != EngineCode.OK || r.size < 4) EngineResult.Failed(if (r.isEmpty()) EngineCode.INTERNAL else r[0].toInt())
        else EngineResult.Ok(EngineClipOut(r[1].toInt(), r[2].toInt(), r[3]))
    }

    /** Split at a SOURCE-time point. Both pieces keep every other choice (crop, speed, turn ...). */
    fun split(c: EngineClip, atSourceUs: Long): EngineResult<Pair<EngineClip, EngineClip>> = guarded {
        val r = NativeVideoEngine.nativeSplit(c.toLongs(), atSourceUs)
        if (r.isEmpty() || r[0].toInt() != EngineCode.OK || r.size < 1 + 2 * EngineClip.LONGS)
            EngineResult.Failed(if (r.isEmpty()) EngineCode.INTERNAL else r[0].toInt())
        else EngineResult.Ok(Pair(EngineClip.fromLongs(r, 1), EngineClip.fromLongs(r, 1 + EngineClip.LONGS)))
    }

    /** True when [order] uses every index 0 until n exactly once (merge in the person's chosen order). */
    fun isValidOrder(order: IntArray): Boolean {
        if (order.isEmpty() || order.size > 100) return false
        val seen = BooleanArray(order.size)
        for (i in order) { if (i < 0 || i >= order.size || seen[i]) return false; seen[i] = true }
        return true
    }

    /** The colour of a filter + Adjust choice as ONE 4x4 matrix (OpenGL column-major) for the GPU: the same colour the engine gives per pixel. */
    fun colorMatrix(c: EngineColor): EngineResult<FloatArray> = guarded {
        val m = NativeVideoEngine.nativeColorMatrix(c.filterId, c.strength, c.brightness, c.contrast, c.saturation, c.temperature)
        if (m.size == 16) EngineResult.Ok(m) else EngineResult.Failed(EngineCode.BAD_RANGE)
    }

    /** Biggest centred crop of a (turned) picture with the ratio ratioW:ratioH. */
    fun cropForRatio(displayW: Int, displayH: Int, ratioW: Int, ratioH: Int): EngineResult<EngineCrop> = guarded {
        val r = NativeVideoEngine.nativeCropForRatio(displayW, displayH, ratioW, ratioH)
        if (r.isEmpty() || r[0].toInt() != EngineCode.OK || r.size < 5) EngineResult.Failed(if (r.isEmpty()) EngineCode.INTERNAL else r[0].toInt())
        else EngineResult.Ok(EngineCrop(r[1].toInt(), r[2].toInt(), r[3].toInt(), r[4].toInt()))
    }

    /** The pieces that stay when [cutsUs] (fromUs, toUs) are removed from the kept range. BAD_RANGE = a piece under 0.1 s, a backwards cut or nothing left. */
    fun cutSegments(trimStartUs: Long, trimEndUs: Long, cutsUs: List<Pair<Long, Long>>): EngineResult<List<EngineSegment>> = guarded {
        val flat = LongArray(cutsUs.size * 2)
        cutsUs.forEachIndexed { i, c -> flat[2 * i] = c.first; flat[2 * i + 1] = c.second }
        val r = NativeVideoEngine.nativeCutSegments(trimStartUs, trimEndUs, flat)
        if (r.isEmpty() || r[0].toInt() != EngineCode.OK || r.size < 2) EngineResult.Failed(if (r.isEmpty()) EngineCode.INTERNAL else r[0].toInt())
        else {
            val n = r[1].toInt()
            if (n < 1 || r.size < 2 + 2 * n) EngineResult.Failed(EngineCode.INTERNAL)
            else EngineResult.Ok(List(n) { i -> EngineSegment(r[2 + 2 * i], r[3 + 2 * i]) })
        }
    }

    /** Font size in pixels of a text of [sizePermille] (thousandths of the picture's short side). */
    fun textFontPx(frameW: Int, frameH: Int, sizePermille: Int): EngineResult<Int> = guarded {
        val r = NativeVideoEngine.nativeTextFontPx(frameW, frameH, sizePermille)
        if (r.isEmpty() || r[0].toInt() != EngineCode.OK || r.size < 2) EngineResult.Failed(if (r.isEmpty()) EngineCode.INTERNAL else r[0].toInt())
        else EngineResult.Ok(r[1].toInt())
    }

    /** Where a text box goes so that its centre is at (cx, cy) thousandths of the picture, kept inside the margins. BAD_RANGE = does not fit. */
    fun textPlacement(frameW: Int, frameH: Int, cx: Int, cy: Int, boxW: Int, boxH: Int): EngineResult<EnginePoint> = guarded {
        val r = NativeVideoEngine.nativeTextPlacement(frameW, frameH, cx, cy, boxW, boxH)
        if (r.isEmpty() || r[0].toInt() != EngineCode.OK || r.size < 3) EngineResult.Failed(if (r.isEmpty()) EngineCode.INTERNAL else r[0].toInt())
        else EngineResult.Ok(EnginePoint(r[1].toInt(), r[2].toInt()))
    }

    fun audioPolicy(c: EngineClip): EngineAudio {
        if (!isAvailable()) return EngineAudio.NONE
        return try {
            when (NativeVideoEngine.nativeAudioPolicy(c.toLongs())) {
                1 -> EngineAudio.KEEP
                2 -> EngineAudio.MUTE
                else -> EngineAudio.NONE
            }
        } catch (t: Throwable) { EngineAudio.NONE }
    }

    /** Which quality choices to OFFER. bit0 original, bit1 720, bit2 1080, bit3 2160 (4K only when the source and the phone really support it). */
    fun availablePresets(sourceShortSide: Int, maxSide: Int, supports1080: Boolean, supports4k: Boolean): List<Int> {
        if (!isAvailable()) return listOf(PRESET_ORIGINAL)
        val mask = try { NativeVideoEngine.nativeAvailablePresets(sourceShortSide.toLong(), maxSide.toLong(), supports1080, supports4k) } catch (t: Throwable) { 1 }
        val out = ArrayList<Int>()
        if (mask and 1 != 0) out.add(PRESET_ORIGINAL)
        if (mask and 2 != 0) out.add(PRESET_720)
        if (mask and 4 != 0) out.add(PRESET_1080)
        if (mask and 8 != 0) out.add(PRESET_2160)
        return out
    }

    fun exportPlan(clips: List<EngineClip>, preset: Int, maxSide: Int, supports1080: Boolean, supports4k: Boolean): EngineResult<EnginePlan> = guarded {
        if (clips.isEmpty() || clips.size > 100) return@guarded EngineResult.Failed(EngineCode.BAD_ARGUMENT)
        val flat = LongArray(clips.size * EngineClip.LONGS)
        clips.forEachIndexed { i, c -> c.toLongs().copyInto(flat, i * EngineClip.LONGS) }
        val r = NativeVideoEngine.nativeExportPlan(flat, clips.size, preset, maxSide.toLong(), supports1080, supports4k)
        if (r.isEmpty()) return@guarded EngineResult.Failed(EngineCode.INTERNAL)
        if (r[0].toInt() != EngineCode.OK) return@guarded EngineResult.Failed(r[0].toInt(), if (r.size > 1) r[1].toInt() else -1)
        if (r.size < 8) return@guarded EngineResult.Failed(EngineCode.INTERNAL)
        val n = r[7].toInt()
        if (n != clips.size || r.size < 8 + n * 4) return@guarded EngineResult.Failed(EngineCode.INTERNAL)
        val rects = ArrayList<EngineRect>(n)
        for (i in 0 until n) { val o = 8 + i * 4; rects.add(EngineRect(r[o].toInt(), r[o + 1].toInt(), r[o + 2].toInt(), r[o + 3].toInt())) }
        EngineResult.Ok(EnginePlan(r[2].toInt(), r[3].toInt(), r[4], r[5].toInt(), r[6] == 1L, rects))
    }

    /** null when the engine is not available or too many sessions are open. The caller must close() it. */
    fun newSession(): EngineSession? {
        if (!isAvailable()) return null
        return try {
            val id = NativeVideoEngine.nativeSessionCreate()
            if (id == 0L) null else EngineSession(id)
        } catch (t: Throwable) { null }
    }

    /** Turn (clockwise 0/90/180/270), mirror, crop of ONE RGBA_8888 frame. [src] and [dst] must be direct buffers. Returns the new size. */
    fun transformFrame(
        session: EngineSession?, src: ByteBuffer, w: Int, h: Int, rotation: Int, flipH: Boolean,
        cropX: Int, cropY: Int, cropW: Int, cropH: Int, dst: ByteBuffer
    ): EngineResult<Pair<Int, Int>> = guarded {
        if (!src.isDirect || !dst.isDirect) return@guarded EngineResult.Failed(EngineCode.BAD_BUFFER)
        val size = IntArray(2)
        val code = NativeVideoEngine.nativeTransformFrame(session?.handle ?: 0L, src, w, h, rotation, flipH, cropX, cropY, cropW, cropH, dst, size)
        if (code == EngineCode.OK) EngineResult.Ok(Pair(size[0], size[1])) else EngineResult.Failed(code)
    }

    /** Filter + Adjust on ONE RGBA_8888 direct buffer, in place. Alpha is never changed. */
    fun applyColor(session: EngineSession?, buf: ByteBuffer, w: Int, h: Int, c: EngineColor): EngineResult<Unit> = guarded {
        if (!buf.isDirect) return@guarded EngineResult.Failed(EngineCode.BAD_BUFFER)
        val code = NativeVideoEngine.nativeApplyColor(session?.handle ?: 0L, buf, w, h, c.filterId, c.strength, c.brightness, c.contrast, c.saturation, c.temperature)
        if (code == EngineCode.OK) EngineResult.Ok(Unit) else EngineResult.Failed(code)
    }

    /** Check a finished file against what the person chose. Ok = safe to call it exported; Failed.detail = the EngineProblem flags. */
    fun validateOutput(info: EngineFileInfo, expect: EngineFileExpect): EngineResult<Unit> = guarded {
        val i = longArrayOf(
            info.durationUs, info.width.toLong(), info.height.toLong(), info.sizeBytes,
            if (info.isMp4) 1L else 0L, if (info.isH264OrHevc) 1L else 0L, if (info.hasAudio) 1L else 0L, if (info.playable) 1L else 0L
        )
        val e = longArrayOf(expect.durationUs, expect.width.toLong(), expect.height.toLong(), expect.maxBytes, if (expect.expectAudio) 1L else 0L)
        val r = NativeVideoEngine.nativeValidateOutput(i, e)
        if (r.isEmpty()) EngineResult.Failed(EngineCode.INTERNAL)
        else if (r[0].toInt() == EngineCode.OK) EngineResult.Ok(Unit)
        else EngineResult.Failed(r[0].toInt(), if (r.size > 1) r[1].toInt() else -1)
    }
}
