package com.sociax.app.data

import com.sociax.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** `reason` is the server's machine-readable code when there is one: "ai_limit" or "media_full" (plan section 8). */
class ApiException(val code: Int, message: String, val reason: String? = null) : Exception(message)

/** A quota was hit: kind = "ai" (free AI allowance used up) or "media" (today's media allowance used up). MainActivity shows the popup. */
data class LimitEvent(val kind: String, val resetHours: Int = 0)

object LimitEvents {
    val current = kotlinx.coroutines.flow.MutableStateFlow<LimitEvent?>(null)
}

/** Today's media allowance (plan v3.2). [resetAt] = epoch seconds when the 24-hour counter restarts (0 = no upload yet today). */
data class MediaQuota(val usedMb: Double, val limitMb: Int, val proLimitMb: Int, val remainingMb: Double,
                      val resetAt: Long = 0, val pro: Boolean = false, val proExpiresAt: Long = 0)
data class AiQuota(val used: Int, val limit: Int, val resetInSeconds: Long, val pro: Boolean = false, val proExpiresAt: Long = 0)
data class PaymentStart(val txRef: String, val checkoutUrl: String)
data class PaymentResult(val status: String, val granted: Boolean, val proExpiresAt: Long)
data class AiAnswer(val answer: String, val quota: AiQuota)

/** Step 40: one page of a ranked list. [nextCursor] = send it back for the next page (null = end). [ranked] false = the server fell back to newest-first. */
data class RankedPage<T>(val items: List<T>, val nextCursor: String?, val ranked: Boolean)

/** Step 40: how much the recommendation engine holds about me (counts only), shown in Settings. */
data class RecState(val events: Int, val notInterested: Int, val hiddenCreators: Int, val retentionDays: Int)

data class Me(
    val onboarded: Boolean,
    val username: String? = null,
    val displayName: String? = null,
    val bio: String = "",
    val avatarUrl: String? = null,
    val isPrivate: Boolean = false,
    val whoCanMessage: String = "everyone",     // Step 19: "everyone" | "followers" | "nobody"
    val defaultAudience: String = "public",     // Step 19: "public" | "followers"
    val followFirst: Boolean = false,           // Step 22: ask people to follow me before I read their messages
    val showLastSeen: Boolean = true,           // Step 25: show my online / last seen (and see other people's)
    val readReceipts: Boolean = true,           // Step 43: show "Seen" in one-to-one chats (and see other people's)
    val notifications: Map<String, Boolean> = NOTIF_GROUPS.associateWith { true },   // Step 27: which notification groups are on
    val language: String? = null,               // Step 37: app language saved on the account (null = never saved)
    val posts: Int = 0,
    val followers: Int = 0,
    val following: Int = 0
)

/** Step 26: everything one search finds. */
data class SearchResult(val users: List<UserHit>, val posts: List<Post>, val shorts: List<ShortVideo>, val next: SearchNext = SearchNext())

/** Step 45: where the next page of each result list starts (null = that list has no more). */
data class SearchNext(val users: Int? = null, val posts: Int? = null, val shorts: Int? = null)

/** Step 25: someone's online status. [lastSeenAt] = epoch seconds of the last heartbeat. null (no object) = hidden. */
data class Presence(val online: Boolean, val lastSeenAt: Long)

data class UploadSignature(
    val cloudName: String, val apiKey: String, val timestamp: Long,
    val publicId: String, val allowedFormats: String, val signature: String
)

private fun JSONObject.toMe() = Me(
    onboarded = optBoolean("onboarded"),
    username = optString("username").ifEmpty { null },
    displayName = optString("display_name").ifEmpty { null },
    bio = optString("bio"),
    avatarUrl = optString("avatar_url").ifEmpty { null },
    isPrivate = optBoolean("is_private"),
    whoCanMessage = optString("who_can_message").ifEmpty { "everyone" },
    defaultAudience = optString("default_audience").ifEmpty { "public" },
    followFirst = optBoolean("follow_first"),
    showLastSeen = if (has("show_last_seen")) optBoolean("show_last_seen") else true,
    readReceipts = if (has("read_receipts")) optBoolean("read_receipts") else true,
    notifications = NOTIF_GROUPS.associateWith { g -> optJSONObject("notifications")?.optBoolean(g, true) ?: true },
    language = optString("language").ifEmpty { null },
    posts = optInt("posts"), followers = optInt("followers"), following = optInt("following")
)

