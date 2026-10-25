package com.sociax.app.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.ApiClient
import com.sociax.app.data.AppLock
import com.sociax.app.data.AuthRepository
import com.sociax.app.data.LockCheck
import com.sociax.app.data.LockType
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// Plan v3.3 section 2 + 3: mandatory four-digit PIN or pattern lock, lock screen, "Forgot PIN/Pattern?" and the
// App Lock block in Settings. The PIN / pattern is only ever kept as a salted verifier on this phone (see AppLock.kt).

private val ErrorRed = Color(0xFFFF8A8A)
private val WaitAmber = Color(0xFFFFE08A)

/** Four dots + on-screen number keys. [onComplete] gets the 4 digits; the entry then clears itself. */
@Composable
internal fun PinEntry(enabled: Boolean, onComplete: (String) -> Unit) {
    var digits by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape)
                        .background(if (i < digits.length) Teal else Color(0x33FFFFFF))
                        .border(1.dp, Color(0x66FFFFFF), CircleShape)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { k ->
                    if (k.isEmpty()) {
                        Spacer(Modifier.size(68.dp))
                    } else {
                        Box(
                            Modifier.size(68.dp).clip(CircleShape).background(ControlFill)
                                .border(1.dp, ControlBorder, CircleShape)
                                .clickable(enabled = enabled) {
                                    if (k == "<") digits = digits.dropLast(1)
                                    else if (digits.length < 4) {
                                        digits += k
                                        if (digits.length == 4) { val d = digits; digits = ""; onComplete(d) }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) { Text(if (k == "<") "⌫" else k, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
    }
}

/** 3x3 dots; drag across them. Dots are numbered 0..8 (row by row). [onComplete] gets the dots in order. */
@Composable
internal fun PatternEntry(enabled: Boolean, onComplete: (List<Int>) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var path by remember { mutableStateOf(listOf<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val accent = Teal

    fun centerOf(i: Int): Offset {
        val cell = size.width / 3f
        return Offset((i % 3 + 0.5f) * cell, (i / 3 + 0.5f) * cell)
    }
    fun hit(p: Offset): Int? {
        val cell = size.width / 3f
        for (i in 0..8) if ((centerOf(i) - p).getDistance() < cell * 0.3f) return i
        return null
    }
    fun add(i: Int) {
        if (i in path) return
        val last = path.lastOrNull()
        var next = path
        // a straight line over a dot that was not touched yet (0 -> 2 passes 1) takes that dot too
        if (last != null && abs(last % 3 - i % 3) != 1 && abs(last / 3 - i / 3) != 1 && (last + i) % 2 == 0) {
            val mid = (last + i) / 2
            if (mid !in next) next = next + mid
        }
        path = next + i
    }

    Box(
        Modifier.size(280.dp).onSizeChanged { size = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { o -> path = emptyList(); finger = o; hit(o)?.let { add(it) } },
                    onDrag = { change, _ -> change.consume(); finger = change.position; hit(change.position)?.let { add(it) } },
                    onDragEnd = { val p = path; finger = null; path = emptyList(); if (p.isNotEmpty()) onComplete(p) },
                    onDragCancel = { path = emptyList(); finger = null }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.width / 3f
            fun c(i: Int) = Offset((i % 3 + 0.5f) * cell, (i / 3 + 0.5f) * cell)
            for (k in 1 until path.size) {
                drawLine(accent, c(path[k - 1]), c(path[k]), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            }
            val f = finger
            if (path.isNotEmpty() && f != null) drawLine(accent.copy(alpha = .6f), c(path.last()), f, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            for (i in 0..8) {
                val on = i in path
                drawCircle(if (on) accent else Color(0x88FFFFFF), if (on) 12.dp.toPx() else 7.dp.toPx(), c(i))
            }
        }
    }
}

/** Asks for the current PIN / pattern, with wrong-try messages and the "too many tries" wait. Calls [onSuccess] when right. */
@Composable
internal fun LockChallenge(lock: AppLock, onSuccess: () -> Unit) {
    val type = lock.type ?: LockType.PIN
    val scope = rememberCoroutineScope()
    var waitLeft by remember { mutableIntStateOf(lock.waitSeconds()) }
    var wrongLeft by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(waitLeft > 0) {
        while (waitLeft > 0) { delay(1000); waitLeft = lock.waitSeconds() }
    }
    fun handle(secret: String) {
        if (busy) return
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.Default) { lock.check(secret) }
            busy = false
            when (r) {
                LockCheck.Ok -> { wrongLeft = -1; onSuccess() }
                is LockCheck.Wrong -> wrongLeft = r.triesLeft
                is LockCheck.Wait -> { wrongLeft = -1; waitLeft = r.seconds }
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(if (type == LockType.PIN) "lock_enter_pin" else "lock_draw_to_unlock"),
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        when {
            waitLeft > 0 -> Text(trf("lock_wait", waitLeft), color = WaitAmber, fontSize = 13.sp, textAlign = TextAlign.Center)
            wrongLeft >= 0 -> Text(trf("lock_wrong", wrongLeft), color = ErrorRed, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(4.dp))
        val enabled = waitLeft == 0 && !busy
        if (type == LockType.PIN) PinEntry(enabled) { handle(it) }
        else PatternEntry(enabled) { handle(it.joinToString("-")) }
    }
}

/** Choose type -> enter -> confirm -> saved. [mandatory] = no cancel (first-time setup): the lock cannot be skipped or left blank. */
@Composable
fun LockSetupScreen(lock: AppLock, mandatory: Boolean, onDone: () -> Unit, onCancel: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    var type by remember { mutableStateOf<LockType?>(null) }
    var first by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    BackHandler(true) {
        when {
            first != null -> { first = null; msg = null }
            type != null -> { type = null; msg = null }
            !mandatory -> onCancel()
            else -> activity?.moveTaskToBack(true)
        }
    }

    fun onSecret(secret: String) {
        val t = type ?: return
        val f = first
        when {
            f == null -> { first = secret; msg = null }
            f == secret -> scope.launch {
                saving = true
                withContext(Dispatchers.Default) { lock.set(t, secret) }
                saving = false
                onDone()
            }
            else -> { first = null; msg = "lock_mismatch" }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SociaxLogo(size = 110.dp)
        Spacer(Modifier.height(8.dp))
        Text(tr("lock_setup_title"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        val t = type
        if (t == null) {
            Text(tr(if (mandatory) "lock_setup_sub_must" else "lock_setup_sub"),
                color = Color.White.copy(.8f), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            PillButton(tr("lock_type_pin"), { type = LockType.PIN }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            PillButton(tr("lock_type_pattern"), { type = LockType.PATTERN }, Modifier.fillMaxWidth(), filled = false)
            if (!mandatory) {
                Spacer(Modifier.height(10.dp))
                PillButton(tr("cancel"), onCancel, Modifier.fillMaxWidth(), filled = false)
            }
        } else {
            val confirming = first != null
            val key = when {
                t == LockType.PIN && !confirming -> "lock_enter_new_pin"
                t == LockType.PIN -> "lock_confirm_pin"
                !confirming -> "lock_draw_pattern"
                else -> "lock_confirm_pattern"
            }
            Text(tr(key), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            msg?.let { Text(tr(it), color = ErrorRed, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(16.dp))
            if (saving) CircularProgressIndicator(color = Teal)
            else if (t == LockType.PIN) PinEntry(true) { onSecret(it) }
            else PatternEntry(true) { p -> if (p.size < 4) msg = "lock_pattern_short" else onSecret(p.joinToString("-")) }
        }
    }
}

/** The lock screen shown when Sociax is opened, or opened again after the timeout. */
@Composable
fun LockScreen(lock: AppLock, onUnlocked: () -> Unit, onForgot: () -> Unit) {
    val activity = LocalContext.current as? Activity
    BackHandler(true) { activity?.moveTaskToBack(true) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        SociaxLogo(size = 110.dp)
        Spacer(Modifier.height(16.dp))
        LockChallenge(lock, onUnlocked)
        Spacer(Modifier.height(16.dp))
        Text(tr("lock_forgot"), color = Teal, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onForgot).padding(12.dp))
    }
}

/** "Forgot PIN/Pattern?": the old one cannot be shown or emailed. A fresh Google sign-in (checked by the server, one-time
 *  token) proves the account is yours; then you choose a new PIN or pattern. */
@Composable
fun LockRecoveryScreen(activity: Activity, auth: AuthRepository, api: ApiClient, onVerified: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    BackHandler(true) { if (!busy) onBack() }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        SociaxLogo(size = 110.dp)
        Spacer(Modifier.height(12.dp))
        Text(tr("lock_recover_title"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(tr("lock_recover_body"), color = Color.White.copy(.85f), fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        if (busy) CircularProgressIndicator(color = Teal)
        else {
            PillButton(tr("lock_recover_btn"), {
                scope.launch {
                    busy = true; msg = null
                    val signed = auth.reauthenticateWithGoogle(activity)
                    if (signed.isSuccess) {
                        try {
                            val token = api.startLockRecovery()
                            api.confirmLockRecovery(token)
                            busy = false
                            onVerified()
                            return@launch
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { msg = "lock_recover_failed" }
                    } else msg = "lock_recover_failed"
                    busy = false
                }
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            PillButton(tr("back"), onBack, Modifier.fillMaxWidth(), filled = false)
        }
        msg?.let { Text(tr(it), color = ErrorRed, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(tr("lock_recover_support"), color = Color.White.copy(.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

/** Covers the whole app while the app is locked (or the lock is not set up yet). The screens underneath stay as they were. */
@Composable
fun LockGate(
    activity: Activity, auth: AuthRepository, api: ApiClient, lock: AppLock, mustSetup: Boolean,
    onUnlocked: () -> Unit, onSetupDone: () -> Unit
) {
    var recovering by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }      // verified with Google: choose a new lock
    SociaxBackground(
        true,
        Modifier.fillMaxSize().pointerInput(Unit) {          // nothing underneath can be touched
            awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
        }
    ) {
        when {
            mustSetup || resetting -> LockSetupScreen(lock, mandatory = true, onDone = onSetupDone)
            recovering -> LockRecoveryScreen(activity, auth, api, onVerified = { recovering = false; resetting = true }, onBack = { recovering = false })
            else -> LockScreen(lock, onUnlocked, onForgot = { recovering = true })
        }
    }
}

/** Settings > App Lock: type, change (needs the current lock first) and the timeout. */
@Composable
internal fun AppLockSection(lock: AppLock, onChange: () -> Unit) {
    var timeout by remember { mutableIntStateOf(lock.timeoutSec) }
    fun label(sec: Int) = when (sec) { 30 -> "lock_t_30s"; 60 -> "lock_t_1m"; 300 -> "lock_t_5m"; else -> "lock_t_15m" }
    Section(tr("lock_settings_title")) {
        Text(trf("lock_current_type", tr(if (lock.type == LockType.PATTERN) "lock_type_pattern" else "lock_type_pin")), color = Color.White)
        PillButton(tr("lock_change"), onChange, Modifier.fillMaxWidth())
        Text(tr("lock_timeout"), color = Color.White, fontWeight = FontWeight.SemiBold)
        AppLock.TIMEOUTS.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { sec ->
                    PillButton(tr(label(sec)), { timeout = sec; lock.timeoutSec = sec }, Modifier.weight(1f), filled = timeout == sec)
                }
            }
        }
        Text(tr("lock_timeout_note"), color = Color.White.copy(.6f), fontSize = 12.sp)
    }
}

/** Change PIN / pattern: first prove you know the current one, then choose the new one. */
@Composable
fun ChangeLockScreen(lock: AppLock, onBack: () -> Unit, onDone: () -> Unit) {
    var verified by remember { mutableStateOf(false) }
    if (verified) {
        LockSetupScreen(lock, mandatory = false, onDone = onDone, onCancel = onBack)
    } else {
        BackHandler(true) { onBack() }
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth()) { TopBar(tr("lock_change"), onBack) }
            Text(tr("lock_verify_current"), color = Color.White.copy(.85f), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            LockChallenge(lock) { verified = true }
        }
    }
}
