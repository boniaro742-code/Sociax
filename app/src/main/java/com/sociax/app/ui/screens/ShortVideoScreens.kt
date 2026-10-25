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

/** One ad page of the Short Video feed (Step 41). */
class AdSlot(val id: Long, val afterVideoId: Long, val ad: LoadedAd)

/** A page of the Short Video pager: an ordinary video or an ad. */
sealed interface FeedSlot {
    val key: String
    class Video(val item: ShortVideo) : FeedSlot { override val key = "v${item.id}" }
    class Ad(val slot: AdSlot) : FeedSlot { override val key = "a${slot.id}" }
}

/** Holds the Short Video feed so it survives opening comments / creating a video and coming back. */
class ShortsState {
    val items = mutableStateListOf<ShortVideo>()
    var loaded by mutableStateOf(false)
    var endReached by mutableStateOf(false)
    var page by mutableIntStateOf(0)
    var cursor: String? = null      // Step 40: where the ranked For You list continues (null = start)
    var ranked = true               // false = the server answered newest-first, so paging continues with "before"

    // Step 41 (plan v3.8 section 28): ad pages between ordinary videos. An ad page sits right AFTER the video it is anchored to.
    val adSlots = mutableStateListOf<AdSlot>()
    val seenSinceAd = HashSet<Long>()   // ordinary videos looked at since the last ad page (never two ad pages in a row)
    var nextGap = 3                     // how many ordinary videos before the next ad (3 or 4, varied)
    private var adCounter = 0L

    fun reset() { items.clear(); loaded = false; endReached = false; page = 0; cursor = null; ranked = true; clearAds() }

    /** The pages of the pager in order: video, video, video, [ad], video ... Ads whose anchor video is gone are ignored. */
    fun slots(): List<FeedSlot> = buildList {
        for (v in items) {
            add(FeedSlot.Video(v))
            adSlots.firstOrNull { it.afterVideoId == v.id }?.let { add(FeedSlot.Ad(it)) }
        }
    }

    fun addAd(afterVideoId: Long, ad: LoadedAd) { adSlots.add(AdSlot(++adCounter, afterVideoId, ad)) }

    fun clearAds() { adSlots.forEach { it.ad.ad.destroy() }; adSlots.clear(); seenSinceAd.clear(); nextGap = 3 }

    /** Ads are no longer allowed (Pro, cap reached): remove the ad pages the person has not seen yet. [keepAfter] = the one on screen. */
    fun dropUnseenAds(keepAfter: Long?) {
        val gone = adSlots.filter { !it.ad.impressed && it.afterVideoId != keepAfter }
        gone.forEach { it.ad.ad.destroy() }
        adSlots.removeAll(gone.toSet())
    }

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
    api: ApiClient, state: ShortsState, ads: AdsController, onCreate: () -> Unit, onComments: (ShortVideo) -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val reportThanks = tr("report_thanks")

    suspend fun loadMore() {
        if (loading || state.endReached) return
        loading = true
        try {
            if (state.ranked) {
                val r = api.shortsForYou(state.cursor)
                state.cursor = r.nextCursor
                if (!r.ranked) state.ranked = false
                state.items.addAll(r.items.filter { n -> state.items.none { it.id == n.id } })
                if (if (r.ranked) r.nextCursor == null else r.items.size < 10) state.endReached = true
            } else {
                val page = api.shortsFeed(state.items.lastOrNull()?.id)
                state.items.addAll(page.filter { n -> state.items.none { it.id == n.id } })
                if (page.size < 10) state.endReached = true
            }
            state.loaded = true; notice = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = if (e is ApiException) e.message else "Cannot reach the Sociax server. Is the backend running?"
        }
        loading = false
    }

    LaunchedEffect(state.loaded) { if (!state.loaded) loadMore() }

