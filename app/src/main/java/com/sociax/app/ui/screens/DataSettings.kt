package com.sociax.app.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.CacheTools
import com.sociax.app.data.Perf
import com.sociax.app.ui.theme.PillButton
import com.sociax.app.ui.theme.tr
import com.sociax.app.ui.theme.trf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Step 44 (plan section 9, "Data / accessibility"). Data: how much temporary picture / video data this phone keeps and a button to
 * remove it (see [CacheTools] for exactly what is touched). Accessibility: where the existing choices are (text size and Reduce Motion
 * in Appearance, Data Saver in Short Video); screen-reader labels of the buttons are translated in all five languages.
 */
@Composable
internal fun DataSettingsSection() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showReport by remember { mutableStateOf(false) }          // Step 52
    var copied by remember { mutableStateOf(false) }
    var bytes by remember { mutableStateOf<Long?>(null) }       // null = still measuring
    var cleared by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { bytes = CacheTools.clearableBytes(context) } catch (e: CancellationException) { throw e } catch (e: Exception) { bytes = 0L }
    }
    Section(tr("data_set_title")) {
        Text(tr("data_cache_title"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(bytes?.let { trf("data_cache_size", CacheTools.format(it)) } ?: tr("data_cache_measuring"),
            color = Color.White.copy(.8f), fontSize = 13.sp)
        PillButton(tr("data_cache_clear"), {
            if (busy) return@PillButton
            busy = true
            scope.launch {
                try { cleared = CacheTools.clear(context); bytes = CacheTools.clearableBytes(context) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { cleared = null }
                busy = false
            }
        })
        cleared?.let { Text(trf("data_cache_done", CacheTools.format(it)), color = Color.White.copy(.8f), fontSize = 12.sp) }
        Text(tr("data_cache_note"), color = Color.White.copy(.6f), fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(tr("data_access_title"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(tr("data_access_note"), color = Color.White.copy(.6f), fontSize = 12.sp)
        // Step 52 (plan v3.9 section 37: measure before tuning; section 18: native code only for a MEASURED need)
        Spacer(Modifier.height(2.dp))
        Text(tr("perf_title"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(tr("perf_note"), color = Color.White.copy(.6f), fontSize = 12.sp)
        PillButton(if (showReport) tr("perf_hide") else tr("perf_show"), { showReport = !showReport; copied = false })
        if (showReport) {
            val text = Perf.report()
            Text(if (Perf.isEmpty()) tr("perf_empty") else text, color = Color.White.copy(.85f), fontSize = 12.sp)
            if (!Perf.isEmpty()) {
                PillButton(if (copied) tr("perf_copied") else tr("perf_copy"), { clipboard.setText(AnnotatedString(text)); copied = true })
            }
        }
    }
}
