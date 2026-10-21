package com.sociax.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import com.sociax.app.data.ApiClient
import com.sociax.app.data.ApiException
import com.sociax.app.data.AuthRepository
import com.sociax.app.data.Me
import com.sociax.app.data.Prefs
import com.sociax.app.data.Post
import com.sociax.app.data.ShortVideo
import com.sociax.app.data.StoryGroup
import com.sociax.app.data.ChatSummary
import com.sociax.app.data.LimitEvents
import com.sociax.app.ui.screens.*
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Gradient continues behind the status bar and navigation bar (like the reference). Icons stay light: every background is blue/navy.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // Saved accent colour is applied before the first screen is drawn.
        Teal = Color(Prefs(applicationContext).accent)
        val auth = AuthRepository(applicationContext)
        val api = ApiClient { auth.idToken() }
        setContent { SociaxApp(this, auth, api) }
    }
}

enum class Tab(val icon: ImageVector, val label: String, val labelKey: String) {
    // thin outlined icons like the reference
    Home(Icons.Outlined.Home, "Home", "tab_home"),
    Bio(Icons.Outlined.Person, "Bio", "tab_bio"),
    Video(Icons.Outlined.PlayCircle, "Short Video", "tab_video"),
    Chat(Icons.Outlined.ChatBubbleOutline, "Chat", "tab_chat")
}

private enum class Stage { Loading, LoggedOut, NeedsOnboarding, Ready }

/** Full-screen pages that open on top of the 4 tabs. */
private sealed interface Overlay {
    data object Settings : Overlay
    data object EditProfile : Overlay
    data object NewPost : Overlay
    data object Search : Overlay
    data object Requests : Overlay
    data object Notifications : Overlay   // Step 27
    data object NewShort : Overlay
    data object NewChat : Overlay
    data object NewStory : Overlay
    data class Story(val groups: List<StoryGroup>, val index: Int) : Overlay
    data object NewGroup : Overlay
    data object Ai : Overlay
    data class UserProfile(val username: String, val backTo: Overlay? = null) : Overlay
    data class FollowList(val username: String, val kind: String, val backTo: Overlay? = null) : Overlay   // Step 20
    data class Conversation(val chat: ChatSummary) : Overlay
    data class GroupInfo(val chat: ChatSummary) : Overlay
    data class Comments(val post: Post) : Overlay
    data class ShortComments(val short: ShortVideo) : Overlay
    data object Blocked : Overlay
    data object Saved : Overlay
    data object SavedShorts : Overlay   // Step 21
}

