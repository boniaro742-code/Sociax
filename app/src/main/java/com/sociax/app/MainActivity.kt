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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.AdsController
import com.sociax.app.data.ApiClient
import com.sociax.app.data.ApiException
import com.sociax.app.data.AuthRepository
import com.sociax.app.data.SessionCheck
import com.sociax.app.data.Me
import com.sociax.app.data.Perf
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
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    /** Status / navigation bar icons: dark on a light page, light on a dark page or over full-screen media. */
    fun applySystemBars(lightIcons: Boolean) {
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (lightIcons) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
            navigationBarStyle = if (lightIcons) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Step 59: the page colour continues behind the status bar and the navigation bar. The icon colour follows the theme (see SociaxApp).
        val startPrefs = Prefs(applicationContext)
        val startMode = runCatching { ThemeMode.valueOf(startPrefs.themeMode) }.getOrDefault(ThemeMode.LIGHT)
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val startDark = when (startMode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemDark }
        applySystemBars(startDark)
        // Saved accent colour and light / dark palette are applied before the first screen is drawn (no flash of the wrong colours).
        Accent = Color(startPrefs.accent)
        applyPalette(startDark)
        val auth = AuthRepository(applicationContext)
        val api = ApiClient { auth.idToken() }
        setContent { SociaxApp(this, auth, api) }
    }
}

enum class Tab(val icon: ImageVector, val activeIcon: ImageVector, val label: String, val labelKey: String) {
    // outlined icon when idle, filled icon + purple label when selected (new design)
    // Step 82 (plan v5.0.0 section 2): exactly five buttons in this order: Home, Short Video, AI, Chat, Profile (Profile is the fifth / rightmost).
    // "Bio" is gone as a name: Bio and Profile are ONE destination.
    Home(Icons.Outlined.Home, Icons.Filled.Home, "Home", "tab_home"),
    Video(Icons.Outlined.PlayCircle, Icons.Filled.PlayCircle, "Short Video", "tab_video"),
    // Step 58 (plan v3.9.5 / v3.9.6): EVERY AI tool lives behind this button; Chat has no AI of its own.
    // Step 59: the AI tab is the Sociax AI chat itself.
    Ai(Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome, "AI", "tab_ai"),
    Chat(Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble, "Chat", "tab_chat"),
    Profile(Icons.Outlined.Person, Icons.Filled.Person, "Profile", "tab_profile");

    /** Plan v5.0.0 section 3: Short Video, AI, Chat and Profile use the whole screen (no bottom bar). Home is the only exception. */
    val fullScreen: Boolean get() = this != Home
}

private enum class Stage { Loading, LoggedOut, NeedsOnboarding, Ready }

