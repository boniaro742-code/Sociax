@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sociax.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Holds the Short Video feed so it survives opening comments / creating a video and coming back. */
class ShortsState {
    val items = mutableStateListOf<ShortVideo>()
    var loaded by mutableStateOf(false)
    var endReached by mutableStateOf(false)
    var page by mutableIntStateOf(0)

    fun reset() { items.clear(); loaded = false; endReached = false; page = 0 }

    fun replace(id: Long, f: (ShortVideo) -> ShortVideo) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = f(items[i])
    }
}

private fun msg(e: Exception) =
    if (e is ApiException) e.message ?: "Something went wrong" else "Cannot reach the Sociax server."

// =====================================================================
// Short Video tab: full-screen vertical feed (swipe up / down)
// =====================================================================
@Composable
fun ShortVideoScreen(
    api: ApiClient, state: ShortsState, onCreate: () -> Unit, onComments: (ShortVideo) -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val reportThanks = tr("report_thanks")

    suspend fun loadMore() {
        if (loading || state.endReached) return
        loading = true
        try {
            val page = api.shortsFeed(state.items.lastOrNull()?.id)
            state.items.addAll(page.filter { n -> state.items.none { it.id == n.id } })
            if (page.size < 10) state.endReached = true
            state.loaded = true; notice = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = if (e is ApiException) e.message else "Cannot reach the Sociax server. Is the backend running?"
        }
        loading = false
    }

    LaunchedEffect(state.loaded) { if (!state.loaded) loadMore() }

    fun toggleLike(s: ShortVideo) {
        val on = !s.liked
        state.replace(s.id) { it.copy(liked = on, likeCount = it.likeCount + if (on) 1 else -1) }
        scope.launch {
            try { api.setShortLike(s.id, on) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state.replace(s.id) { it.copy(liked = !on, likeCount = it.likeCount + if (on) -1 else 1) }
                notice = msg(e)
            }
        }
    }

    fun toggleReaction(s: ShortVideo, kind: String) {
        val now = s.reactions[kind] ?: React()
        val on = !now.mine
        fun setReact(mine: Boolean, count: Int) = state.replace(s.id) {
            it.copy(reactions = it.reactions + (kind to React(count.coerceAtLeast(0), mine)))
        }
        setReact(on, now.count + if (on) 1 else -1)
        scope.launch {
            try { api.setShortReaction(s.id, kind, on) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                setReact(now.mine, now.count); notice = msg(e)
            }
        }
    }

    fun toggleSave(s: ShortVideo) {
        val on = !s.saved
        state.replace(s.id) { it.copy(saved = on, saveCount = (it.saveCount + if (on) 1 else -1).coerceAtLeast(0)) }
        scope.launch {
            try { api.setShortBookmark(s.id, on) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state.replace(s.id) { it.copy(saved = !on, saveCount = (it.saveCount + if (on) -1 else 1).coerceAtLeast(0)) }
                notice = msg(e)
            }
        }
    }

    fun toggleFollow(s: ShortVideo) {
        val follow = s.followStatus == "none"
        scope.launch {
            try {
                val status = api.setFollow(s.username, follow)
                state.items.indices.forEach { i ->
                    if (state.items[i].username == s.username) state.items[i] = state.items[i].copy(followStatus = status)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = msg(e) }
        }
    }

    fun delete(s: ShortVideo) {
        scope.launch {
            try { api.deleteShort(s.id); state.items.removeAll { it.id == s.id } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { notice = msg(e) }
        }
    }

    fun report(s: ShortVideo, reason: String) {
        scope.launch {
            try { api.reportShort(s.id, reason); notice = reportThanks }
            catch (e: CancellationException) { throw e } catch (e: Exception) { notice = msg(e) }
        }
    }

    Box(Modifier.fillMaxSize().background(CinematicBackground)) {
        if (state.items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                if (loading) CircularProgressIndicator(color = Teal)
                else {
                    Text(notice ?: tr("no_shorts"),
                        color = Color.White.copy(.8f))
                    Spacer(Modifier.height(14.dp))
                    PillButton(tr("try_again"), { state.endReached = false; scope.launch { loadMore() } }, filled = false)
                }
            }
        } else {
            val pager = rememberPagerState(initialPage = state.page.coerceIn(0, state.items.size - 1)) { state.items.size }
            LaunchedEffect(pager.currentPage) {
                state.page = pager.currentPage
                if (pager.currentPage >= state.items.size - 3) loadMore()
            }
            VerticalPager(pager, Modifier.fillMaxSize(), key = { state.items.getOrNull(it)?.id ?: it }) { i ->
                val s = state.items.getOrNull(i) ?: return@VerticalPager
                ShortPage(
                    s, active = pager.currentPage == i, autoplay = prefs.autoplay, dataSaver = prefs.dataSaver,
                    onLike = { toggleLike(s) }, onComments = { onComments(s) }, onFollow = { toggleFollow(s) },
                    onReact = { toggleReaction(s, it) }, onSave = { toggleSave(s) },
                    onDelete = { delete(s) }, onReport = { report(s, it) }
                )
            }
        }

        // top bar like the reference: round frosted back button (to Home) on the left, round + (upload) on the right
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.KeyboardArrowLeft, "Back", onBack, size = 40, onMedia = true)
            notice?.takeIf { state.items.isNotEmpty() }?.let {
                Text(it, color = Color(0xFFFFB4A8), fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            } ?: Spacer(Modifier.weight(1f))
            GlassIconButton(Icons.Filled.Add, "Upload short (max 25 s)", onCreate, size = 40, onMedia = true)
        }
    }
}

