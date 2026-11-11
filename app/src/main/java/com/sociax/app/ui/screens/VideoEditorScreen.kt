package com.sociax.app.ui.screens

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.sociax.app.data.EngineAudio
import com.sociax.app.data.EngineResult
import com.sociax.app.data.VideoCrop
import com.sociax.app.data.VideoEdits
import com.sociax.app.data.VideoEngine
import com.sociax.app.data.VideoFilters
import com.sociax.app.data.VideoSource
import com.sociax.app.data.VideoCut
import com.sociax.app.data.VideoSpeed
import com.sociax.app.data.editSegments
import com.sociax.app.data.keptLengthMs
import com.sociax.app.data.VideoText
import com.sociax.app.data.planVideoEffects
import com.sociax.app.data.speedAudio
import com.sociax.app.ui.theme.Accent
import com.sociax.app.ui.theme.CardFill
import com.sociax.app.ui.theme.GlassIconButton
import com.sociax.app.ui.theme.Ink
import com.sociax.app.ui.theme.MediaGlass
import com.sociax.app.ui.theme.MediaGlassBorder
import com.sociax.app.ui.theme.Navy
import com.sociax.app.ui.theme.PillButton
import com.sociax.app.ui.theme.Surface
import com.sociax.app.ui.theme.tr
import com.sociax.app.ui.theme.trf
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Step 81 added the Cut tab (take parts out of the middle; the engine checks every piece stays at least 0.1 s; preview plays the pieces one after another).
 * Step 80 added the Text tab (one text, 2 lines at most: colour, size, place; the engine keeps it inside the picture).
 * Step 79 added the Speed tab (0.25x - 4x; the sound is kept at 0.5x - 2x and removed at other speeds, and the screen SAYS which before export).
 * Step 78 (plan v4.0.3 section 3, Stage A #38): the VIDEO EDITOR of a short. A dedicated full-screen dark screen (like the photo editor):
 * big live preview, a tab bar (Trim / Filters / Adjust / Crop & rotate), Reset, Cancel, Next.
 *  - Trim: start and end sliders (never shorter than 0.1 s);
 *  - Filters: 12 real filters + strength; Adjust: brightness, contrast, saturation, warmth;
 *  - Crop & rotate: ratio presets (centred) and turn left / right.
 * The preview runs the SAME effects as the export (GPU); speed is shown by the player's own playback speed. Colour and crop come from the native C++ engine; when it can not load on this
 * phone only Trim and Turn are offered (plan section 6: a native problem never blocks posting). The original video is never changed.
 */

/** True while the video editor is on screen: the status-bar icons must be light over its dark background (MainActivity reads this). */
object VideoEditorFlag {
    var open by mutableStateOf(false)
}

private enum class VTab { Trim, Cut, Speed, Text, Filters, Adjust, Crop }

/** m:ss.t - a tenth of a second, so a 0.1 s minimum is visible. */
private fun clockText(ms: Long): String {
    val tenths = (ms / 100) % 10
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, tenths)
}