/** Full-screen pages that open on top of the 5 tabs. */
private sealed interface Overlay {
    data object Settings : Overlay
    data object EditProfile : Overlay
    data class NewPost(val uri: Uri? = null) : Overlay   // Step 83: uri = the photo that was taken / chosen in the Create screen
    data object Search : Overlay
    data object Requests : Overlay
    data object Notifications : Overlay   // Step 27
    data class NewShort(val uri: Uri? = null) : Overlay  // Step 83: uri = the video that was recorded / chosen in the Create screen
    data object Create : Overlay                         // Step 83 (plan v5.0.0 section 5): the ONE Create entry: Take Photo / Record Video / Choose from Gallery
    data object NewChat : Overlay
    data object NewStory : Overlay
    data class Story(val groups: List<StoryGroup>, val index: Int) : Overlay
    data object NewGroup : Overlay
    data class UserProfile(val username: String, val backTo: Overlay? = null) : Overlay
    data class FollowList(val username: String, val kind: String, val backTo: Overlay? = null) : Overlay   // Step 20
    data class Conversation(val chat: ChatSummary) : Overlay
    data class GroupInfo(val chat: ChatSummary) : Overlay
    data class Comments(val post: Post) : Overlay
    data class ShortComments(val short: ShortVideo, val backTo: Overlay? = null) : Overlay   // backTo: the full-screen Home video it was opened from (Step 75)
    data class HomeVideo(val startId: Long) : Overlay   // Step 75 (plan v4.0.1): a Home video tapped -> the full-screen Short Video player on that video
    data object Blocked : Overlay
    data object Saved : Overlay
    data object SavedShorts : Overlay   // Step 21
    data object ChangeLock : Overlay    // Step 32
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
    SideEffect { applyPalette(dark) }          // Step 59: every colour token follows the chosen theme (light / dark / system)
    var stage by remember { mutableStateOf(Stage.Loading) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var startupError by remember { mutableStateOf<String?>(null) }   // Step 37: shown on the start-up screen with "Try again"
    var waking by remember { mutableStateOf(false) }                  // Step 51: the server is being retried automatically (sleeping free server)
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var me by remember { mutableStateOf(Me(onboarded = false)) }
    var remembered by remember { mutableStateOf(prefs.rememberedAccounts) }   // Step 54: accounts used on this phone (login screen list)
    LaunchedEffect(stage) { if (stage == Stage.LoggedOut) remembered = prefs.rememberedAccounts }
    var tab by remember { mutableStateOf(Tab.Home) }
    var feedKey by remember { mutableIntStateOf(0) }   // bump to reload the feed
    val shorts = remember { ShortsState() }            // Short Video feed, kept while pages open on top
    val homeFeed = remember { HomeFeedState() }        // Step 75: the Home rows + scroll position, kept while pages open on top of Home
    var homeViewer by remember { mutableStateOf<ShortsState?>(null) }   // Step 75: the player opened from a Home video (null = none open)
    val aiChat = remember { AiChatState() }            // Step 59: the AI conversation, kept while the person visits other tabs (wiped on log out)
    val ads = remember { AdsController(api, activity.applicationContext) }   // Step 41: Start.io ads (Free accounts only, decided by the server)
    // Step 41: every time the Short Video tab is the screen the person is looking at (opened, or back from Settings / a payment),
    // ask the SERVER again who may see ads: a Pro purchase switches ads off at once, a reached cap or a new day is noticed.
    LaunchedEffect(stage, tab, overlay) {
        if (stage == Stage.Ready && ((tab == Tab.Video && overlay == null) || overlay is Overlay.HomeVideo)) ads.refresh(activity)
    }

    // Phone back button closes an open page. Leaving comments/search may change counts, so reload the feed.
    var returnTo by remember { mutableStateOf<Overlay?>(null) }   // a post opened from "Saved posts" goes back there
    // Step 56 (plan v3.10.1): while the soft keyboard is open, Back only CLOSES THE KEYBOARD. The page stays and the typed text stays.
    // (Normally the keyboard itself takes the Back key first; this is the safety net for phones / Android versions where it does not.)
    val rootView = LocalView.current
    val focusManager = LocalFocusManager.current
    val hideKeyboard = rememberKeyboardHider()
    // Step 75: leaving the full-screen Home video: what was done there is applied to the Home rows (no reload, the scroll position stays)
    fun closeHomeVideo() { homeViewer?.let { homeFeed.absorbViewer(it); it.clearAds() }; homeViewer = null }
    BackHandler(enabled = overlay != null) {
        val keyboardUp = ViewCompat.getRootWindowInsets(rootView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (keyboardUp) { hideKeyboard(); focusManager.clearFocus(); return@BackHandler }
        val o = overlay
        val inHomeVideo = homeViewer != null      // while the Home video (or its comments / a profile opened from it) is open Home is NOT reloaded
        overlay = when (o) {
            is Overlay.HomeVideo -> { closeHomeVideo(); null }
            is Overlay.ShortComments -> o.backTo
            is Overlay.GroupInfo -> Overlay.Conversation(o.chat)       // group info goes back to its chat
            Overlay.Blocked, Overlay.Saved, Overlay.SavedShorts, Overlay.ChangeLock -> Overlay.Settings   // the Settings lists go back to Settings
            is Overlay.Comments -> returnTo.also { returnTo = null }
            is Overlay.NewPost -> if (o.uri != null) Overlay.Create else null     // Step 83: back from the editor flow to the Create screen
            is Overlay.NewShort -> if (o.uri != null) Overlay.Create else null
            is Overlay.UserProfile -> o.backTo           // a profile opened from a followers list goes back to that list
            is Overlay.FollowList -> o.backTo
            else -> null
        }
        if (!inHomeVideo) feedKey++
    }

    // Step 82 (plan v5.0.0 section 3): Short Video, AI, Chat and Profile are full-screen destinations without the bottom bar. The phone's
    // Back (and their own top Back arrow) returns to Home, where the five-button bar is. While the keyboard is open Back only closes it.
    BackHandler(enabled = stage == Stage.Ready && overlay == null && tab.fullScreen) {
        val keyboardUp = ViewCompat.getRootWindowInsets(rootView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (keyboardUp) { hideKeyboard(); focusManager.clearFocus() } else tab = Tab.Home
    }

    // Decide the starting screen: returning users skip onboarding.
    /** Step 37 (plan v3.5): the app language and the account language stay together.
     *  New phone, nothing chosen here yet -> the account's language is used. Chosen here -> it is saved on the account. */
    suspend fun syncLanguage() {
        val saved = me.language
        if (!prefs.languageChosen && saved != null) {
            lang = Lang.fromCode(saved); prefs.adoptLanguage(lang.code)
        } else if (prefs.languageChosen && me.onboarded && saved != lang.code) {
            runCatching { api.setLanguage(lang.code) }.onSuccess { me = me.copy(language = lang.code) }
        }
    }

    // Step 51: what the person sees when the server cannot be reached after choosing a Google account. It now says WHAT failed and which
    // address the app is using (so one screenshot is enough to find the cause), and an app built WITHOUT the server address says so.
    fun connectFailure(detail: String): String {
        if (BuildConfig.API_BASE_URL.contains("10.0.2.2")) return Strings.get(lang, "err_no_server_address")
        val host = runCatching { java.net.URI(BuildConfig.API_BASE_URL).host }.getOrNull() ?: BuildConfig.API_BASE_URL
        return Strings.get(lang, "err_connecting") + " [" + detail + " \u00B7 " + host + "]"
    }

    suspend fun routeAfterLoginRun() {
        // Step 51: a free Render server sleeps and answers 502 / 503 or times out while it wakes up. Try up to 3 times by itself
        // (8 s, then 20 s apart) before showing the error, so the person does not have to tap "Try again" for a normal wake-up.
        val pauses = longArrayOf(8_000L, 20_000L)
        var attempt = 0
        var dataLoading = false
        var refreshedOnce = false      // Step 57: a 401 first gets ONE fresh token before it is believed
        while (true) {
            var problem: String? = null
            try {
                startupError = null
                me = api.me()
                if (me.onboarded) syncLanguage()
                // Step 54 (plan v3.9.2 section 3): remember WHO signed in here (name + @username only) for the login screen
                if (me.onboarded) auth.uid?.let { prefs.rememberAccount(it, me.displayName.orEmpty(), me.username.orEmpty()) }
                stage = if (me.onboarded) Stage.Ready else Stage.NeedsOnboarding
                error = null
                waking = false
                Perf.end("login_to_ready")
                return
            } catch (e: ApiException) {
                // Plan v3.6 section 4: choosing a Google account is NOT a finished login. If the server answers wrong, say what it means.
                android.util.Log.w("SociaxAuth", "after Google sign-in the server answered HTTP ${e.code}")
                if (e.code == 401 && !refreshedOnce) {
                    // Step 57 (plan v3.10.0 section 4): "not signed in" from the server may only mean an OLD token. Ask Firebase for a new one
                    // first. Only when Firebase itself says the session is invalid is the person asked to sign in again; no network = keep waiting.
                    refreshedOnce = true
                    when (auth.refreshSession()) {
                        SessionCheck.Valid -> continue
                        SessionCheck.Offline -> problem = "offline"
                        SessionCheck.Invalid -> {
                            auth.signOut(); waking = false
                            error = Strings.get(lang, "err_session_ended")
                            stage = Stage.LoggedOut
                            return
                        }
                    }
                } else if (e.code >= 500) {        // the server is starting or failing: stay signed in
                    problem = "HTTP ${e.code}"
                    // Step 54: the server answers 503 "data_unavailable" while it loads the saved database. That is NOT an empty
                    // account: the person stays signed in and is told so (never sent to "create a new account").
                    if (e.reason == "data_unavailable") dataLoading = true
                } else {
                    waking = false
                    error = when (e.code) {
                        404 -> Strings.get(lang, "err_wrong_server")
                        401 -> Strings.get(lang, "err_session_ended")
                        else -> e.message
                    }
                    stage = Stage.LoggedOut
                    return
                }
            } catch (e: Exception) {
                android.util.Log.w("SociaxAuth", "after Google sign-in the server could not be reached (${e.javaClass.simpleName})")
                problem = e.javaClass.simpleName.ifEmpty { "error" }
            }
            // reached only when the server could not be used this time (stay signed in; never throw the person back to the login screen)
            if (attempt < pauses.size && !BuildConfig.API_BASE_URL.contains("10.0.2.2")) {
                waking = true; stage = Stage.Loading
                delay(pauses[attempt]); attempt++
            } else {
                waking = false
                startupError = if (dataLoading) Strings.get(lang, "err_data_loading") else connectFailure(problem ?: "error")
                stage = Stage.Loading
                return
            }
        }
    }

    // Step 57 (plan v3.10.0 section 5): ONE start-up / session decision at a time. "Try again", the network coming back and a fresh
    // Google sign-in can all ask for it; a second request while one is running is ignored (no double work, no flicker).
    val routingNow = remember { booleanArrayOf(false) }
    suspend fun routeAfterLogin() {
        if (routingNow[0]) return
        routingNow[0] = true
        try { routeAfterLoginRun() } finally { routingNow[0] = false }
    }

    // Step 37: timings (Logcat tag SociaxPerf): process start -> first screen -> login screen / home
    LaunchedEffect(stage) {
        Perf.mark(when (stage) {
            Stage.Loading -> "startup_screen_shown"; Stage.LoggedOut -> "login_screen_shown"
            Stage.NeedsOnboarding -> "onboarding_shown"; Stage.Ready -> "home_ready"
        })
    }

    // Step 57 (plan v3.10.0 sections 3 and 5): the screen stays on "loading" until Firebase has said whether a saved session exists, so a
    // signed-in person is never shown the login or the onboarding page first, and goes straight to Home.
    LaunchedEffect(Unit) {
        if (auth.awaitSession()) routeAfterLogin() else stage = Stage.LoggedOut
    }

    // Step 57: the app was opened without internet (the saved account is kept, nothing is reset). When the network comes back, the same
    // start-up decision runs again by itself; the person does not need to tap anything.
    val startupErrorNow by rememberUpdatedState(startupError)
    DisposableEffect(Unit) {
        val cm = activity.getSystemService(android.net.ConnectivityManager::class.java)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                handler.post { if (startupErrorNow != null) scope.launch { routeAfterLogin() } }
            }
        }
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
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
    // Step 32 (plan v3.3 section 2): mandatory App Lock. A fresh start always asks; coming back asks again after the timeout.
    // No lock yet (first-time account, or an older install) -> the setup screen comes first and cannot be skipped.
    val lock = remember { com.sociax.app.data.AppLock(activity) }
    var lockSet by remember { mutableStateOf(lock.isSet) }
    var locked by remember { mutableStateOf(lock.isSet) }
    // Step 54 (plan v3.9.2): logging out keeps the lock (it is a setting) but it belongs to ONE account. Someone else signing in on
    // this phone never uses it and never sees anything of the previous account: their lock is cleared and they set their own.
    // Just signed in with Google = the same proof "Forgot PIN" asks for, so the same account is not asked for the PIN a second time.
    fun adoptLockAfterSignIn() {
        val uid = auth.uid ?: return
        if (lock.isSet) {
            val owner = lock.ownerUid
            if (owner != null && owner != uid) { lock.clear(); lockSet = false }
            else lock.bindOwner(uid)
        }
        locked = false
    }
    // An app that starts with a session already open: a lock made before this version has no owner yet and belongs to this account.
    LaunchedEffect(stage) {
        val uid = auth.uid
        if (stage == Stage.Ready && uid != null && lock.isSet) {
            val owner = lock.ownerUid
            if (owner == null) lock.bindOwner(uid)
            else if (owner != uid) { lock.clear(); lockSet = false; locked = false }
        }
    }
    DisposableEffect(lifeOwner) {
        var leftAt = 0L
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) leftAt = android.os.SystemClock.elapsedRealtime()
            else if (ev == Lifecycle.Event.ON_START) {
                if (lock.isSet && leftAt > 0 && android.os.SystemClock.elapsedRealtime() - leftAt >= lock.timeoutSec * 1000L) locked = true
                leftAt = 0L
            }
        }
        lifeOwner.lifecycle.addObserver(obs)
        onDispose { lifeOwner.lifecycle.removeObserver(obs) }
    }

