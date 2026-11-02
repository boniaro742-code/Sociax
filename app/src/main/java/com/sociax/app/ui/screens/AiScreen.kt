package com.sociax.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One line of the AI conversation. [at] is only used to show the time. */
class AiMsg(val mine: Boolean, val text: String, val at: Long = System.currentTimeMillis())

/**
 * The private AI conversation lives HERE (in memory, on the phone, never saved), so it stays while the person visits another tab and
 * is wiped on log out. Only the single question you send goes to the server: nothing from your other chats is ever sent.
 */
class AiChatState {
    var msgs by mutableStateOf<List<AiMsg>>(emptyList())
    var draft by mutableStateOf("")
    var failedQuestion by mutableStateOf<String?>(null)    // Step 36: offered again with "Try again" when the AI is unavailable
    fun reset() { msgs = emptyList(); draft = ""; failedQuestion = null }
}

/**
 * Step 59 (new design): the AI tab IS the Sociax AI chat. A purple welcome card with the glowing orb and three things to try, then the
 * conversation, quick follow-up chips under the last answer (shorter / hashtags / funny) and the message bar with a New chat button.
 * Step 58 rules still hold: every AI tool lives behind the AI tab, nothing from the person's chats is sent, and the allowance is shown
 * exactly as the SERVER counts it (only when the server answered). "Create images and videos" is NOT shown: it does not work yet
 * (plan v3.9.5 / v3.9.9: do not display unavailable features as if they are working).
 */
