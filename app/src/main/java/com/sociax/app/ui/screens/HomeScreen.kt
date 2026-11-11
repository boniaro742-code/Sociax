package com.sociax.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sociax.app.R
import com.sociax.app.data.ApiClient
import com.sociax.app.data.ApiException
import com.sociax.app.data.Post
import com.sociax.app.data.Prefs
import com.sociax.app.data.REACTION_KINDS
import com.sociax.app.data.ShortVideo
import com.sociax.app.data.StoryGroup
import com.sociax.app.data.timeAgo
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Applies like / save / follow / delete / report to a list of posts (optimistic where safe). */
class PostController(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val posts: SnapshotStateList<Post>,
    private val onError: (String) -> Unit
) {
    private fun fail(e: Exception) =
        onError(if (e is ApiException) e.message ?: "Something went wrong" else "Cannot reach the Sociax server.")

    /** Step 40: Home sets this to true. "Not interested" / "Hide creator" belong to the feed only (not to profiles or saved lists). */
    var feedControls = false

    /** Step 74: Home connects these so a follow / "Hide creator" done on a photo card also updates the video cards of the same person. */
    var onFollowChanged: ((String, String) -> Unit)? = null
    var onCreatorHidden: ((String) -> Unit)? = null

    fun setFollowStatus(username: String, status: String) {
        posts.indices.forEach { i -> if (posts[i].username == username) posts[i] = posts[i].copy(followStatus = status) }
    }

    fun removeCreator(username: String) { posts.removeAll { it.username == username } }

    /** The post stayed on screen for one second: a clean "seen" signal for the For You ranker (sent in the background). */
    fun seen(p: Post) { if (!p.mine) api.trackEvent("post", p.id, "impression", 1000) }

    /** The share sheet was opened (the phone cannot know whether the person finished sharing). */
    fun shared(p: Post) { if (!p.mine) api.trackEvent("post", p.id, "share") }

    fun notInterested(p: Post) {
        scope.launch {
            try { api.recFeedback("not_interested", "post", p.id); posts.removeAll { it.id == p.id } } catch (e: Exception) { fail(e) }
        }
    }

    fun hideCreator(p: Post) {
        scope.launch {
            try {
                api.recFeedback("hide_creator", "post", p.id); posts.removeAll { it.username == p.username }
                onCreatorHidden?.invoke(p.username)
            } catch (e: Exception) { fail(e) }
        }
    }

    private fun replace(id: Long, f: (Post) -> Post) {
        val i = posts.indexOfFirst { it.id == id }
        if (i >= 0) posts[i] = f(posts[i])
    }

    fun toggleLike(p: Post) {
        val on = !p.liked
        replace(p.id) { it.copy(liked = on, likeCount = it.likeCount + if (on) 1 else -1) }
        scope.launch {
            try { api.setLike(p.id, on) } catch (e: Exception) {
                replace(p.id) { it.copy(liked = !on, likeCount = it.likeCount + if (on) -1 else 1) }; fail(e)
            }
        }
    }

    fun toggleReaction(p: Post, kind: String) {
        val now = p.reactions[kind] ?: com.sociax.app.data.React()
        val on = !now.mine
        fun setReact(mine: Boolean, count: Int) = replace(p.id) {
            it.copy(reactions = it.reactions + (kind to com.sociax.app.data.React(count.coerceAtLeast(0), mine)))
        }
        setReact(on, now.count + if (on) 1 else -1)
        scope.launch {
            try { api.setReaction(p.id, kind, on) } catch (e: Exception) { setReact(now.mine, now.count); fail(e) }
        }
    }

    fun toggleSave(p: Post) {
        val on = !p.saved
        replace(p.id) { it.copy(saved = on) }
        scope.launch {
            try { api.setBookmark(p.id, on) } catch (e: Exception) { replace(p.id) { it.copy(saved = !on) }; fail(e) }
        }
    }

    fun toggleFollow(p: Post) {
        val follow = p.followStatus == "none"
        scope.launch {
            try {
                val status = api.setFollow(p.username, follow)
                setFollowStatus(p.username, status)
                onFollowChanged?.invoke(p.username, status)
            } catch (e: Exception) { fail(e) }
        }
    }

    fun delete(p: Post) {
        scope.launch { try { api.deletePost(p.id); posts.removeAll { it.id == p.id } } catch (e: Exception) { fail(e) } }
    }

    fun report(p: Post, reason: String) {
        scope.launch { try { api.reportPost(p.id, reason); onError("Thanks, we received your report.") } catch (e: Exception) { fail(e) } }
    }
}

