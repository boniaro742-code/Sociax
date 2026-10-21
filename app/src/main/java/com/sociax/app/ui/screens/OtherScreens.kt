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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.ui.theme.*

// ---------------- BIO / PROFILE ----------------
@Composable
fun BioScreen(me: com.sociax.app.data.Me, onOpenList: (String) -> Unit = {}, onSettings: () -> Unit, onEdit: () -> Unit, onRequests: () -> Unit, onCreateGroup: () -> Unit = {},
              api: com.sociax.app.data.ApiClient? = null, onOpenPost: (com.sociax.app.data.Post) -> Unit = {}) {
    val addBio = tr("add_bio")
    var myPosts by remember { mutableStateOf<List<com.sociax.app.data.Post>>(emptyList()) }
    LaunchedEffect(me.username, me.posts) {
        val u = me.username ?: return@LaunchedEffect
        if (api == null) return@LaunchedEffect
        try { myPosts = api.userPosts(u) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GlassIconButton(Icons.Filled.Settings, tr("settings"), onSettings)
        }
        Avatar(me.avatarUrl, 96)
        Spacer(Modifier.height(10.dp))
        Text(me.displayName.orEmpty(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(me.username.orEmpty(), color = Teal)
        Spacer(Modifier.height(6.dp))
        Text(me.bio.ifBlank { addBio }, color = Color.White.copy(.8f))
        Spacer(Modifier.height(14.dp))
        GlassPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(me.posts.toString(), tr("posts"))
                Stat(me.followers.toString(), tr("followers"), Modifier.clickable { onOpenList("followers") })
                Stat(me.following.toString(), tr("following"), Modifier.clickable { onOpenList("following") })
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(tr("edit_profile"), onEdit); PillButton(tr("share_profile"), {}, filled = false)
        }
        Spacer(Modifier.height(14.dp))
        GlassPanel(Modifier.fillMaxWidth().clickable(onClick = onRequests)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PersonAdd, null, tint = Teal)
                Spacer(Modifier.width(10.dp))
                Text(tr("follow_requests"), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (me.isPrivate) tr("private_acc") else tr("public_acc"), color = Color.White.copy(.6f), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // Groups area (private, never discoverable via search)
        GlassPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Groups, null, tint = Teal)
                Spacer(Modifier.width(10.dp))
                Text(tr("groups"), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                PillButton(tr("create"), onCreateGroup)
            }
        }
        if (myPosts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            PostsGrid(myPosts, onOpenPost)
        }
        Spacer(Modifier.height(bottomBarClearance()))   // the floating tab bar covers the bottom of the page
    }
}

@Composable
private fun Stat(v: String, l: String, modifier: Modifier = Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(v, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(l, color = Color.White.copy(.7f), fontSize = 12.sp)
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
    onEditProfile: () -> Unit = {}, onBlocked: () -> Unit = {}, onSaved: () -> Unit = {}, onSavedShorts: () -> Unit = {}, onDeleted: () -> Unit = {}
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Filled.ArrowBack, "Back", onBack); Spacer(Modifier.width(12.dp))
            Text(tr("settings"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Section(tr("appearance")) {
            OptionRow(tr("mode_dark"), mode == ThemeMode.DARK) { onMode(ThemeMode.DARK) }
            OptionRow(tr("mode_light"), mode == ThemeMode.LIGHT) { onMode(ThemeMode.LIGHT) }
            OptionRow(tr("mode_system"), mode == ThemeMode.SYSTEM) { onMode(ThemeMode.SYSTEM) }
            Text(tr("accent"), color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentChoices.forEach { c ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == accent) 3.dp else 1.dp, if (c == accent) Color.White else GlassBorder, CircleShape)
                            .clickable { onAccent(c) },
                        contentAlignment = Alignment.Center
                    ) { if (c == accent) Icon(Icons.Filled.Check, null, tint = Navy, modifier = Modifier.size(20.dp)) }
                }
            }
            Text(tr("text_size"), color = Color.White, fontWeight = FontWeight.SemiBold)
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
        PrivacySection(me, api, onMeChanged, onBlocked, onSaved, onSavedShorts)
        NotificationsSection(me, api, onMeChanged)
        ShortVideoSettings()
        Section(tr("account")) {
            if (me != null) NavRow(tr("edit_profile"), onEditProfile)
            GoogleAccountRow()
            PillButton(tr("logout"), onLogout)
            DeleteAccountRow(api, onDeleted)
        }
        HelpLegalSection(api)
        Section(tr("about")) {
            Text("Sociax  •  " + tr("version") + " " + com.sociax.app.BuildConfig.VERSION_NAME, color = Color.White.copy(.8f))
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** One choice in a list (theme, language): label on the left, a check mark on the selected one. */
@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x26FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, null, tint = Teal)
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
        Text(tr("upload_quality"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("low" to tr("q_low"), "standard" to tr("q_std"), "high" to tr("q_high")).forEach { (k, label) ->
                PillButton(label, { quality = k; prefs.uploadQuality = k }, filled = quality == k)
            }
        }
        Text(tr("quality_note"), color = Color.White.copy(.6f), fontSize = 12.sp)
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Color.White.copy(.6f), fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(
            checked, onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Navy)
        )
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(14.dp))
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Teal, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