    // Step 33: if daily alarms are On, make sure the next 3:00 AM / 3:00 PM are scheduled for the phone's current time zone
    LaunchedEffect(stage) { if (stage == Stage.Ready && prefs.alarmsEnabled) com.sociax.app.data.DailyAlarm.schedule(activity) }

    // Step 46: push is On for this phone -> register the CURRENT Firebase token again (it may have changed since last time)
    LaunchedEffect(stage) { if (stage == Stage.Ready) com.sociax.app.data.PushRegistrar.refresh(activity, api) }

    LaunchedEffect(stage, me.showLastSeen, appOnScreen) {
        if (stage != Stage.Ready || !me.showLastSeen || !appOnScreen) return@LaunchedEffect
        while (true) {
            try { api.heartbeat() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            delay(45000)
        }
    }

    // Step 27: number of unread notifications (dot on the Home bell), refreshed every 30 s while no page is open on top
    var notifUnread by remember { mutableIntStateOf(0) }
    var chatUnread by remember { mutableIntStateOf(0) }   // Step 59: the purple dot on the Chat tab (unread messages)
    LaunchedEffect(stage, appOnScreen, overlay == null) {
        if (stage != Stage.Ready || !appOnScreen || overlay != null) return@LaunchedEffect
        while (true) {
            try { notifUnread = api.notificationUnread() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            delay(30000)
        }
    }

    // Step 59: unread chat messages (dot on the Chat tab), refreshed every 45 s while no page is open on top. The Chat tab itself looks every 6 s.
    LaunchedEffect(stage, appOnScreen, overlay == null, tab) {
        if (stage != Stage.Ready || !appOnScreen || overlay != null) return@LaunchedEffect
        while (true) {
            try { chatUnread = api.chats().sumOf { it.unread } } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            delay(if (tab == Tab.Chat) 6000L else 45000L)
        }
    }

    // Step 59: status bar icons: light over the dark video / story, otherwise they follow the theme (dark icons on the light page)
    val mediaScreen = stage == Stage.Ready && ((overlay == null && tab == Tab.Video) || overlay is Overlay.Story || overlay is Overlay.HomeVideo ||
        (overlay is Overlay.NewPost && PhotoEditorFlag.open) ||   // Step 76: the dark photo editor needs light status-bar icons
        (overlay is Overlay.NewShort && VideoEditorFlag.open))   // Step 78: so does the dark video editor
    LaunchedEffect(dark, mediaScreen) { activity.applySystemBars(dark || mediaScreen) }

    // Step 57: Firebase ends the session by itself (account disabled / deleted, revoked elsewhere) -> back to sign-in with a clear message.
    // A normal Log out, a restart and a network problem never reach this.
    DisposableEffect(Unit) {
        val stop = auth.observeEnded {
            if (stage != Stage.LoggedOut) {
                error = Strings.get(lang, "err_session_ended")
                ads.reset(); shorts.reset(); homeFeed.reset(); homeViewer = null; aiChat.reset(); locked = false; overlay = null; tab = Tab.Home; returnTo = null; notifUnread = 0; chatUnread = 0
                me = Me(onboarded = false); stage = Stage.LoggedOut
            }
        }
        onDispose { stop() }
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
                stage == Stage.Loading -> LoadingScreen(startupError ?: (if (waking) Strings.get(lang, "waking_server") else null), if (startupError != null) ({ scope.launch { routeAfterLogin() } }) else null)
                stage == Stage.LoggedOut -> LoginScreen(busy, error, lang, { lang = it; prefs.language = it.code },
                    remembered = remembered, onForget = { id -> prefs.forgetAccount(id); remembered = prefs.rememberedAccounts }) {
                    scope.launch {
                        busy = true; error = null
                        Perf.start("login_to_ready")
                        auth.signInWithGoogle(activity)
                            .onSuccess { adoptLockAfterSignIn(); routeAfterLogin() }
                            .onFailure { error = it.message ?: "Sign-in failed" }
                        busy = false
                    }
                }
                stage == Stage.NeedsOnboarding -> {
                    // Step 57 (plan v3.10.0 sections 2 and 6): the first-time form is only for an account the SERVER says has no profile yet.
                    // What was typed before (this account only) is put back, so closing the app half way does not mean starting again.
                    val onboardUid = auth.uid
                    val seed = remember(onboardUid) {
                        onboardUid?.let { u ->
                            prefs.onboardingDraft(u)
                                ?: prefs.rememberedAccounts.firstOrNull { it.uid == u }?.let { Triple(it.displayName, it.username.removePrefix("@"), "") }
                        }
                    }
                    OnboardingScreen(busy, error,
                        initialName = seed?.first.orEmpty(), initialUsername = seed?.second.orEmpty(), initialAge = seed?.third.orEmpty(),
                        onDraft = { n, u, a -> onboardUid?.let { id -> prefs.saveOnboardingDraft(id, n, u, a) } }
                    ) { name, username, age, source ->
                        scope.launch {
                            busy = true; error = null
                            try {
                                try {
                                    api.onboard(name, username, age, source, if (prefs.languageChosen) lang.code else null)
                                } catch (e: ApiException) {
                                    // 409 = this account already has a profile (an earlier try was saved but its answer was lost): that is
                                    // success, not an error. 409 for a taken @username has no profile behind it and is shown as before.
                                    val saved = if (e.code == 409) runCatching { api.me() }.getOrNull() else null
                                    if (saved?.onboarded != true) throw e
                                }
                                // "Onboarding complete" only when the server really returns the saved profile.
                                val fresh = api.me()
                                if (fresh.onboarded) {
                                    me = fresh
                                    prefs.clearOnboardingDraft()
                                    auth.uid?.let { prefs.rememberAccount(it, fresh.displayName.orEmpty(), fresh.username.orEmpty()) }
                                    stage = Stage.Ready
                                } else error = Strings.get(lang, "err_connecting")
                            } catch (e: ApiException) {
                                error = e.message
                            } catch (e: Exception) {
                                error = "Cannot reach the Sociax server."
                            }
                            busy = false
                        }
                    }
                }
                // Step 56: every page opened on top of the tabs (Search, Chat, AI, comments, edit profile, new post ...) is lifted above the keyboard HERE, once.
                // navigationBarsPadding first, then imePadding: Compose subtracts what was already used, so nothing is added twice (no blank gap).
                else -> Box(Modifier.fillMaxSize().then(if (overlay != null && overlay !is Overlay.Story && overlay !is Overlay.HomeVideo) Modifier.navigationBarsPadding().imePadding() else Modifier)) { when (val o = overlay) {
                    Overlay.EditProfile -> EditProfileScreen(me, api, onSaved = { me = it }, onBack = { overlay = null })
                    Overlay.Settings -> SettingsScreen(
                        mode = themeMode, onMode = { themeMode = it; prefs.themeMode = it.name },
                        lang = lang, onLang = {
                            lang = it; prefs.language = it.code
                            scope.launch { runCatching { api.setLanguage(it.code) } }      // also saved on the account (the phone keeps its own copy)
                        },
                        accent = accent, onAccent = { accent = it; prefs.accent = it; Accent = Color(it) },
                        textSize = textSize, onTextSize = { textSize = it; prefs.textSize = it.name },
                        reduceMotion = reduceMotion, onReduceMotion = { reduceMotion = it; prefs.reduceMotion = it },
                        onBack = { overlay = null }, api = api,
                        me = me, onMeChanged = { me = it },
                        onEditProfile = { overlay = Overlay.EditProfile },
                        onBlocked = { overlay = Overlay.Blocked },
                        onSaved = { overlay = Overlay.Saved },
                        onSavedShorts = { overlay = Overlay.SavedShorts },
                        onDeleted = { auth.uid?.let { prefs.forgetAccount(it) }; prefs.clearOnboardingDraft(); prefs.pushEnabled = false; auth.signOut(); ads.reset(); shorts.reset(); homeFeed.reset(); homeViewer = null; aiChat.reset(); lock.clear(); lockSet = false; locked = false; overlay = null; tab = Tab.Home; returnTo = null; notifUnread = 0; chatUnread = 0; me = Me(onboarded = false); stage = Stage.LoggedOut },
                        onLogout = {
                            // Step 54 (plan v3.9.2 section 1): log out ONLY ends this session. Nothing on the server is touched (no call to
                            // deleteAccount), the App Lock and the settings stay on the phone, and only what is on screen in memory is cleared so
                            // that a different account signing in next sees nothing of this one.
                            val finish = { auth.signOut(); prefs.clearOnboardingDraft(); ads.reset(); shorts.reset(); homeFeed.reset(); homeViewer = null; aiChat.reset(); locked = false; overlay = null; tab = Tab.Home; returnTo = null; notifUnread = 0; chatUnread = 0; me = Me(onboarded = false); stage = Stage.LoggedOut }
                            // Step 46: tell the server this phone no longer wants push for this account BEFORE signing out (3 s at most; the phone stops showing pushes at once anyway)
                            if (prefs.pushEnabled) scope.launch { withTimeoutOrNull(3000) { com.sociax.app.data.PushRegistrar.disable(activity, api) }; prefs.pushEnabled = false; finish() }
                            else finish()
                        },
                        lock = lock, onChangeLock = { overlay = Overlay.ChangeLock })
                    Overlay.ChangeLock -> ChangeLockScreen(lock, onBack = { overlay = Overlay.Settings }, onDone = { overlay = Overlay.Settings })
                    Overlay.Blocked -> BlockedUsersScreen(api, onBack = { overlay = Overlay.Settings })
                    Overlay.SavedShorts -> SavedShortsScreen(api, onBack = { overlay = Overlay.Settings })
                    Overlay.Saved -> SavedPostsScreen(api, onBack = { overlay = Overlay.Settings },
                        onOpenPost = { returnTo = Overlay.Saved; overlay = Overlay.Comments(it) })
                    Overlay.Create -> CreateHubScreen(onBack = { overlay = null },
                        onPhoto = { overlay = Overlay.NewPost(it) }, onVideo = { overlay = Overlay.NewShort(it) })
                    is Overlay.NewPost -> CreatePostScreen(api, onBack = { overlay = if (o.uri != null) Overlay.Create else null },
                        onPosted = {
                            overlay = null; feedKey++
                            scope.launch { runCatching { me = api.me() } }   // refresh post count
                        }, defaultFollowersOnly = me.defaultAudience == "followers", initialUri = o.uri)
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
                    is Overlay.NewShort -> CreateShortScreen(api, onBack = { overlay = if (o.uri != null) Overlay.Create else null },
                        defaultFollowersOnly = me.defaultAudience == "followers", initialUri = o.uri,
                        onPosted = { overlay = null; shorts.reset() })   // reload the feed so the new clip shows up
                    is Overlay.ShortComments -> ShortCommentsScreen(api, o.short, onBack = { overlay = o.backTo },
                        onChanged = {
                            shorts.replace(o.short.id) { it.copy(commentCount = it.commentCount + 1) }
                            homeViewer?.replace(o.short.id) { it.copy(commentCount = it.commentCount + 1) }
                        })
                    // Step 75 (plan v4.0.1): the Short Video player, opened on the Home video that was tapped; Back goes to Home
                    is Overlay.HomeVideo -> {
                        val vs = homeViewer
                        if (vs != null) ShortVideoScreen(api, vs, ads, onCreate = {},
                            onComments = { overlay = Overlay.ShortComments(it, backTo = o) },
                            onClose = { closeHomeVideo(); overlay = null },
                            onOpenProfile = { name -> overlay = Overlay.UserProfile(name, backTo = o) })
                        else LaunchedEffect(Unit) { overlay = null }
                    }
                    Overlay.NewStory -> CreateStoryScreen(api, onBack = { overlay = null },
                        onPosted = { overlay = null; feedKey++ })   // reload so the new ring shows up
                    is Overlay.Story -> StoryViewerScreen(api, o.groups, o.index, onClose = { overlay = null; feedKey++ })
                    Overlay.NewChat -> NewChatScreen(api, onBack = { overlay = null },
                        onOpen = { overlay = Overlay.Conversation(it) })
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
                        // Step 82: pages leave room for the bar only while it is shown (Home); full-screen destinations take the whole screen.
                        CompositionLocalProvider(LocalBottomBarShown provides !tab.fullScreen) {
                        Box(Modifier.fillMaxSize().then(if (tab == Tab.Video) Modifier else Modifier.statusBarsPadding())) {
                            when (tab) {
                                Tab.Home -> HomeScreen(api, homeFeed, feedKey,
                                    onCreate = { overlay = Overlay.Create },
                                    onSearch = { overlay = Overlay.Search },
                                    onNotifications = { overlay = Overlay.Notifications },
                                    notifUnread = notifUnread,
                                    onMessages = { tab = Tab.Chat },
                                    onAddStory = { overlay = Overlay.NewStory },
                                    onOpenStory = { groups, i -> overlay = Overlay.Story(groups, i) },
                                    onComments = { overlay = Overlay.Comments(it) },
                                    onShortComments = { overlay = Overlay.ShortComments(it) },   // Step 74: comments of a video card in Home
                                    onOpenVideo = { v ->     // Step 75: tap on a Home video -> that exact video, full screen
                                        homeViewer = homeFeed.makeViewer(v.id)
                                        if (homeViewer != null) overlay = Overlay.HomeVideo(v.id)
                                    })
                                Tab.Profile -> BioScreen(me,
                                    onOpenList = { kind -> me.username?.let { overlay = Overlay.FollowList(it, kind) } },
                                    onSettings = { overlay = Overlay.Settings },
                                    onEdit = { overlay = Overlay.EditProfile },
                                    onRequests = { overlay = Overlay.Requests },
                                    onCreateGroup = { tab = Tab.Chat; overlay = Overlay.NewGroup },
                                    api = api, onOpenPost = { overlay = Overlay.Comments(it) },
                                    onBack = { tab = Tab.Home })
                                Tab.Video -> ShortVideoScreen(api, shorts, ads,
                                    onCreate = { overlay = Overlay.Create },
                                    onSearch = { overlay = Overlay.Search },
                                    onComments = { overlay = Overlay.ShortComments(it) },
                                    onBack = { tab = Tab.Home })
                                // Step 58: Chat = people and groups only. No AI card, button or shortcut here (plan v3.9.6 section 2).
                                Tab.Chat -> ChatListScreen(api,
                                    onOpen = { overlay = Overlay.Conversation(it) },
                                    onNewChat = { overlay = Overlay.NewChat },
                                    onNewGroup = { overlay = Overlay.NewGroup },
                                    onBack = { tab = Tab.Home })
                                // Step 59: the AI tab is the Sociax AI chat (its gear opens Settings).
                                Tab.Ai -> AiHubScreen(api, aiChat, onSettings = { overlay = Overlay.Settings }, onBack = { tab = Tab.Home })
                            }
                        }
                        }
                        // Step 82 (plan v5.0.0 section 3): the five-button bar is shown on Home only.
                        if (!tab.fullScreen) BottomBar(tab, chatUnread > 0, Modifier.align(Alignment.BottomCenter)) { tab = it }
                    }
                } }
            }
            // Covers everything (and blocks touches) while locked or while the lock is not set up yet.
            if (stage == Stage.Ready && (!lockSet || locked)) {
                LockGate(activity, auth, api, lock, mustSetup = !lockSet,
                    onUnlocked = { locked = false },
                    onSetupDone = { lock.bindOwner(auth.uid); lockSet = true; locked = false })
            }
        }
      }
    }
}