@Composable
fun VideoEditorScreen(uri: Uri, source: VideoSource, initial: VideoEdits, error: String?, onCancel: () -> Unit, onDone: (VideoEdits) -> Unit) {
    var edits by remember(uri) { mutableStateOf(initial) }
    var tab by remember { mutableStateOf(VTab.Trim) }
    var askDiscard by remember { mutableStateOf(false) }
    val engineOk = remember { VideoEngine.isAvailable() }

    DisposableEffect(Unit) {
        VideoEditorFlag.open = true
        onDispose { VideoEditorFlag.open = false }
    }

    val changed = edits != initial
    val closeAsk: () -> Unit = { if (changed) askDiscard = true else onCancel() }
    BackHandler { closeAsk() }

    val intensityLabel = tr("editor_intensity")
    val startLabel = tr("trim_start")
    val endLabel = tr("trim_end")
    val durationMs = source.durationMs
    val endMs = if (edits.trimEndMs > 0L) edits.trimEndMs else durationMs

    // Step 81: cuts that leave a piece under 0.1 s (e.g. after the trim was changed) must be changed first
    val cutsInvalid = edits.cuts.isNotEmpty() && editSegments(edits, source) is EngineResult.Failed
    var cutFrom by remember(uri) { mutableStateOf(0L) }
    var cutTo by remember(uri) { mutableStateOf(1000L) }

    Column(Modifier.fillMaxSize().background(Navy).statusBarsPadding().imePadding()) {
        // ---- top bar ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.Close, tr("cancel"), { closeAsk() }, size = 48, onMedia = true)
            Spacer(Modifier.width(10.dp))
            Text(tr("edit_video"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { edits = VideoEdits() }, enabled = !edits.isDefault) {
                Text(tr("editor_reset"), color = if (edits.isDefault) Color.White.copy(.4f) else Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            PillButton(tr("next"), { if (!cutsInvalid) onDone(edits) })
        }

        // ---- big live preview ----
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            VideoEditPreview(uri, edits, source, Modifier.fillMaxSize(), tr("a11y_video_preview"))
        }
        error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        if (cutsInvalid) Text(tr("cut_invalid"), color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (!engineOk) Text(tr("video_tools_limited"), color = Color.White.copy(.7f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        // ---- tool panel ----
        Column(Modifier.fillMaxWidth().background(Surface).padding(top = 10.dp)) {
            when (tab) {
                VTab.Trim -> {
                    val startF = edits.trimStartMs.toFloat()
                    val endF = endMs.toFloat()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(startLabel, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            startF, { v ->
                                val s = minOf(v.toLong(), endMs - 100L).coerceAtLeast(0L)
                                edits = edits.copy(trimStartMs = s)
                            },
                            Modifier.weight(1f).semantics { contentDescription = startLabel },
                            valueRange = 0f..(durationMs - 100L).toFloat().coerceAtLeast(1f), colors = editorSliderColors()
                        )
                        Text(clockText(edits.trimStartMs), color = Color.White.copy(.8f), fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(endLabel, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            endF, { v ->
                                val e = maxOf(v.toLong(), edits.trimStartMs + 100L).coerceAtMost(durationMs)
                                edits = edits.copy(trimEndMs = if (e >= durationMs) 0L else e)
                            },
                            Modifier.weight(1f).semantics { contentDescription = endLabel },
                            valueRange = 100f..durationMs.toFloat().coerceAtLeast(101f), colors = editorSliderColors()
                        )
                        Text(clockText(endMs), color = Color.White.copy(.8f), fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    }
                    Text(
                        trf("trim_length", clockText(keptLengthMs(edits, source))) + " · " + tr("trim_note"),
                        color = Color.White.copy(.7f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                VTab.Cut -> {
                    val fromLabel = tr("cut_from")
                    val toLabel = tr("cut_to")
                    val lo = edits.trimStartMs
                    val from = cutFrom.coerceIn(lo, (endMs - VideoCut.MIN_MS).coerceAtLeast(lo))
                    val to = cutTo.coerceIn((from + VideoCut.MIN_MS).coerceAtMost(endMs), endMs)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(fromLabel, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            from.toFloat(), { v -> cutFrom = v.toLong(); if (cutTo < cutFrom + VideoCut.MIN_MS) cutTo = (cutFrom + VideoCut.MIN_MS).coerceAtMost(endMs) },
                            Modifier.weight(1f).semantics { contentDescription = fromLabel },
                            valueRange = lo.toFloat()..(endMs - VideoCut.MIN_MS).toFloat().coerceAtLeast(lo + 1f), colors = editorSliderColors()
                        )
                        Text(clockText(from), color = Color.White.copy(.8f), fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(toLabel, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            to.toFloat(), { v -> cutTo = maxOf(v.toLong(), from + VideoCut.MIN_MS) },
                            Modifier.weight(1f).semantics { contentDescription = toLabel },
                            valueRange = (from + VideoCut.MIN_MS).toFloat()..endMs.toFloat().coerceAtLeast(from + VideoCut.MIN_MS + 1f), colors = editorSliderColors()
                        )
                        Text(clockText(to), color = Color.White.copy(.8f), fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    }
                    // the engine decides whether this cut can be added (every piece that stays must be at least 0.1 s)
                    val candidate = edits.copy(cuts = edits.cuts + (from to to))
                    val canCut = edits.cuts.size < VideoCut.MAX && to - from >= VideoCut.MIN_MS && editSegments(candidate, source) is EngineResult.Ok
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(tr("cut_button"), { if (canCut) edits = candidate })
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (edits.cuts.isEmpty()) tr("cut_none") else trf("trim_length", clockText(keptLengthMs(edits, source))),
                            color = Color.White.copy(.75f), fontSize = 12.sp, modifier = Modifier.weight(1f)
                        )
                    }
                    if (edits.cuts.isNotEmpty()) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(edits.cuts, key = { it.first * 10_000_000L + it.second }) { c ->
                                EditorChip(clockText(c.first) + "-" + clockText(c.second) + "  \u00D7", false) { edits = edits.copy(cuts = edits.cuts - c) }
                            }
                        }
                    }
                    Text(tr("cut_note"), color = Color.White.copy(.7f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                VTab.Speed -> {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(VideoSpeed.PRESETS, key = { it }) { ms ->
                            EditorChip(if (ms == 1000) tr("speed_normal") else VideoSpeed.label(ms), edits.speedMilli == ms) { edits = edits.copy(speedMilli = ms) }
                        }
                    }
                    val resultMs = keptLengthMs(edits, source) * 1000L / edits.speedMilli
                    val audioNote = when (speedAudio(edits, source)) {
                        EngineAudio.KEEP -> if (edits.speedMilli == 1000) null else tr("speed_audio_keep")
                        EngineAudio.MUTE -> tr("speed_audio_mute")
                        EngineAudio.NONE -> if (source.hasAudio == false) tr("speed_audio_none") else null
                    }
                    Text(
                        trf("speed_result", clockText(resultMs)) + " \u00B7 " + tr("speed_preview_note"),
                        color = Color.White.copy(.7f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    if (audioNote != null) Text(audioNote, color = Color.White.copy(.85f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                VTab.Text -> {
                    val textHint = tr("text_hint")
                    BasicTextField(
                        value = edits.text,
                        onValueChange = { v -> edits = edits.copy(text = VideoText.clean(v)) },
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Accent),
                        maxLines = VideoText.MAX_LINES,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp)).background(MediaGlass)
                            .border(1.dp, MediaGlassBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .semantics { contentDescription = textHint },
                        decorationBox = { inner ->
                            if (edits.text.isEmpty()) Text(textHint, color = Color.White.copy(.5f), fontSize = 16.sp)
                            inner()
                        }
                    )
                    if (edits.hasText) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val colorNames = listOf(tr("text_color_white"), tr("text_color_black"), tr("text_color_yellow"), tr("text_color_teal"), tr("text_color_red"))
                            VideoText.COLORS.forEachIndexed { i, argb ->
                                val colorName = colorNames[i]
                                val chosen = edits.textColor == argb
                                Box(
                                    Modifier.size(48.dp)
                                        .semantics { contentDescription = colorName; selected = chosen; role = Role.Button }
                                        .clickable { edits = edits.copy(textColor = argb) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(argb)).border(if (chosen) 3.dp else 1.dp, if (chosen) Accent else Color.White.copy(.5f), CircleShape))
                                }
                            }
                            val sizeNames = listOf(tr("text_size_small"), tr("text_size_medium"), tr("text_size_large"))
                            Spacer(Modifier.width(8.dp))
                            VideoText.SIZES.forEachIndexed { i, sz -> EditorChip(sizeNames[i], edits.textSize == sz) { edits = edits.copy(textSize = sz) } }
                        }
                        AdjustRow(tr("text_pos_x"), edits.textX / 1000f * 2f - 1f) { edits = edits.copy(textX = ((it + 1f) / 2f * 1000f).roundToInt().coerceIn(0, 1000)) }
                        AdjustRow(tr("text_pos_y"), edits.textY / 1000f * 2f - 1f) { edits = edits.copy(textY = ((it + 1f) / 2f * 1000f).roundToInt().coerceIn(0, 1000)) }
                    }
                    Text(tr("text_note"), color = Color.White.copy(.7f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                VTab.Filters -> {
                    if (edits.filterId != 0) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(intensityLabel, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(92.dp))
                            Slider(
                                edits.strength / 100f, { edits = edits.copy(strength = (it * 100).roundToInt()) },
                                Modifier.weight(1f).semantics { contentDescription = intensityLabel },
                                valueRange = 0f..1f, colors = editorSliderColors()
                            )
                            Text("${edits.strength}%", color = Color.White.copy(.8f), fontSize = 12.sp, modifier = Modifier.width(40.dp))
                        }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(VideoFilters.NAMES.indices.toList(), key = { it }) { id ->
                            EditorChip(tr("filter_${VideoFilters.NAMES[id]}"), edits.filterId == id) {
                                edits = edits.copy(filterId = id, strength = if (id == edits.filterId) edits.strength else 100)
                            }
                        }
                    }
                }
                VTab.Adjust -> {
                    AdjustRow(tr("adj_brightness"), edits.brightness / 100f) { edits = edits.copy(brightness = (it * 100).roundToInt()) }
                    AdjustRow(tr("adj_contrast"), edits.contrast / 100f) { edits = edits.copy(contrast = (it * 100).roundToInt()) }
                    AdjustRow(tr("adj_saturation"), edits.saturation / 100f) { edits = edits.copy(saturation = (it * 100).roundToInt()) }
                    AdjustRow(tr("adj_warmth"), edits.temperature / 100f) { edits = edits.copy(temperature = (it * 100).roundToInt()) }
                }
                VTab.Crop -> {
                    if (engineOk) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(VideoCrop.IDS, key = { it }) { id ->
                                EditorChip(if (id == "original") tr("crop_original") else id, edits.cropId == id) { edits = edits.copy(cropId = id) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(Icons.Filled.RotateLeft, tr("a11y_rotate_left"), { edits = edits.copy(quarterTurns = (edits.quarterTurns + 3) % 4) }, size = 48, onMedia = true)
                        GlassIconButton(Icons.Filled.RotateRight, tr("a11y_rotate_right"), { edits = edits.copy(quarterTurns = (edits.quarterTurns + 1) % 4) }, size = 48, onMedia = true)
                        if (engineOk && edits.cropId != "original") Text(tr("video_crop_centred"), color = Color.White.copy(.7f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                }
            }

            // ---- tab bar (six tabs: a row that scrolls sideways on a narrow phone, every tab the same width) ----
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                val tabW = Modifier.width(76.dp)
                EditorTabButton(Icons.Filled.ContentCut, tr("editor_tab_trim"), tab == VTab.Trim, tabW) { tab = VTab.Trim }
                if (engineOk) EditorTabButton(Icons.Filled.Delete, tr("editor_tab_cut"), tab == VTab.Cut, tabW) { tab = VTab.Cut }
                if (engineOk) {
                    EditorTabButton(Icons.Filled.Speed, tr("editor_tab_speed"), tab == VTab.Speed, tabW) { tab = VTab.Speed }
                    EditorTabButton(Icons.Filled.TextFields, tr("editor_tab_text"), tab == VTab.Text, tabW) { tab = VTab.Text }
                    EditorTabButton(Icons.Filled.AutoFixHigh, tr("editor_tab_filters"), tab == VTab.Filters, tabW) { tab = VTab.Filters }
                    EditorTabButton(Icons.Filled.Tune, tr("editor_tab_adjust"), tab == VTab.Adjust, tabW) { tab = VTab.Adjust }
                }
                EditorTabButton(Icons.Filled.Crop, tr("editor_tab_crop"), tab == VTab.Crop, tabW) { tab = VTab.Crop }
            }

        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            containerColor = CardFill,
            title = { Text(tr("editor_discard_title"), color = Ink, fontWeight = FontWeight.Bold) },
            text = { Text(tr("editor_discard_body"), color = Ink.copy(.8f)) },
            confirmButton = { TextButton(onClick = { askDiscard = false; onCancel() }) { Text(tr("editor_discard"), color = Accent, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(tr("editor_keep"), color = Ink) } }
        )
    }
}

/** The video as it will be posted (trim, turn, crop, filter, adjust), looping. Effects are the export's own effects, limited to 720p for speed. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoEditPreview(uri: Uri, edits: VideoEdits, source: VideoSource, modifier: Modifier, description: String) {
    val context = LocalContext.current
    val exo = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ALL; volume = 0f } }
    DisposableEffect(exo) { onDispose { exo.release() } }

    // effects (everything except the trim): re-applied a moment after the last change so a slider drag stays smooth
    val effectsKey = edits.copy(trimStartMs = 0L, trimEndMs = 0L, speedMilli = 1000)
    LaunchedEffect(effectsKey) {
        delay(120)
        when (val plan = planVideoEffects(edits, source, 720, withSpeed = false)) {
            is EngineResult.Ok -> try { exo.setVideoEffects(plan.value.effects) } catch (t: Throwable) { Log.w("SociaxVideoEdit", "preview effects failed (${t.javaClass.simpleName})") }
            is EngineResult.Failed -> Log.w("SociaxVideoEdit", "preview effects not planned (${plan.name})")
        }
    }
    // Step 79: the preview plays at the chosen speed (silent, like before); the EXPORT applies the speed to picture and sound
    LaunchedEffect(edits.speedMilli) { exo.playbackParameters = PlaybackParameters(edits.speedMilli / 1000f) }
    // trim + cuts: a new playlist (the preview restarts from the start; the pieces that stay play one after another, then it loops)
    LaunchedEffect(uri, edits.trimStartMs, edits.trimEndMs, edits.cuts) {
        delay(150)
        fun itemOf(fromMs: Long, toMs: Long): MediaItem {
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(fromMs).apply { if (toMs > 0L) setEndPositionMs(toMs) }.build()
            return MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()
        }
        val pieces: List<MediaItem> = if (edits.cuts.isEmpty()) {
            listOf(itemOf(edits.trimStartMs, edits.trimEndMs))
        } else when (val r = editSegments(edits, source)) {
            is EngineResult.Ok -> r.value.map { itemOf(it.startUs / 1000L, it.endUs / 1000L) }
            is EngineResult.Failed -> listOf(itemOf(edits.trimStartMs, edits.trimEndMs))   // invalid cuts: show the plain trim; the screen tells the person
        }
        exo.setMediaItems(pieces)
        exo.prepare()
        exo.playWhenReady = true
    }
    AndroidView(
        factory = { c -> PlayerView(c).apply { player = exo; useController = false } },
        modifier = modifier.semantics { contentDescription = description }
    )
}
