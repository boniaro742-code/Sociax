package com.sociax.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.ApiClient
import com.sociax.app.data.RecState
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Step 40 (plan v3.9 sections 30, 31.2): Settings > Recommendations.
 * Explains in plain words what personalisation uses, shows how much is remembered (counts only) and lets the person reset it.
 */
@Composable
fun PersonalizationSection(api: ApiClient?) {
    if (api == null) return
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RecState?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun load() {
        try { state = api.recState(); failed = false }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = true }
    }
    LaunchedEffect(Unit) { load() }

    Section(tr("personalization")) {
        Text(tr("personalization_sub"), color = Color.White.copy(.7f), fontSize = 12.sp)
        state?.let {
            Text(trf("recs_counts", it.events, it.notInterested, it.hiddenCreators, it.retentionDays), color = Color.White, fontSize = 13.sp)
        }
        if (failed) Text(tr("no_server"), color = Color(0xFFFFB4A8), fontSize = 12.sp)
        if (done) Text(tr("reset_recs_done"), color = Teal, fontSize = 13.sp)
        if (!confirming) {
            PillButton(tr("reset_recs"), { confirming = true; done = false }, filled = false)
        } else {
            Text(tr("reset_recs_confirm"), color = Color.White, fontSize = 13.sp)
            Row {
                PillButton(tr("yes"), {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try { api.recFeedback("reset"); done = true; load() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failed = true }
                            busy = false; confirming = false
                        }
                    }
                })
                Spacer(Modifier.width(8.dp))
                PillButton(tr("cancel"), { confirming = false }, filled = false)
            }
        }
    }
}
