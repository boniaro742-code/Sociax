package com.sociax.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class AiMsg(val mine: Boolean, val text: String)

/** Private one-to-one conversation with Sociax AI. History stays on the phone (in memory) and
 *  only the single question you send goes to the server: nothing from your other chats is ever sent. */
@Composable
fun AiAssistantScreen(api: ApiClient, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var msgs by remember { mutableStateOf<List<AiMsg>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quota by remember { mutableStateOf<AiQuota?>(null) }

    LaunchedEffect(Unit) { try { quota = api.aiQuota() } catch (e: CancellationException) { throw e } catch (e: Exception) { } }
    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1 + (if (busy) 1 else 0)) }

    fun send() {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        text = ""; error = null; busy = true
        msgs = msgs + AiMsg(true, q)
        scope.launch {
            try {
                val r = api.askAi(q)
                msgs = msgs + AiMsg(false, r.answer)
                quota = r.quota
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errText(e) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF6A3DF0), Color(0xFF1FB6FF)))),
                contentAlignment = Alignment.Center) { Text("AI", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("ai_title"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(tr("ai_private_note"), color = Color.White.copy(.6f), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty()) Text(tr("ai_welcome"), color = Color.White.copy(.6f), modifier = Modifier.align(Alignment.Center))
            else LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(msgs.size) { i ->
                    val m = msgs[i]
                    val fill = if (m.mine) Modifier.background(Brush.linearGradient(listOf(Color(0xFF3A6BFF), Color(0xFF1FB6FF))))
                    else Modifier.background(Brush.linearGradient(listOf(Color(0x556A3DF0), Color(0x553A6BFF))))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).then(fill).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (!m.mine) Text("Sociax AI", color = Color(0xFFC9B8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(m.text, color = Color.White, fontSize = 15.sp)
                        }
                    }
                }
                if (busy) item { Text(tr("ai_thinking"), color = Color.White.copy(.6f), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp)) }
            }
        }

        quota?.takeIf { it.limit > 0 }?.let {
            Text(trf("ai_usage_line", it.used, it.limit, (it.resetInSeconds / 3600).toInt() + 1),
                color = Color.White.copy(.55f), fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        Spacer(Modifier.height(4.dp))

        InsetPill(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 6.dp, end = 5.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextField(
                    text, { text = it.take(2000) }, Modifier.weight(1f),
                    placeholder = { Text(tr("ai_ask_hint"), color = Color.White.copy(.6f), fontSize = 14.sp) },
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Box(Modifier.size(42.dp).clip(CircleShape).background(PaperWhite).clickable { send() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Send, "Send", tint = Navy, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
