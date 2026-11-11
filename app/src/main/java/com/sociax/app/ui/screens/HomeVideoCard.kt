package com.sociax.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.sociax.app.data.ApiClient
import com.sociax.app.data.ApiException
import com.sociax.app.data.Post
import com.sociax.app.data.REACTION_KINDS
import com.sociax.app.data.React
import com.sociax.app.data.ShortVideo
import com.sociax.app.data.timeAgo
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =====================================================================================================================
// Step 74 (plan v3.10.2 "Home feed content mix"): photo posts AND video posts in ONE Home feed.
// Nothing new on the server: the videos come from the same ranked "For You" list the Short Video tab uses
// (GET /shorts/for-you, already filtered for privacy / blocks / deleted clips before ranking). The Short Video tab itself is untouched.
// =====================================================================================================================

/** One row of the Home feed: a photo post or a video post. */
sealed interface HomeItem {
    val key: String
    class Photo(val post: Post) : HomeItem { override val key = "p${post.id}" }
    class Video(val video: ShortVideo) : HomeItem { override val key = "v${video.id}" }
}

/** Where one feed item sits in the Home order (the item itself lives in the photo list or the video list, so likes etc. stay in one place). */
data class HomeRef(val video: Boolean, val id: Long) {
    val key: String get() = (if (video) "v" else "p") + id
}

/**
 * Merges the NEW photos and the NEW videos of one page into one run: [photoRun] photos, then one video, again and again.
 * The ranking order inside each kind is kept. When one kind runs out the other one continues. Only the page's own items are placed,
 * so rows already on screen never move when the next page arrives.
 */
fun mixHomeRefs(photoIds: List<Long>, videoIds: List<Long>, photoRun: Int = 2): List<HomeRef> {
    val out = ArrayList<HomeRef>(photoIds.size + videoIds.size)
    var p = 0
    var v = 0
    while (p < photoIds.size || v < videoIds.size) {
        var n = 0
        while (n < photoRun && p < photoIds.size) { out.add(HomeRef(false, photoIds[p])); p++; n++ }
        if (v < videoIds.size) { out.add(HomeRef(true, videoIds[v])); v++ }
    }
    return out
}

/** One page of videos for Home. */
class HomeVideoPart(val items: List<ShortVideo>, val cursor: String?, val ranked: Boolean, val last: Boolean)

