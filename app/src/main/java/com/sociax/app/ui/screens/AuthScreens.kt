package com.sociax.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.ui.theme.*

private val USERNAME_RE = Regex("^[a-z0-9_.]{3,20}$")

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SociaxLogo()
    }
}

@Composable
fun LoginScreen(busy: Boolean, error: String?, onGoogle: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SociaxLogo(size = 220.dp)
        Spacer(Modifier.height(48.dp))
        // Google is the ONLY sign-in button (plan section 3)
        if (busy) CircularProgressIndicator(color = Teal)
        else PillButton(tr("continue_google"), onGoogle, Modifier.fillMaxWidth())
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(tr("terms_note"), color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
    }
}

private val sources = listOf("TikTok", "YouTube", "Facebook", "Instagram", "Google Search", "Friend/Family", "AI Assistant", "Other")

@Composable
fun OnboardingScreen(busy: Boolean, serverError: String?, onSubmit: (name: String, username: String, age: Int, source: String?) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val errName = tr("err_name"); val errUsername = tr("err_username"); val errAge = tr("err_age")

    // If the server rejects (e.g. username taken), go back to the first step.
    LaunchedEffect(serverError) { if (serverError != null) { error = serverError; step = 0 } }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState())) {
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
                error?.let { Text(it, color = Color(0xFFFF8A8A), modifier = Modifier.padding(top = 8.dp)) }
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
                    PillButton(sourceLabel(s), { source = s; step = 2 }, Modifier.fillMaxWidth().padding(vertical = 4.dp), filled = false)
                }
                PillButton(tr("skip"), { source = null; step = 2 }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            else -> {
                Title(tr("upgrade_anytime"))
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(tr("ai_pro_title"), color = Teal, fontWeight = FontWeight.Bold)
                        Text(tr("ai_pro_body"), color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(tr("app_pro_title"), color = Teal, fontWeight = FontWeight.Bold)
                        Text(tr("app_pro_body"), color = Color.White)
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (busy) CircularProgressIndicator(color = Teal, modifier = Modifier.align(Alignment.CenterHorizontally))
                else PillButton(tr("skip_now"), { onSubmit(name.trim(), username, age.toInt(), source) }, Modifier.fillMaxWidth())
            }
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
    Text(t, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))

@Composable
fun GlassField(value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value, onChange, placeholder = { Text(hint, color = Color.White.copy(.6f)) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        // dark inset field like the "Send Message..." bar of the reference
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = Teal, unfocusedBorderColor = InsetBorder,
            focusedContainerColor = InsetFill, unfocusedContainerColor = InsetFill,
            cursorColor = Color.White
        )
    )
}