@Composable
fun HomeScreen(
    api: ApiClient, feed: HomeFeedState, refreshKey: Int, onCreate: () -> Unit, onSearch: () -> Unit,
    onNotifications: () -> Unit, onMessages: () -> Unit,
    onAddStory: () -> Unit, onOpenStory: (List<StoryGroup>, Int) -> Unit,
    onComments: (Post) -> Unit, notifUnread: Int = 0, onShortComments: (ShortVideo) -> Unit = {},
    onOpenVideo: (ShortVideo) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    // Step 75 (plan v4.0.1): the rows, the paging and the scroll position live in [feed] (kept by MainActivity), so they survive a page that
    // opens on top of Home (the full-screen video). Step 74: photos and videos in one list; `order` says in which order they are shown.
    val posts = feed.posts
    val videos = feed.videos
    val order = feed.order
    val ctl = remember(feed) { PostController(api, scope, posts) { feed.notice = it }.also { it.feedControls = true } }
    val vctl = remember(feed) { HomeVideoController(api, scope, videos) { feed.notice = it } }
    remember(ctl, vctl) {       // a follow / "Hide creator" on one kind of card also updates the other kind
        ctl.onFollowChanged = { u, st -> vctl.setFollowStatus(u, st) }
        ctl.onCreatorHidden = { u -> vctl.removeCreator(u) }
        vctl.onFollowChanged = { u, st -> ctl.setFollowStatus(u, st) }
        vctl.onCreatorHidden = { u -> ctl.removeCreator(u) }
        true
    }

    // First entry, a refresh, another tab chip or a new refreshKey = load again. Coming back from the full-screen video (or a quick switch
    // to another bottom tab) keeps the rows exactly as they were.
    val loadKey = "${feed.tab}/$refreshKey/${feed.reload}"
    LaunchedEffect(loadKey) {
        if (feed.canKeep(loadKey)) { feed.keepOnReturn = false; return@LaunchedEffect }
        feed.signature = loadKey
        feed.keepOnReturn = false
        // Story rings: reloaded together with the feed (e.g. after watching or sharing a story). A failure just hides the rings.
        launch {
            try { feed.storyGroups = api.stories() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { }
        }
        feed.loadPage(api, true)
    }

    // the rows in the order they are shown (a photo or a video that was removed, e.g. "Not interested", simply disappears)
    val shown: List<HomeItem> = order.mapNotNull { r ->
        if (r.video) videos.firstOrNull { it.id == r.id }?.let { HomeItem.Video(it) }
        else posts.firstOrNull { it.id == r.id }?.let { HomeItem.Photo(it) }
    }
    val listState = feed.listState
    val activeVideo by remember { derivedStateOf { pickActiveVideo(listState) } }      // the one video card that may play inline

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        // the docked tab bar sits on top of the page, so the last post must be able to scroll above it
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp + bottomBarClearance()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { // header like the new design: "Sociax" in purple on the left; new post, search and the bell (with its unread number) on the right
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SociaxMark(Modifier.weight(1f))
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(AccentBrush).clickable(onClick = onCreate),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, tr("a11y_new_post"), tint = OnAccent, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.width(10.dp))
                GlassIconButton(Icons.Outlined.Search, tr("search"), onSearch, size = 44)
                Spacer(Modifier.width(10.dp))
                Box {   // Step 27: the bell opens the notification list; the number shows how many are unread
                    GlassIconButton(Icons.Outlined.Notifications, tr("a11y_notifications"), onNotifications, size = 44)
                    if (notifUnread > 0) CountBadge(
                        notifUnread, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp), color = Color(0xFFFF3B30)
                    )
                }
            }
        }
        item { AlarmPrompt() }      // Step 33: optional daily-alarm prompt (Yes / No / X); shows nothing once answered
        item { StoriesRow(feed.storyGroups, onAddStory) { onOpenStory(feed.storyGroups, it) } }
        item { // For you / Following as two chips, refresh at the end
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(tr("for_you"), { feed.tab = "for_you" }, active = feed.tab == "for_you")
                SmallChip(tr("following"), { feed.tab = "following" }, active = feed.tab == "following")
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.Refresh, tr("a11y_refresh"), tint = Ink.copy(.7f),
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { feed.reload++ }.padding(7.dp)
                )
            }
        }
        feed.notice?.let { item {   // Step 35: a feed failure always offers "Try again" (never a silent empty feed)
            Column {
                Text(it, color = ErrorInk, fontSize = 13.sp)
                Text(tr("try_again"), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable { feed.notice = null; feed.reload++ }
                        .padding(horizontal = 4.dp, vertical = 6.dp))
            }
        } }
        if (!feed.loading && shown.isEmpty() && feed.notice == null) item {
            Text(
                if (feed.tab == "following") tr("empty_following") else tr("empty_foryou"),
                color = Ink.copy(.7f), modifier = Modifier.padding(24.dp)
            )
        }
        items(shown, key = { it.key }) { row ->
            when (row) {
                is HomeItem.Photo -> {
                    val p = row.post
                    LaunchedEffect(p.id) { delay(1000); ctl.seen(p) }      // Step 40: counts only after 1 s really on screen (pre-loaded items do not count)
                    PostCard(p, ctl, onComments)
                }
                is HomeItem.Video -> HomeVideoCard(
                    row.video, active = activeVideo == row.key, autoplay = prefs.autoplay, dataSaver = prefs.dataSaver,
                    ctl = vctl, onComments = onShortComments, onOpen = onOpenVideo
                )
            }
            if (row.key == shown.last().key && !feed.endReached && !feed.loading && !feed.pageFailed) {
                LaunchedEffect(row.key) { scope.launch { feed.loadPage(api, false) } }
            }
        }
        if (feed.loading) item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        }
    }
}

