package com.sociax.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.RememberedAccount
import com.sociax.app.ui.theme.*

private val USERNAME_RE = Regex("^[a-z0-9_.]{3,20}$")

/** Start-up screen: just the logo. If the server cannot be reached yet (for example a free server that is waking up) it says so
 *  and offers "Try again": the person stays signed in and nothing else is waiting on it. */
@Composable
fun LoadingScreen(message: String? = null, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SociaxLogo(showName = true)
            if (message != null) {
                Spacer(Modifier.height(24.dp))
                Text(message, color = ErrorInk, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                if (onRetry != null) {
                    Spacer(Modifier.height(12.dp))
                    PillButton(tr("try_again"), onRetry)
                }
            }
        }
    }
}

/** "Choose your language" (plan v3.5 section 24): the five languages, available BEFORE Google sign-in, no account and no internet needed.
 *  The whole app changes at once because the app reads one saved language everywhere. */
@Composable
fun LanguageSelector(lang: Lang, onLang: (Lang) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(tr("choose_language"), color = Ink.copy(alpha = .7f), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Box {
            InsetPill(Modifier.clickable { open = true }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Language, tr("choose_language"), tint = Ink.copy(alpha = .85f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(lang.label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Filled.ArrowDropDown, null, tint = Ink.copy(alpha = .85f), modifier = Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Lang.values().forEach { l ->
                    DropdownMenuItem(
                        text = { Text(l.label) },
                        onClick = { open = false; onLang(l) },
                        trailingIcon = if (l == lang) ({ Icon(Icons.Filled.Check, null) }) else null
                    )
                }
            }
        }
    }
}

@Composable
fun LoginScreen(busy: Boolean, error: String?, lang: Lang, onLang: (Lang) -> Unit,
                remembered: List<RememberedAccount> = emptyList(), onForget: (String) -> Unit = {}, onGoogle: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // at the TOP of the opening screen, before sign-in (plan v3.5 section 24)
        LanguageSelector(lang, onLang, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp))
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SociaxLogo(size = 200.dp, showName = true)
            Spacer(Modifier.height(48.dp))
            // Google is the ONLY sign-in button (plan section 3)
            if (busy) CircularProgressIndicator(color = Accent)
            else PillButton(tr("continue_google"), onGoogle, Modifier.fillMaxWidth())
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = ErrorInk, fontSize = 13.sp)
            }
            // Step 54 (plan v3.9.2 section 3): accounts used on this phone. A name here is NOT a sign-in: tapping it opens the normal
            // Google account chooser, and whichever account is chosen there is the one that opens (nothing is merged or switched silently).
            if (!busy && remembered.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(tr("remembered_title"), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(tr("remembered_hint"), color = Ink.copy(alpha = .6f), fontSize = 12.sp)
                remembered.forEach { acc ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onGoogle() }.padding(vertical = 6.dp)) {
                            Text(acc.displayName.ifBlank { acc.username }, color = Ink, fontSize = 15.sp)
                            if (acc.username.isNotBlank()) Text(acc.username, color = Accent, fontSize = 13.sp)
                        }
                        TextButton(onClick = { onForget(acc.uid) }) { Text(tr("remembered_forget"), color = Ink.copy(alpha = .7f), fontSize = 12.sp) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(tr("terms_note"), color = Ink.copy(alpha = .6f), fontSize = 12.sp)
        }
    }
}

private val sources = listOf("TikTok", "YouTube", "Facebook", "Instagram", "Google Search", "Friend/Family", "AI Assistant", "Other")

