package com.sociax.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sociax.app.data.ApiClient
import com.sociax.app.data.StoryGroup
import com.sociax.app.data.loadJpeg
import com.sociax.app.data.timeAgo
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val STORY_MS = 5000          // how long one photo stays on screen
private const val STORY_CAPTION_MAX = 200  // same limit as the server

// ---------------- Row of story rings on Home (like the reference: "Add Story" + people) ----------------
@Composable
fun StoriesRow(groups: List<StoryGroup>, onAdd: () -> Unit, onOpen: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // "Add Story": a clear glass circle with a thin light edge and a thin white + (reference)
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color(0x2EFFFFFF))
                        .border(1.dp, Color(0x80FFFFFF), CircleShape).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, "Add story", tint = Color.White, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.height(5.dp))
                Text(tr("add_story"), color = Color.White.copy(.9f), fontSize = 12.sp, maxLines = 1)
            }
        }
        itemsIndexed(groups) { i, g ->
            Column(Modifier.width(68.dp).clickable { onOpen(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                // bright ring = something new to watch; faint ring = already seen
                val ring = if (g.allSeen) SolidColor(Color(0x66FFFFFF))
                else Brush.sweepGradient(listOf(Color(0xFFE6F0FF), Teal, BlueGlow, Color(0xFFE6F0FF)))
                Box(Modifier.size(64.dp).border(2.dp, ring, CircleShape).padding(4.dp)) { Avatar(g.avatarUrl, 56) }
                Spacer(Modifier.height(5.dp))
                Text(
                    if (g.isMe) "Your story" else g.username.removePrefix("@"),
                    color = Color.White.copy(.9f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------- Create story ----------------
@Composable
fun CreateStoryScreen(api: ApiClient, onBack: () -> Unit, onPosted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var textMode by remember { mutableStateOf(false) }       // false = photo story, true = text-only story
    var uri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    var words by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri = it ?: uri }
    val errPick = tr("err_choose_photo")
    val errWrite = tr("err_write_text")

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TopBar(tr("new_story"), onBack)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(tr("story_photo"), { textMode = false; error = null }, Modifier.weight(1f), filled = !textMode)
            PillButton(tr("story_text"), { textMode = true; error = null }, Modifier.weight(1f), filled = textMode)
        }
        Spacer(Modifier.height(12.dp))
        if (textMode) {
            // live preview: the same look as the viewer (middle reference screen)
            GlassPanel(Modifier.fillMaxWidth().height(420.dp), corner = 28) {
                SociaxBackground(true, Modifier.fillMaxSize()) {
                    TextStoryBody(words.ifBlank { tr("text_story_preview") }, dim = words.isBlank(), modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(14.dp))
            GlassField(words, { words = it.take(STORY_CAPTION_MAX) }, tr("text_story_hint"))
        } else {
            GlassPanel(Modifier.fillMaxWidth().height(420.dp), corner = 28) {
                if (uri != null) AsyncImage(uri, "Selected photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Filled.AddPhotoAlternate, null, tint = Teal, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(tr("choose_photo"), color = Color.White)
                }
            }
            Spacer(Modifier.height(10.dp))
            PillButton(if (uri == null) tr("choose_gallery") else tr("change_photo"), {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, filled = false)
            Spacer(Modifier.height(14.dp))
            GlassField(caption, { caption = it.take(STORY_CAPTION_MAX) }, tr("story_text_hint"))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            tr("story_note"),
            color = Color.White.copy(.7f), fontSize = 12.sp
        )
        error?.let { Text(it, color = Color(0xFFFF8A8A), modifier = Modifier.padding(top = 10.dp), fontSize = 13.sp) }
        Spacer(Modifier.height(20.dp))
        if (busy) CircularProgressIndicator(color = Teal, modifier = Modifier.align(Alignment.CenterHorizontally))
        else PillButton(tr("share_story"), {
            if (textMode) {
                val clean = words.trim()
                if (clean.isEmpty()) { error = errWrite; return@PillButton }
                scope.launch {
                    busy = true; error = null
                    try {
                        api.createTextStory(clean)
                        onPosted()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                    busy = false
                }
            } else {
                val chosen = uri ?: run { error = errPick; return@PillButton }
                scope.launch {
                    busy = true; error = null
                    try {
                        val bytes = loadJpeg(context, chosen, 1440)
                        val sig = api.signUpload("story", bytes.size)
                        api.uploadToCloudinary(sig, bytes)
                        api.createStory(sig.publicId, caption.trim())
                        onPosted()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                    busy = false
                }
            }
        }, Modifier.fillMaxWidth())
    }
}

/**
 * The words of a text-only story, like the middle reference screen: large, centred, soft grey at the top
 * getting brighter towards the bottom. [dim] is used for the empty-preview hint.
 */
@Composable
private fun TextStoryBody(text: String, modifier: Modifier = Modifier, dim: Boolean = false) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text, textAlign = TextAlign.Center,
            style = TextStyle(
                brush = Brush.verticalGradient(listOf(Color(if (dim) 0x55FFFFFF else 0x88FFFFFF), Color(if (dim) 0x88FFFFFF else 0xFFFFFFFF))),
                fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium
            ),
            modifier = Modifier.padding(horizontal = 28.dp)
        )
    }
}

// ---------------- Full-screen viewer (like the middle reference screen) ----------------
@Composable
fun StoryViewerScreen(api: ApiClient, groups: List<StoryGroup>, startGroup: Int, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(groups) }
    var gi by remember { mutableIntStateOf(startGroup.coerceIn(0, (groups.size - 1).coerceAtLeast(0))) }
    var si by remember {
        mutableIntStateOf(groups.getOrNull(gi)?.stories?.indexOfFirst { !it.seen }?.takeIf { it >= 0 } ?: 0)
    }
    val progress = remember { Animatable(0f) }
    var restart by remember { mutableIntStateOf(0) }    // bump to restart the timer after the bar was reset in place
    var holding by remember { mutableStateOf(false) }   // finger held on the photo = pause
    var typing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val paused = holding || typing || menuOpen

    val group = data.getOrNull(gi)
    val story = group?.stories?.getOrNull(si)
    if (group == null || story == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    suspend fun goTo(g: Int, s: Int) { progress.snapTo(0f); notice = null; text = ""; gi = g; si = s }
    suspend fun advance() {
        if (si < group.stories.lastIndex) goTo(gi, si + 1)
        else if (gi < data.lastIndex) goTo(gi + 1, 0)
        else onClose()
    }

    // Count it as "seen" on the server (the server ignores views of my own stories).
    LaunchedEffect(story.id) { try { api.viewStory(story.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { } }

    // Timer: runs only while nothing pauses it; continues from where it stopped.
    val reduceMotion = LocalReduceMotion.current
    val sentToFmt = tr("sent_to_fmt")
    val waitingNote = tr("follow_first_waiting_long")
    LaunchedEffect(gi, si, paused, restart, data) {
        if (paused) return@LaunchedEffect
        val remaining = ((1f - progress.value) * STORY_MS).toInt().coerceAtLeast(1)
        if (reduceMotion) {
            // Reduce Motion: the bar moves in 10 steps instead of sliding; pause / resume still work.
            while (progress.value < 0.999f) {
                kotlinx.coroutines.delay(STORY_MS / 10L)
                progress.snapTo(minOf(1f, progress.value + 0.1f))
            }
        } else {
            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        }
        if (progress.value >= 0.999f) advance()
    }

    fun reply(message: String) {
        scope.launch {
            try {
                val chat = api.startDm(group.username)
                val r = api.sendMessage(chat.id, message)
                notice = String.format(sentToFmt, group.username) + if (r.followPrompt != null) "\n" + waitingNote else ""; text = ""
            } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = errText(e) }
        }
    }

    fun deleteCurrent() {
        scope.launch {
            try {
                api.deleteStory(story.id)
                val left = group.stories.filter { it.id != story.id }
                val updated = if (left.isEmpty()) data.filterIndexed { i, _ -> i != gi }
                else data.mapIndexed { i, g -> if (i == gi) g.copy(stories = left) else g }
                if (updated.isEmpty()) { onClose(); return@launch }
                progress.snapTo(0f)
                data = updated
                restart++
                if (left.isEmpty()) { gi = gi.coerceAtMost(updated.lastIndex); si = 0 } else si = si.coerceAtMost(left.lastIndex)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = errText(e) }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Navy).pointerInput(gi, si, data) {
            detectTapGestures(
                onPress = { holding = true; tryAwaitRelease(); holding = false },
                onTap = { offset ->
                    val leftThird = offset.x < size.width / 3f   // left third = back, the rest = next
                    scope.launch {
                        if (leftThird) {
                            if (si > 0) goTo(gi, si - 1)
                            else if (gi > 0) goTo(gi - 1, 0)
                            else { progress.snapTo(0f); restart++ }   // first story: just start it again
                        } else advance()
                    }
                }
            )
        }
    ) {
        if (story.isText) SociaxBackground(true, Modifier.fillMaxSize()) { }   // text story: the cinematic blue background, no photo
        else AsyncImage(story.imageUrl, story.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // soft dark blue scrims so the header, the text and the message bar stay readable on any photo
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x990B1220), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color(0xCC0B1220))
            )
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            StorySegments(group.stories.size, si, progress)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(group.avatarUrl, 40)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (group.isMe) tr("your_story") else group.username, color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(timeAgo(story.createdAt), color = Color.White.copy(.7f), fontSize = 11.sp)
                }
                if (story.mine) Box {
                    GlassIconButton(Icons.Filled.MoreVert, "More", { menuOpen = true }, size = 36, onMedia = true)
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(tr("delete_story")) }, onClick = { menuOpen = false; deleteCurrent() })
                    }
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Filled.Close, "Close", onClose, size = 36, onMedia = true)
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                contentAlignment = if (story.isText) Alignment.Center else Alignment.BottomCenter) {
                if (story.isText) {
                    TextStoryBody(story.caption, Modifier.fillMaxSize())
                } else if (story.caption.isNotBlank()) {
                    Text(
                        story.caption, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }

            notice?.let { Text(it, color = Color(0xFFFFE08A), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }

            if (story.mine) {
                // my own story: only a count of views, never who viewed
                Text(
                    trf("seen_by", story.viewCount), color = Color.White.copy(.8f), fontSize = 13.sp,
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)
                )
            } else {
                // reply bar like the reference: dark inset pill with the soft-white send button inside it,
                // and a separate frosted round heart button next to it
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InsetPill(Modifier.weight(1f)) {
                        Row(Modifier.padding(start = 6.dp, end = 5.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                text, { text = it.take(500) }, Modifier.weight(1f).onFocusChanged { typing = it.isFocused },
                                placeholder = { Text(tr("send_message"), color = Color.White.copy(.6f), fontSize = 14.sp) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White
                                )
                            )
                            Box(
                                Modifier.size(42.dp).clip(CircleShape).background(PaperWhite)
                                    .clickable { if (text.isNotBlank()) reply("Replied to your story: ${text.trim()}") },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Outlined.Send, "Send", tint = Navy, modifier = Modifier.size(20.dp)) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(50.dp).clip(CircleShape).background(ControlFill).border(1.dp, ControlBorder, CircleShape)
                            .clickable { reply("\u2764\uFE0F Liked your story") },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Favorite, "Send a heart", tint = LikeRed) }
                }
            }
        }
    }
}

/** The thin bars at the top: finished stories are full, the current one fills while it plays. */
@Composable
private fun StorySegments(count: Int, current: Int, progress: Animatable<Float, *>) {
    val now = progress.value
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until count) {
            val fill = when {
                i < current -> 1f
                i == current -> now.coerceIn(0f, 1f)
                else -> 0f
            }
            Box(Modifier.weight(1f).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
            }
        }
    }
}
