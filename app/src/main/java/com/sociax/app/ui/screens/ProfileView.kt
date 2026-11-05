package com.sociax.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Anything (a post author, a search result) can open someone's profile through this. Set once in MainActivity. */
val LocalOpenProfile = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Photo thumbnails, three per row with thin gaps (used on other people's profiles and on my own Profile page). */
@Composable
fun PostsGrid(posts: List<Post>, onOpen: (Post) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        posts.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { p ->
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(FieldFill).clickable { onOpen(p) }) {
                        p.imageUrl?.let { AsyncImage(it, p.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Plan v3.10.2 (Bio/Profile, appearance only): a CENTERED "Profile" heading with the back control on the left and the existing overflow
 * control on the right. Both side slots are 44 dp wide so the title stays exactly in the middle, with or without a control.
 */
@Composable
internal fun ProfileHeading(title: String, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) GlassIconButton(Icons.Filled.ArrowBack, tr("a11y_back"), onBack, size = 44)
        }
        Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Box(Modifier.size(44.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

/**
 * The tab row above the media grid. The app has ONE kind of profile media (photo posts), so the row honestly shows only "Posts" (selected,
 * purple, with the underline); "Videos" and "Tagged" are not shown because nothing in the app or on the server backs them.
 */
@Composable
internal fun ProfilePostsTab() {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GridView, null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("posts"), color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline)) {
            Box(Modifier.fillMaxWidth(0.3f).height(2.dp).align(Alignment.CenterStart).background(Accent))
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun UserProfileScreen(api: ApiClient, username: String, onBack: () -> Unit, onOpenPost: (Post) -> Unit, onChat: (ChatSummary) -> Unit,
                      onOpenList: (username: String, kind: String) -> Unit = { _, _ -> }) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            val p = api.userProfile(username)
            profile = p
            posts = if (p.canViewPosts) api.userPosts(username) else emptyList()
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        loading = false
    }
    LaunchedEffect(username) { load() }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block(); load() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileHeading(tr("tab_bio"), onBack) {
            // the existing overflow menu (Block), moved from the button row to the heading; same menu, same action
            val pm = profile
            if (pm != null && !pm.isMe && !pm.iBlocked) Box {
                GlassIconButton(Icons.Filled.MoreVert, tr("a11y_more"), { menu = true }, size = 44)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("block_user") + " " + pm.username) },
                        onClick = { menu = false; act { api.setBlock(pm.username, true) } })
                }
            }
        }
        error?.let { Text(it, color = ErrorInk, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (loading) CircularProgressIndicator(color = Accent)
        val p = profile
        if (p != null) {
            RingedAvatar(p.avatarUrl, 92)
            Spacer(Modifier.height(10.dp))
            Text(p.displayName, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(p.username, color = Ink.copy(.55f), fontSize = 14.sp)
            if (p.followsMe && !p.isMe) Text(tr("follows_you"), color = Ink.copy(.6f), fontSize = 12.sp)
            if (p.bio.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(p.bio, color = Ink.copy(.8f), textAlign = TextAlign.Center) }
            Spacer(Modifier.height(14.dp))
            GlassPanel(Modifier.fillMaxWidth(), fill = LavenderFill) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ProfileStat(p.posts?.toString() ?: "-", tr("posts"))
                    // the lists open only when the server will show them (not blocked, not a private account I do not follow)
                    val listOpen = !p.iBlocked && (p.isMe || p.canViewPosts)
                    ProfileStat(p.followers.toString(), tr("followers"),
                        if (listOpen) Modifier.clickable { onOpenList(p.username, "followers") } else Modifier)
                    ProfileStat(p.following.toString(), tr("following"),
                        if (listOpen) Modifier.clickable { onOpenList(p.username, "following") } else Modifier)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (!p.isMe) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p.iBlocked) {
                        PillButton(tr("unblock_user"), { act { api.setBlock(p.username, false) } })
                    } else {
                        val label = when (p.followStatus) { "accepted" -> tr("following"); "pending" -> tr("requested"); else -> "+ " + tr("follow") }
                        PillButton(label, { act { api.setFollow(p.username, p.followStatus == "none") } }, filled = p.followStatus == "none")
                        if (p.canMessage) PillButton(tr("message_btn"), {
                            scope.launch {
                                try { onChat(api.startDm(p.username)) }
                                catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                            }
                        }, filled = false)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            when {
                p.iBlocked -> Text(tr("you_blocked"), color = Ink.copy(.7f), textAlign = TextAlign.Center)
                !p.canViewPosts -> Text(tr("private_account_note"), color = Ink.copy(.7f), textAlign = TextAlign.Center)
                posts.isEmpty() -> Text(tr("no_posts_yet"), color = Ink.copy(.7f))
                else -> { ProfilePostsTab(); PostsGrid(posts, onOpenPost) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileStat(v: String, l: String, modifier: Modifier = Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(v, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
    Text(l, color = Ink.copy(.55f), fontSize = 13.sp)
}
