package com.sociax.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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

/** Photo thumbnails, three per row (used on other people's profiles and on my own Bio page). */
@Composable
fun PostsGrid(posts: List<Post>, onOpen: (Post) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        posts.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { p ->
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(Color(0x22FFFFFF)).clickable { onOpen(p) }) {
                        p.imageUrl?.let { AsyncImage(it, p.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
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
        TopBar(username, onBack)
        error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (loading) CircularProgressIndicator(color = Teal)
        val p = profile
        if (p != null) {
            Avatar(p.avatarUrl, 96)
            Spacer(Modifier.height(10.dp))
            Text(p.displayName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(p.username, color = Teal)
            if (p.followsMe && !p.isMe) Text(tr("follows_you"), color = Color.White.copy(.6f), fontSize = 12.sp)
            if (p.bio.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(p.bio, color = Color.White.copy(.8f), textAlign = TextAlign.Center) }
            Spacer(Modifier.height(14.dp))
            GlassPanel(Modifier.fillMaxWidth()) {
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
                        Box {
                            GlassIconButton(Icons.Filled.MoreVert, "More", { menu = true })
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(tr("block_user") + " " + p.username) },
                                    onClick = { menu = false; act { api.setBlock(p.username, true) } })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            when {
                p.iBlocked -> Text(tr("you_blocked"), color = Color.White.copy(.7f), textAlign = TextAlign.Center)
                !p.canViewPosts -> Text(tr("private_account_note"), color = Color.White.copy(.7f), textAlign = TextAlign.Center)
                posts.isEmpty() -> Text(tr("no_posts_yet"), color = Color.White.copy(.7f))
                else -> PostsGrid(posts, onOpenPost)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileStat(v: String, l: String, modifier: Modifier = Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(v, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(l, color = Color.White.copy(.7f), fontSize = 12.sp)
}