/**
 * Exactly five primary tabs (Home, Short Video, AI, Chat, Profile) in a white bar docked at the bottom of HOME (the other four are full-screen, plan v5.0.0), like the new design: a thin edge
 * on top, an outlined grey icon with its name under it, and the selected tab in purple (filled icon, bold name). The Chat tab shows a
 * small purple dot while there are unread messages. Every tab is at least 56 dp tall (the Android minimum touch size is 48 dp) and
 * tells TalkBack its name, that it is a tab, and whether it is selected.
 */
@Composable
fun BottomBar(current: Tab, chatDot: Boolean, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    Column(modifier.fillMaxWidth().background(CardFill)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.values().forEach { t ->
                val active = t == current
                val name = tr(t.labelKey)
                val tint = if (active) Accent else Ink.copy(alpha = 0.55f)
                Column(
                    Modifier.weight(1f).height(BottomBarHeight)
                        .semantics { selected = active; contentDescription = name }
                        .clickable(role = Role.Tab) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    // plan v3.10.2 (appearance only): the selected tab gets a soft lavender pill behind its filled purple icon
                    Box(
                        Modifier.width(52.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (active) LavenderFill else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Box {
                            Icon(if (active) t.activeIcon else t.icon, null, tint = tint, modifier = Modifier.size(25.dp))
                            if (t == Tab.Chat && chatDot) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp).size(9.dp).clip(CircleShape).background(Accent))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(name, color = tint, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}