/** Asks for one page of videos. A failure returns null: the photos of Home are never held back by the videos. */
suspend fun loadHomeVideos(api: ApiClient, cursor: String?, ranked: Boolean, lastId: Long?): HomeVideoPart? =
    try {
        if (ranked) {
            val r = api.shortsForYou(cursor)
            HomeVideoPart(r.items, r.nextCursor, r.ranked, if (r.ranked) r.nextCursor == null else r.items.size < 10)
        } else {
            val page = api.shortsFeed(lastId)
            HomeVideoPart(page, null, false, page.size < 10)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/**
 * The ONE video card that may play: the video whose card is mostly on screen (at least 55 %) and closest to the middle of the screen.
 * Every other video card shows only its picture, so there is never more than one player and never two sounds at once.
 */
fun pickActiveVideo(state: LazyListState): String? {
    val info = state.layoutInfo
    val top = info.viewportStartOffset
    val bottom = info.viewportEndOffset
    val middle = (top + bottom) / 2
    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    for (row in info.visibleItemsInfo) {
        val key = row.key as? String ?: continue
        if (!key.startsWith("v") || row.size <= 0) continue
        val rowTop = row.offset
        val rowBottom = row.offset + row.size
        val shown = (minOf(bottom, rowBottom) - maxOf(top, rowTop)).coerceAtLeast(0)
        if (shown * 100 < row.size * 55) continue
        val distance = kotlin.math.abs((rowTop + rowBottom) / 2 - middle)
        if (distance < bestDistance) { best = key; bestDistance = distance }
    }
    return best
}

/** Like / react / save / follow / delete / report for the videos of Home (optimistic where safe), same rules as the Short Video tab. */
class HomeVideoController(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val videos: SnapshotStateList<ShortVideo>,
    private val onError: (String) -> Unit
) {
    /** Home connects these so a follow / "Hide creator" done on a video card also updates the photo cards of the same person. */
    var onFollowChanged: ((String, String) -> Unit)? = null
    var onCreatorHidden: ((String) -> Unit)? = null

    private fun fail(e: Exception) =
        onError(if (e is ApiException) e.message ?: "Something went wrong" else "Cannot reach the Sociax server.")

    private fun replace(id: Long, f: (ShortVideo) -> ShortVideo) {
        val i = videos.indexOfFirst { it.id == id }
        if (i >= 0) videos[i] = f(videos[i])
    }

    fun setFollowStatus(username: String, status: String) {
        videos.indices.forEach { i -> if (videos[i].username == username) videos[i] = videos[i].copy(followStatus = status) }
    }

    fun removeCreator(username: String) { videos.removeAll { it.username == username } }

    /** The video was the active card for one second: a clean "seen" signal for the For You ranker. */
    fun seen(s: ShortVideo) { if (!s.mine) api.trackEvent("short", s.id, "impression", 1000) }

    fun shared(s: ShortVideo) { if (!s.mine) api.trackEvent("short", s.id, "share") }

    /** What really played in the feed card (muted preview included). A quick scroll past is NOT reported as "skip": it is normal browsing. */
    fun watched(s: ShortVideo, ms: Long, done: Boolean) {
        if (s.mine) return
        if (ms >= 1000) api.trackEvent("short", s.id, "watch", ms)
        if (done) api.trackEvent("short", s.id, "complete", ms)
    }

    fun toggleLike(s: ShortVideo) {
        val on = !s.liked
        replace(s.id) { it.copy(liked = on, likeCount = it.likeCount + if (on) 1 else -1) }
        scope.launch {
            try { api.setShortLike(s.id, on) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                replace(s.id) { it.copy(liked = !on, likeCount = it.likeCount + if (on) -1 else 1) }; fail(e)
            }
        }
    }

    fun toggleReaction(s: ShortVideo, kind: String) {
        val now = s.reactions[kind] ?: React()
        val on = !now.mine
        fun setReact(mine: Boolean, count: Int) = replace(s.id) {
            it.copy(reactions = it.reactions + (kind to React(count.coerceAtLeast(0), mine)))
        }
        setReact(on, now.count + if (on) 1 else -1)
        scope.launch {
            try { api.setShortReaction(s.id, kind, on) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                setReact(now.mine, now.count); fail(e)
            }
        }
    }

    fun toggleSave(s: ShortVideo) {
        val on = !s.saved
        replace(s.id) { it.copy(saved = on, saveCount = (it.saveCount + if (on) 1 else -1).coerceAtLeast(0)) }
        scope.launch {
            try { api.setShortBookmark(s.id, on) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                replace(s.id) { it.copy(saved = !on, saveCount = (it.saveCount + if (on) -1 else 1).coerceAtLeast(0)) }; fail(e)
            }
        }
    }

    fun toggleFollow(s: ShortVideo) {
        val follow = s.followStatus == "none"
        scope.launch {
            try {
                val status = api.setFollow(s.username, follow)
                setFollowStatus(s.username, status)
                onFollowChanged?.invoke(s.username, status)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }

    fun delete(s: ShortVideo) {
        scope.launch {
            try { api.deleteShort(s.id); videos.removeAll { it.id == s.id } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }

    fun report(s: ShortVideo, reason: String) {
        scope.launch {
            try { api.reportShort(s.id, reason); onError("Thanks, we received your report.") }
            catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }

    fun notInterested(s: ShortVideo) {
        scope.launch {
            try { api.recFeedback("not_interested", "short", s.id); videos.removeAll { it.id == s.id } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }

    fun hideCreator(s: ShortVideo) {
        scope.launch {
            try {
                api.recFeedback("hide_creator", "short", s.id)
                videos.removeAll { it.username == s.username }
                onCreatorHidden?.invoke(s.username)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }
}

private fun durationLabel(seconds: Double): String {
    val t = seconds.toLong().coerceAtLeast(0L)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%d:%02d", m, s)
}

/**
 * A video post in the Home feed, built like the photo card (author line, media, caption, actions, reactions).
 * Only the [active] card plays inline, muted at first, with a clear sound button. Step 75 (plan v4.0.1): a tap on the video (or on the
 * marked full-screen button) calls [onOpen] = the full-screen Short Video player opens on THIS video; a double tap still likes it.
 * The other cards show their picture and a play symbol. Autoplay off (Settings) = no inline playback; Data Saver = the 480p copy.
 */
@Composable
fun HomeVideoCard(
    v: ShortVideo, active: Boolean, autoplay: Boolean, dataSaver: Boolean,
    ctl: HomeVideoController, onComments: (ShortVideo) -> Unit, onOpen: (ShortVideo) -> Unit = {}
) {
    val context = LocalContext.current
    val openProfile = LocalOpenProfile.current
    var menu by remember { mutableStateOf(false) }
    var muted by remember(v.id) { mutableStateOf(true) }
    var failed by remember(v.id) { mutableStateOf(false) }
    var playerOn by remember(v.id) { mutableStateOf(false) }
    val url = (if (dataSaver) v.videoUrlLow else v.videoUrl) ?: v.videoUrl
    val mayPlay = autoplay
    val showingVideo = playerOn && mayPlay && url != null && !failed

    // The player starts 350 ms after the card became the active one (a fast fling never starts one) and is dropped at once when it leaves.
    LaunchedEffect(v.id, active) {
        if (active) { delay(350); playerOn = true } else { playerOn = false; muted = true }
    }
    LaunchedEffect(v.id, active) { if (active) { delay(1000); ctl.seen(v) } }

    GlassPanel(Modifier.fillMaxWidth(), corner = 22, lift = true) {
        Column(Modifier.padding(12.dp)) {
            // author line
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).clickable { openProfile(v.username) }) { Avatar(v.avatarUrl, 42) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable { openProfile(v.username) }) {
                    Text(v.username, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeAgo(v.createdAt), color = Ink.copy(.55f), fontSize = 12.sp)
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            if (v.audience == "followers") Icons.Outlined.Group else Icons.Outlined.Public,
                            if (v.audience == "followers") tr("followers_only") else tr("public"),
                            tint = Ink.copy(.55f), modifier = Modifier.size(13.dp)
                        )
                    }
                }
                when (v.followStatus) {
                    "self" -> {}
                    "none" -> SmallChip("+ " + tr("follow"), { ctl.toggleFollow(v) })
                    "pending" -> SmallChip(tr("requested"), { ctl.toggleFollow(v) }, active = false)
                    else -> SmallChip(tr("following"), { ctl.toggleFollow(v) }, active = false)
                }
                Box {
                    Icon(
                        Icons.Filled.MoreHoriz, tr("a11y_more"), tint = Ink.copy(.7f),
                        modifier = Modifier.padding(start = 4.dp).size(36.dp).clip(CircleShape).clickable { menu = true }.padding(6.dp)
                    )
                    DropdownMenu(menu, { menu = false }) {
                        if (v.mine) DropdownMenuItem(text = { Text(tr("delete_video")) }, onClick = { menu = false; ctl.delete(v) })
                        else {
                            DropdownMenuItem(text = { Text(tr("not_interested")) }, onClick = { menu = false; ctl.notInterested(v) })
                            DropdownMenuItem(text = { Text(tr("hide_creator")) }, onClick = { menu = false; ctl.hideCreator(v) })
                            listOf("Spam", "Harassment", "Inappropriate content").forEach { r ->
                                DropdownMenuItem(text = { Text(trf("report_fmt", reasonLabel(r))) }, onClick = { menu = false; ctl.report(v, r) })
                            }
                        }
                    }
                }
            }

            // the video: picture first, the player only while this card is the active one
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(16.dp)).background(Color.Black)
                    .pointerInput(v.id, v.liked) {
                        detectTapGestures(
                            onDoubleTap = { if (!v.liked) ctl.toggleLike(v) },
                            onTap = { onOpen(v) }          // Step 75: the exact video opens full screen; the Home rows stay where they are
                        )
                    }
            ) {
                v.posterUrl?.let { AsyncImage(it, v.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (showingVideo && url != null) {
                    HomeVideoPlayer(
                        url, playing = true, muted = muted, modifier = Modifier.fillMaxSize(),
                        onStats = { ms, done -> ctl.watched(v, ms, done) }, onFailed = { failed = true }
                    )
                }
                if (!showingVideo) {
                    if (failed || url == null) {
                        Text(
                            tr("video_cannot_play"), color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                    } else {
                        Box(
                            Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(MediaGlass),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.PlayArrow, tr("a11y_play"), tint = Color.White, modifier = Modifier.size(36.dp)) }
                    }
                }
                v.duration?.let {
                    Text(
                        durationLabel(it), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50))
                            .background(MediaGlass).padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                // clearly marked "open full screen" control (a tap anywhere on the video does the same)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(MediaGlass)
                        .clickable { onOpen(v) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Fullscreen, tr("a11y_open_fullscreen"), tint = Color.White, modifier = Modifier.size(20.dp)) }
                if (showingVideo) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(MediaGlass)
                            .clickable { muted = !muted },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            tr(if (muted) "a11y_sound_on" else "a11y_sound_off"), tint = Color.White, modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Step 84: the same caption behaviour as a photo post (a few lines, "See more" / "See less"); the video above stays the main thing
            if (v.caption.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                ExpandableCaption(v.caption, "v${v.id}", previewLines = 3)
            }

            // ♥ 💬 ➤                                  🔖
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PostAction(
                    if (v.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, tr("a11y_heart"),
                    v.likeCount, if (v.liked) Accent else Ink
                ) { ctl.toggleLike(v) }
                PostAction(Icons.Outlined.ChatBubbleOutline, tr("comments"), if (v.commentsEnabled) v.commentCount else null, Ink) { onComments(v) }
                PostAction(Icons.Outlined.Send, tr("a11y_share"), null, Ink) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${v.username} on Sociax: ${v.caption}")
                    }
                    ctl.shared(v)
                    context.startActivity(Intent.createChooser(send, "Share"))
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (v.saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, tr("a11y_save"),
                    tint = if (v.saved) Accent else Ink,
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { ctl.toggleSave(v) }.padding(6.dp)
                )
            }
            // emoji reactions (😮 😂 😢)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                REACTION_KINDS.forEach { (kind, emoji) ->
                    val r = v.reactions[kind] ?: React()
                    EmojiChip(emoji, r.count, r.mine) { ctl.toggleReaction(v, kind) }
                }
            }
        }
    }
}

/** Plain counters for one feed video: milliseconds really played, and whether it reached (nearly) the end. */
private class HomeWatchStats { var ms = 0L; var done = false }

/**
 * ONE ExoPlayer for the active feed card. Muted unless the person turned the sound on; audio focus is taken only while the sound is on.
 * Pauses when the app goes to the background, is released when the card leaves the screen, reports a playback error instead of crashing.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun HomeVideoPlayer(
    url: String, playing: Boolean, muted: Boolean, modifier: Modifier = Modifier,
    onStats: (Long, Boolean) -> Unit, onFailed: () -> Unit
) {
    val context = LocalContext.current
    val exo = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url)); repeatMode = Player.REPEAT_MODE_ONE; volume = 0f; prepare()
        }
    }
    val stats = remember(exo) { HomeWatchStats() }
    val statsCallback by rememberUpdatedState(onStats)
    val failedCallback by rememberUpdatedState(onFailed)
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failedCallback() }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener); statsCallback(stats.ms, stats.done); exo.release() }
    }

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
    LaunchedEffect(muted, exo) {
        exo.volume = if (muted) 0f else 1f
        exo.setAudioAttributes(AudioAttributes.DEFAULT, !muted)      // audio focus only while the sound is really on
    }

    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(exo) {
        var last = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (exo.isPlaying) stats.ms += (now - last).coerceIn(0L, 1000L)
            last = now
            val d = exo.duration
            progress = if (d > 0) (exo.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            if (progress >= 0.9f) stats.done = true
            delay(250)
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = exo; useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)   // the picture stays visible until the first frame
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        LinearProgressIndicator(
            progress = { progress }, color = Accent, trackColor = Color.White.copy(.2f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
        )
    }
}
