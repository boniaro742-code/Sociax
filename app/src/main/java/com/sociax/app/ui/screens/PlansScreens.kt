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

/** Plan v3.1: free plan + two SEPARATE paid plans. Real numbers come from the server (never trusted from the app).
 *  Both plans are paid with Chapa (TEST mode) and verified by the server; each one has its own price, its own
 *  expiry, and buying one never unlocks the other. */
@Composable
internal fun PlansSection(api: ApiClient?) {
    var media by remember { mutableStateOf<MediaQuota?>(null) }
    var ai by remember { mutableStateOf<AiQuota?>(null) }
    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        try { media = api.mediaQuota() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        try { ai = api.aiQuota() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }

    Section(tr("plans_title")) {
        media?.let { m ->
            val used = String.format(Locale.US, "%.1f", m.usedMb)
            Text(trf("storage_line", used, m.limitMb), color = Color.White, fontSize = 14.sp)
            LinearProgressIndicator(
                progress = { (m.usedMb / m.limitMb).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp), color = Teal, trackColor = Color(0x33FFFFFF), strokeCap = StrokeCap.Round)
            Text(trf("storage_left", String.format(Locale.US, "%.1f", (m.limitMb - m.usedMb).coerceAtLeast(0.0))),
                color = Color.White.copy(.65f), fontSize = 12.sp)
        }
        ai?.takeIf { it.limit > 0 }?.let {
            Spacer(Modifier.height(4.dp))
            Text(trf("ai_usage_line", it.used, it.limit, (it.resetInSeconds / 3600).toInt() + 1), color = Color.White, fontSize = 14.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(tr("plan_free_note"), color = Color.White.copy(.75f), fontSize = 13.sp)
    }
    ProSection(ProKind.AI, api, ai?.aiPro == true, ai?.aiProExpiresAt ?: 0L) { c -> ai = c.aiQuota() }
    ProSection(ProKind.APP, api, media?.appPro == true, media?.appProExpiresAt ?: 0L) { c -> media = c.mediaQuota() }
}

/** Plan section 8: when a free limit is reached, explain the two products independently.
 *  AI limit  -> offer AI Pro ($10/month, 5x) or waiting for the 48-hour reset.
 *  Media full -> offer App Pro ($3/month, 600 MB) or freeing space (the media allowance does not reset by itself).
 *  "See plans" opens Settings > Storage & plans, where each plan can be bought (Chapa TEST mode). */
@Composable
fun LimitDialog(event: LimitEvent, onSeePlans: () -> Unit, onDismiss: () -> Unit) {
    val ai = event.kind == "ai"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121C2E),
        title = { Text(tr(if (ai) "limit_ai_title" else "limit_media_title"), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ai) Text(trf("limit_ai_body", event.resetHours), color = Color.White.copy(.85f))
                else Text(tr("limit_media_body"), color = Color.White.copy(.85f))
                Text(tr(if (ai) "limit_ai_offer" else "limit_media_offer"), color = Color.White.copy(.85f))
                Text(tr("limit_separate_note"), color = Color.White.copy(.6f), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onSeePlans) { Text(tr("limit_see_plans"), color = Teal, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(if (ai) "limit_wait" else "limit_not_now"), color = Color.White) } }
    )
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is LifecycleOwner) return c; c = c.baseContext }
    return null
}

/** The two SEPARATE paid plans (plan v3.1). Each one has its own texts here and its own price / expiry on the server. */
private enum class ProKind(
    val titleKey: String, val bodyKey: String, val termsKey: String,
    val buyKey: String, val buyMoreKey: String, val activeKey: String
) {
    AI("ai_pro_title", "ai_pro_body", "ai_pro_terms", "ai_pro_buy", "ai_pro_buy_more", "ai_pro_active"),
    APP("app_pro_title", "app_pro_body", "app_pro_terms", "app_pro_buy", "app_pro_buy_more", "app_pro_active")
}

/** One paid plan: $10 AI Pro or $3 App Pro, each for 30 days, one-time (not a subscription). Flow: the server creates
 *  the Chapa checkout -> the browser opens it -> when the user comes back the SERVER verifies with Chapa.
 *  Only the server decides "paid"; [onPaid] then reloads the real numbers from the server. */
@Composable
private fun ProSection(kind: ProKind, api: ApiClient?, active: Boolean, expiresAt: Long, onPaid: suspend (ApiClient) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val apiNow by rememberUpdatedState(api)
    val onPaidNow by rememberUpdatedState(onPaid)
    fun pendingRef(): String? = if (kind == ProKind.AI) prefs.pendingPayment else prefs.pendingAppPayment
    fun setPendingRef(v: String?) { if (kind == ProKind.AI) prefs.pendingPayment = v else prefs.pendingAppPayment = v }
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
                val s = if (kind == ProKind.AI) client.startAiProPayment() else client.startAppProPayment()
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

    Section(tr(kind.titleKey)) {
        Text(tr(kind.bodyKey), color = Color.White)
        Text(tr(kind.termsKey), color = Color.White.copy(.65f), fontSize = 12.sp)
        if (active && expiresAt > 0) {
            val until = java.text.DateFormat.getDateInstance().format(java.util.Date(expiresAt * 1000))
            Text(trf(kind.activeKey, until), color = Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        PillButton(tr(if (active) kind.buyMoreKey else kind.buyKey), { buy() }, Modifier.fillMaxWidth())
        if (pending != null) PillButton(tr("pay_check"), { check() }, Modifier.fillMaxWidth(), filled = false)
        msg?.let { Text(tr(it), color = Color(0xFFFFE08A), fontSize = 12.sp) }
        Text(tr("pay_test_note"), color = Color.White.copy(.5f), fontSize = 11.sp)
    }
}
