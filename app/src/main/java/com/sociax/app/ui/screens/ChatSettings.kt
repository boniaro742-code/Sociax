package com.sociax.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.ApiClient
import com.sociax.app.data.Me
import com.sociax.app.ui.theme.Navy
import com.sociax.app.ui.theme.Teal
import com.sociax.app.ui.theme.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Step 43 (plan section 9, "Chat" block): read receipts. The SERVER decides what is shown (one-to-one chats only, both people's
 * switches on); this switch only changes my own choice. Group management lives in each group's "Group info" page, so it is
 * pointed to here and not copied. (Chat has no photos / videos yet, so there is no "media auto-download" switch: it would do nothing.)
 */
@Composable
internal fun ChatSettingsSection(me: Me?, api: ApiClient?, onMeChanged: (Me) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Section(tr("chat_set_title")) {
        if (me != null && api != null) {
            var on by remember(me.readReceipts) { mutableStateOf(me.readReceipts) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("read_receipts"), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(tr("read_receipts_sub"), color = Color.White.copy(.6f), fontSize = 12.sp)
                }
                Switch(
                    on,
                    { v ->
                        val old = on
                        on = v
                        scope.launch {
                            try { onMeChanged(api.updatePrivacy(readReceipts = v)); error = null }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { on = old; error = errText(e) }
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Navy)
                )
            }
        }
        error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 12.sp) }
        Text(tr("chat_set_groups"), color = Color.White.copy(.65f), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
