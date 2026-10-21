package com.sociax.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Step 17 (plan sections 9 + 11): Privacy & Security settings, blocked-users list, saved-posts list, account deletion.
// All rules live on the server; these screens only show what the server answers.

/** A tappable row with a chevron (Settings > Privacy, Account). */
@Composable
internal fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = Color.White.copy(.7f))
    }
}

/** A titled single-choice list with a check mark on the selected option (Step 19). */
@Composable
internal fun ChoiceGroup(title: String, subtitle: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Color.White.copy(.6f), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (value != selected) onSelect(value) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = Color.White, modifier = Modifier.weight(1f))
                if (value == selected) Icon(Icons.Filled.Check, null, tint = Teal)
            }
        }
    }
}

/** Settings > Privacy & Security: private account switch + links to the blocked-users and saved-posts lists. */
@Composable
fun PrivacySection(me: Me?, api: ApiClient?, onMeChanged: (Me) -> Unit, onBlocked: () -> Unit, onSaved: () -> Unit, onSavedShorts: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var isPrivate by remember(me?.isPrivate) { mutableStateOf(me?.isPrivate ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    Section(tr("privacy")) {
        if (me != null && api != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("private_acc"), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(tr("private_acc_sub"), color = Color.White.copy(.6f), fontSize = 12.sp)
                }
                Switch(
                    isPrivate,
                    { v ->
                        val old = isPrivate
                        isPrivate = v
                        scope.launch {
                            try { onMeChanged(api.updateProfile(me.displayName ?: "", me.bio, v)); error = null }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { isPrivate = old; error = errText(e) }
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Navy)
                )
            }
            error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
            // Step 22: "Follow me first" - messages from people who do not follow me wait (hidden, not read) until they follow
            var followFirst by remember(me.followFirst) { mutableStateOf(me.followFirst) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr("follow_first_setting"), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(tr("follow_first_setting_sub"), color = Color.White.copy(.6f), fontSize = 12.sp)
                }
                Switch(
                    followFirst,
                    { v ->
                        val old = followFirst
                        followFirst = v
                        scope.launch {
                            try { onMeChanged(api.updatePrivacy(followFirst = v)); error = null }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { followFirst = old; error = errText(e) }
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Navy)
                )
            }
            // Step 25: Online / last seen. Reciprocal: if it is off, I do not see other people's status either.
            var showLastSeen by remember(me.showLastSeen) { mutableStateOf(me.showLastSeen) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr("last_seen_setting"), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(tr("last_seen_setting_sub"), color = Color.White.copy(.6f), fontSize = 12.sp)
                }
                Switch(
                    showLastSeen,
                    { v ->
                        val old = showLastSeen
                        showLastSeen = v
                        scope.launch {
                            try { onMeChanged(api.updatePrivacy(showLastSeen = v)); error = null }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { showLastSeen = old; error = errText(e) }
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Navy)
                )
            }
            // Step 19: who may start a chat with me / add me to a group (checked on the server)
            ChoiceGroup(
                tr("who_can_message"), tr("who_can_message_sub"),
                listOf("everyone" to tr("msg_everyone"), "followers" to tr("msg_followers"), "nobody" to tr("msg_nobody")),
                me.whoCanMessage
            ) { v -> scope.launch {
                try { onMeChanged(api.updatePrivacy(whoCanMessage = v)); error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = errText(e) }
            } }
            // Step 19: audience that is pre-selected when I publish a post / short video
            ChoiceGroup(
                tr("default_audience"), tr("default_audience_sub"),
                listOf("public" to tr("public"), "followers" to tr("followers_only")),
                me.defaultAudience
            ) { v -> scope.launch {
                try { onMeChanged(api.updatePrivacy(defaultAudience = v)); error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = errText(e) }
            } }
        }
        NavRow(tr("blocked_users"), onBlocked)
        NavRow(tr("saved_posts"), onSaved)
        NavRow(tr("saved_shorts"), onSavedShorts)
    }
}

/** Settings > Account > Delete account. Asks once more, then the server removes everything. */
@Composable
fun DeleteAccountRow(api: ApiClient?, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text(tr("delete_account_note"), color = Color.White.copy(.6f), fontSize = 12.sp)
    error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
    PillButton(tr("delete_account"), { if (api != null && !busy) ask = true }, filled = false)
    if (ask) {
        AlertDialog(
            onDismissRequest = { if (!busy) ask = false },
            containerColor = Color(0xFF121C2E),
            title = { Text(tr("delete_confirm_title"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(tr("delete_confirm_body"), color = Color.White.copy(.8f)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try { api?.deleteAccount(); ask = false; onDeleted() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = errText(e); ask = false }
                        busy = false
                    }
                }) { Text(tr("delete_forever"), color = Color(0xFFFF8A8A), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { ask = false }) { Text(tr("cancel"), color = Color.White) } }
        )
    }
}

/** People I blocked, each with an Unblock button. */
@Composable
fun BlockedUsersScreen(api: ApiClient, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<BlockedUser>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try { users = api.blockedUsers() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        TopBar(tr("blocked_users"), onBack)
        Text(tr("blocked_note"), color = Color.White.copy(.7f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        error?.let { Text(it, color = Color(0xFFFF8A8A)) }
        if (loading) CircularProgressIndicator(color = Teal)
        else if (users.isEmpty()) Text(tr("no_blocked"), color = Color.White.copy(.7f))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(users, key = { it.username }) { u ->
                GlassPanel(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.avatarUrl, 44)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.username, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(u.displayName, color = Color.White.copy(.7f), fontSize = 12.sp)
                        }
                        PillButton(tr("unblock_user"), {
                            scope.launch {
                                try { api.setBlock(u.username, false); users = users - u }
                                catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                            }
                        }, filled = false)
                    }
                }
            }
        }
    }
}

/** Posts I saved (bookmark), as a photo grid. Posts I may no longer see are filtered out by the server. */
@Composable
fun SavedPostsScreen(api: ApiClient, onBack: () -> Unit, onOpenPost: (Post) -> Unit) {
    val scope = rememberCoroutineScope()
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pageSize = 30

    LaunchedEffect(Unit) {
        try { posts = api.savedPosts(); more = posts.size >= pageSize }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TopBar(tr("saved_posts"), onBack)
        error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (loading) CircularProgressIndicator(color = Teal)
        else if (posts.isEmpty()) Text(tr("no_saved"), color = Color.White.copy(.7f))
        else {
            PostsGrid(posts, onOpenPost)
            if (more) {
                Spacer(Modifier.height(12.dp))
                PillButton(tr("load_more"), {
                    scope.launch {
                        try {
                            val next = api.savedPosts(before = posts.last().id)
                            posts = posts + next; more = next.size >= pageSize
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                    }
                }, filled = false)
            }
            Spacer(Modifier.height(10.dp))
            Text(tr("saved_note"), color = Color.White.copy(.5f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}
