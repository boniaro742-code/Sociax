package com.sociax.app.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.AiQuota
import com.sociax.app.data.ApiClient
import com.sociax.app.ui.theme.tr
import com.sociax.app.ui.theme.trf
import kotlinx.coroutines.CancellationException

/**
 * Step 42 (plan section 9, "Sociax AI" block): the AI allowance and reset time (as the SERVER counts it), which providers the
 * server has connected, and the privacy and group rules in plain words. Information only: nothing here changes who can read what.
 */
@Composable
internal fun AiSettingsSection(api: ApiClient?) {
    var quota by remember { mutableStateOf<AiQuota?>(null) }
    var providers by remember { mutableStateOf<List<String>?>(null) }     // null = not known (offline / still loading)
    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        try { quota = api.aiQuota() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        try { providers = api.aiProviders() } catch (e: CancellationException) { throw e } catch (e: Exception) { providers = null }
    }
    Section(tr("ai_set_title")) {
        quota?.takeIf { it.limit > 0 }?.let {
            Text(trf("ai_usage_line", it.used, it.limit, (it.resetInSeconds / 3600).toInt() + 1), color = Color.White, fontSize = 14.sp)
        }
        val names = providers?.map { p -> when (p) { "grok" -> "Grok"; "gemini" -> "Gemini"; "claude" -> "Claude"; else -> p } }
        when {
            names == null -> Text(tr("ai_set_unknown"), color = Color.White.copy(.65f), fontSize = 12.sp)
            names.isEmpty() -> Text(tr("ai_set_none"), color = Color.White.copy(.8f), fontSize = 13.sp)
            else -> Text(trf("ai_set_providers", names.joinToString(", ")), color = Color.White.copy(.8f), fontSize = 13.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(tr("ai_set_privacy"), color = Color.White.copy(.8f), fontSize = 13.sp)
        Text(tr("ai_set_group"), color = Color.White.copy(.8f), fontSize = 13.sp)
        Text(tr(if (quota?.pro == true) "ai_set_pro_on" else "ai_set_pro_free"), color = Color.White.copy(.65f), fontSize = 12.sp)
    }
}
