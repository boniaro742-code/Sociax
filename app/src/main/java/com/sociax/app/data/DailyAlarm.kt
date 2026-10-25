package com.sociax.app.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sociax.app.MainActivity
import com.sociax.app.R
import com.sociax.app.ui.theme.Lang
import com.sociax.app.ui.theme.Strings
import java.util.Calendar

/**
 * Plan v3.3 section 1: two OPTIONAL daily alarms, 3:00 AM and 3:00 PM in the phone's own local time and time zone.
 * They run completely on the phone: Android's alarm scheduler + sounds stored inside the app. No internet, no server.
 *  - AlarmManager.setAlarmClock() is used: it is exact, works in Doze, and needs no special "exact alarm" permission.
 *  - The only permission asked is Notifications (Android 13+). Without it the feature stays Off.
 *  - Rescheduled after every ring, after reboot, after an app update, and when the time or time zone changes.
 * HONEST LIMITS: battery saver / "restricted" battery mode, Do Not Disturb, a muted alarm volume or a phone that was
 * force-stopped can still stop an alarm. It is never promised to be guaranteed.
 */
object DailyAlarm {
    const val CHANNEL_ID = "sociax_daily_alarm"
    private const val CHANNEL_FALLBACK = "sociax_daily_alarm_fallback"
    const val ACTION_FIRE = "com.sociax.app.ALARM_FIRE"
    const val NOTIF_ID = 4101
    private const val NOTIF_FALLBACK_ID = 4102

    /** Alarm times as minutes after local midnight: 3:00 AM and 3:00 PM. */
    val SLOTS = listOf(3 * 60, 15 * 60)

    class Sound(val nameKey: String, val raw: Int)
    /** Five built-in sounds, generated for Sociax (original, no licence needed), stored in res/raw. */
    val SOUNDS = listOf(
        Sound("alarm_s1", R.raw.alarm_sunrise), Sound("alarm_s2", R.raw.alarm_bell), Sound("alarm_s3", R.raw.alarm_beep),
        Sound("alarm_s4", R.raw.alarm_chime), Sound("alarm_s5", R.raw.alarm_pulse)
    )

    /** The next 3:00 AM and 3:00 PM after [now], by the phone's CURRENT time zone. */
    fun nextTimes(now: Long = System.currentTimeMillis()): List<Long> = SLOTS.map { m ->
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, m / 60); c.set(Calendar.MINUTE, m % 60); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        c.timeInMillis
    }

    fun notificationsAllowed(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun firePending(ctx: Context, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, slot, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Cancels the scheduled alarms (alarms Off). */
    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        SLOTS.indices.forEach { am.cancel(firePending(ctx, it)) }
    }

    /** Schedules the next valid 3:00 AM and 3:00 PM when alarms are On (and cancels everything when Off). Returns false if Android refused. */
    fun schedule(ctx: Context): Boolean {
        cancel(ctx)
        if (!Prefs(ctx).alarmsEnabled) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            val times = nextTimes()
            SLOTS.indices.forEach { i ->
                val open = PendingIntent.getActivity(ctx, 100 + i, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(times[i], open), firePending(ctx, i))
            }
            true
        } catch (e: Exception) { false }
    }

    private fun text(ctx: Context, key: String) = Strings.get(Lang.fromCode(Prefs(ctx).language), key)

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // ringing channel: the service plays the sound itself
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, text(ctx, "alarm_settings_title"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true)
        })
        // fallback channel: Android plays the bundled sound if the service could not start
        nm.createNotificationChannel(NotificationChannel(CHANNEL_FALLBACK, text(ctx, "alarm_settings_title"), NotificationManager.IMPORTANCE_HIGH).apply {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            setSound(Uri.parse("android.resource://${ctx.packageName}/${SOUNDS[0].raw}"), attrs)
            enableVibration(true)
        })
    }

    private fun contentIntent(ctx: Context) = PendingIntent.getActivity(ctx, 200, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun ringingNotification(ctx: Context): Notification {
        ensureChannels(ctx)
        val stop = PendingIntent.getService(ctx, 300, Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_alarm)
            .setContentTitle(text(ctx, "alarm_notif_title")).setContentText(text(ctx, "alarm_notif_text"))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true).setContentIntent(contentIntent(ctx))
            .addAction(0, text(ctx, "alarm_notif_stop"), stop).build()
    }

    /** Used only when the ringing service could not start: a high-priority notification with the first bundled sound. */
    fun fallbackNotification(ctx: Context) {
        if (!notificationsAllowed(ctx)) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_FALLBACK)
            .setSmallIcon(R.drawable.ic_notif_alarm)
            .setContentTitle(text(ctx, "alarm_notif_title")).setContentText(text(ctx, "alarm_notif_text"))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(contentIntent(ctx)).build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_FALLBACK_ID, n) } catch (e: SecurityException) { }
    }
}

/** Fires at 3:00 AM / 3:00 PM: schedules the next ones, then starts the ringing service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs(context).alarmsEnabled) return
        DailyAlarm.schedule(context)                       // next 3:00 AM / 3:00 PM
        try {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_START))
        } catch (e: Exception) {
            DailyAlarm.fallbackNotification(context)       // e.g. Android refused to start the service from the background
        }
    }
}

/** Reboot, app update, time or time-zone change: the schedule is made again for the phone's current time. */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Prefs(context).alarmsEnabled) DailyAlarm.schedule(context)
    }
}

/** Plays the chosen bundled sound (looping, alarm volume) for up to 1 minute, with a Stop button. Needs no internet. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, DailyAlarm.NOTIF_ID, DailyAlarm.ringingNotification(this), type)
        } catch (e: Exception) {
            DailyAlarm.fallbackNotification(this); stopSelf(); return START_NOT_STICKY
        }
        startSound()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, RING_MS)
        return START_NOT_STICKY
    }

    private fun startSound() {
        stopSound()
        try {
            val idx = Prefs(this).alarmSound.coerceIn(0, DailyAlarm.SOUNDS.size - 1)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val session = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
            val mp = MediaPlayer.create(this, DailyAlarm.SOUNDS[idx].raw, attrs, session)
            if (mp != null) { mp.isLooping = true; mp.start() }
            player = mp
        } catch (e: Exception) { player = null }
    }

    private fun stopSound() {
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        player = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.sociax.app.ALARM_START"
        const val ACTION_STOP = "com.sociax.app.ALARM_STOP"
        private const val RING_MS = 60_000L
    }
}
