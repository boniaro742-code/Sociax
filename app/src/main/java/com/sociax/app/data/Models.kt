package com.sociax.app.data

import org.json.JSONArray
import org.json.JSONObject

/** One emoji reaction on a post: how many people used it and whether I did. */
data class React(val count: Int = 0, val mine: Boolean = false)

/** The extra reactions next to the heart (like), in the order shown: 😮 😂 😢. Codes match the server. */
val REACTION_KINDS = listOf("wow" to "😮", "haha" to "😂", "sad" to "😢")

data class Post(
    val id: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val caption: String,
    val imageUrl: String?,
    val audience: String,
    val createdAt: Long,
    val likeCount: Int,
    val commentCount: Int,
    val liked: Boolean,
    val saved: Boolean,
    val followStatus: String,   // self | none | pending | accepted
    val reactions: Map<String, React> = emptyMap()
) {
    val mine get() = followStatus == "self"
}

data class ShortVideo(
    val id: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val caption: String,
    val videoUrl: String?,
    val videoUrlLow: String?,   // smaller 480p copy, used when Data Saver is on
    val posterUrl: String?,
    val duration: Double,
    val audience: String,
    val commentsEnabled: Boolean,
    val createdAt: Long,
    val likeCount: Int,
    val commentCount: Int,
    val liked: Boolean,
    val followStatus: String,  // self | none | pending | accepted
    val saved: Boolean = false,
    val saveCount: Int = 0,
    val reactions: Map<String, React> = emptyMap()
) {
    val mine get() = followStatus == "self"
}

data class ChatSummary(
    val id: Long, val kind: String, val title: String, val username: String?, val avatarUrl: String?,
    val unread: Int, val lastText: String, val lastKind: String?, val lastAt: Long,
    val aiEnabled: Boolean, val myRole: String
) { val isGroup get() = kind == "group" }

data class ChatMessage(
    val id: Long, val kind: String, val text: String, val createdAt: Long, val mine: Boolean,
    val username: String?, val displayName: String?, val avatarUrl: String?,
    val held: Boolean = false     // Step 22: waiting until I follow the recipient ("Follow me first")
)

/** Step 43: one page of messages. [peerReadId] = id of the newest message of mine the OTHER person has seen in a one-to-one chat;
 *  null = read receipts are not visible (group chat, or either of us switched them off). */
data class MessagePage(val messages: List<ChatMessage>, val peerReadId: Long?)

/** `followPrompt` = @username of the person who asked to be followed first (null = nothing to ask). */
data class SendResult(val message: ChatMessage, val ai: String?, val followPrompt: String? = null)

data class GroupMember(val username: String, val displayName: String, val avatarUrl: String?, val role: String)
data class GroupInfo(val id: Long, val name: String, val aiEnabled: Boolean, val myRole: String, val members: List<GroupMember>)

data class Comment(val id: Long, val username: String, val avatarUrl: String?, val text: String, val createdAt: Long)

data class UserHit(
    val username: String, val displayName: String, val avatarUrl: String?,
    val isPrivate: Boolean, val followStatus: String
)

data class FollowRequest(val username: String, val displayName: String, val avatarUrl: String?)

/**
 * One story (photo or text-only). It disappears 24 hours after [createdAt]. [viewCount] is only filled in for my own stories.
 * A text story has no photo: [kind] is "text" and the words are in [caption].
 */
data class StoryItem(
    val id: Long, val imageUrl: String?, val caption: String, val createdAt: Long,
    val seen: Boolean, val viewCount: Int, val mine: Boolean, val kind: String = "photo"
) { val isText get() = kind == "text" }

/** All active stories of one person (one ring on the Home screen). */
data class StoryGroup(
    val username: String, val displayName: String, val avatarUrl: String?,
    val isMe: Boolean, val allSeen: Boolean, val stories: List<StoryItem>
)

/** Step 27: the six notification groups of Settings > Notifications (same codes as the server). */
val NOTIF_GROUPS = listOf("messages", "follows", "likes_comments", "group_activity", "shorts", "ai")

/** Step 27: one entry of the notification list. [username] is null when the AI is the "actor". */
data class NotificationItem(
    val id: Long, val type: String, val category: String, val ref: Long, val count: Int, val createdAt: Long,
    val read: Boolean, val username: String?, val displayName: String?, val avatarUrl: String?
)

data class NotificationPage(val items: List<NotificationItem>, val unread: Int, val nextBefore: Long?)

private fun JSONObject.str(k: String) = optString(k).ifEmpty { null }

fun JSONObject.toNotificationItem() = NotificationItem(
    id = getLong("id"), type = getString("type"), category = optString("category"), ref = optLong("ref"),
    count = optInt("count", 1), createdAt = getLong("created_at"), read = optBoolean("read"),
    username = str("username"), displayName = str("display_name"), avatarUrl = str("avatar_url")
)

fun JSONObject.toPost() = Post(
    id = getLong("id"), username = getString("username"), displayName = optString("display_name"),
    avatarUrl = str("avatar_url"), caption = optString("caption"), imageUrl = str("image_url"),
    audience = optString("audience", "public"), createdAt = getLong("created_at"),
    likeCount = optInt("like_count"), commentCount = optInt("comment_count"),
    liked = optBoolean("liked"), saved = optBoolean("saved"), followStatus = optString("follow_status", "none"),
    reactions = optJSONObject("reactions")?.let { o ->
        REACTION_KINDS.associate { (k, _) -> k to (o.optJSONObject(k)?.let { React(it.optInt("count"), it.optBoolean("mine")) } ?: React()) }
    } ?: emptyMap()
)