@Composable
private fun ShortPage(
    s: ShortVideo, active: Boolean, autoplay: Boolean, dataSaver: Boolean,
    onLike: () -> Unit, onComments: () -> Unit, onFollow: () -> Unit,
    onReact: (String) -> Unit, onSave: () -> Unit, onDelete: () -> Unit, onReport: (String) -> Unit
) {
    val context = LocalContext.current
    var paused by remember(s.id) { mutableStateOf(false) }
    var startedByUser by remember(s.id) { mutableStateOf(false) }      // used when Autoplay is off
    var menu by remember { mutableStateOf(false) }
    var expanded by remember(s.id) { mutableStateOf(false) }
    var captionCut by remember(s.id) { mutableStateOf(false) }
    val mayPlay = autoplay || startedByUser
    val url = (if (dataSaver) s.videoUrlLow else s.videoUrl) ?: s.videoUrl

    Box(
        Modifier.fillMaxSize().pointerInput(s.id, mayPlay, s.liked) {
            detectTapGestures(
                onDoubleTap = { if (!s.liked) onLike() },
                onTap = { if (mayPlay) paused = !paused else startedByUser = true }
            )
        }
    ) {
        s.posterUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (active && mayPlay && url != null) ShortPlayer(url, playing = !paused, Modifier.fillMaxSize())

        if (!mayPlay || paused) {
            Box(Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(ControlFill),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }

        // dark scrim so text stays readable on any video
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC0B1220))))

        // right action rail like the reference: comments, save, share, more (the heart now sits in the reaction row)
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Rail(Icons.Outlined.ChatBubbleOutline, if (s.commentsEnabled) s.commentCount.toString() else "Off",
                Color.White, onComments)
            Rail(if (s.saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, s.saveCount.toString(),
                Color.White, onSave)
            Rail(Icons.Filled.Share, "", Color.White) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${s.username} on Sociax: ${s.caption}")
                }
                context.startActivity(Intent.createChooser(send, "Share"))
            }
            Box {
                Rail(Icons.Filled.MoreHoriz, "", Color.White) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (s.mine) DropdownMenuItem(text = { Text(tr("delete_video")) }, onClick = { menu = false; onDelete() })
                    else listOf("Spam", "Harassment", "Inappropriate content").forEach { r ->
                        DropdownMenuItem(text = { Text(trf("report_fmt", reasonLabel(r))) }, onClick = { menu = false; onReport(r) })
                    }
                }
            }
        }

        // bottom-left: author, follow, caption
        // (bottom padding leaves room for the floating tab bar, like the reaction row in the reference)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 76.dp, bottom = 10.dp + bottomBarClearance())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.border(1.5.dp, Color.White.copy(.7f), CircleShape).padding(2.dp)) { Avatar(s.avatarUrl, 40) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(s.username, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(timeAgo(s.createdAt) + if (s.audience == "followers") " · Followers" else "",
                        color = Color.White.copy(.7f), fontSize = 11.sp)
                }
                Spacer(Modifier.width(10.dp))
                when (s.followStatus) {
                    "self" -> {}
                    "none" -> PillButton("+ " + tr("follow"), onFollow)
                    "pending" -> PillButton(tr("requested"), onFollow, filled = false)
                    else -> PillButton(tr("following"), onFollow, filled = false)
                }
            }
            if (s.caption.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    s.caption, color = Color.White.copy(.92f), fontSize = 13.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) captionCut = it.hasVisualOverflow }
                )
                if (captionCut || expanded) {
                    Text(
                        if (expanded) tr("show_less") else tr("read_more"),
                        color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }
            }
            // Reaction row like the reference: heart + 😮 😂 😢 with counts
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                ReactionChip(if (s.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    s.likeCount.toString(), if (s.liked) LikeRed else Color.White, onMedia = true, onClick = onLike)
                REACTION_KINDS.forEach { (kind, emoji) ->
                    val r = s.reactions[kind] ?: React()
                    EmojiChip(emoji, r.count, r.mine) { onReact(kind) }
                }
            }
        }
    }
}