/** One icon with its number (♥ 1.2K, 💬 128). */
@Composable
internal fun PostAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, count: Int?, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp))
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(compactCount(count), color = if (tint == Ink) Ink.copy(.75f) else tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** A post like the new design: author line on top, the photo with rounded corners, the caption, then ♥ 💬 ➤ and the bookmark. */
@Composable
fun PostCard(p: Post, ctl: PostController, onComments: (Post) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val openProfile = LocalOpenProfile.current

    GlassPanel(Modifier.fillMaxWidth(), corner = 22, lift = true) {
        Column(Modifier.padding(12.dp)) {
            // author line: avatar, name, "2h ago" with a globe (public) or a people icon (followers only), Follow chip, menu
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).clickable { openProfile(p.username) }) { Avatar(p.avatarUrl, 42) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable { openProfile(p.username) }) {
                    Text(p.username, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeAgo(p.createdAt), color = Ink.copy(.55f), fontSize = 12.sp)
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            if (p.audience == "followers") Icons.Outlined.Group else Icons.Outlined.Public,
                            if (p.audience == "followers") tr("followers_only") else tr("public"),
                            tint = Ink.copy(.55f), modifier = Modifier.size(13.dp)
                        )
                    }
                }
                when (p.followStatus) {
                    "self" -> {}
                    "none" -> SmallChip("+ " + tr("follow"), { ctl.toggleFollow(p) })
                    "pending" -> SmallChip(tr("requested"), { ctl.toggleFollow(p) }, active = false)
                    else -> SmallChip(tr("following"), { ctl.toggleFollow(p) }, active = false)
                }
                Box {
                    Icon(
                        Icons.Filled.MoreHoriz, tr("a11y_more"), tint = Ink.copy(.7f),
                        modifier = Modifier.padding(start = 4.dp).size(36.dp).clip(CircleShape).clickable { menu = true }.padding(6.dp)
                    )
                    DropdownMenu(menu, { menu = false }) {
                        if (p.mine) DropdownMenuItem(text = { Text(tr("delete_post")) }, onClick = { menu = false; ctl.delete(p) })
                        else {
                            if (ctl.feedControls) {
                                DropdownMenuItem(text = { Text(tr("not_interested")) }, onClick = { menu = false; ctl.notInterested(p) })
                                DropdownMenuItem(text = { Text(tr("hide_creator")) }, onClick = { menu = false; ctl.hideCreator(p) })
                            }
                            listOf("Spam", "Harassment", "Inappropriate content").forEach { r ->
                                DropdownMenuItem(text = { Text(trf("report_fmt", reasonLabel(r))) }, onClick = { menu = false; ctl.report(p, r) })
                            }
                        }
                    }
                }
            }

            // Step 84 (plan v5.0.0 section 6): the caption sits ABOVE the photo like a familiar social post, a few lines and "See more" for a long one
            if (p.caption.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                ExpandableCaption(p.caption, "p${p.id}")
            }

            // the photo
            if (p.imageUrl != null) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(16.dp)).background(FieldFill)) {
                    AsyncImage(p.imageUrl, p.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }

            // ♥ 1.2K   💬 128   ➤                                  🔖
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PostAction(
                    if (p.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, tr("a11y_heart"),
                    p.likeCount, if (p.liked) Accent else Ink
                ) { ctl.toggleLike(p) }
                PostAction(Icons.Outlined.ChatBubbleOutline, tr("comments"), p.commentCount, Ink) { onComments(p) }
                PostAction(Icons.Outlined.Send, tr("a11y_share"), null, Ink) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${p.username} on Sociax: ${p.caption}")
                    }
                    ctl.shared(p)
                    context.startActivity(Intent.createChooser(send, "Share"))
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (p.saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, tr("a11y_save"),
                    tint = if (p.saved) Accent else Ink,
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { ctl.toggleSave(p) }.padding(6.dp)
                )
            }
            // emoji reactions (😮 😂 😢): small chips under the actions
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                REACTION_KINDS.forEach { (kind, emoji) ->
                    val r = p.reactions[kind] ?: com.sociax.app.data.React()
                    EmojiChip(emoji, r.count, r.mine) { ctl.toggleReaction(p, kind) }
                }
            }
        }
    }
}