@Composable
fun SociaxApp(activity: MainActivity, auth: AuthRepository, api: ApiClient) {
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(activity) }
    var themeMode by remember {
        mutableStateOf(runCatching { ThemeMode.valueOf(prefs.themeMode) }.getOrDefault(ThemeMode.DARK))
    }
    var lang by remember { mutableStateOf(Lang.fromCode(prefs.language)) }
    var textSize by remember { mutableStateOf(runCatching { TextSize.valueOf(prefs.textSize) }.getOrDefault(TextSize.NORMAL)) }
    var accent by remember { mutableStateOf(prefs.accent) }
    var reduceMotion by remember { mutableStateOf(prefs.reduceMotion) }
    val dark = isDarkMode(themeMode)
    var stage by remember { mutableStateOf(Stage.Loading) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var me by remember { mutableStateOf(Me(onboarded = false)) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var feedKey by remember { mutableIntStateOf(0) }   // bump to reload the feed
    val shorts = remember { ShortsState() }            // Short Video feed, kept while pages open on top

    // Phone back button closes an open page. Leaving comments/search may change counts, so reload the feed.
    var returnTo by remember { mutableStateOf<Overlay?>(null) }   // a post opened from "Saved posts" goes back there
    BackHandler(enabled = overlay != null) {
        val o = overlay
        overlay = when (o) {
            is Overlay.GroupInfo -> Overlay.Conversation(o.chat)       // group info goes back to its chat
            Overlay.Blocked, Overlay.Saved, Overlay.SavedShorts -> Overlay.Settings   // the Settings lists go back to Settings
            is Overlay.Comments -> returnTo.also { returnTo = null }
            is Overlay.UserProfile -> o.backTo           // a profile opened from a followers list goes back to that list
            is Overlay.FollowList -> o.backTo
            else -> null
        }
        feedKey++
    }

    // Decide the starting screen: returning users skip onboarding.
    suspend fun routeAfterLogin() {
        try {
            me = api.me()
            stage = if (me.onboarded) Stage.Ready else Stage.NeedsOnboarding
            error = null
        } catch (e: ApiException) {
            error = e.message; stage = Stage.LoggedOut
        } catch (e: Exception) {
            error = "Cannot reach the Sociax server. Is the backend running?"; stage = Stage.LoggedOut
        }
    }

    LaunchedEffect(Unit) {
        if (auth.isSignedIn) routeAfterLogin() else stage = Stage.LoggedOut
    }

    // Step 25: "Online / last seen" heartbeat, only while the app is on screen and my switch is on
    val lifeOwner = LocalLifecycleOwner.current
    var appOnScreen by remember { mutableStateOf(true) }
    DisposableEffect(lifeOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_PAUSE) appOnScreen = false else if (ev == Lifecycle.Event.ON_RESUME) appOnScreen = true
        }
        lifeOwner.lifecycle.addObserver(obs)
        onDispose { lifeOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(stage, me.showLastSeen, appOnScreen) {
        if (stage != Stage.Ready || !me.showLastSeen || !appOnScreen) return@LaunchedEffect
        while (true) {
            try { api.heartbeat() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            delay(45000)
        }
    }

    // Step 27: number of unread notifications (dot on the Home bell), refreshed every 30 s while no page is open on top
    var notifUnread by remember { mutableIntStateOf(0) }
    LaunchedEffect(stage, appOnScreen, overlay == null) {
        if (stage != Stage.Ready || !appOnScreen || overlay != null) return@LaunchedEffect
        while (true) {
            try { notifUnread = api.notificationUnread() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            delay(30000)
        }
    }

    SociaxTheme(themeMode, textSize.factor, lang, reduceMotion) {
      androidx.compose.runtime.CompositionLocalProvider(LocalOpenProfile provides { name: String -> overlay = Overlay.UserProfile(name) }) {
        SociaxBackground(dark, Modifier.fillMaxSize()) {
            val limit by LimitEvents.current.collectAsState()
            limit?.let { ev ->
                LimitDialog(ev,
                    onSeePlans = { LimitEvents.current.value = null; if (stage == Stage.Ready) overlay = Overlay.Settings },
                    onDismiss = { LimitEvents.current.value = null })
            }
            when {
                stage == Stage.Loading -> LoadingScreen()
                stage == Stage.LoggedOut -> LoginScreen(busy, error) {
                    scope.launch {
                        busy = true; error = null
                        auth.signInWithGoogle(activity)
                            .onSuccess { routeAfterLogin() }
                            .onFailure { error = it.message ?: "Sign-in failed" }
                        busy = false
                    }
                }
                stage == Stage.NeedsOnboarding -> OnboardingScreen(busy, error) { name, username, age, source ->
                    scope.launch {
                        busy = true; error = null
                        try {
                            api.onboard(name, username, age, source)
                            me = api.me()
                            stage = Stage.Ready
                        } catch (e: ApiException) {
                            error = e.message
                        } catch (e: Exception) {
                            error = "Cannot reach the Sociax server."
                        }
                        busy = false
                    }
                }
                else -> Box(Modifier.fillMaxSize().then(if (overlay != null && overlay !is Overlay.Story) Modifier.navigationBarsPadding() else Modifier)) { when (val o = overlay) {
                    Overlay.EditProfile -> EditProfileScreen(me, api, onSaved = { me = it }, onBack = { overlay = null })
                    Overlay.Settings -> SettingsScreen(
                        mode = themeMode, onMode = { themeMode = it; prefs.themeMode = it.name },
                        lang = lang, onLang = { lang = it; prefs.language = it.code },
                        accent = accent, onAccent = { accent = it; prefs.accent = it; Teal = Color(it) },
                        textSize = textSize, onTextSize = { textSize = it; prefs.textSize = it.name },
                        reduceMotion = reduceMotion, onReduceMotion = { reduceMotion = it; prefs.reduceMotion = it },
                        onBack = { overlay = null }, api = api,
                        me = me, onMeChanged = { me = it },
                        onEditProfile = { overlay = Overlay.EditProfile },
                        onBlocked = { overlay = Overlay.Blocked },
                        onSaved = { overlay = Overlay.Saved },
                        onSavedShorts = { overlay = Overlay.SavedShorts },
                        onDeleted = { auth.signOut(); overlay = null; me = Me(onboarded = false); stage = Stage.LoggedOut },
                        onLogout = { auth.signOut(); overlay = null; me = Me(onboarded = false); stage = Stage.LoggedOut })
                    Overlay.Blocked -> BlockedUsersScreen(api, onBack = { overlay = Overlay.Settings })
                    Overlay.SavedShorts -> SavedShortsScreen(api, onBack = { overlay = Overlay.Settings })
                    Overlay.Saved -> SavedPostsScreen(api, onBack = { overlay = Overlay.Settings },
                        onOpenPost = { returnTo = Overlay.Saved; overlay = Overlay.Comments(it) })
                    Overlay.NewPost -> CreatePostScreen(api, onBack = { overlay = null },
                        onPosted = {
                            overlay = null; feedKey++
                            scope.launch { runCatching { me = api.me() } }   // refresh post count
                        }, defaultFollowersOnly = me.defaultAudience == "followers")
                    Overlay.Search -> SearchScreen(api, onBack = { overlay = null; feedKey++ }, onComments = { overlay = Overlay.Comments(it) })
                    Overlay.Notifications -> NotificationsScreen(api, onBack = { overlay = null; notifUnread = 0 },
                        onRequests = { overlay = Overlay.Requests },
                        onOpenUser = { overlay = Overlay.UserProfile(it, backTo = Overlay.Notifications) },
                        onOpenChat = { tab = Tab.Chat; overlay = null; notifUnread = 0 })
                    Overlay.Requests -> RequestsScreen(api, onBack = {
                        overlay = null
                        scope.launch { runCatching { me = api.me() } }
                    })
                    is Overlay.Comments -> CommentsScreen(api, o.post, onBack = { overlay = returnTo; returnTo = null; feedKey++ }, onChanged = {})
                    Overlay.NewShort -> CreateShortScreen(api, onBack = { overlay = null },
                        defaultFollowersOnly = me.defaultAudience == "followers",
                        onPosted = { overlay = null; shorts.reset() })   // reload the feed so the new clip shows up
                    is Overlay.ShortComments -> ShortCommentsScreen(api, o.short, onBack = { overlay = null },
                        onChanged = { shorts.replace(o.short.id) { it.copy(commentCount = it.commentCount + 1) } })
                    Overlay.NewStory -> CreateStoryScreen(api, onBack = { overlay = null },
                        onPosted = { overlay = null; feedKey++ })   // reload so the new ring shows up
                    is Overlay.Story -> StoryViewerScreen(api, o.groups, o.index, onClose = { overlay = null; feedKey++ })
                    Overlay.NewChat -> NewChatScreen(api, onBack = { overlay = null },
                        onOpen = { overlay = Overlay.Conversation(it) })
                    Overlay.Ai -> AiAssistantScreen(api, onBack = { overlay = null })
                    is Overlay.UserProfile -> UserProfileScreen(api, o.username, onBack = { overlay = o.backTo },
                        onOpenPost = { overlay = Overlay.Comments(it) }, onChat = { overlay = Overlay.Conversation(it) },
                        onOpenList = { name, kind -> overlay = Overlay.FollowList(name, kind, backTo = o) })
                    is Overlay.FollowList -> FollowListScreen(api, o.username, o.kind, onBack = { overlay = o.backTo },
                        onOpenUser = { overlay = Overlay.UserProfile(it, backTo = o) })
                    Overlay.NewGroup -> NewGroupScreen(api, onBack = { overlay = null },
                        onCreated = { overlay = Overlay.Conversation(it) })
                    is Overlay.Conversation -> ConversationScreen(api, o.chat, onBack = { overlay = null },
                        onInfo = { overlay = Overlay.GroupInfo(o.chat) })
                    is Overlay.GroupInfo -> GroupInfoScreen(api, o.chat, me.username,
                        onBack = { overlay = Overlay.Conversation(o.chat) }, onLeft = { overlay = null })
                    // The tab bar floats ON TOP of the page (like the reference); every tab leaves room for it at its bottom.
                    // Short Video is full-bleed (video behind the status bar and the bar), the other tabs start under the status bar.
                    null -> Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().then(if (tab == Tab.Video) Modifier else Modifier.statusBarsPadding())) {
                            when (tab) {
                                Tab.Home -> HomeScreen(api, feedKey,
                                    onCreate = { overlay = Overlay.NewPost },
                                    onSearch = { overlay = Overlay.Search },
                                    onNotifications = { overlay = Overlay.Notifications },
                                    notifUnread = notifUnread,
                                    onMessages = { tab = Tab.Chat },
                                    onAddStory = { overlay = Overlay.NewStory },
                                    onOpenStory = { groups, i -> overlay = Overlay.Story(groups, i) },
                                    onComments = { overlay = Overlay.Comments(it) })
                                Tab.Bio -> BioScreen(me,
                                    onOpenList = { kind -> me.username?.let { overlay = Overlay.FollowList(it, kind) } },
                                    onSettings = { overlay = Overlay.Settings },
                                    onEdit = { overlay = Overlay.EditProfile },
                                    onRequests = { overlay = Overlay.Requests },
                                    onCreateGroup = { tab = Tab.Chat; overlay = Overlay.NewGroup },
                                    api = api, onOpenPost = { overlay = Overlay.Comments(it) })
                                Tab.Video -> ShortVideoScreen(api, shorts,
                                    onCreate = { overlay = Overlay.NewShort },
                                    onComments = { overlay = Overlay.ShortComments(it) },
                                    onBack = { tab = Tab.Home })
                                Tab.Chat -> ChatListScreen(api,
                                    onOpen = { overlay = Overlay.Conversation(it) },
                                    onNewChat = { overlay = Overlay.NewChat },
                                    onNewGroup = { overlay = Overlay.NewGroup },
                                    onAi = { overlay = Overlay.Ai })
                            }
                        }
                        BottomBar(tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
                    }
                } }
            }
        }
      }
    }
}

/**
 * Exactly four primary tabs, floating frosted pill like the reference: round frosted circles, the selected one is
 * a soft-white circle with a dark icon. It floats above the page (the page scrolls behind it).
 */
@Composable
fun BottomBar(selected: Tab, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    GlassPanel(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp).navigationBarsPadding(),
        corner = 40, fill = BarFill, borderColor = ControlBorder
    ) {
        Row(
            Modifier.fillMaxWidth().padding(7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.values().forEach { t ->
                val active = t == selected
                Box(
                    Modifier.size(46.dp).clip(CircleShape)
                        .background(if (active) PaperWhite else BarItemFill)
                        .clickable { onSelect(t) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(t.icon, tr(t.labelKey), tint = if (active) Navy else Color.White, modifier = Modifier.size(23.dp))
                }
            }
        }
    }
}
