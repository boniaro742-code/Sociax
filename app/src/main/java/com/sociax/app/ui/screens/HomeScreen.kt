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
        // the floating tab bar sits on top of the page, so the last post must be able to scroll above it
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp + bottomBarClearance()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { // header like the reference: white S + "Sociax" on the left, three round frosted buttons on the right
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SociaxMark(Modifier.weight(1f))
                GlassIconButton(Icons.Filled.Add, tr("a11y_new_post"), onCreate); Spacer(Modifier.width(8.dp))
                Box {   // Step 27: the bell opens the notification list; a small dot shows unread ones
                    GlassIconButton(Icons.Outlined.Notifications, tr("a11y_notifications"), onNotifications)
                    if (notifUnread > 0) Box(Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(Teal))
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Outlined.ChatBubbleOutline, tr("a11y_messages"), onMessages)
            }
        }
        item { AlarmPrompt() }      // Step 33: optional daily-alarm prompt (Yes / No / X); shows nothing once answered
        item { StoriesRow(storyGroups, onAddStory) { onOpenStory(storyGroups, it) } }
        item { // For you / Following: dark inset pill with a soft-white selected part, exactly like the reference
            InsetPill(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(4.dp)) {
                    SegTab(tr("for_you"), tab == "for_you", Modifier.weight(1f)) { tab = "for_you" }
                    SegTab(tr("following"), tab == "following", Modifier.weight(1f)) { tab = "following" }
                }
            }
        }
        item { // One unified search field (plan section 4): slim inset pill, same family as the switch above
            InsetPill(Modifier.fillMaxWidth().clickable(onClick = onSearch)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = Color.White.copy(.7f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("search_hint"), color = Color.White.copy(.6f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Filled.Refresh, "Refresh", tint = Color.White.copy(.8f),
                        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(50)).clickable { reload++ }
                    )
                }
            }
        }
        notice?.let { item {   // Step 35: a feed failure always offers "Try again" (never a silent empty feed)
            Column {
                Text(it, color = Color(0xFFFFB4A8), fontSize = 13.sp)
                Text(tr("try_again"), color = Teal, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable { notice = null; reload++ }
                        .padding(horizontal = 4.dp, vertical = 6.dp))
            }
        } }
        if (!loading && posts.isEmpty() && notice == null) item {
            Text(
                if (tab == "following") tr("empty_following") else tr("empty_foryou"),
                color = Color.White.copy(.7f), modifier = Modifier.padding(24.dp)
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
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Teal) }
        }
    }
}

@Composable
private fun SegTab(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (active) PaperWhite else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (active) Navy else Color.White.copy(.85f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun PostCard(p: Post, ctl: PostController, onComments: (Post) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }

    GlassPanel(Modifier.fillMaxWidth().height(430.dp), corner = 28) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A6FD8), Color(0xFF0F2150)))))
        p.imageUrl?.let { AsyncImage(it, p.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // dark scrim so the caption and chips stay readable on any photo
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0.55f to Color.Transparent, 1f to Color(0x990B1220))))

        // author bar: frosted pill with a ringed avatar, name, small grey line and the soft-white Follow pill (reference)
        Row(
            Modifier.padding(12.dp).fillMaxWidth().align(Alignment.TopCenter)
                .clip(RoundedCornerShape(50)).background(ControlFill)
                .border(1.dp, ControlBorder, RoundedCornerShape(50)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val openProfile = LocalOpenProfile.current
            Box(Modifier.border(1.5.dp, Color.White.copy(.7f), CircleShape).padding(2.dp).clickable { openProfile(p.username) }) { Avatar(p.avatarUrl, 38) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { openProfile(p.username) }) {
                Text(p.username, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(timeAgo(p.createdAt) + if (p.audience == "followers") " · Followers" else "",
                    color = Color.White.copy(.7f), fontSize = 11.sp)
            }
            when (p.followStatus) {
                "self" -> {}
                "none" -> PillButton("+ " + tr("follow"), { ctl.toggleFollow(p) })
                "pending" -> PillButton(tr("requested"), { ctl.toggleFollow(p) }, filled = false)
                else -> PillButton(tr("following"), { ctl.toggleFollow(p) }, filled = false)
            }
            Box {
                GlassIconButton(Icons.Filled.MoreVert, tr("a11y_more"), { menu = true }, size = 36, onMedia = true)
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

        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            if (p.caption.isNotBlank()) {
                Text(p.caption, color = Color.White, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
            }
            // Reaction row like the reference: ♥ 😮 😂 😢
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ReactionChip(if (p.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    p.likeCount.toString(), if (p.liked) LikeRed else Color.White, onMedia = true) { ctl.toggleLike(p) }
                REACTION_KINDS.forEach { (kind, emoji) ->
                    val r = p.reactions[kind] ?: com.sociax.app.data.React()
                    EmojiChip(emoji, r.count, r.mine) { ctl.toggleReaction(p, kind) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ReactionChip(Icons.Outlined.ChatBubbleOutline, p.commentCount.toString(), onMedia = true) { onComments(p) }
                GlassIconButton(Icons.Outlined.Send, tr("a11y_share"), {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${p.username} on Sociax: ${p.caption}")
                    }
                    ctl.shared(p)
                    context.startActivity(Intent.createChooser(send, "Share"))
                }, size = 34, onMedia = true)
                Spacer(Modifier.weight(1f))
                GlassIconButton(if (p.saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Save", { ctl.toggleSave(p) }, size = 36, onMedia = true)
            }
        }
    }
}
