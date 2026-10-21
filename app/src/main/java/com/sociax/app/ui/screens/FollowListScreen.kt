package com.sociax.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Step 20 (plan section 4): tap "Followers" or "Following" on a profile to see the people.
// The server decides what may be shown (blocked people, private accounts); this screen only displays the answer.

/** Followers / Following of one account, with a switch between the two and "Load more" after 50 people. */
@Composable
fun FollowListScreen(api: ApiClient, username: String, startKind: String, onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    var kind by remember { mutableStateOf(startKind) }
    var users by remember { mutableStateOf<List<BlockedUser>>(emptyList()) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // (re)load the first page whenever the switch changes
    LaunchedEffect(username, kind) {
        loading = true; error = null; users = emptyList(); more = false
        try { val (list, hasMore) = api.followList(username, kind); users = list; more = hasMore }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        TopBar(username, onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            PillButton(tr("followers"), { if (kind != "followers") kind = "followers" }, filled = kind == "followers")
            PillButton(tr("following"), { if (kind != "following") kind = "following" }, filled = kind == "following")
        }
        error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (loading) CircularProgressIndicator(color = Teal)
        else if (users.isEmpty() && error == null)
            Text(tr(if (kind == "followers") "no_followers_yet" else "no_following_yet"), color = Color.White.copy(.7f))
        val scope = rememberCoroutineScope()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(users, key = { it.username }) { u ->
                GlassPanel(Modifier.fillMaxWidth().clickable { onOpenUser(u.username) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.avatarUrl, 44)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.username, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(u.displayName, color = Color.White.copy(.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
            if (more) item {
                PillButton(tr("load_more"), {
                    val k = kind; val start = users.size
                    scope.launch {
                        try {
                            val (list, hasMore) = api.followList(username, k, start)
                            if (k == kind) { users = users + list.filter { n -> users.none { it.username == n.username } }; more = hasMore }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                    }
                }, filled = false)
            }
        }
    }
}
