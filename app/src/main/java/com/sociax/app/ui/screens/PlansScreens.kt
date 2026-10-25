package com.sociax.app.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** Plan v3.2: free plan + ONE paid plan, Sociax Pro ($15 / month). Real numbers come from the server (never trusted from the app).
 *  Media is a DAILY allowance (500 MB free, 1.5 GB Pro) with an exact reset time. Pro is paid with Chapa (TEST mode)
 *  and verified by the server. */
@Composable
internal fun PlansSection(api: ApiClient?) {
    var media by remember { mutableStateOf<MediaQuota?>(null) }
    var ai by remember { mutableStateOf<AiQuota?>(null) }
    suspend fun reload(c: ApiClient) {
        try { media = c.mediaQuota() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        try { ai = c.aiQuota() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }
    LaunchedEffect(api) { if (api != null) reload(api) }

    Section(tr("plans_title")) {
        media?.let { m ->
            val used = String.format(Locale.US, "%.1f", m.usedMb)
            Text(trf("storage_line", used, m.limitMb), color = Color.White, fontSize = 14.sp)
            LinearProgressIndicator(
                progress = { if (m.limitMb > 0) (m.usedMb / m.limitMb).toFloat().coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp), color = Teal, trackColor = Color(0x33FFFFFF), strokeCap = StrokeCap.Round)
            Text(trf("storage_left", String.format(Locale.US, "%.1f", m.remainingMb.coerceAtLeast(0.0))),
                color = Color.White.copy(.65f), fontSize = 12.sp)
            if (m.resetAt > 0) {
                val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(m.resetAt * 1000))
                Text(trf("storage_reset", at), color = Color.White.copy(.65f), fontSize = 12.sp)
            } else Text(tr("storage_reset_none"), color = Color.White.copy(.65f), fontSize = 12.sp)
        }
        ai?.takeIf { it.limit > 0 }?.let {
            Spacer(Modifier.height(4.dp))
            Text(trf("ai_usage_line", it.used, it.limit, (it.resetInSeconds / 3600).toInt() + 1), color = Color.White, fontSize = 14.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(tr("plan_free_note"), color = Color.White.copy(.75f), fontSize = 13.sp)
    }
    // ONE Pro card only (plan v3.2): no duplicate Pro cards or separate upgrade buttons.
    ProSection(api, media?.pro == true || ai?.pro == true,
        maxOf(media?.proExpiresAt ?: 0L, ai?.proExpiresAt ?: 0L)) { c -> reload(c) }
}

/** Plan v3.2: when a free limit is reached, offer the ONE Pro plan or waiting for the reset.
 *  AI limit   -> Sociax Pro ($15 / month, 7x the free AI allowance) or waiting for the reset.
 *  Media full -> Sociax Pro (1.5 GB per day instead of 500 MB) or waiting for the daily reset.
 *  "See plans" opens Settings > Storage & plans, where Pro can be bought (Chapa TEST mode). */
@Composable
fun LimitDialog(event: LimitEvent, onSeePlans: () -> Unit, onDismiss: () -> Unit) {
    val ai = event.kind == "ai"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121C2E),
        title = { Text(tr(if (ai) "limit_ai_title" else "limit_media_title"), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(trf(if (ai) "limit_ai_body" else "limit_media_body", event.resetHours), color = Color.White.copy(.85f))
                Text(tr(if (ai) "limit_ai_offer" else "limit_media_offer"), color = Color.White.copy(.85f))
            }
        },
        confirmButton = { TextButton(onClick = onSeePlans) { Text(tr("limit_see_plans"), color = Teal, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("limit_wait"), color = Color.White) } }
    )
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is LifecycleOwner) return c; c = c.baseContext }
    return null
}

/** The ONE paid plan: Sociax Pro, $15 for 30 days, one-time (not a subscription). Flow: the server creates
 *  the Chapa checkout -> the browser opens it -> when the user comes back the SERVER verifies with Chapa.
 *  Only the server decides "paid"; [onPaid] then reloads the real numbers from the server (benefits start immediately). */
@Composable
private fun ProSection(api: ApiClient?, active: Boolean, expiresAt: Long, onPaid: suspend (ApiClient) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val apiNow by rememberUpdatedState(api)
    val onPaidNow by rememberUpdatedState(onPaid)
    fun pendingRef(): String? = prefs.pendingPayment
    fun setPendingRef(v: String?) { prefs.pendingPayment = v }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(pendingRef()) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun check() {
        val client = apiNow
        val tx = pendingRef()
        if (client == null || tx == null || busy) return
        busy = true
        scope.launch {
            try {
                val r = client.verifyPayment(tx)
                if (r.granted) {
                    setPendingRef(null); pending = null; msg = "pay_success"
                    try { onPaidNow(client) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
                } else msg = if (r.status == "pending") "pay_pending" else "pay_not_done"
            } catch (e: CancellationException) { throw e
            } catch (e: ApiException) {
                if (e.code == 404) { setPendingRef(null); pending = null }
                msg = "pay_check_error"
            } catch (e: Exception) { msg = "pay_check_error" }
            busy = false
        }
    }

    fun buy() {
        val client = apiNow
        if (client == null || busy) return
        busy = true
        scope.launch {
            try {
                val s = client.startProPayment()
                if (!s.checkoutUrl.startsWith("https://")) throw IllegalStateException("bad checkout url")
                setPendingRef(s.txRef); pending = s.txRef; msg = null
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.checkoutUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: CancellationException) { throw e
            } catch (e: ApiException) { msg = if (e.code == 503) "pay_not_ready" else "pay_start_error"
            } catch (e: Exception) { msg = "pay_start_error" }
            busy = false
        }
    }

    // When the user comes back from the browser, ask the server to verify the payment.
    val owner = remember(ctx) { ctx.findLifecycleOwner() }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME && pendingRef() != null) check() }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    Section(tr("pro_title")) {
        Text(tr("pro_body"), color = Color.White)
        Text(tr("pro_terms"), color = Color.White.copy(.65f), fontSize = 12.sp)
        if (active && expiresAt > 0) {
            val until = java.text.DateFormat.getDateInstance().format(java.util.Date(expiresAt * 1000))
            Text(trf("pro_active", until), color = Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        PillButton(tr(if (active) "pro_buy_more" else "pro_buy"), { buy() }, Modifier.fillMaxWidth())
        if (pending != null) PillButton(tr("pay_check"), { check() }, Modifier.fillMaxWidth(), filled = false)
        msg?.let { Text(tr(it), color = Color(0xFFFFE08A), fontSize = 12.sp) }
        Text(tr("pay_test_note"), color = Color.White.copy(.5f), fontSize = 11.sp)
    }
}