@Composable
fun AiHubScreen(api: ApiClient, chat: AiChatState, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quota by remember { mutableStateOf<AiQuota?>(null) }

    LaunchedEffect(Unit) { try { quota = api.aiQuota() } catch (e: CancellationException) { throw e } catch (e: Exception) { } }

    fun ask(q: String) {
        if (q.isBlank() || busy) return
        error = null; busy = true; chat.failedQuestion = null
        chat.msgs = chat.msgs + AiMsg(true, q)
        scope.launch {
            try {
                val r = api.askAi(q)
                chat.msgs = chat.msgs + AiMsg(false, r.answer)
                quota = r.quota
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = errText(e)
                // the server marks "AI unavailable" as retryable (and did not charge the allowance); no answer came, so offer to ask again
                if (e !is ApiException || e.reason == "ai_unavailable" || e.code == 0 || e.code >= 500) chat.failedQuestion = q
                if (chat.failedQuestion != null) chat.msgs = chat.msgs.dropLast(1)
            }
            busy = false
        }
    }

    fun send() {
        val q = chat.draft.trim()
        if (q.isEmpty() || busy) return
        chat.draft = ""
        ask(q)
    }

    val msgs = chat.msgs
    val lastAnswer = msgs.lastOrNull()?.takeIf { !it.mine }?.text
    // follow-up questions that carry the last answer (the person's own AI text, sent only when they tap the chip)
    val promptShorter = lastAnswer?.let { trf("ai_prompt_shorter", it) }
    val promptHashtags = lastAnswer?.let { trf("ai_prompt_hashtags", it) }
    val promptFunny = lastAnswer?.let { trf("ai_prompt_funny", it) }
    val promptCaption = tr("ai_prompt_caption")
    val promptIdeas = tr("ai_prompt_ideas")
    val promptSummarize = tr("ai_prompt_summarize")

    // scroll the newest line into view when a message arrives, and when the keyboard opens
    val showChips = !busy && lastAnswer != null
    val total = 2 + (if (msgs.isNotEmpty()) 1 else 0) + msgs.size +
        (if (busy) 1 else 0) + (if (showChips) 1 else 0) + (if (error != null) 1 else 0) + (if (chat.failedQuestion != null) 1 else 0)
    LaunchedEffect(msgs.size, busy, error) { if (msgs.isNotEmpty()) listState.animateScrollToItem(total - 1) }
    val keyboardOpen = rememberImeVisible()
    LaunchedEffect(keyboardOpen) { if (keyboardOpen && msgs.isNotEmpty()) listState.scrollToItem(total - 1) }

    // The page lifts above the keyboard by itself (tab pages are not wrapped like the pages that open on top); without the keyboard it
    // leaves room for the docked tab bar.
    Column(Modifier.fillMaxSize().imePadding().padding(bottom = if (keyboardOpen) 0.dp else bottomBarClearance())) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("ai_title"), color = Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.AutoAwesome, null, tint = Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.weight(1f))
            GlassIconButton(Icons.Outlined.Settings, tr("settings"), onSettings, size = 44)
        }

        LazyColumn(
            state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                AiHeroCard(
                    onCaption = { chat.draft = promptCaption },
                    onIdeas = { ask(promptIdeas) },
                    onSummarize = { chat.draft = promptSummarize }
                )
            }
            item {   // the allowance as the SERVER counts it (only when it answered) + the privacy promise
                Column(Modifier.padding(horizontal = 4.dp)) {
                    quota?.takeIf { it.limit > 0 }?.let {
                        Text(trf("ai_usage_line", it.used, it.limit, (it.resetInSeconds / 3600).toInt() + 1), color = Ink.copy(.6f), fontSize = 12.sp)
                    }
                    Text(tr("ai_private_note"), color = Ink.copy(.5f), fontSize = 11.sp)
                }
            }
            if (msgs.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(Hairline))
                    Text(tr("ai_today"), color = Ink.copy(.5f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(Hairline))
                }
            }
            items(msgs.size) { i -> AiBubble(msgs[i]) }
            if (busy) item { Text(tr("ai_thinking"), color = Ink.copy(.6f), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp)) }
            if (showChips) item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmallChip(tr("ai_chip_shorter"), { promptShorter?.let { ask(it) } }, active = false)
                    SmallChip(tr("ai_chip_hashtags"), { promptHashtags?.let { ask(it) } }, active = false)
                    SmallChip(tr("ai_chip_funny"), { promptFunny?.let { ask(it) } }, active = false)
                }
            }
            error?.let { item { Text(it, color = ErrorInk, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) } }
            chat.failedQuestion?.let { q ->
                item {
                    Text(tr("try_again"), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { chat.failedQuestion = null; error = null; ask(q) }
                            .padding(horizontal = 6.dp, vertical = 6.dp))
                }
            }
        }

        // message bar: New chat (+), the text box, the purple send button
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.Add, tr("ai_new_chat"), { if (!busy) { chat.reset(); error = null } }, size = 46)
            Spacer(Modifier.width(8.dp))
            InsetPill(Modifier.weight(1f)) {
                ComposerField(chat.draft, { chat.draft = it.take(2000) }, tr("ai_msg_hint"), { send() }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(46.dp).clip(CircleShape).background(AccentBrush).clickable { send() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Send, tr("a11y_send"), tint = OnAccent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The welcome card: "Hi there!", what the assistant can do, the glowing orb and three things to try. */
@Composable
private fun AiHeroCard(onCaption: () -> Unit, onIdeas: () -> Unit, onSummarize: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(SoftAccent, CardFill)))
            .border(1.dp, Hairline, shape)
    ) {
        AiOrb(136.dp, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 2.dp))
        Column(Modifier.padding(18.dp)) {
            Text(tr("ai_hero_title"), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text(tr("ai_hero_sub"), color = Ink.copy(.7f), fontSize = 14.sp, modifier = Modifier.fillMaxWidth(0.62f))
            Spacer(Modifier.height(18.dp))
            Text(tr("ai_try_asking"), color = Ink.copy(.55f), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(tr("ai_chip_caption"), onCaption, active = false, icon = Icons.Outlined.Edit)
                SmallChip(tr("ai_chip_ideas"), onIdeas, active = false, icon = Icons.Outlined.Lightbulb)
                SmallChip(tr("ai_chip_summarize"), onSummarize, active = false, icon = Icons.Outlined.Description)
            }
        }
    }
}

/** My question: pale purple, on the right, with the time and the double check. Sociax AI: the orb and a soft grey bubble, on the left. */
@Composable
private fun AiBubble(m: AiMsg) {
    val time = remember(m.at) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(m.at)) }
    if (m.mine) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier.widthIn(max = 290.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp))
                    .background(SoftAccent).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(m.text, color = Ink, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, color = Ink.copy(.5f), fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.DoneAll, null, tint = Accent, modifier = Modifier.size(14.dp))
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            AiOrb(34.dp, glow = false)
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier.widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp))
                    .background(FieldFill).padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(m.text, color = Ink, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(time, color = Ink.copy(.5f), fontSize = 11.sp)
            }
        }
    }
}
