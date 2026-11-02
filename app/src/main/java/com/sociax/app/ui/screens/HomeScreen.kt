package com.sociax.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.sociax.app.data.REACTION_KINDS
import com.sociax.app.data.StoryGroup
import com.sociax.app.data.timeAgo
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
            try { api.recFeedback("hide_creator", "post", p.id); posts.removeAll { it.username == p.username } } catch (e: Exception) { fail(e) }
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
                posts.indices.forEach { i -> if (posts[i].username == p.username) posts[i] = posts[i].copy(followStatus = status) }
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
    api: ApiClient, refreshKey: Int, onCreate: () -> Unit, onSearch: () -> Unit,
    onNotifications: () -> Unit, onMessages: () -> Unit,
    onAddStory: () -> Unit, onOpenStory: (List<StoryGroup>, Int) -> Unit,
    onComments: (Post) -> Unit, notifUnread: Int = 0
) {
    val scope = rememberCoroutineScope()
    val posts = remember { mutableStateListOf<Post>() }
    var tab by remember { mutableStateOf("for_you") }
    var loading by remember { mutableStateOf(true) }
    var endReached by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var storyGroups by remember { mutableStateOf<List<StoryGroup>>(emptyList()) }
    val ctl = remember { PostController(api, scope, posts) { notice = it }.also { it.feedControls = true } }
    // Step 40: For You uses the ranked list (a cursor per refresh). If the server answers newest-first instead, paging continues with "before".
    var cursor by remember { mutableStateOf<String?>(null) }
    var rankedMode by remember { mutableStateOf(false) }
    var pageFailed by remember { mutableStateOf(false) }      // a failed "next page" must not retry in a tight loop; "Try again" restarts

    suspend fun loadPage(reset: Boolean) {
        loading = true
        try {
            if (reset) { cursor = null; rankedMode = tab == "for_you" }
            val page: List<Post>
            val last: Boolean
            if (rankedMode) {
                val r = api.feedForYou(if (reset) null else cursor)
                cursor = r.nextCursor
                if (!r.ranked) rankedMode = false
                page = r.items
                last = if (r.ranked) r.nextCursor == null else r.items.size < 20
            } else {
                page = api.feed(tab, if (reset) null else posts.lastOrNull()?.id)
                last = page.size < 20
            }
            if (reset) { posts.clear(); endReached = false }
            posts.addAll(page.filter { n -> posts.none { it.id == n.id } })
            if (last) endReached = true
            if (reset) notice = null
            pageFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            notice = e.message; pageFailed = true
        } catch (e: Exception) {
            notice = "Cannot reach the Sociax server. Is the backend running?"; pageFailed = true
        }
        loading = false
    }

    LaunchedEffect(tab, refreshKey, reload) { loadPage(true) }

    // Story rings: reloaded together with the feed (e.g. after watching or sharing a story). A failure just hides the rings.
    LaunchedEffect(refreshKey, reload) {
        try { storyGroups = api.stories() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
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
        item { StoriesRow(storyGroups, onAddStory) { onOpenStory(storyGroups, it) } }
        item { // For you / Following as two chips, refresh at the end
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(tr("for_you"), { tab = "for_you" }, active = tab == "for_you")
                SmallChip(tr("following"), { tab = "following" }, active = tab == "following")
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.Refresh, tr("a11y_refresh"), tint = Ink.copy(.7f),
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { reload++ }.padding(7.dp)
                )
            }
        }
        notice?.let { item {   // Step 35: a feed failure always offers "Try again" (never a silent empty feed)
            Column {
                Text(it, color = ErrorInk, fontSize = 13.sp)
                Text(tr("try_again"), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable { notice = null; reload++ }
                        .padding(horizontal = 4.dp, vertical = 6.dp))
            }
        } }
        if (!loading && posts.isEmpty() && notice == null) item {
            Text(
                if (tab == "following") tr("empty_following") else tr("empty_foryou"),
                color = Ink.copy(.7f), modifier = Modifier.padding(24.dp)
            )
        }
        items(posts, key = { it.id }) { p ->
            LaunchedEffect(p.id) { delay(1000); ctl.seen(p) }      // Step 40: counts only after 1 s really on screen (pre-loaded items do not count)
            PostCard(p, ctl, onComments)
            if (p.id == posts.last().id && !endReached && !loading && !pageFailed) {
                LaunchedEffect(p.id) { scope.launch { loadPage(false) } }
            }
        }
        if (loading) item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        }
    }
}

/** One icon with its number (♥ 1.2K, 💬 128). */
@Composable
private fun PostAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, count: Int?, tint: Color, onClick: () -> Unit) {
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
    var expanded by remember(p.id) { mutableStateOf(false) }
    var captionCut by remember(p.id) { mutableStateOf(false) }
    val openProfile = LocalOpenProfile.current

    GlassPanel(Modifier.fillMaxWidth(), corner = 22) {
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

            // the photo
            if (p.imageUrl != null) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(16.dp)).background(FieldFill)) {
                    AsyncImage(p.imageUrl, p.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }

            // the caption: two lines, then "more" (tap again for "less")
            if (p.caption.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    p.caption, color = Ink, fontSize = 14.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) captionCut = it.hasVisualOverflow }
                )
                if (captionCut || expanded) {
                    Text(
                        if (expanded) tr("show_less") else tr("read_more"),
                        color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
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
