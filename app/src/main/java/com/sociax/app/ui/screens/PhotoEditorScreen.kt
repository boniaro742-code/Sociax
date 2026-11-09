package com.sociax.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.PhotoEdits
import com.sociax.app.data.PhotoMath
import com.sociax.app.data.decodeUpright
import com.sociax.app.data.rotateFlip
import com.sociax.app.data.squareThumb
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Step 76 (plan v3.10.4 sections 1-2, Stage A #38): the PHOTO EDITOR of a normal Photo Post. A dedicated full-screen dark screen,
 * visually different from the (later) video camera: big preview, a tab bar (Filters / Adjust / Crop & rotate), Reset, Cancel, Next.
 *  - 22 real filters (PhotoMath.FILTER_IDS) in a scrolling tray with a thumbnail of YOUR photo for each, and a strength slider 0-100 %;
 *  - Adjust: brightness, contrast, saturation, warmth;
 *  - Crop and rotate: ratio presets, turn left / right, flip, and drag the photo to move the crop window.
 * Non-destructive: the picked file is only read; the preview is drawn by the GPU through ONE colour matrix, so nothing accumulates.
 */

/** True while the photo editor is on screen: the status-bar icons must be light over its dark background (MainActivity reads this). */
object PhotoEditorFlag {
    var open by mutableStateOf(false)
}

private sealed interface Loaded {
    data object Loading : Loaded
    data object Failed : Loaded
    class Ready(val bitmap: Bitmap) : Loaded
}

private enum class EditorTab { Filters, Adjust, Crop }

/** Reads the photo upright (EXIF applied) off the main thread. */
@Composable
private fun rememberLoaded(uri: Uri, maxSide: Int): Loaded {
    val context = LocalContext.current
    val state = produceState<Loaded>(Loaded.Loading, uri) {
        value = Loaded.Loading
        value = try {
            Loaded.Ready(decodeUpright(context, uri, maxSide))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Loaded.Failed
        } catch (e: OutOfMemoryError) {
            Loaded.Failed
        }
    }
    return state.value
}

/** The photo turned and flipped as chosen. Only a turn / flip makes a new bitmap; a crop or a filter never does. */
@Composable
private fun rememberTurned(base: Bitmap?, quarterTurns: Int, flip: Boolean): Bitmap? {
    val state = produceState<Bitmap?>(null, base, quarterTurns, flip) {
        val b = base
        value = if (b == null) null else withContext(Dispatchers.Default) { rotateFlip(b, quarterTurns, flip) }
    }
    return state.value
}

/** Draws the crop window of [image] fitted into the box, through the colour matrix (GPU: instant, no new bitmap per change). */
@Composable
private fun EditedCanvas(image: ImageBitmap, edits: PhotoEdits, description: String, modifier: Modifier) {
    val filter = remember(edits.filterId, edits.intensity, edits.brightness, edits.contrast, edits.saturation, edits.warmth) {
        val m = edits.colorMatrix()
        if (PhotoMath.isIdentity(m, 0.0005f)) null else ColorFilter.colorMatrix(ColorMatrix(m))
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val ratio = PhotoMath.aspectOf(edits.aspect)
        val r = PhotoMath.cropRect(image.width, image.height, ratio[0], ratio[1], edits.panX, edits.panY)
        val scale = min(size.width / r[2], size.height / r[3])
        val dw = (r[2] * scale).roundToInt().coerceAtLeast(1)
        val dh = (r[3] * scale).roundToInt().coerceAtLeast(1)
        drawImage(
            image,
            srcOffset = IntOffset(r[0], r[1]), srcSize = IntSize(r[2], r[3]),
            dstOffset = IntOffset(((size.width - dw) / 2f).roundToInt(), ((size.height - dh) / 2f).roundToInt()),
            dstSize = IntSize(dw, dh),
            colorFilter = filter
        )
    }
}

/** The photo exactly as it will be posted (turn, crop, filter, adjust), fitted into its box. Used by the Create post page. */
@Composable
fun EditedPhotoPreview(uri: Uri, edits: PhotoEdits, modifier: Modifier = Modifier) {
    val loaded = rememberLoaded(uri, 1080)
    val base = (loaded as? Loaded.Ready)?.bitmap
    val turned = rememberTurned(base, edits.quarterTurns, edits.flip)
    val image = remember(turned) { turned?.asImageBitmap() }
    val description = tr("a11y_selected_photo")
    Box(modifier, contentAlignment = Alignment.Center) {
        val img = image
        if (loaded is Loaded.Failed) {
            Text(tr("err_edit_load"), color = Ink.copy(.7f), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        } else if (img == null) {
            CircularProgressIndicator(color = Accent)
        } else {
            EditedCanvas(img, edits, description, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun PhotoEditorScreen(uri: Uri, initial: PhotoEdits, onCancel: () -> Unit, onDone: (PhotoEdits) -> Unit) {
    var edits by remember(uri) { mutableStateOf(initial) }
    var tab by remember { mutableStateOf(EditorTab.Filters) }
    var askDiscard by remember { mutableStateOf(false) }

    val loaded = rememberLoaded(uri, 1080)
    val base = (loaded as? Loaded.Ready)?.bitmap
    val turned = rememberTurned(base, edits.quarterTurns, edits.flip)
    val image = remember(turned) { turned?.asImageBitmap() }
    val thumbState = produceState<ImageBitmap?>(null, turned) {
        val b = turned
        value = if (b == null) null else withContext(Dispatchers.Default) { squareThumb(b, 160).asImageBitmap() }
    }
    val filterIds = remember { PhotoMath.FILTER_IDS.toList() }
    val cropIds = remember { PhotoMath.CROP_IDS.toList() }

    DisposableEffect(Unit) {
        PhotoEditorFlag.open = true
        onDispose { PhotoEditorFlag.open = false }
    }

    val changed = edits != initial      // something was done in THIS visit: ask before throwing it away
    val latestEdits by rememberUpdatedState(edits)
    val latestImage by rememberUpdatedState(image)
    val closeAsk: () -> Unit = {
        if (changed) {
            askDiscard = true
        } else {
            onCancel()
        }
    }
    val intensityLabel = tr("editor_intensity")      // read here: tr() cannot be called inside the semantics lambda below
    BackHandler { closeAsk() }

    Column(Modifier.fillMaxSize().background(Navy).statusBarsPadding()) {
        // ---- top bar ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.Close, tr("cancel"), { closeAsk() }, size = 48, onMedia = true)
            Spacer(Modifier.width(10.dp))
            Text(tr("edit_photo"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { edits = PhotoEdits() }, enabled = !edits.isDefault) {
                Text(tr("editor_reset"), color = if (edits.isDefault) Color.White.copy(.4f) else Color.White, fontWeight = FontWeight.SemiBold)
            }
            if (image != null) {
                Spacer(Modifier.width(6.dp))
                PillButton(tr("next"), { onDone(edits) })
            }
        }

        // ---- big preview ----
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            val img = image
            if (loaded is Loaded.Failed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(tr("err_edit_load"), color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    PillButton(tr("cancel"), onCancel, filled = false)
                }
            } else if (img == null) {
                CircularProgressIndicator(color = Accent)
            } else {
                val canMove = tab == EditorTab.Crop && PhotoMath.aspectOf(edits.aspect)[0] > 0
                val description = tr("a11y_selected_photo")
                EditedCanvas(
                    img, edits, description,
                    Modifier.fillMaxSize().pointerInput(canMove) {
                        if (!canMove) return@pointerInput
                        detectDragGestures { change, drag ->
                            change.consume()
                            val cur = latestEdits
                            val shown = latestImage ?: return@detectDragGestures
                            val ratio = PhotoMath.aspectOf(cur.aspect)
                            if (ratio[0] > 0) {
                                val r = PhotoMath.cropRect(shown.width, shown.height, ratio[0], ratio[1], cur.panX, cur.panY)
                                val scale = min(size.width.toFloat() / r[2], size.height.toFloat() / r[3])
                                edits = cur.copy(
                                    panX = PhotoMath.panBy(cur.panX, drag.x, (r[2] * scale).roundToInt(), r[2], shown.width - r[2]),
                                    panY = PhotoMath.panBy(cur.panY, drag.y, (r[3] * scale).roundToInt(), r[3], shown.height - r[3])
                                )
                            }
                        }
                    }
                )
            }
        }

        // ---- tool panel ----
        Column(Modifier.fillMaxWidth().background(Surface).padding(top = 10.dp)) {
            when (tab) {
                EditorTab.Filters -> {
                    if (edits.filterId != "original") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(intensityLabel, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(92.dp))
                            Slider(
                                edits.intensity, { edits = edits.copy(intensity = it) },
                                Modifier.weight(1f).semantics { contentDescription = intensityLabel },
                                valueRange = 0f..1f, colors = editorSliderColors()
                            )
                            Text("${(edits.intensity * 100).roundToInt()}%", color = Color.White.copy(.8f), fontSize = 12.sp, modifier = Modifier.width(40.dp))
                        }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filterIds, key = { it }) { id ->
                            FilterTile(id, thumbState.value, edits.filterId == id) {
                                edits = edits.copy(filterId = id, intensity = if (id == edits.filterId) edits.intensity else 1f)
                            }
                        }
                    }
                }
                EditorTab.Adjust -> {
                    AdjustRow(tr("adj_brightness"), edits.brightness) { edits = edits.copy(brightness = it) }
                    AdjustRow(tr("adj_contrast"), edits.contrast) { edits = edits.copy(contrast = it) }
                    AdjustRow(tr("adj_saturation"), edits.saturation) { edits = edits.copy(saturation = it) }
                    AdjustRow(tr("adj_warmth"), edits.warmth) { edits = edits.copy(warmth = it) }
                }
                EditorTab.Crop -> {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cropIds, key = { it }) { id ->
                            val label = if (id == "original") tr("crop_original") else id
                            EditorChip(label, edits.aspect == id) { edits = edits.copy(aspect = id, panX = 0f, panY = 0f) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(Icons.Filled.RotateLeft, tr("a11y_rotate_left"), {
                            edits = edits.copy(quarterTurns = (edits.quarterTurns + 3) % 4, panX = 0f, panY = 0f)
                        }, size = 48, onMedia = true)
                        GlassIconButton(Icons.Filled.RotateRight, tr("a11y_rotate_right"), {
                            edits = edits.copy(quarterTurns = (edits.quarterTurns + 1) % 4, panX = 0f, panY = 0f)
                        }, size = 48, onMedia = true)
                        GlassIconButton(Icons.Filled.Flip, tr("a11y_flip"), {
                            edits = edits.copy(flip = !edits.flip, panX = -edits.panX)
                        }, size = 48, onMedia = true)
                        if (edits.aspect != "original") {
                            Text(tr("crop_hint"), color = Color.White.copy(.7f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // ---- tab bar ----
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                EditorTabButton(Icons.Filled.AutoFixHigh, tr("editor_tab_filters"), tab == EditorTab.Filters, Modifier.weight(1f)) { tab = EditorTab.Filters }
                EditorTabButton(Icons.Filled.Tune, tr("editor_tab_adjust"), tab == EditorTab.Adjust, Modifier.weight(1f)) { tab = EditorTab.Adjust }
                EditorTabButton(Icons.Filled.Crop, tr("editor_tab_crop"), tab == EditorTab.Crop, Modifier.weight(1f)) { tab = EditorTab.Crop }
            }
        }
    }

    // v3.10.3 section 2: ask before discarding unsaved edits. The original photo is never changed either way.
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

@Composable
private fun editorSliderColors() = SliderDefaults.colors(
    thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f)
)

/** One filter of the tray: a thumbnail of the person's own photo with the filter applied, its name, a clear selected ring. */
@Composable
private fun FilterTile(id: String, thumb: ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    val name = tr("filter_$id")
    val tint = remember(id) { ColorFilter.colorMatrix(ColorMatrix(PhotoMath.filter(id))) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(72.dp)
            .semantics { contentDescription = name; this.selected = selected; role = Role.Button }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(64.dp).clip(shape).background(Color.Black)
                .border(if (selected) 2.dp else 1.dp, if (selected) Accent else MediaGlassBorder, shape)
        ) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = tint)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name, color = if (selected) Color.White else Color.White.copy(.7f), fontSize = 12.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun AdjustRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(92.dp))
        Slider(
            value, onChange, Modifier.weight(1f).semantics { contentDescription = label },
            valueRange = -1f..1f, colors = editorSliderColors()
        )
        Text("${(value * 100).roundToInt()}", color = Color.White.copy(.8f), fontSize = 12.sp, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun EditorChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp)
            .semantics { this.selected = selected; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(if (selected) Accent else MediaGlass)
                .border(1.dp, if (selected) Accent else MediaGlassBorder, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
    }
}

@Composable
private fun EditorTabButton(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) Accent else Color.White.copy(.7f)
    Column(
        modifier.heightIn(min = 56.dp)
            .semantics { contentDescription = label; this.selected = selected; role = Role.Tab }
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}
