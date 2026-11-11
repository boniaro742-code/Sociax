package com.sociax.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.ui.theme.*

// ---------------- PROFILE (the "Bio" tab, new design) ----------------
@Composable
fun BioScreen(me: com.sociax.app.data.Me, onOpenList: (String) -> Unit = {}, onSettings: () -> Unit, onEdit: () -> Unit, onRequests: () -> Unit, onCreateGroup: () -> Unit = {},
              api: com.sociax.app.data.ApiClient? = null, onOpenPost: (com.sociax.app.data.Post) -> Unit = {},
              onBack: (() -> Unit)? = null) {   // Step 82 (plan v5.0.0 section 3): Profile is full-screen, with a Back arrow to Home
    val addBio = tr("add_bio")
    val context = androidx.compose.ui.platform.LocalContext.current
    var myPosts by remember { mutableStateOf<List<com.sociax.app.data.Post>>(emptyList()) }
    LaunchedEffect(me.username, me.posts) {
        val u = me.username ?: return@LaunchedEffect
        if (api == null) return@LaunchedEffect
        try { myPosts = api.userPosts(u) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        // header: the title in the middle, Settings on the right
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) GlassIconButton(Icons.Filled.ArrowBack, tr("a11y_back"), onBack, size = 44) else Spacer(Modifier.size(44.dp))
            Text(tr("tab_profile"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            GlassIconButton(Icons.Outlined.Settings, tr("settings"), onSettings, size = 44)
        }
        Spacer(Modifier.height(14.dp))
        RingedAvatar(me.avatarUrl, 92)
        Spacer(Modifier.height(10.dp))
        Text(me.displayName.orEmpty(), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(me.username.orEmpty(), color = Ink.copy(.55f), fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(me.bio.ifBlank { addBio }, color = Ink.copy(.8f), fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        GlassPanel(Modifier.fillMaxWidth(), fill = LavenderFill) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(compactCount(me.posts), tr("posts"))
                Stat(compactCount(me.followers), tr("followers"), Modifier.clickable { onOpenList("followers") })
                Stat(compactCount(me.following), tr("following"), Modifier.clickable { onOpenList("following") })
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconActionButton(tr("edit_profile"), Icons.Outlined.Edit, onEdit, Modifier.weight(1f), filled = false)
            IconActionButton(tr("share_profile"), Icons.Outlined.Share, {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, me.username.orEmpty() + " on Sociax")
                }
                context.startActivity(android.content.Intent.createChooser(send, "Share"))
            }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        GlassPanel(Modifier.fillMaxWidth().clickable(onClick = onRequests)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PersonAdd, null, tint = Accent)
                Spacer(Modifier.width(10.dp))
                Text(tr("follow_requests"), color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (me.isPrivate) tr("private_acc") else tr("public_acc"), color = Ink.copy(.6f), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        // Groups area (private, never discoverable via search)
        GlassPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Groups, null, tint = Accent)
                Spacer(Modifier.width(10.dp))
                Text(tr("groups"), color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                PillButton(tr("create"), onCreateGroup)
            }
        }
        // the "Posts" tab line of the new design (the only tab this app has: there are no tagged photos)
        Spacer(Modifier.height(18.dp))
        ProfilePostsTab()
        if (myPosts.isNotEmpty()) PostsGrid(myPosts, onOpenPost)
        Spacer(Modifier.height(bottomBarClearance()))   // the docked tab bar covers the bottom of the page
    }
}

@Composable
private fun Stat(v: String, l: String, modifier: Modifier = Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(v, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
    Text(l, color = Ink.copy(.55f), fontSize = 13.sp)
}

// ---------------- SETTINGS ----------------
@Composable
fun SettingsScreen(
    mode: ThemeMode, onMode: (ThemeMode) -> Unit,
    lang: Lang, onLang: (Lang) -> Unit,
    accent: Long, onAccent: (Long) -> Unit,
    textSize: TextSize, onTextSize: (TextSize) -> Unit,
    reduceMotion: Boolean, onReduceMotion: (Boolean) -> Unit,
    onBack: () -> Unit, onLogout: () -> Unit = {}, api: com.sociax.app.data.ApiClient? = null,
    me: com.sociax.app.data.Me? = null, onMeChanged: (com.sociax.app.data.Me) -> Unit = {},
    onEditProfile: () -> Unit = {}, onBlocked: () -> Unit = {}, onSaved: () -> Unit = {}, onSavedShorts: () -> Unit = {}, onDeleted: () -> Unit = {},
    lock: com.sociax.app.data.AppLock? = null, onChangeLock: () -> Unit = {}
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.ArrowBack, tr("a11y_back"), onBack); Spacer(Modifier.width(12.dp))
            Text(tr("settings"), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Section(tr("appearance")) {
            OptionRow(tr("mode_dark"), mode == ThemeMode.DARK) { onMode(ThemeMode.DARK) }
            OptionRow(tr("mode_light"), mode == ThemeMode.LIGHT) { onMode(ThemeMode.LIGHT) }
            OptionRow(tr("mode_system"), mode == ThemeMode.SYSTEM) { onMode(ThemeMode.SYSTEM) }
            Text(tr("accent"), color = Ink, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentChoices.forEach { c ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == accent) 3.dp else 1.dp, if (c == accent) Ink else GlassBorder, CircleShape)
                            .clickable { onAccent(c) },
                        contentAlignment = Alignment.Center
                    ) { if (c == accent) Icon(Icons.Filled.Check, null, tint = OnAccent, modifier = Modifier.size(20.dp)) }
                }
            }
            Text(tr("text_size"), color = Ink, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(tr("size_small"), { onTextSize(TextSize.SMALL) }, filled = textSize == TextSize.SMALL)
                PillButton(tr("size_normal"), { onTextSize(TextSize.NORMAL) }, filled = textSize == TextSize.NORMAL)
                PillButton(tr("size_large"), { onTextSize(TextSize.LARGE) }, filled = textSize == TextSize.LARGE)
            }
            SwitchRow(tr("reduce_motion"), tr("reduce_motion_sub"), reduceMotion, onReduceMotion)
        }
        Section(tr("language")) {
            Lang.values().forEach { l -> OptionRow(l.label, l == lang) { onLang(l) } }
        }
        PlansSection(api)
        AiSettingsSection(api)                                    // Step 42
        if (lock != null) AppLockSection(lock, onChangeLock)      // Step 32
        DailyAlarmSection()                                       // Step 33
        PrivacySection(me, api, onMeChanged, onBlocked, onSaved, onSavedShorts)
        ChatSettingsSection(me, api, onMeChanged)                 // Step 43
        NotificationsSection(me, api, onMeChanged)
        ShortVideoSettings()
        PersonalizationSection(api)                               // Step 40
        DataSettingsSection()                                     // Step 44
        Section(tr("account")) {
            if (me != null) NavRow(tr("edit_profile"), onEditProfile)
            GoogleAccountRow()
            PillButton(tr("logout"), onLogout)
            Text(tr("logout_note"), color = Ink.copy(.6f), fontSize = 12.sp)   // Step 54: log out only ends the session
            DeleteAccountRow(api, onDeleted)
        }
        HelpLegalSection(api)
        Section(tr("about")) {
            Text("Sociax  •  " + tr("version") + " " + com.sociax.app.BuildConfig.VERSION_NAME, color = Ink.copy(.8f))
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** One choice in a list (theme, language): label on the left, a check mark on the selected one. */
@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) SoftAccent else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Ink, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, null, tint = Accent)
    }
}

@Composable
private fun ShortVideoSettings() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { com.sociax.app.data.Prefs(ctx) }
    var autoplay by remember { mutableStateOf(prefs.autoplay) }
    var dataSaver by remember { mutableStateOf(prefs.dataSaver) }
    var quality by remember { mutableStateOf(prefs.uploadQuality) }
    Section(tr("tab_video")) {
        SwitchRow(tr("autoplay"), tr("autoplay_sub"), autoplay) { autoplay = it; prefs.autoplay = it }
        SwitchRow(tr("data_saver"), tr("data_saver_sub"), dataSaver) { dataSaver = it; prefs.dataSaver = it }
        Text(tr("upload_quality"), color = Ink, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("low" to tr("q_low"), "standard" to tr("q_std"), "high" to tr("q_high")).forEach { (k, label) ->
                PillButton(label, { quality = k; prefs.uploadQuality = k }, filled = quality == k)
            }
        }
        Text(tr("quality_note"), color = Ink.copy(.6f), fontSize = 12.sp)
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Ink.copy(.6f), fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(
            checked, onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent)
        )
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(14.dp))
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Accent, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
