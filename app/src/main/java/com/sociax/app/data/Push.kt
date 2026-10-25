package com.sociax.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sociax.app.MainActivity
import com.sociax.app.R
import com.sociax.app.ui.theme.Lang
import com.sociax.app.ui.theme.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Step 46 (plan v3.9 section 20, Notifications -> Firebase Cloud Messaging). Push is OPT-IN, per phone: Settings > Notifications
 * has a switch; nothing is registered before the person turns it on. The server only sends a small DATA message
 * (type, @actor, item number, count); THIS phone builds the visible sentence in the person's language from the same texts as the
 * bell list on Home. Message text, captions and photos never travel through push. The 3:00 AM / 3:00 PM alarms are local alarms
 * and do not use any of this.
 */
object PushRegistrar {
    /** False when this build has no Firebase (no google-services.json): push cannot work then. */
    fun available(): Boolean = try { FirebaseMessaging.getInstance(); true } catch (e: Exception) { false }

    /** Android 13+ asks for permission to show notifications; older versions do not. */
    fun permissionGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Registers THIS phone for the signed-in account and remembers that push is On. Throws when the server or Firebase says no. */
    suspend fun enable(ctx: Context, api: ApiClient) {
        api.registerPushToken(FirebaseMessaging.getInstance().token.await())
        Prefs(ctx).pushEnabled = true
    }

    /** Push Off on this phone. The phone stops showing pushes at once; telling the server is best effort (a dead token is dropped by the server anyway). */
    suspend fun disable(ctx: Context, api: ApiClient) {
        Prefs(ctx).pushEnabled = false
        try { api.removePushToken(FirebaseMessaging.getInstance().token.await()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { /* offline or signed out already: nothing more to do */ }
    }

    /** Called each time the app opens signed in: Firebase may have given this phone a new token, so register the current one again. */
    suspend fun refresh(ctx: Context, api: ApiClient) {
        if (!Prefs(ctx).pushEnabled || !permissionGranted(ctx)) return
        try { api.registerPushToken(FirebaseMessaging.getInstance().token.await()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { /* try again next time the app opens */ }
    }
}

object PushNotifier {
    private const val CHANNEL = "sociax_push"
    private const val NOTIF_ID = 46
    /** The kinds the server may send (the same as the bell list). Anything else is ignored. */
    private val TYPES = setOf(
        "message", "follow", "follow_request", "follow_accepted", "post_like", "post_react", "post_comment",
        "group_added", "short_like", "short_react", "short_comment", "ai_reply"
    )

    fun show(ctx: Context, data: Map<String, String>) {
        val prefs = Prefs(ctx)
        if (!prefs.pushEnabled || !PushRegistrar.permissionGranted(ctx)) return        // switched off / signed out / no permission: show nothing
        val type = data["type"]?.takeIf { it in TYPES } ?: return
        val actor = (data["actor"] ?: "").filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '@' }.take(40)
        val count = (data["count"]?.toIntOrNull() ?: 1).coerceIn(1, 999)
        val ref = data["ref"]?.toLongOrNull() ?: 0L
        val lang = Lang.fromCode(prefs.language)
        val body = when {
            type == "ai_reply" -> Strings.get(lang, "notif_ai_reply")
            type == "message" && count > 1 -> String.format(Strings.get(lang, "notif_messages_n"), actor, count)
            else -> String.format(Strings.get(lang, "notif_$type"), actor)
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, Strings.get(lang, "push_channel_name"), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_push)
            .setContentTitle("Sociax").setContentText(body)
            .setAutoCancel(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // same tag = the newest one replaces the older one for the same chat / item (3 messages show as ONE "3 new messages")
        try { NotificationManagerCompat.from(ctx).notify("$type:$ref", NOTIF_ID, n) } catch (e: SecurityException) { }
    }
}

/** Receives the data messages sent by the server. A new FCM token is picked up by PushRegistrar.refresh the next time the app opens. */
class SociaxMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        PushNotifier.show(applicationContext, message.data)
    }
}