/** Talks to the Sociax FastAPI backend. Every call sends the Firebase ID token. */
class ApiClient(private val tokenProvider: suspend () -> String?) {

    private val http = OkHttpClient()
    // Videos are bigger than photos: allow longer reads/writes for the Cloudinary upload only.
    private val uploadHttp = http.newBuilder()
        .writeTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    // AI answers can take a while: longer read timeout for /ai/ask only.
    private val aiHttp = http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()
    // Step 37: the first call after start-up may reach a sleeping free server (it can need up to a minute to wake): wait for it
    // here, on the "connecting" screen, instead of failing after 10 seconds.
    private val bootHttp = http.newBuilder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    private suspend fun call(method: String, path: String, body: JSONObject? = null, client: OkHttpClient = http): JSONObject =
        withContext(Dispatchers.IO) {
            val token = tokenProvider() ?: throw ApiException(401, "Not signed in")
            val payload = body?.toString()?.toRequestBody(json)
                ?: if (method == "POST" || method == "PATCH") "{}".toRequestBody(json) else null
            val req = Request.Builder()
                .url(BuildConfig.API_BASE_URL + path)
                .header("Authorization", "Bearer $token")
                .method(method, payload)
                .build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = runCatching { JSONObject(text).opt("detail") }.getOrNull()
                    val obj = detail as? JSONObject
                    val msg = obj?.optString("message")?.ifEmpty { null } ?: (detail as? String) ?: "Request failed (${r.code})"
                    val reason = obj?.optString("code")?.ifEmpty { null }
                    when (reason) {
                        "ai_limit" -> LimitEvents.current.value = LimitEvent("ai", ((obj?.optLong("reset_in_seconds") ?: 0L) / 3600).toInt() + 1)
                        "media_full" -> LimitEvents.current.value = LimitEvent("media", ((obj?.optLong("reset_in_seconds") ?: 0L) / 3600).toInt() + 1)
                    }
                    throw ApiException(r.code, msg, reason)
                }
                JSONObject(text.ifEmpty { "{}" })
            }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- Sociax AI (Grok leads, Gemini assists; keys live on the server only) ----
    // ---- other people's profiles (the server decides what I may see) ----
    suspend fun userProfile(username: String): UserProfile = call("GET", "/users/${handle(username)}").toUserProfile()

    suspend fun userPosts(username: String, before: Long? = null): List<Post> =
        call("GET", "/users/${handle(username)}/posts" + (before?.let { "?before=$it" } ?: ""))
            .getJSONArray("posts").mapObjects { it.toPost() }

    suspend fun setBlock(username: String, on: Boolean) { call(if (on) "POST" else "DELETE", "/users/${handle(username)}/block") }

    suspend fun mediaQuota(): MediaQuota = call("GET", "/quota/media").let {
        MediaQuota(it.optDouble("used_mb", 0.0), it.optInt("limit_mb", 500), it.optInt("pro_limit_mb", 1536),
            it.optDouble("remaining_mb", 0.0), if (it.isNull("reset_at")) 0L else it.optLong("reset_at", 0),
            it.optBoolean("pro", false), if (it.isNull("pro_expires_at")) 0L else it.optLong("pro_expires_at", 0))
    }

    suspend fun aiQuota(): AiQuota = call("GET", "/quota/ai").let {
        AiQuota(it.optInt("used", 0), it.optInt("free_requests", 0), it.optLong("reset_in_seconds", 0),
            it.optBoolean("pro", false), if (it.isNull("pro_expires_at")) 0L else it.optLong("pro_expires_at", 0))
    }

    /** Step 42: which AI providers the SERVER has connected (names only, never keys). */
    suspend fun aiProviders(): List<String> = call("GET", "/ai/status").optJSONObject("providers").let { o ->
        if (o == null) emptyList() else o.keys().asSequence().filter { o.optBoolean(it, false) }.toList()
    }

    // ---- Sociax Pro payment ($15 / month, Chapa TEST mode). The price is fixed on the SERVER; the app never sends one,
    // and "paid" is only ever decided by the server after it checks with Chapa. There is ONE paid plan (plan v3.2). ----
    suspend fun startProPayment(): PaymentStart = startPayment("pro")

    // ---- App Lock recovery (Step 32): a fresh Google sign-in -> one-time token -> redeemed once. The PIN / pattern never leaves the phone. ----
    suspend fun startLockRecovery(): String = call("POST", "/lock/recovery").getString("token")
    suspend fun confirmLockRecovery(token: String) { call("POST", "/lock/recovery/confirm", JSONObject().apply { put("token", token) }) }

    private suspend fun startPayment(product: String): PaymentStart = call("POST", "/payments/initialize?product=${enc(product)}").let {
        PaymentStart(it.getString("tx_ref"), it.getString("checkout_url"))
    }

    suspend fun verifyPayment(txRef: String): PaymentResult = call("GET", "/payments/verify/${enc(txRef)}").let {
        PaymentResult(it.optString("status"), it.optBoolean("granted"), it.optLong("pro_expires_at", 0))
    }

    suspend fun askAi(prompt: String): AiAnswer =
        call("POST", "/ai/ask", JSONObject().apply { put("prompt", prompt) }, aiHttp).let {
            AiAnswer(it.getString("answer"), AiQuota(it.optInt("used", 0), it.optInt("limit", 0), it.optLong("reset_in_seconds", 0)))
        }
    private fun handle(username: String) = enc(username.removePrefix("@"))

    // ---- account / profile ----
    suspend fun me(): Me = call("GET", "/users/me", client = bootHttp).toMe()

    /** Step 37: keep the app language on the account too (the phone keeps its own copy, so this never blocks anything). */
    suspend fun setLanguage(code: String) { call("PATCH", "/users/me", JSONObject().apply { put("language", code) }) }

    suspend fun onboard(displayName: String, username: String, age: Int, source: String?, language: String? = null) {
        call("POST", "/users/onboard", JSONObject().apply {
            put("display_name", displayName); put("username", username); put("age", age)
            if (source != null) put("source", source)
            if (language != null) put("language", language)
        })
    }

    suspend fun updateProfile(displayName: String, bio: String, isPrivate: Boolean, username: String? = null): Me =
        call("PATCH", "/users/me", JSONObject().apply {
            put("display_name", displayName); put("bio", bio); put("is_private", isPrivate)
            username?.let { put("username", it) }     // Step 29: the server checks the rules and uniqueness
        }).toMe()

    /** Step 19: change only the privacy choices that are given (the server leaves the other fields alone). */
    suspend fun updatePrivacy(whoCanMessage: String? = null, defaultAudience: String? = null, followFirst: Boolean? = null,
                              showLastSeen: Boolean? = null, readReceipts: Boolean? = null): Me =
        call("PATCH", "/users/me", JSONObject().apply {
            followFirst?.let { put("follow_first", it) }
            showLastSeen?.let { put("show_last_seen", it) }
            readReceipts?.let { put("read_receipts", it) }
            whoCanMessage?.let { put("who_can_message", it) }
            defaultAudience?.let { put("default_audience", it) }
        }).toMe()

    /** Step 46: this phone wants push for this account / does not want it any more. */
    suspend fun registerPushToken(token: String) { call("POST", "/push/token", JSONObject().put("token", token)) }
    suspend fun removePushToken(token: String) { call("POST", "/push/token/remove", JSONObject().put("token", token)) }

    /** Step 27: switch one notification group on / off (the server leaves the other groups alone). */
    suspend fun updateNotifications(group: String, on: Boolean): Me =
        call("PATCH", "/users/me", JSONObject().apply { put("notifications", JSONObject().put(group, on)) }).toMe()

    /** Step 27: my notifications, newest first (30 per page; [before] = `nextBefore` of the previous page). */
    suspend fun notifications(before: Long? = null): NotificationPage =
        call("GET", "/notifications?limit=30" + (before?.let { "&before=$it" } ?: "")).let { o ->
            NotificationPage(
                o.getJSONArray("notifications").mapObjects { it.toNotificationItem() }, o.optInt("unread"),
                if (o.isNull("next_before")) null else o.optLong("next_before")
            )
        }

    suspend fun notificationUnread(): Int = call("GET", "/notifications/unread-count").optInt("unread")

    suspend fun markNotificationsRead() { call("POST", "/notifications/read", JSONObject()) }

    /** Step 25: "I am here" heartbeat (the server records it only while my Online / last seen switch is on). */
    suspend fun heartbeat() { call("POST", "/presence", JSONObject()) }

    /** Step 25: null = hidden (switched off, blocked, no chat together, nothing recorded). */
    suspend fun presence(username: String): Presence? =
        call("GET", "/presence/${handle(username)}").optJSONObject("presence")
            ?.let { Presence(it.optBoolean("online"), it.optLong("last_seen_at")) }

    suspend fun confirmAvatar(publicId: String): Me =
        call("POST", "/users/me/avatar", JSONObject().apply { put("public_id", publicId) }).toMe()

    // ---- media upload (purpose = "avatar" | "post" | "short" | "story") ----
    suspend fun signUpload(purpose: String, sizeBytes: Int): UploadSignature =
        call("POST", "/media/sign-upload", JSONObject().apply { put("purpose", purpose); put("size_bytes", sizeBytes) })
            .let {
                UploadSignature(
                    it.getString("cloud_name"), it.getString("api_key"), it.getLong("timestamp"),
                    it.getString("public_id"), it.getString("allowed_formats"), it.getString("signature")
                )
            }

    /** Uploads straight to Cloudinary using the server-made signature (no secret in the app). */
    suspend fun uploadToCloudinary(sig: UploadSignature, jpegBytes: ByteArray) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "photo.jpg", jpegBytes.toRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("api_key", sig.apiKey)
            .addFormDataPart("timestamp", sig.timestamp.toString())
            .addFormDataPart("public_id", sig.publicId)
            .addFormDataPart("allowed_formats", sig.allowedFormats)
            .addFormDataPart("signature", sig.signature)
            .build()
        val req = Request.Builder()
            .url("https://api.cloudinary.com/v1_1/${sig.cloudName}/image/upload").post(body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "Photo upload failed")
        }
    }

    // ---- posts / feed ----
    suspend fun createPost(publicId: String, caption: String, audience: String): Post =
        call("POST", "/posts", JSONObject().apply {
            put("public_id", publicId); put("caption", caption); put("audience", audience)
        }).toPost()

    suspend fun deletePost(id: Long) { call("DELETE", "/posts/$id") }

    suspend fun feed(tab: String, before: Long? = null): List<Post> =
        call("GET", "/feed?tab=$tab" + (before?.let { "&before=$it" } ?: ""))
            .getJSONArray("posts").mapObjects { it.toPost() }

    // ---- Step 40: ranked For You, watch signals and recommendation controls (plan v3.9 sections 31, 32) ----
    /** Reads one ranked page. If a page comes back empty only because items were filtered out at serve time, it asks for the next one (max 3 tries). */
    private suspend fun <T> rankedPage(path: String, key: String, cursor: String?, parse: (JSONObject) -> T): RankedPage<T> {
        var cur = cursor
        var tries = 0
        while (true) {
            val o = call("GET", if (cur == null) path else path + "&cursor=" + enc(cur))
            val items = o.getJSONArray(key).mapObjects(parse)
            val next = if (o.isNull("next_cursor")) null else o.optString("next_cursor").ifEmpty { null }
            tries++
            if (items.isNotEmpty() || next == null || tries >= 3) return RankedPage(items, next, o.optBoolean("ranked", false))
            cur = next
        }
    }

    /** Ranked For You photos. An older server without this address (404 on the first page) answers with the plain newest-first list. */
    suspend fun feedForYou(cursor: String?): RankedPage<Post> =
        try {
            rankedPage("/feed/for-you?limit=20", "posts", cursor) { it.toPost() }
        } catch (e: ApiException) {
            if (e.code == 404 && cursor == null) RankedPage(feed("for_you"), null, false) else throw e
        }

    /** Ranked For You short videos (same rules as feedForYou). */
    suspend fun shortsForYou(cursor: String?): RankedPage<ShortVideo> =
        try {
            rankedPage("/shorts/for-you?limit=10", "shorts", cursor) { it.toShortVideo() }
        } catch (e: ApiException) {
            if (e.code == 404 && cursor == null) RankedPage(shortsFeed(), null, false) else throw e
        }

    // Events are sent in the background and never block or break the screen: a lost event is harmless.
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val impressionsSent = java.util.Collections.synchronizedSet(HashSet<String>())
    private val sessionNonce = java.util.UUID.randomUUID().toString().take(8)

    /** What the phone SAW: impression / watch / complete / skip / share. Likes, saves, comments and reports are recorded by the server itself. */
    fun trackEvent(contentType: String, id: Long, event: String, valueMs: Long? = null) {
        if (event == "impression" && !impressionsSent.add("$contentType:$id")) return      // one impression per item per app session
        val key = if (event == "impression") "i:$contentType:$id:$sessionNonce" else "e:" + java.util.UUID.randomUUID().toString()
        eventScope.launch {
            try {
                call("POST", "/events/interaction", JSONObject().apply {
                    put("content_type", contentType); put("content_id", id); put("event", event)
                    put("idem_key", key); put("version", 1)
                    if (valueMs != null) put("value_ms", valueMs.coerceIn(0L, 3_600_000L))
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    // ---- Step 41 (plan v3.8 section 28): AdMob. The SERVER decides who may see ads and counts impressions (max 20 per day, Free only). ----
    private fun adsFrom(o: JSONObject) = AdsStatus(
        eligible = o.optBoolean("eligible", false), pro = o.optBoolean("pro", false),
        dailyCap = o.optInt("daily_cap", 20), used = o.optInt("used", 0), remaining = o.optInt("remaining", 0),
        resetsAt = o.optLong("resets_at", 0), everyMin = o.optInt("every_min", 3).coerceIn(2, 10),
        everyMax = o.optInt("every_max", 4).coerceIn(2, 10), minor = o.optBoolean("minor", false))

    suspend fun adsStatus(): AdsStatus = adsFrom(call("GET", "/ads/status"))

    /** Report ONE impression confirmed by the official SDK callback. The same key on a retry is counted once by the server. */
    suspend fun adImpression(key: String): AdsStatus =
        adsFrom(call("POST", "/ads/impression", JSONObject().apply { put("idem_key", key) }))

    /** "Not interested" / "Hide creator" (needs the item) and "reset" (wipes what the engine learned about me). */
    suspend fun recFeedback(action: String, contentType: String? = null, id: Long? = null) {
        call("POST", "/feedback/recommendation", JSONObject().apply {
            put("action", action)
            if (contentType != null) put("content_type", contentType)
            if (id != null) put("content_id", id)
        })
    }

    suspend fun recState(): RecState = call("GET", "/feedback/recommendation").let {
        RecState(it.optInt("events"), it.optInt("not_interested"), it.optInt("hidden_creators"), it.optInt("retention_days", 90))
    }

    suspend fun setLike(id: Long, on: Boolean) { call(if (on) "POST" else "DELETE", "/posts/$id/like") }
    suspend fun setReaction(id: Long, kind: String, on: Boolean) { call(if (on) "POST" else "DELETE", "/posts/$id/react/$kind") }
    suspend fun setBookmark(id: Long, on: Boolean) { call(if (on) "POST" else "DELETE", "/posts/$id/bookmark") }

    suspend fun comments(id: Long): List<Comment> =
        call("GET", "/posts/$id/comments").getJSONArray("comments").mapObjects { it.toComment() }

    suspend fun addComment(id: Long, text: String) {
        call("POST", "/posts/$id/comments", JSONObject().apply { put("text", text) })
    }

    suspend fun reportPost(id: Long, reason: String) {
        call("POST", "/posts/$id/report", JSONObject().apply { put("reason", reason) })
    }

    // ---- short video (max 25 s, checked again on the server) ----
    /** Uploads the prepared clip straight to Cloudinary (video endpoint) with the server-made signature. */
    suspend fun uploadVideoToCloudinary(sig: UploadSignature, file: File) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "clip.mp4", file.asRequestBody("video/mp4".toMediaType()))
            .addFormDataPart("api_key", sig.apiKey)
            .addFormDataPart("timestamp", sig.timestamp.toString())
            .addFormDataPart("public_id", sig.publicId)
            .addFormDataPart("allowed_formats", sig.allowedFormats)
            .addFormDataPart("signature", sig.signature)
            .build()
        val req = Request.Builder()
            .url("https://api.cloudinary.com/v1_1/${sig.cloudName}/video/upload").post(body).build()
        val call = uploadHttp.newCall(req)
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(ApiException(0, "Video upload failed. Check your connection."))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!cont.isActive) return
                        if (it.isSuccessful) cont.resume(Unit)
                        else cont.resumeWithException(ApiException(it.code, "Video upload failed (${it.code})"))
                    }
                }
            })
        }
    }

    /** Plan v3.9.1: no length is sent and none is required; the server reads the real size / type from Cloudinary and the length only if it can. */
    suspend fun createShort(publicId: String, caption: String, audience: String, commentsEnabled: Boolean): ShortVideo =
        call("POST", "/shorts", JSONObject().apply {
            put("public_id", publicId); put("caption", caption); put("audience", audience)
            put("comments_enabled", commentsEnabled)
        }).toShortVideo()

    suspend fun shortsFeed(before: Long? = null): List<ShortVideo> =
        call("GET", "/shorts/feed?limit=10" + (before?.let { "&before=$it" } ?: ""))
            .getJSONArray("shorts").mapObjects { it.toShortVideo() }

    /** Step 21: short videos I saved (newest first, 30 per page). The server hides ones I may no longer see. */
    suspend fun savedShorts(before: Long? = null): List<ShortVideo> =
        call("GET", "/shorts/saved" + (before?.let { "?before=$it" } ?: ""))
            .getJSONArray("shorts").mapObjects { it.toShortVideo() }

    suspend fun deleteShort(id: Long) { call("DELETE", "/shorts/$id") }
    suspend fun setShortLike(id: Long, on: Boolean) { call(if (on) "POST" else "DELETE", "/shorts/$id/like") }
    suspend fun setShortReaction(id: Long, kind: String, on: Boolean) { call(if (on) "POST" else "DELETE", "/shorts/$id/react/$kind") }
    suspend fun setShortBookmark(id: Long, on: Boolean) { call(if (on) "POST" else "DELETE", "/shorts/$id/bookmark") }

    /** Returns the comments and whether the owner allows comments on this video. */
    suspend fun shortComments(id: Long): Pair<List<Comment>, Boolean> =
        call("GET", "/shorts/$id/comments").let {
            it.getJSONArray("comments").mapObjects { o -> o.toComment() } to it.optBoolean("comments_enabled", true)
        }

    suspend fun addShortComment(id: Long, text: String) {
        call("POST", "/shorts/$id/comments", JSONObject().apply { put("text", text) })
    }

    suspend fun reportShort(id: Long, reason: String) {
        call("POST", "/shorts/$id/report", JSONObject().apply { put("reason", reason) })
    }

    /** Settings > Help & legal > Report a problem (Step 28). */
    suspend fun reportProblem(text: String) {
        call("POST", "/feedback", JSONObject().apply {
            put("text", text)
            put("app_version", com.sociax.app.BuildConfig.VERSION_NAME)
        })
    }

    // ---- chat, groups, AI Join (all checks are enforced on the server) ----
    suspend fun chats(): List<ChatSummary> =
        call("GET", "/chats").getJSONArray("chats").mapObjects { it.toChatSummary() }

    suspend fun startDm(username: String): ChatSummary =
        call("POST", "/chats/dm", JSONObject().apply { put("username", username.removePrefix("@")) }).toChatSummary()

    /** [after] = id of the newest message you already have (polling); null = latest page. */
    suspend fun messages(chatId: Long, after: Long?): List<ChatMessage> = messagesPage(chatId, after).messages

    /** Step 43: the same call, plus what the other person has seen (`peer_read_id`, null = receipts not visible). */
    suspend fun messagesPage(chatId: Long, after: Long?): MessagePage =
        call("GET", "/chats/$chatId/messages" + (after?.let { "?after=$it" } ?: "")).let { o ->
            MessagePage(
                o.getJSONArray("messages").mapObjects { it.toChatMessage() },
                if (o.has("peer_read_id") && !o.isNull("peer_read_id")) o.optLong("peer_read_id") else null
            )
        }

    suspend fun sendMessage(chatId: Long, text: String): SendResult =
        call("POST", "/chats/$chatId/messages", JSONObject().apply { put("text", text) }).let {
            SendResult(it.getJSONObject("message").toChatMessage(), it.optString("ai").ifEmpty { null }.takeIf { v -> v != "null" },
                it.optJSONObject("follow_prompt")?.optString("username")?.ifEmpty { null })
        }

    suspend fun markRead(chatId: Long) { call("POST", "/chats/$chatId/read") }

    suspend fun createGroup(name: String, usernames: List<String>, confirmed: Boolean): ChatSummary =
        call("POST", "/groups", JSONObject().apply {
            put("name", name); put("confirmed", confirmed)
            put("usernames", org.json.JSONArray(usernames.map { it.removePrefix("@") }))
        }).toChatSummary()

    suspend fun groupInfo(id: Long): GroupInfo = call("GET", "/groups/$id").toGroupInfo()

    suspend fun addGroupMember(id: Long, username: String) {
        call("POST", "/groups/$id/members", JSONObject().apply { put("username", username.removePrefix("@")) })
    }

    suspend fun removeGroupMember(id: Long, username: String) { call("DELETE", "/groups/$id/members/${handle(username)}") }

    suspend fun setGroupRole(id: Long, username: String, admin: Boolean) {
        call("PUT", "/groups/$id/members/${handle(username)}/role", JSONObject().apply { put("role", if (admin) "admin" else "member") })
    }

    suspend fun setGroupAi(id: Long, enabled: Boolean) {
        call("PUT", "/groups/$id/ai", JSONObject().apply { put("enabled", enabled) })
    }

    suspend fun deleteGroup(id: Long) { call("DELETE", "/groups/$id") }

    // ---- stories (photo, visible 24 hours, only to accepted followers; the server enforces this) ----
    suspend fun stories(): List<StoryGroup> =
        call("GET", "/stories").getJSONArray("groups").mapObjects { it.toStoryGroup() }

    suspend fun createStory(publicId: String, caption: String): StoryItem =
        call("POST", "/stories", JSONObject().apply { put("public_id", publicId); put("caption", caption) }).toStoryItem()

    suspend fun createTextStory(text: String): StoryItem =
        call("POST", "/stories/text", JSONObject().apply { put("text", text) }).toStoryItem()

    suspend fun viewStory(id: Long) { call("POST", "/stories/$id/view") }

    suspend fun deleteStory(id: Long) { call("DELETE", "/stories/$id") }

    // ---- follow / search ----
    /** Returns the new status: accepted | pending | none */
    suspend fun setFollow(username: String, on: Boolean): String =
        call(if (on) "POST" else "DELETE", "/users/${handle(username)}/follow").getString("status")

    suspend fun followRequests(): List<FollowRequest> =
        call("GET", "/follow-requests").getJSONArray("requests").mapObjects { it.toFollowRequest() }

    suspend fun answerRequest(username: String, accept: Boolean) {
        call("POST", "/follow-requests/${handle(username)}/${if (accept) "accept" else "decline"}")
    }

    suspend fun search(q: String): Pair<List<UserHit>, List<Post>> = searchAll(q).let { it.users to it.posts }

    /** Step 26: people + public posts + public short videos (the server hides everything private or blocked). */
    suspend fun searchAll(q: String): SearchResult = searchPage(q, null, 0)

    /** Step 45: the next page of ONE list ([type] = "users" | "posts" | "shorts", [offset] = the `next` value the server gave). */
    suspend fun searchMore(q: String, type: String, offset: Int): SearchResult = searchPage(q, type, offset)

    private suspend fun searchPage(q: String, type: String?, offset: Int): SearchResult =
        call("GET", "/search?q=${enc(q.take(50))}" + (type?.let { "&type=$it&offset=$offset" } ?: "")).let {
            val n = it.optJSONObject("next")
            fun nextOf(k: String): Int? = if (n != null && n.has(k) && !n.isNull(k)) n.optInt(k) else null
            SearchResult(
                it.getJSONArray("users").mapObjects { o -> o.toUserHit() },
                it.getJSONArray("posts").mapObjects { o -> o.toPost() },
                it.optJSONArray("shorts")?.mapObjects { o -> o.toShortVideo() } ?: emptyList(),
                SearchNext(nextOf("users"), nextOf("posts"), nextOf("shorts"))
            )
        }

    // ---- privacy lists + account deletion (Step 17) ----
    suspend fun blockedUsers(): List<BlockedUser> =
        call("GET", "/users/me/blocked").getJSONArray("users").mapObjects { it.toBlockedUser() }

    /** Step 20: kind = "followers" or "following". Returns one page (50) and whether more pages exist. */
    suspend fun followList(username: String, kind: String, offset: Int = 0): Pair<List<BlockedUser>, Boolean> {
        val r = call("GET", "/users/${handle(username)}/$kind?offset=$offset")
        return r.getJSONArray("users").mapObjects { it.toBlockedUser() } to r.optBoolean("more")
    }

    suspend fun savedPosts(before: Long? = null): List<Post> =
        call("GET", "/posts/saved" + (before?.let { "?before=$it" } ?: "")).getJSONArray("posts").mapObjects { it.toPost() }

    /** Irreversible: the server removes the profile, posts, shorts, stories, chats and the login. */
    suspend fun deleteAccount() { call("DELETE", "/users/me") }
}