@Composable
private fun Rail(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    // reference: plain white outline icons with the number underneath, no circle behind them
    Column(Modifier.clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(30.dp))
        if (label.isNotEmpty()) Text(label, color = Color.White, fontSize = 12.sp)
    }
}

/** One ExoPlayer, only for the video currently on screen (saves memory and data). Loops. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ShortPlayer(url: String, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exo = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url)); repeatMode = Player.REPEAT_MODE_ONE; prepare()
        }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }

    // pause when the app goes to the background
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) resumed = false else if (e == Lifecycle.Event.ON_RESUME) resumed = true
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(playing, resumed, exo) { exo.playWhenReady = playing && resumed }

    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(exo) {
        while (true) {
            val d = exo.duration
            progress = if (d > 0) (exo.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(250)
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = exo; useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)   // poster stays visible until the first frame
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        LinearProgressIndicator(
            progress = { progress }, color = Teal, trackColor = Color.White.copy(.2f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
        )
    }
}

/** Plays a local clip (optionally only start..end) in a loop: used for trimming and the pre-publish preview. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ClipPreview(uri: Uri, startMs: Long, endMs: Long?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exo = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ALL } }
    DisposableEffect(exo) { onDispose { exo.release() } }
    LaunchedEffect(uri, startMs, endMs) {
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).apply {
            if (endMs != null) setEndPositionMs(endMs)
        }.build()
        exo.setMediaItem(MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build())
        exo.prepare(); exo.playWhenReady = true
    }
    AndroidView(factory = { c -> PlayerView(c).apply { player = exo; useController = false } }, modifier = modifier)
}

// =====================================================================
// Create a short: pick / record -> (trim if too long) -> compress -> preview -> publish
// =====================================================================
private enum class CStage { Pick, Trim, Working, Preview, Uploading }

private class CaptureShort : ActivityResultContract<Pair<Uri, Int>, Boolean>() {
    override fun createIntent(context: Context, input: Pair<Uri, Int>): Intent =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input.first)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, input.second)   // asks the camera app to stop at the chosen length
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
}

private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun CreateShortScreen(api: ApiClient, onBack: () -> Unit, onPosted: () -> Unit, defaultFollowersOnly: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }

    var stage by remember { mutableStateOf(CStage.Pick) }
    var limitSec by remember { mutableIntStateOf(25) }               // the user picks 20 or 25 seconds
    var source by remember { mutableStateOf<Uri?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableLongStateOf(0L) }
    var appliedStart by remember { mutableLongStateOf(0L) }
    var prepared by remember { mutableStateOf<File?>(null) }
    var preparedMs by remember { mutableLongStateOf(0L) }
    var caption by remember { mutableStateOf("") }
    var followersOnly by remember { mutableStateOf(defaultFollowersOnly) }
    var commentsOn by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val errPrepare = tr("err_prepare"); val errRead = tr("err_read_video"); val errReadTry = tr("err_read_video_try"); val errTooLongFmt = tr("err_too_long")
    var pendingCamera by remember { mutableStateOf<Uri?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) { onDispose { prepared?.delete() } }

    fun launchStep(block: suspend () -> Unit) { job?.cancel(); job = scope.launch { block() } }
    val limitMs = limitSec * 1000L

    /** Cuts (if needed) and compresses the video, then shows the preview. */
    suspend fun prepare(uri: Uri, start: Long, end: Long?) {
        stage = CStage.Working; error = null
        val trimmed = start > 0 || end != null
        prepared?.delete(); prepared = null
        try {
            prepared = exportShort(context, uri, start, end, prefs.uploadQuality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (trimmed) { error = errPrepare; stage = CStage.Pick; return }
            // short enough already: use the original if compressing is not possible on this phone
            try { prepared = copyVideoToCache(context, uri) } catch (e2: Exception) {
                error = errRead; stage = CStage.Pick; return
            }
        }
        val f = prepared!!
        preparedMs = withContext(Dispatchers.IO) { videoDurationMs(context, Uri.fromFile(f)) }
        if (preparedMs <= 0 || preparedMs > limitMs + 500) {     // never let a longer clip reach the upload step
            f.delete(); prepared = null
            error = String.format(errTooLongFmt, limitSec)
            stage = if (durationMs > limitMs) CStage.Trim else CStage.Pick
            return
        }
        stage = CStage.Preview
    }

    fun onPicked(uri: Uri) = launchStep {
        error = null; stage = CStage.Working
        val d = withContext(Dispatchers.IO) { videoDurationMs(context, uri) }
        if (d <= 0) { error = errReadTry; stage = CStage.Pick; return@launchStep }
        source = uri; durationMs = d; startMs = 0; appliedStart = 0
        if (d > limitMs + 300) stage = CStage.Trim            // longer than allowed: the user MUST trim it
        else prepare(uri, 0, if (d > limitMs) limitMs else null)
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) onPicked(u) }
    val camera = rememberLauncherForActivityResult(remember { CaptureShort() }) { ok ->
        val u = pendingCamera
        if (ok && u != null) onPicked(u)
    }

    fun publish() {
        val f = prepared ?: return
        launchStep {
            stage = CStage.Uploading; error = null
            try {
                val sig = api.signUpload("short", f.length().toInt())
                api.uploadVideoToCloudinary(sig, f)
                api.createShort(sig.publicId, caption.trim(), if (followersOnly) "followers" else "public", commentsOn, limitSec)
                onPosted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = msg(e); stage = CStage.Preview
            }
            Unit
        }
    }

    // Back: step back inside this flow first; leave only from the first step.
    BackHandler(enabled = stage != CStage.Pick) {
        job?.cancel()
        if (stage == CStage.Preview) { prepared?.delete(); prepared = null }
        stage = CStage.Pick
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TopBar(tr("new_short"), onBack)

        when (stage) {
            CStage.Pick -> {
                GlassPanel(Modifier.fillMaxWidth().height(260.dp), corner = 28) {
                    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.VideoLibrary, null, tint = Teal, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(tr("choose_record"), color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(trf("max_len_note", limitSec),
                            color = Color.White.copy(.65f), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(tr("max_length"), color = Teal, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("20 s", { limitSec = 20 }, filled = limitSec == 20)
                    PillButton("25 s", { limitSec = 25 }, filled = limitSec == 25)
                }
                Spacer(Modifier.height(16.dp))
                PillButton(tr("choose_gallery"), {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PillButton(tr("record_camera"), {
                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                    val f = File(dir, "rec_${System.currentTimeMillis()}.mp4")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                    pendingCamera = uri
                    camera.launch(uri to limitSec)
                }, Modifier.fillMaxWidth(), filled = false)
                error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
                Spacer(Modifier.height(10.dp))
                Text(trf("quality_line", when (prefs.uploadQuality) { "low" -> tr("ql_low"); "high" -> tr("ql_high"); else -> tr("ql_std") }), color = Color.White.copy(.55f), fontSize = 12.sp)
            }

            CStage.Trim -> {
                val src = source
                val maxStart = (durationMs - limitMs).coerceAtLeast(1L)
                Text(trf("trim_note", mmss(durationMs), limitSec),
                    color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                GlassPanel(Modifier.fillMaxWidth().height(380.dp), corner = 28) {
                    if (src != null) ClipPreview(src, appliedStart, appliedStart + limitMs, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = startMs.toFloat().coerceIn(0f, maxStart.toFloat()),
                    onValueChange = { startMs = it.toLong() },
                    onValueChangeFinished = { appliedStart = startMs },
                    valueRange = 0f..maxStart.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal)
                )
                Text(trf("keeping", mmss(startMs), mmss(startMs + limitMs)), color = Teal, fontWeight = FontWeight.SemiBold)
                error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(14.dp))
                PillButton(tr("trim_continue"), {
                    val u = source ?: return@PillButton
                    launchStep { prepare(u, startMs, startMs + limitMs) }
                }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PillButton(tr("choose_another"), { stage = CStage.Pick }, Modifier.fillMaxWidth(), filled = false)
            }

            CStage.Working, CStage.Uploading -> {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Teal)
                    Spacer(Modifier.height(16.dp))
                    Text(if (stage == CStage.Working) tr("preparing") else tr("uploading"),
                        color = Color.White)
                    Spacer(Modifier.height(20.dp))
                    PillButton(tr("cancel"), { job?.cancel(); if (stage == CStage.Uploading) stage = CStage.Preview else stage = CStage.Pick }, filled = false)
                }
            }

            CStage.Preview -> {
                val f = prepared
                Text(tr("preview"), color = Teal, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                GlassPanel(Modifier.fillMaxWidth().height(380.dp), corner = 28) {
                    if (f != null) ClipPreview(Uri.fromFile(f), 0, null, Modifier.fillMaxSize())
                }
                if (f != null) Text("${"%.1f".format(preparedMs / 1000.0)} s · ${"%.1f".format(f.length() / 1048576.0)} MB",
                    color = Color.White.copy(.65f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                GlassField(caption, { caption = it.take(2200) }, tr("write_caption"))
                Spacer(Modifier.height(12.dp))
                Text(tr("who_can_watch"), color = Teal, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(tr("public"), { followersOnly = false }, filled = !followersOnly)
                    PillButton(tr("followers_only"), { followersOnly = true }, filled = followersOnly)
                }
                Spacer(Modifier.height(12.dp))
                Text(tr("comments"), color = Teal, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(tr("on"), { commentsOn = true }, filled = commentsOn)
                    PillButton(tr("off"), { commentsOn = false }, filled = !commentsOn)
                }
                error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(18.dp))
                PillButton(tr("publish"), { publish() }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PillButton(tr("choose_another"), { prepared?.delete(); prepared = null; stage = CStage.Pick },
                    Modifier.fillMaxWidth(), filled = false)
            }
        }
    }
}

// =====================================================================
// Comments for a short (same screen as post comments)
// =====================================================================
@Composable
fun ShortCommentsScreen(api: ApiClient, short: ShortVideo, onBack: () -> Unit, onChanged: () -> Unit) {
    CommentsScaffold(
        load = { api.shortComments(short.id) },
        send = { api.addShortComment(short.id, it) },
        onBack = onBack, onChanged = onChanged, key = "s${short.id}"
    )
}


// =====================================================================
// Step 21: Settings > Privacy & Security > Saved short videos
// =====================================================================
/** Short videos I saved (bookmark), as a grid of first-frame thumbnails; tap one to watch it full-screen. */
@Composable
fun SavedShortsScreen(api: ApiClient, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shorts by remember { mutableStateOf<List<ShortVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var watching by remember { mutableStateOf<ShortVideo?>(null) }
    val pageSize = 30

    LaunchedEffect(Unit) {
        try { shorts = api.savedShorts(); more = shorts.size >= pageSize }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e) }
        loading = false
    }

    val current = watching
    if (current != null) {
        BackHandler { watching = null }
        SavedShortPlayer(
            s = current, dataSaver = Prefs(LocalContext.current).dataSaver,
            onClose = { watching = null },
            onUnsave = {
                scope.launch {
                    try { api.setShortBookmark(current.id, false); shorts = shorts.filter { it.id != current.id }; watching = null }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e); watching = null }
                }
            }
        )
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TopBar(tr("saved_shorts"), onBack)
        error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (loading) CircularProgressIndicator(color = Teal)
        else if (shorts.isEmpty()) Text(tr("no_saved_shorts"), color = Color.White.copy(.7f))
        else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shorts.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { sv ->
                            Box(
                                Modifier.weight(1f).aspectRatio(0.6f).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                    .background(Color(0x22FFFFFF)).clickable { watching = sv }
                            ) {
                                sv.posterUrl?.let { coil.compose.AsyncImage(it, sv.caption, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (more) {
                Spacer(Modifier.height(12.dp))
                PillButton(tr("load_more"), {
                    scope.launch {
                        try {
                            val next = api.savedShorts(before = shorts.last().id)
                            shorts = shorts + next.filter { n -> shorts.none { it.id == n.id } }; more = next.size >= pageSize
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e) }
                    }
                }, filled = false)
            }
            Spacer(Modifier.height(10.dp))
            Text(tr("saved_shorts_note"), color = Color.White.copy(.5f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Full-screen playback of one saved clip with close and "remove from saved" buttons. */
@Composable
private fun SavedShortPlayer(s: ShortVideo, dataSaver: Boolean, onClose: () -> Unit, onUnsave: () -> Unit) {
    val url = (if (dataSaver) s.videoUrlLow else s.videoUrl) ?: s.videoUrl
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (url != null) ShortPlayer(url, playing = true, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.ArrowBack, "Back", onClose)
            Spacer(Modifier.weight(1f))
            PillButton(tr("remove_saved"), onUnsave, filled = false)
        }
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp)) {
            Text(s.username, color = Color.White, fontWeight = FontWeight.Bold)
            if (s.caption.isNotBlank()) Text(s.caption, color = Color.White.copy(.9f), fontSize = 14.sp, maxLines = 3)
        }
    }
}

/** Step 26: full-screen playback of one clip opened from Search (back button only). */
@Composable
fun SearchShortPlayer(s: ShortVideo, dataSaver: Boolean, onClose: () -> Unit) {
    val url = (if (dataSaver) s.videoUrlLow else s.videoUrl) ?: s.videoUrl
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (url != null) ShortPlayer(url, playing = true, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.ArrowBack, "Back", onClose)
        }
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp)) {
            Text(s.username, color = Color.White, fontWeight = FontWeight.Bold)
            if (s.caption.isNotBlank()) Text(s.caption, color = Color.White.copy(.9f), fontSize = 14.sp, maxLines = 3)
        }
    }
}