fun JSONObject.toShortVideo() = ShortVideo(
    id = getLong("id"), username = getString("username"), displayName = optString("display_name"),
    avatarUrl = str("avatar_url"), caption = optString("caption"), videoUrl = str("video_url"),
    videoUrlLow = str("video_url_low"), posterUrl = str("poster_url"), duration = optDouble("duration", 0.0),
    audience = optString("audience", "public"), commentsEnabled = optBoolean("comments_enabled", true),
    createdAt = getLong("created_at"), likeCount = optInt("like_count"), commentCount = optInt("comment_count"),
    liked = optBoolean("liked"), followStatus = optString("follow_status", "none"),
    saved = optBoolean("saved"), saveCount = optInt("save_count"),
    reactions = optJSONObject("reactions")?.let { o ->
        REACTION_KINDS.associate { (k, _) -> k to (o.optJSONObject(k)?.let { React(it.optInt("count"), it.optBoolean("mine")) } ?: React()) }
    } ?: emptyMap()
)

fun JSONObject.toChatSummary() = ChatSummary(
    id = getLong("id"), kind = getString("kind"), title = optString("title"), username = str("username"),
    avatarUrl = str("avatar_url"), unread = optInt("unread"), lastText = optString("last_text"),
    lastKind = str("last_kind"), lastAt = optLong("last_at"), aiEnabled = optBoolean("ai_enabled"),
    myRole = optString("my_role", "member")
)

fun JSONObject.toChatMessage() = ChatMessage(
    id = getLong("id"), kind = optString("kind", "user"), text = optString("text"), createdAt = getLong("created_at"),
    mine = optBoolean("mine"), username = str("username"), displayName = str("display_name"), avatarUrl = str("avatar_url"),
    held = optBoolean("held")
)

fun JSONObject.toGroupInfo() = GroupInfo(
    id = getLong("id"), name = optString("name"), aiEnabled = optBoolean("ai_enabled"), myRole = optString("my_role", "member"),
    members = getJSONArray("members").mapObjects {
        GroupMember(it.getString("username"), it.optString("display_name"), it.optString("avatar_url").ifEmpty { null }, it.optString("role", "member"))
    }
)

fun JSONObject.toComment() = Comment(getLong("id"), getString("username"), str("avatar_url"), getString("text"), getLong("created_at"))
fun JSONObject.toUserHit() = UserHit(getString("username"), optString("display_name"), str("avatar_url"),
    optBoolean("is_private"), optString("follow_status", "none"))
data class BlockedUser(val username: String, val displayName: String, val avatarUrl: String?)
fun JSONObject.toBlockedUser() = BlockedUser(getString("username"), optString("display_name"), str("avatar_url"))
fun JSONObject.toFollowRequest() = FollowRequest(getString("username"), optString("display_name"), str("avatar_url"))

fun JSONObject.toStoryItem() = StoryItem(
    id = getLong("id"), imageUrl = str("image_url"), caption = optString("caption"), createdAt = getLong("created_at"),
    seen = optBoolean("seen"), viewCount = optInt("view_count"), mine = optBoolean("mine"),
    kind = optString("kind", "photo")
)

fun JSONObject.toStoryGroup() = StoryGroup(
    username = getString("username"), displayName = optString("display_name"), avatarUrl = str("avatar_url"),
    isMe = optBoolean("is_me"), allSeen = optBoolean("all_seen"),
    stories = getJSONArray("stories").mapObjects { it.toStoryItem() }
)

fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> = List(length()) { f(getJSONObject(it)) }

/** "now", "5m", "3h", "2d" ... */
fun timeAgo(epochSeconds: Long): String {
    val d = System.currentTimeMillis() / 1000 - epochSeconds
    return when {
        d < 60 -> "now"
        d < 3600 -> "${d / 60}m"
        d < 86400 -> "${d / 3600}h"
        d < 86400 * 7 -> "${d / 86400}d"
        else -> "${d / (86400 * 7)}w"
    }
}


/** Someone's profile page. [posts] is null when a private account hides its post count. */
data class UserProfile(
    val username: String, val displayName: String, val bio: String, val avatarUrl: String?,
    val isPrivate: Boolean, val posts: Int?, val followers: Int, val following: Int,
    val followStatus: String,           // self | none | pending | accepted
    val followsMe: Boolean, val canViewPosts: Boolean, val canMessage: Boolean,
    val iBlocked: Boolean, val isMe: Boolean
)

fun JSONObject.toUserProfile() = UserProfile(
    username = getString("username"), displayName = optString("display_name"), bio = optString("bio"),
    avatarUrl = str("avatar_url"), isPrivate = optBoolean("is_private"),
    posts = if (isNull("posts")) null else optInt("posts"),
    followers = optInt("followers"), following = optInt("following"),
    followStatus = optString("follow_status", "none"), followsMe = optBoolean("follows_me"),
    canViewPosts = optBoolean("can_view_posts"), canMessage = optBoolean("can_message"),
    iBlocked = optBoolean("i_blocked"), isMe = optBoolean("is_me")
)
