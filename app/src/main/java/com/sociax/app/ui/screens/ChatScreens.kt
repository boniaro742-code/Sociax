package com.sociax.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Send
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ErrorRed = Color(0xFFFF8A8A)

@Composable
private fun ChatAvatar(chat: ChatSummary, size: Int) {
    if (chat.isGroup) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF3A6BFF), Color(0xFF6A3DF0)))),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
        }
    } else Avatar(chat.avatarUrl, size)
}

// =====================================================================
// Chat tab: list of private chats and groups (updates every few seconds)
// =====================================================================
@Composable
fun ChatListScreen(api: ApiClient, onOpen: (ChatSummary) -> Unit, onNewChat: () -> Unit, onNewGroup: () -> Unit, onAi: () -> Unit) {
    var chats by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            try { chats = api.chats(); error = null }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
            loaded = true
            delay(6000)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("tab_chat"), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            GlassIconButton(Icons.Filled.GroupAdd, tr("new_group"), onNewGroup)
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Filled.Edit, tr("new_chat"), onNewChat)
        }
        Spacer(Modifier.height(12.dp))
        // Sociax AI: always first in the list (private, opt-in assistant)
        GlassPanel(Modifier.fillMaxWidth().clickable { onAi() }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF6A3DF0), Color(0xFF1FB6FF)))),
                    contentAlignment = Alignment.Center) { Text("AI", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("ai_title"), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(tr("ai_card_sub"), color = Color.White.copy(.65f), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (!loaded) CircularProgressIndicator(color = Teal)
        else if (chats.isEmpty() && error == null) {
            Text(tr("no_chats"), color = Color.White.copy(.7f))
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = bottomBarClearance())   // the floating tab bar covers the bottom of the page
        ) {
            items(chats, key = { it.id }) { c ->
                GlassPanel(Modifier.fillMaxWidth().clickable { onOpen(c) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(c, 48)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                when (c.lastKind) { null -> tr("no_messages_yet"); "ai" -> "AI: " + c.lastText; else -> c.lastText },
                                color = Color.White.copy(if (c.unread > 0) .95f else .65f), fontSize = 13.sp, maxLines = 1
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(timeAgo(c.lastAt), color = Color.White.copy(.55f), fontSize = 11.sp)
                            if (c.unread > 0) {
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.defaultMinSize(minWidth = 20.dp).height(20.dp).clip(CircleShape).background(Teal),
                                    contentAlignment = Alignment.Center) {
                                    Text(if (c.unread > 99) "99+" else c.unread.toString(), color = Navy, fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// One conversation (private or group)
// =====================================================================
@Composable
fun ConversationScreen(api: ApiClient, chat: ChatSummary, onBack: () -> Unit, onInfo: () -> Unit) {
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<ChatMessage>() }
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var followAsk by remember { mutableStateOf<String?>(null) }      // Step 22: @username who wants a follow first
    var presence by remember(chat.id) { mutableStateOf<Presence?>(null) }     // Step 25: online / last seen of the other person
    var peerReadId by remember(chat.id) { mutableStateOf<Long?>(null) }       // Step 43: newest message of mine the other person has seen (null = not shown)
    // Step 43: a message is only marked read while this screen is really on screen (not while the app is in the background / phone locked)
    val lifeOwner = LocalLifecycleOwner.current
    var onScreen by remember { mutableStateOf(true) }
    DisposableEffect(lifeOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_PAUSE) onScreen = false else if (ev == Lifecycle.Event.ON_RESUME) onScreen = true
        }
        lifeOwner.lifecycle.addObserver(obs)
        onDispose { lifeOwner.lifecycle.removeObserver(obs) }
    }
    val listState = rememberLazyListState()
    val aiNoPrivate = tr("ai_na_private"); val aiDisabled = tr("ai_disabled_group"); val aiNotConnected = tr("ai_not_connected"); val aiQuota = tr("ai_quota_group"); val aiError = tr("ai_error_group")

    fun merge(list: List<ChatMessage>) {
        val merged = (msgs.toList() + list).distinctBy { it.id }.sortedBy { it.id }
        if (merged.size != msgs.size) { msgs.clear(); msgs.addAll(merged) }
    }

    // first load, then check for new messages every few seconds while this page is open
    LaunchedEffect(chat.id) {
        var needsRead = true            // Step 43: something new has not been marked read yet (it waits while the app is not on screen)
        try {
            val first = api.messagesPage(chat.id, null)
            merge(first.messages); peerReadId = first.peerReadId
            if (onScreen) { api.markRead(chat.id); needsRead = false }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        loading = false
        while (true) {
            delay(2500)
            try {
                val page = api.messagesPage(chat.id, msgs.lastOrNull()?.id)
                peerReadId = page.peerReadId
                if (page.messages.isNotEmpty()) { merge(page.messages); needsRead = true }
                if (needsRead && onScreen) { api.markRead(chat.id); needsRead = false }
                error = null
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        }
    }
    LaunchedEffect(chat.id) {
        val peerName = chat.username
        if (chat.isGroup || peerName == null) return@LaunchedEffect
        while (true) {
            try { presence = api.presence(peerName) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { }
            delay(20000)
        }
    }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) {
            if (reduceMotion) listState.scrollToItem(msgs.size - 1) else listState.animateScrollToItem(msgs.size - 1)
        }
    }

    fun send(body: String, clearInput: Boolean) {
        val t = body.trim()
        if (t.isEmpty()) return
        scope.launch {
            try {
                val r = api.sendMessage(chat.id, t)
                merge(listOf(r.message))
                if (r.followPrompt != null) followAsk = r.followPrompt
                if (clearInput) text = ""
                error = null
                notice = when (r.ai) {
                    "private_chat" -> aiNoPrivate
                    "disabled" -> aiDisabled
                    "not_connected" -> aiNotConnected
                    "quota" -> aiQuota
                    "error" -> aiError
                    else -> null
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        }
    }

    // Step 22: "Follow me first" - Yes follows, No leaves the message waiting (it is never shown as read)
    followAsk?.let { who ->
        AlertDialog(
            onDismissRequest = { followAsk = null },
            containerColor = Color(0xFF121C2E),
            title = { Text(tr("follow_first_title"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(trf("follow_first_body", who), color = Color.White.copy(.8f)) },
            confirmButton = {
                TextButton(onClick = {
                    followAsk = null
                    scope.launch {
                        try {
                            if (api.setFollow(who, true) == "accepted") {
                                val fixed = msgs.map { if (it.held) it.copy(held = false) else it }
                                msgs.clear(); msgs.addAll(fixed)
                            }
                            notice = null; error = null
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                    }
                }) { Text(tr("yes"), color = Teal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { followAsk = null }) { Text(tr("no"), color = Color.White) } }
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.ArrowBack, tr("a11y_back"), onBack)
            Spacer(Modifier.width(10.dp))
            ChatAvatar(chat, 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(chat.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                Text(if (chat.isGroup) tr("private_group") else chat.username.orEmpty(), color = Color.White.copy(.6f), fontSize = 12.sp)
                presence?.let { p ->
                    Text(
                        if (p.online) tr("presence_online") else trf("presence_last_seen", timeAgo(p.lastSeenAt)),
                        color = if (p.online) Teal else Color.White.copy(.6f), fontSize = 11.sp
                    )
                }
            }
            if (chat.isGroup) GlassIconButton(Icons.Filled.Info, tr("group_info"), onInfo)
        }
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading) CircularProgressIndicator(color = Teal, modifier = Modifier.align(Alignment.Center))
            else if (msgs.isEmpty()) Text(tr("say_hello"), color = Color.White.copy(.6f), modifier = Modifier.align(Alignment.Center))
            else LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Step 43: "Seen" only under the NEWEST of my messages the other person has seen (one-to-one chats, both switches on)
                val seenId = peerReadId?.let { pr -> msgs.filter { it.mine && !it.held && it.id <= pr }.maxOfOrNull { it.id } }
                items(msgs, key = { it.id }) { Bubble(it, chat.isGroup, seen = it.id == seenId) }
            }
        }

        notice?.let { Text(it, color = Color(0xFFFFE08A), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        error?.let { Text(it, color = ErrorRed, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        Spacer(Modifier.height(4.dp))

        // composer like the reference: dark inset pill with the soft-white send button inside it + a frosted round heart beside it
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InsetPill(Modifier.weight(1f)) {
                Row(Modifier.padding(start = 6.dp, end = 5.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextField(
                        text, { text = it.take(2000) }, Modifier.weight(1f),
                        placeholder = { Text(tr("send_message"), color = Color.White.copy(.6f), fontSize = 14.sp) },
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Box(Modifier.size(42.dp).clip(CircleShape).background(PaperWhite).clickable { send(text, true) },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Send, tr("a11y_send"), tint = Navy, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(50.dp).clip(CircleShape).background(ControlFill).border(1.dp, ControlBorder, CircleShape)
                .clickable { send("\u2764\uFE0F", false) },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Favorite, tr("a11y_heart"), tint = LikeRed)
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, group: Boolean, seen: Boolean = false) {
    if (m.kind == "system") {
        Text(m.text, color = Color.White.copy(.6f), fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
        return
    }
    val mine = m.mine
    val fill = when {
        mine -> Modifier.background(Brush.linearGradient(listOf(Color(0xFF3A6BFF), Color(0xFF1FB6FF))))
        m.kind == "ai" -> Modifier.background(Brush.linearGradient(listOf(Color(0x556A3DF0), Color(0x553A6BFF))))
        else -> Modifier.background(Color(0x33FFFFFF))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp)).then(fill).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!mine && (m.kind == "ai" || group)) {
                Text(if (m.kind == "ai") "Sociax AI" else m.username.orEmpty(), color = if (m.kind == "ai") Color(0xFFC9B8FF) else Teal,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(m.text, color = Color.White, fontSize = 15.sp)
            Text(timeAgo(m.createdAt), color = Color.White.copy(.55f), fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
            if (m.held) Text(tr("follow_first_waiting"), color = Color.White.copy(.8f), fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
            else if (seen) Text(tr("seen_label"), color = Color.White.copy(.8f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.End))
        }
    }
}

// =====================================================================
// New private chat: find a person, then open the chat
// =====================================================================
@Composable
fun NewChatScreen(api: ApiClient, onBack: () -> Unit, onOpen: (ChatSummary) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<UserHit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { users = emptyList(); return@LaunchedEffect }
        delay(300)
        try { users = api.search(query.trim()).first; error = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(16.dp)) {
        TopBar(tr("new_chat"), onBack)
        GlassField(query, { query = it }, tr("search_user"))
        error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(users, key = { it.username }) { u ->
                GlassPanel(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                    scope.launch {
                        busy = true; error = null
                        try { onOpen(api.startDm(u.username)) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                        busy = false
                    }
                }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.avatarUrl, 44)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.username, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(u.displayName + if (u.isPrivate) " · " + tr("private_tag") else "", color = Color.White.copy(.7f), fontSize = 12.sp)
                        }
                        Icon(Icons.Filled.ChatBubble, tr("a11y_message"), tint = Teal)
                    }
                }
            }
        }
    }
}

// =====================================================================
// New group: name -> (optional) members -> summary -> explicit confirmation
// =====================================================================
@Composable
fun NewGroupScreen(api: ApiClient, onBack: () -> Unit, onCreated: (ChatSummary) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }            // 0 name, 1 members, 2 summary
    val errGroupName = tr("err_group_name")
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<UserHit>() }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<UserHit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(300)
        try { hits = api.search(query.trim()).first }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TopBar(tr("new_group"), { if (step > 0) step-- else onBack() })
        when (step) {
            0 -> {
                Text(tr("groups_private_note"),
                    color = Color.White.copy(.75f), fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                GlassField(name, { name = it.take(50) }, tr("group_name"))
                Spacer(Modifier.height(16.dp))
                Text(tr("add_members_now"), color = Teal, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(tr("yes_add_members"), { if (name.isBlank()) error = errGroupName else { error = null; step = 1 } })
                    PillButton(tr("not_now"), { if (name.isBlank()) error = errGroupName else { error = null; picked.clear(); step = 2 } }, filled = false)
                }
            }
            1 -> {
                GlassField(query, { query = it }, tr("search_user"))
                Spacer(Modifier.height(10.dp))
                if (picked.isNotEmpty()) {
                    Text(trf("selected_n", picked.size), color = Teal, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    picked.forEach { u ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(u.username, color = Color.White, modifier = Modifier.weight(1f))
                            PillButton(tr("remove"), { picked.remove(u) }, filled = false)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                hits.filter { h -> picked.none { it.username == h.username } }.forEach { u ->
                    GlassPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { picked.add(u) }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u.avatarUrl, 40)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.username, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(u.displayName, color = Color.White.copy(.7f), fontSize = 12.sp)
                            }
                            Icon(Icons.Filled.Add, tr("a11y_add"), tint = Teal)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                PillButton(tr("continue"), { step = 2 }, Modifier.fillMaxWidth())
            }
            else -> {
                Text(tr("check_before"), color = Teal, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name.trim(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (picked.isEmpty()) tr("members_just_you")
                            else tr("members_you_plus") + picked.joinToString(", ") { it.username },
                            color = Color.White.copy(.85f), fontSize = 14.sp
                        )
                        Text(tr("owner_note"),
                            color = Color.White.copy(.65f), fontSize = 12.sp)
                    }
                }
                error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(16.dp))
                if (busy) CircularProgressIndicator(color = Teal, modifier = Modifier.align(Alignment.CenterHorizontally))
                else {
                    PillButton(tr("confirm_create"), {
                        scope.launch {
                            busy = true; error = null
                            try { onCreated(api.createGroup(name.trim(), picked.map { it.username }, confirmed = true)) }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                            busy = false
                        }
                    }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    PillButton(tr("back"), { step = if (picked.isEmpty()) 0 else 1 }, Modifier.fillMaxWidth(), filled = false)
                }
            }
        }
        if (step != 2) error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
    }
}

// =====================================================================
// Group info: members, roles, AI Join
// =====================================================================
@Composable
fun GroupInfoScreen(api: ApiClient, chat: ChatSummary, myUsername: String?, onBack: () -> Unit, onLeft: () -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<GroupInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }     // ai_on | ai_off | leave | delete
    var addQuery by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<UserHit>>(emptyList()) }

    suspend fun reload() {
        try { info = api.groupInfo(chat.id); error = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
    }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block(); reload() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
        }
    }
    LaunchedEffect(chat.id) { reload() }
    LaunchedEffect(addQuery) {
        if (addQuery.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(300)
        try { hits = api.search(addQuery.trim()).first }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TopBar(tr("group_info"), onBack)
        val g = info
        if (g == null) {
            if (error == null) CircularProgressIndicator(color = Teal)
        } else {
            val manage = g.myRole == "owner" || g.myRole == "admin"
            Text(g.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(trf("members_count_note", g.members.size), color = Color.White.copy(.65f), fontSize = 12.sp)

            // ---- AI Join ----
            Spacer(Modifier.height(14.dp))
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("ai_join"), color = Teal, fontWeight = FontWeight.Bold)
                            Text(if (g.aiEnabled) tr("ai_is_on") else tr("ai_is_off"), color = Color.White, fontSize = 14.sp)
                        }
                        if (manage) Switch(
                            g.aiEnabled, { pending = if (it) "ai_on" else "ai_off" },
                            colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Navy)
                        )
                    }
                    Text(tr("ai_rule") + if (manage) tr("ai_rule_manage") else tr("ai_rule_nomanage"),
                        color = Color.White.copy(.65f), fontSize = 12.sp)
                }
            }

            pending?.let { p ->
                Spacer(Modifier.height(12.dp))
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when (p) {
                            "ai_on" -> tr("confirm_ai_on")
                            "ai_off" -> tr("confirm_ai_off")
                            "leave" -> tr("confirm_leave")
                            else -> tr("confirm_delete")
                        }, color = Color.White, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(tr("yes"), {
                                pending = null
                                when (p) {
                                    "ai_on" -> act { api.setGroupAi(chat.id, true) }
                                    "ai_off" -> act { api.setGroupAi(chat.id, false) }
                                    "leave" -> scope.launch {
                                        try { api.removeGroupMember(chat.id, myUsername.orEmpty()); onLeft() }
                                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                                    }
                                    else -> scope.launch {
                                        try { api.deleteGroup(chat.id); onLeft() }
                                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
                                    }
                                }
                            })
                            PillButton(tr("no"), { pending = null }, filled = false)
                        }
                    }
                }
            }

            // ---- members ----
            Spacer(Modifier.height(16.dp))
            Text(tr("members"), color = Teal, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            g.members.forEach { m ->
                val isMe = m.username == myUsername
                val canRemove = !isMe && (g.myRole == "owner" || (g.myRole == "admin" && m.role == "member"))
                val canPromote = !isMe && g.myRole == "owner" && m.role != "owner"
                GlassPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.avatarUrl, 40)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.username + if (isMe) " " + tr("you_tag") else "", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(when (m.role) { "owner" -> tr("role_owner"); "admin" -> tr("role_admin"); else -> tr("role_member") },
                                color = Color.White.copy(.65f), fontSize = 12.sp)
                        }
                        if (canPromote) PillButton(if (m.role == "admin") tr("remove_admin") else tr("make_admin"),
                            { act { api.setGroupRole(chat.id, m.username, m.role != "admin") } }, filled = false)
                        if (canPromote && canRemove) Spacer(Modifier.width(6.dp))
                        if (canRemove) PillButton(tr("remove"), { act { api.removeGroupMember(chat.id, m.username) } }, filled = false)
                    }
                }
            }

            // ---- add member (owner / admin) ----
            if (manage) {
                Spacer(Modifier.height(14.dp))
                Text(tr("add_member"), color = Teal, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                GlassField(addQuery, { addQuery = it }, tr("search_user"))
                hits.filter { h -> g.members.none { it.username == h.username } }.forEach { u ->
                    GlassPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u.avatarUrl, 36)
                            Spacer(Modifier.width(10.dp))
                            Text(u.username, color = Color.White, modifier = Modifier.weight(1f))
                            PillButton(tr("add"), { act { api.addGroupMember(chat.id, u.username); addQuery = "" } })
                        }
                    }
                }
            }

            error?.let { Text(it, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(20.dp))
            PillButton(tr("leave_group"), { pending = "leave" }, Modifier.fillMaxWidth(), filled = false)
            if (g.myRole == "owner") {
                Spacer(Modifier.height(8.dp))
                PillButton(tr("delete_group"), { pending = "delete" }, Modifier.fillMaxWidth(), filled = false)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