    // Step 41: the SERVER decides whether this account may see ads (Free, cap not reached). Unknown = no ads. Pro = the SDK never starts.
    // MainActivity asks it each time this tab is on screen; here it is asked again when the app comes back to the front.
    val lifeOwner = LocalLifecycleOwner.current
    DisposableEffect(lifeOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && ads.statusIsStale()) scope.launch { ads.refresh(activity) }
        }
        lifeOwner.lifecycle.addObserver(obs)
        onDispose { lifeOwner.lifecycle.removeObserver(obs) }
    }

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

    // Step 40: "Not interested" / "Hide creator" (plan v3.9 section 31.2). The video leaves the list at once.
    fun notInterested(s: ShortVideo) {
        scope.launch {
            try { api.recFeedback("not_interested", "short", s.id); state.items.removeAll { it.id == s.id } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { notice = msg(e) }
        }
    }

    fun hideCreator(s: ShortVideo) {
        scope.launch {
            try { api.recFeedback("hide_creator", "short", s.id); state.items.removeAll { it.username == s.username } }
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
            val pager = rememberPagerState(
                initialPage = state.page.coerceIn(0, (state.slots().size - 1).coerceAtLeast(0))
            ) { state.slots().size }
            LaunchedEffect(pager.currentPage) {
                state.page = pager.currentPage
                val slots = state.slots()
                val here = slots.getOrNull(pager.currentPage)
                val videoId = when (here) { is FeedSlot.Video -> here.item.id; is FeedSlot.Ad -> here.slot.afterVideoId; null -> null }
                val vIdx = state.items.indexOfFirst { it.id == videoId }
                if (vIdx < 0 || vIdx >= state.items.size - 3) loadMore()

                // Step 41: an ad page only AFTER about 3 ordinary videos (3 or 4, varied), never two in a row, never as the last page,
                // and only when the server said Free + under the daily cap and an ad is really loaded. No ad -> the feed just continues.
                if (here is FeedSlot.Video) {
                    state.seenSinceAd.add(here.item.id)
                    val nextIsAd = slots.getOrNull(pager.currentPage + 1) is FeedSlot.Ad
                    val hasNextVideo = vIdx in 0 until state.items.size - 1
                    if (state.seenSinceAd.size >= state.nextGap && !nextIsAd && hasNextVideo && ads.allowed) {
                        if (ads.statusIsStale()) ads.refresh(activity)          // re-check Free / cap with the server before placing
                        val loaded = if (ads.allowed) ads.take() else null
                        if (loaded != null) {
                            state.addAd(here.item.id, loaded)
                            state.seenSinceAd.clear()
                            val st = ads.status
                            val lo = st?.everyMin ?: 3
                            val hi = maxOf(st?.everyMax ?: 4, lo)
                            state.nextGap = kotlin.random.Random.nextInt(lo, hi + 1)
                        }
                    }
                    ads.ensureLoaded()
                }
            }
            // Pro bought / cap reached: ad pages not yet seen disappear at once (the one on screen stays until swiped)
            LaunchedEffect(ads.allowed) {
                if (!ads.allowed) {
                    val cur = state.slots().getOrNull(pager.currentPage)
                    state.dropUnseenAds((cur as? FeedSlot.Ad)?.slot?.afterVideoId)
                }
            }
            VerticalPager(pager, Modifier.fillMaxSize(), key = { state.slots().getOrNull(it)?.key ?: it }) { i ->
                when (val slot = state.slots().getOrNull(i)) {
                    null -> return@VerticalPager
                    is FeedSlot.Ad -> AdPage(slot.slot.ad)
                    is FeedSlot.Video -> {
                        val s = slot.item
                        ShortPage(
                            s, active = pager.currentPage == i, autoplay = prefs.autoplay, dataSaver = prefs.dataSaver,
                            onLike = { toggleLike(s) }, onComments = { onComments(s) }, onFollow = { toggleFollow(s) },
                            onReact = { toggleReaction(s, it) }, onSave = { toggleSave(s) },
                            onDelete = { delete(s) }, onReport = { report(s, it) },
                            onNotInterested = { notInterested(s) }, onHideCreator = { hideCreator(s) },
                            onImpression = { if (!s.mine) api.trackEvent("short", s.id, "impression", 1000) },
                            onShared = { if (!s.mine) api.trackEvent("short", s.id, "share") },
                            onWatched = { ms, done ->
                                // what really played: a clean "watch" (and "complete" near the end); a 1-3 s look and leave = "skip"
                                if (!s.mine) {
                                    if (ms >= 1000) api.trackEvent("short", s.id, "watch", ms)
                                    if (done) api.trackEvent("short", s.id, "complete", ms)
                                    else if (ms >= 1000 && ms < 3000) api.trackEvent("short", s.id, "skip", ms)
                                }
                            }
                        )
                    }
                }
            }
        }

        // top bar like the reference: round frosted back button (to Home) on the left, round + (upload) on the right
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.KeyboardArrowLeft, tr("a11y_back"), onBack, size = 40, onMedia = true)
            notice?.takeIf { state.items.isNotEmpty() }?.let {
                Text(it, color = Color(0xFFFFB4A8), fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            } ?: Spacer(Modifier.weight(1f))
            GlassIconButton(Icons.Filled.Refresh, tr("a11y_refresh"), { state.reset() }, size = 40, onMedia = true)   // Step 40: a new ranking
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Filled.Add, tr("a11y_upload_short"), onCreate, size = 40, onMedia = true)
        }
    }
}