@Composable
fun OnboardingScreen(
    busy: Boolean, serverError: String?,
    initialName: String = "", initialUsername: String = "", initialAge: String = "",
    onDraft: (name: String, username: String, age: String) -> Unit = { _, _, _ -> },
    ads: com.sociax.app.data.AdsController? = null,      // v0.86.0: new-account full-screen ad after "Where did you hear about SociaX?"
    onSubmit: (name: String, username: String, age: Int, source: String?) -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var age by rememberSaveable { mutableStateOf(initialAge) }
    // Step 57 (plan v3.10.0): what is typed is kept on the phone for THIS account until the profile is saved
    LaunchedEffect(name, username, age) { onDraft(name, username, age) }
    var source by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // v0.86.0 (plan v3.9.5 section 1): ONE ad, only here, only for a NEW account, never required. The ad is asked for while the person answers
    // the question (age is already typed: under 18 = no ad), and shown only when it is READY; if not (offline, no fill, blocked) the flow goes on at once.
    var onboardAd by remember { mutableStateOf<com.sociax.app.data.LoadedAd?>(null) }
    var adDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(step) { if (step == 1 && ads != null && !adDone) ads.refresh(null, localAge = age.toIntOrNull()) }
    fun answered(s: String?) {
        source = s
        val ad = if (!adDone && ads != null && ads.allowed) ads.take() else null
        adDone = true
        if (ad != null) onboardAd = ad else step = 2
    }
    val errName = tr("err_name"); val errUsername = tr("err_username"); val errAge = tr("err_age")

    // If the server rejects (e.g. username taken), go back to the first step.
    LaunchedEffect(serverError) { if (serverError != null) { error = serverError; step = 0 } }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState())) {   // Step 56: imePadding so the age / username boxes are not hidden by the keyboard
        SociaxLogo(Modifier.align(Alignment.CenterHorizontally), size = 96.dp)
        Spacer(Modifier.height(16.dp))
        when (step) {
            0 -> {
                Title(tr("tell_about_you"))
                GlassField(name, { name = it }, tr("display_name"))
                Spacer(Modifier.height(12.dp))
                GlassField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' } }, tr("username_hint"))
                Spacer(Modifier.height(12.dp))
                GlassField(age, { age = it.filter(Char::isDigit).take(3) }, tr("age"))
                error?.let { Text(it, color = ErrorInk, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(20.dp))
                PillButton(tr("next"), {
                    val a = age.toIntOrNull()
                    when {
                        name.isBlank() -> error = errName
                        !USERNAME_RE.matches(username) -> error = errUsername
                        a == null || a < 15 -> error = errAge   // enforced again on the server
                        else -> { error = null; step = 1 }
                    }
                }, Modifier.fillMaxWidth())
            }
            1 -> {
                Title(tr("heard"))
                sources.forEach { s ->
                    PillButton(sourceLabel(s), { answered(s) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), filled = false)
                }
                PillButton(tr("skip"), { answered(null) }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            else -> {
                Title(tr("upgrade_anytime"))
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(tr("pro_title"), color = Accent, fontWeight = FontWeight.Bold)
                        Text(tr("pro_body"), color = Ink)
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (busy) CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.CenterHorizontally))
                else PillButton(tr("skip_now"), { onSubmit(name.trim(), username, age.toInt(), source) }, Modifier.fillMaxWidth())
            }
        }
    }
    onboardAd?.let { ad ->
        FullScreenAdOverlay(ad, onSeen = { ads?.markSeen(ad.key) }, onClose = { onboardAd = null; step = 2 })
    }
    }
}

/** Shows the source name in the chosen language; the English value is what gets saved. */
@Composable
private fun sourceLabel(s: String): String = when (s) {
    "Google Search" -> tr("src_google")
    "Friend/Family" -> tr("src_friend")
    "AI Assistant" -> tr("src_ai")
    "Other" -> tr("src_other")
    else -> s
}

@Composable
private fun Title(t: String) =
    Text(t, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))

/**
 * The dark rounded text field used for search, names, usernames, ages and member search.
 * Step 56 (plan v3.10.1): [imeAction] / [onAction] give the keyboard its action key (Search for the search page: it hides the keyboard
 * and runs the action; the text stays), and [clearable] shows an X that empties the box. Nothing here clears text by itself.
 */
@Composable
fun GlassField(
    value: String, onChange: (String) -> Unit, hint: String,
    imeAction: ImeAction = ImeAction.Done, onAction: (() -> Unit)? = null, clearable: Boolean = false
) {
    val hideKeyboard = rememberKeyboardHider()
    OutlinedTextField(
        value, onChange, placeholder = { Text(hint, color = Ink.copy(.6f)) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = if (onAction != null) KeyboardActions(onAny = { hideKeyboard(); onAction() }) else KeyboardActions.Default,
        trailingIcon = if (clearable && value.isNotEmpty()) {
            {
                Icon(
                    Icons.Filled.Close, tr("a11y_clear"), tint = Ink.copy(.8f),
                    modifier = Modifier.padding(end = 4.dp).clip(CircleShape).clickable { onChange("") }.padding(6.dp)
                )
            }
        } else null,
        // dark inset field like the "Send Message..." bar of the reference
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedBorderColor = Accent, unfocusedBorderColor = InsetBorder,
            focusedContainerColor = InsetFill, unfocusedContainerColor = InsetFill,
            cursorColor = Ink
        )
    )
}
