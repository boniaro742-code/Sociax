package com.sociax.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Step 27 (plan section 9: Notifications). Six switches in Settings, and a list under the bell on Home.
// The server decides what is written (a switched-off group writes nothing); these screens only show it.

/** Settings > Notifications: one switch per group (messages, follows, likes/comments, group activity, short video, AI). */
@Composable
fun NotificationsSection(me: Me?, api: ApiClient?, onMeChanged: (Me) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Section(tr("notifications")) {
        if (me == null || api == null) {
            Text(tr("notifications_body"), color = Color.White)
        } else {
            NOTIF_GROUPS.forEach { g ->
                var on by remember(me.notifications[g]) { mutableStateOf(me.notifications[g] ?: true) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("notif_g_$g"), color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(tr("notif_g_${g}_sub"), color = Color.White.copy(.6f), fontSize = 12.sp)
                    }
                    Switch(
                        on,
                        { v ->
                            val old = on
                            on = v
                            scope.launch {
                                try { onMeChanged(api.updateNotifications(g, v)); error = null }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { on = old; error = errText(e) }
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Navy)
                    )
                }
            }
            error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
            Text(tr("notif_note"), color = Color.White.copy(.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            PushSwitch(api)                                                              // Step 46
        }
    }
}

/** Step 46: push (Firebase Cloud Messaging) for THIS phone. Opt-in; the server uses the six switches above to decide what to send. */
@Composable
private fun PushSwitch(api: ApiClient) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val available = remember { PushRegistrar.available() }
    var on by remember { mutableStateOf(prefs.pushEnabled && PushRegistrar.permissionGranted(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun turnOn() {
        busy = true
        scope.launch {
            try { PushRegistrar.enable(ctx, api); on = true; msg = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { on = false; msg = errText(e) }
            busy = false
        }
    }
    val permissionNeeded = tr("push_permission_needed")      // tr() is @Composable: read it HERE, not inside the result callback
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) turnOn() else { on = false; msg = permissionNeeded }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(tr("push_title"), color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(tr("push_sub"), color = Color.White.copy(.6f), fontSize = 12.sp)
        }
        Switch(
            on,
            { v ->
                if (v) {
                    if (PushRegistrar.permissionGranted(ctx)) turnOn() else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    on = false; msg = null
                    scope.launch { PushRegistrar.disable(ctx, api) }
                }
            },
            enabled = available && !busy,
            colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Navy)
        )
    }
    if (!available) Text(tr("push_unavailable"), color = Color.White.copy(.6f), fontSize = 12.sp)
    msg?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
}

@Composable
private fun notifText(n: NotificationItem): String {
    val who = n.displayName?.takeIf { it.isNotBlank() } ?: n.username.orEmpty()
    return when (n.type) {
        "message" -> if (n.count > 1) trf("notif_messages_n", who, n.count) else trf("notif_message", who)
        "ai_reply" -> tr("notif_ai_reply")
        else -> trf("notif_${n.type}", who)
    }
}

/** The bell on Home: everything that happened (newest first). Unread ones have a dot; opening the list marks them as read. */
@Composable
fun NotificationsScreen(
    api: ApiClient, onBack: () -> Unit, onRequests: () -> Unit,
    onOpenUser: (String) -> Unit, onOpenChat: () -> Unit
) {
    val entries = remember { mutableStateListOf<NotificationItem>() }
    var nextBefore by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val page = api.notifications()
            entries.clear(); entries.addAll(page.items); nextBefore = page.nextBefore
            api.markNotificationsRead()          // the dots stay visible on this screen; the list is read on the server now
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        TopBar(tr("notifications"), onBack)
        NavRow(tr("follow_requests"), onRequests)
        error?.let { Text(it, color = Color(0xFFFF8A8A)) }
        if (loading) CircularProgressIndicator(color = Teal)
        else if (entries.isEmpty()) Text(tr("notif_empty"), color = Color.White.copy(.7f), modifier = Modifier.padding(top = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
            items(entries, key = { it.id }) { n ->
                GlassPanel(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable {
                        when (n.category) {
                            "messages", "group_activity", "ai" -> onOpenChat()
                            else -> n.username?.let(onOpenUser)
                        }
                    }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (n.username == null) {   // the AI
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Teal.copy(.25f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.AutoAwesome, null, tint = Teal)
                            }
                        } else Avatar(n.avatarUrl, 44)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(notifText(n), color = Color.White, fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold)
                            Text(timeAgo(n.createdAt), color = Color.White.copy(.6f), fontSize = 12.sp)
                        }
                        if (!n.read) Box(Modifier.size(10.dp).clip(CircleShape).background(Teal))
                    }
                }
            }
            if (nextBefore != null) item {
                PillButton(tr("load_more"), {
                    scope.launch {
                        try {
                            val page = api.notifications(nextBefore)
                            entries.addAll(page.items); nextBefore = page.nextBefore
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                    }
                }, filled = false)
            }
        }
    }
}