@Composable
private fun ShortPage(
    s: ShortVideo, active: Boolean, autoplay: Boolean, dataSaver: Boolean,
    onLike: () -> Unit, onComments: () -> Unit, onFollow: () -> Unit,
    onReact: (String) -> Unit, onSave: () -> Unit, onDelete: () -> Unit, onReport: (String) -> Unit,
    onNotInterested: () -> Unit, onHideCreator: () -> Unit, onImpression: () -> Unit, onShared: () -> Unit,
    onWatched: (Long, Boolean) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(s.id, active) { if (active) { delay(1000); onImpression() } }      // Step 40: seen = 1 s as the current video
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
        if (active && mayPlay && url != null) ShortPlayer(url, playing = !paused, Modifier.fillMaxSize(), onWatched = onWatched)

        if (!mayPlay || paused) {
            Box(Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(ControlFill),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, tr("a11y_play"), tint = Color.White, modifier = Modifier.size(40.dp))
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
                onShared()
                context.startActivity(Intent.createChooser(send, "Share"))
            }
            Box {
                Rail(Icons.Filled.MoreHoriz, "", Color.White) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (s.mine) DropdownMenuItem(text = { Text(tr("delete_video")) }, onClick = { menu = false; onDelete() })
                    else {
                        DropdownMenuItem(text = { Text(tr("not_interested")) }, onClick = { menu = false; onNotInterested() })
                        DropdownMenuItem(text = { Text(tr("hide_creator")) }, onClick = { menu = false; onHideCreator() })
                        listOf("Spam", "Harassment", "Inappropriate content").forEach { r ->
                            DropdownMenuItem(text = { Text(trf("report_fmt", reasonLabel(r))) }, onClick = { menu = false; onReport(r) })
                        }
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

/** Step 40: plain counters for one video on screen: milliseconds really played, and whether it got to the end (90 %). */
private class WatchStats { var ms = 0L; var done = false }

/** One ExoPlayer, only for the video currently on screen (saves memory and data). Loops. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ShortPlayer(url: String, playing: Boolean, modifier: Modifier = Modifier, onWatched: ((Long, Boolean) -> Unit)? = null) {
    val context = LocalContext.current
    val exo = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url)); repeatMode = Player.REPEAT_MODE_ONE; prepare()
        }
    }
    // Step 40: how long this video REALLY played and whether it reached (nearly) the end. Reported once, when it leaves the screen.
    val stats = remember(exo) { WatchStats() }
    val watchedCallback by rememberUpdatedState(onWatched)
    DisposableEffect(exo) { onDispose { watchedCallback?.invoke(stats.ms, stats.done); exo.release() } }

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

/** Plays a local clip (optionally only start..end) in a loop: used for the pre-publish preview. */
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
// Create a short: pick / record -> compress -> preview -> publish
// Plan v3.9.1: there is NO duration limit and the length is never required. The video is never trimmed or shortened
// by the app; the only checks are file size, file type, login and the daily media quota (all on the server).
// =====================================================================
private enum class CStage { Pick, Working, Preview, Uploading }

private class CaptureShort : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)                   // no EXTRA_DURATION_LIMIT: the camera records as long as the person wants
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
}

/** h:mm:ss from one hour on, m:ss below. Only used to DISPLAY a length that was read; an unknown length shows nothing. */
private fun mmss(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun CreateShortScreen(api: ApiClient, onBack: () -> Unit, onPosted: () -> Unit, defaultFollowersOnly: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }

    var stage by remember { mutableStateOf(CStage.Pick) }
    var prepared by remember { mutableStateOf<File?>(null) }
    var preparedMs by remember { mutableLongStateOf(0L) }            // 0 = length unknown (display only, never sent and never a limit)
    var caption by remember { mutableStateOf("") }
    var followersOnly by remember { mutableStateOf(defaultFollowersOnly) }
    var commentsOn by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val errRead = tr("err_read_video")
    var pendingCamera by remember { mutableStateOf<Uri?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Step 35: a clip already uploaded to Cloudinary (prepared file path -> public id): "try again" re-sends only the record
    var uploadedClip by remember { mutableStateOf<Pair<String, String>?>(null) }

    DisposableEffect(Unit) { onDispose { prepared?.delete() } }

    fun launchStep(block: suspend () -> Unit) { job?.cancel(); job = scope.launch { block() } }

    /** Compresses the whole video (never cuts it), then shows the preview. */
    suspend fun prepare(uri: Uri) {
        stage = CStage.Working; error = null
        prepared?.delete(); prepared = null
        try {
            prepared = exportShort(context, uri, 0L, null, prefs.uploadQuality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // compressing is not possible on this phone / for this file: publish the original, unchanged
            try { prepared = copyVideoToCache(context, uri) } catch (e2: CancellationException) { throw e2 } catch (e2: Exception) {
                error = errRead; stage = CStage.Pick; return
            }
        }
        val f = prepared!!
        // Plan v3.9.1: the length is OPTIONAL. It is read only to show it in the preview; if it cannot be read, nothing is blocked,
        // nothing is invented (0 = unknown) and the problem is only written to the log (VideoUtils).
        preparedMs = withContext(Dispatchers.IO) { try { videoDurationMs(context, Uri.fromFile(f)) } catch (e: Exception) { 0L } }.coerceAtLeast(0L)
        stage = CStage.Preview
    }

    // Plan v3.9.1: picking a video does NOT read its length first (that read used to block publishing: "Could not read the video length").
    fun onPicked(uri: Uri) = launchStep { prepare(uri) }

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
                val publicId = uploadedClip?.takeIf { it.first == f.absolutePath }?.second ?: run {
                    val sig = api.signUpload("short", f.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())   // no wrap-around for a huge file: the server then says "too big"
                    api.uploadVideoToCloudinary(sig, f)
                    uploadedClip = f.absolutePath to sig.publicId
                    sig.publicId
                }
                // "Published" only after the server saved the clip record (onPosted runs after createShort returns)
                api.createShort(publicId, caption.trim(), if (followersOnly) "followers" else "public", commentsOn)
                uploadedClip = null
                onPosted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the server deletes a refused clip, so after a permanent refusal the next try must upload again
                if (e is ApiException && e.code in 400..499 && e.reason != "media_full" && e.code != 429) uploadedClip = null
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
                        Text(tr("max_len_note"),
                            color = Color.White.copy(.65f), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
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
                    camera.launch(uri)
                }, Modifier.fillMaxWidth(), filled = false)
                error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
                Spacer(Modifier.height(10.dp))
                Text(trf("quality_line", when (prefs.uploadQuality) { "low" -> tr("ql_low"); "high" -> tr("ql_high"); else -> tr("ql_std") }), color = Color.White.copy(.55f), fontSize = 12.sp)
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
                if (f != null) Text(
                    // an unknown length is simply not shown (plan v3.9.1: never a made-up 0:00)
                    (if (preparedMs > 0) "${mmss(preparedMs)} · " else "") + "${"%.1f".format(f.length() / 1048576.0)} MB",
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
            GlassIconButton(Icons.Filled.ArrowBack, tr("a11y_back"), onClose)
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
            GlassIconButton(Icons.Filled.ArrowBack, tr("a11y_back"), onClose)
        }
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp)) {
            Text(s.username, color = Color.White, fontWeight = FontWeight.Bold)
            if (s.caption.isNotBlank()) Text(s.caption, color = Color.White.copy(.9f), fontSize = 14.sp, maxLines = 3)
        }
    }
}
