package com.sociax.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.sociax.app.data.DailyAlarm
import com.sociax.app.data.Prefs
import com.sociax.app.ui.theme.*

// Plan v3.3 section 1 + 4: optional daily alarms at 3:00 AM and 3:00 PM (phone's local time), working without internet.
// Home shows a dismissible, non-blocking prompt (Yes / No / X); Settings > Daily Alarms has the master switch, times,
// five sounds with preview and the permission status. Nothing here ever blocks normal use of the app.

private val WaitAmber = Color(0xFFFFE08A)

private fun turnOn(ctx: Context, prefs: Prefs) {
    prefs.alarmsEnabled = true
    prefs.alarmPromptDone = true
    DailyAlarm.schedule(ctx)
}

private fun needsPermissionRequest(ctx: Context) = Build.VERSION.SDK_INT >= 33 && !DailyAlarm.notificationsAllowed(ctx)

private fun timeText(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(millis))

/** The prompt near the top of Home. Yes = alarms On (after the notification permission), No = no thanks, X = close. */
@Composable
internal fun AlarmPrompt() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var visible by remember { mutableStateOf(!prefs.alarmPromptDone && !prefs.alarmsEnabled) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted || DailyAlarm.notificationsAllowed(ctx)) { turnOn(ctx, prefs); visible = false }
        else { prefs.alarmPromptDone = true; denied = true }       // stays Off; the text says how to turn it on later
    }
    if (!visible && !denied) return
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(tr("alarm_prompt"), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, tr("close"), tint = Color.White.copy(.8f),
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(50)).clickable {
                        prefs.alarmPromptDone = true; visible = false; denied = false
                    }.padding(4.dp))
            }
            if (denied) {
                Text(tr("alarm_perm_needed"), color = WaitAmber, fontSize = 13.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(tr("yes"), {
                        if (needsPermissionRequest(ctx)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else { turnOn(ctx, prefs); visible = false }
                    }, Modifier.weight(1f))
                    PillButton(tr("no"), { prefs.alarmPromptDone = true; visible = false }, Modifier.weight(1f), filled = false)
                }
            }
        }
    }
}

/** Settings > Daily Alarms: master On/Off, the two times, five sounds (tap = choose, Preview = listen), permission status. */
@Composable
internal fun DailyAlarmSection() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var enabled by remember { mutableStateOf(prefs.alarmsEnabled) }
    var sound by remember { mutableIntStateOf(prefs.alarmSound) }
    var allowed by remember { mutableStateOf(DailyAlarm.notificationsAllowed(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableIntStateOf(-1) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() { player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }; player = null; playing = -1 }
    fun preview(i: Int) {
        stopPreview()
        try {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val mp = MediaPlayer.create(ctx, DailyAlarm.SOUNDS[i].raw, attrs, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (mp != null) {
                mp.setOnCompletionListener { stopPreview() }
                mp.start(); player = mp; playing = i
            }
        } catch (e: Exception) { stopPreview() }
    }
    fun switchOn() { turnOn(ctx, prefs); enabled = true; msg = null }
    fun switchOff() { prefs.alarmsEnabled = false; enabled = false; DailyAlarm.cancel(ctx) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = DailyAlarm.notificationsAllowed(ctx)
        if (granted || allowed) switchOn() else { switchOff(); msg = "alarm_perm_needed" }     // denied: stays Off, with the explanation
    }

    // Coming back from the phone's settings: re-read the permission. If it was taken away, alarms go Off (never half-on).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                allowed = DailyAlarm.notificationsAllowed(ctx)
                if (enabled && !allowed) { switchOff(); msg = "alarm_perm_needed" }
                else if (enabled) DailyAlarm.schedule(ctx)         // refresh for the current time zone
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); stopPreview() }
    }

    Section(tr("alarm_settings_title")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("alarm_master"), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(tr(if (enabled) "alarm_state_on" else "alarm_state_off"), color = if (enabled) Teal else Color.White.copy(.6f), fontSize = 12.sp)
            }
            Switch(
                enabled, { on ->
                    if (!on) switchOff()
                    else if (needsPermissionRequest(ctx)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else switchOn()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Navy)
            )
        }
        Text(tr("alarm_times"), color = Color.White.copy(.85f), fontSize = 13.sp)
        if (enabled) DailyAlarm.nextTimes().minOrNull()?.let {
            Text(trf("alarm_next", timeText(it)), color = Teal, fontSize = 13.sp)
        }
        msg?.let { Text(tr(it), color = WaitAmber, fontSize = 13.sp) }

        Text(tr("alarm_sound"), color = Color.White, fontWeight = FontWeight.SemiBold)
        DailyAlarm.SOUNDS.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .clickable { sound = i; prefs.alarmSound = i }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr(s.nameKey), color = Color.White, fontWeight = if (sound == i) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                if (sound == i) { Icon(Icons.Filled.Check, null, tint = Teal); Spacer(Modifier.width(8.dp)) }
                PillButton(tr(if (playing == i) "alarm_stop_preview" else "preview"), { if (playing == i) stopPreview() else preview(i) }, filled = false)
            }
        }

        Text(trf("alarm_perm_status", tr(if (allowed) "alarm_perm_allowed" else "alarm_perm_denied")),
            color = if (allowed) Color.White.copy(.8f) else WaitAmber, fontSize = 13.sp)
        if (!allowed) PillButton(tr("alarm_open_settings"), {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, Modifier.fillMaxWidth(), filled = false)
        Text(tr("alarm_honest"), color = Color.White.copy(.6f), fontSize = 12.sp)
    }
}
