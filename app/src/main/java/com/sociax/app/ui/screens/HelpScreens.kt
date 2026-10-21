package com.sociax.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.data.ApiClient
import com.sociax.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Step 28 (plan section 9, "Data/accessibility, help, report problem, Terms, Privacy Policy"):
// Settings > Help & legal, and the connected Google account row in Settings > Account.

/** Which text dialog is open. */
private enum class HelpDoc { HELP, TERMS, PRIVACY }

@Composable
fun HelpLegalSection(api: ApiClient?) {
    var doc by remember { mutableStateOf<HelpDoc?>(null) }
    var reporting by remember { mutableStateOf(false) }
    Section(tr("help_legal")) {
        NavRow(tr("help")) { doc = HelpDoc.HELP }
        NavRow(tr("report_problem")) { if (api != null) reporting = true }
        NavRow(tr("terms")) { doc = HelpDoc.TERMS }
        NavRow(tr("privacy_policy")) { doc = HelpDoc.PRIVACY }
    }
    doc?.let { d ->
        val title = when (d) { HelpDoc.HELP -> tr("help"); HelpDoc.TERMS -> tr("terms"); HelpDoc.PRIVACY -> tr("privacy_policy") }
        val body = when (d) { HelpDoc.HELP -> tr("help_body"); HelpDoc.TERMS -> tr("terms_body"); HelpDoc.PRIVACY -> tr("privacy_policy_body") }
        AlertDialog(
            onDismissRequest = { doc = null },
            containerColor = Color(0xFF121C2E),
            title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(body, color = Color.White.copy(.85f), fontSize = 14.sp)
                    if (d != HelpDoc.HELP) {
                        Spacer(Modifier.height(12.dp))
                        Text(tr("legal_draft_note"), color = Color.White.copy(.6f), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { doc = null }) { Text(tr("close"), color = Teal, fontWeight = FontWeight.Bold) } }
        )
    }
    if (reporting && api != null) ReportProblemDialog(api) { reporting = false }
}

@Composable
private fun ReportProblemDialog(api: ApiClient, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        containerColor = Color(0xFF121C2E),
        title = { Text(tr("report_problem"), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (sent) {
                    Text(tr("report_problem_sent"), color = Color.White.copy(.85f))
                } else {
                    Text(tr("report_problem_hint"), color = Color.White.copy(.7f), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { if (it.length <= 1000) text = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Teal, unfocusedBorderColor = InsetBorder,
                            focusedContainerColor = InsetFill, unfocusedContainerColor = InsetFill,
                            cursorColor = Color.White
                        )
                    )
                    error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 13.sp) }
                }
            }
        },
        confirmButton = {
            if (sent) {
                TextButton(onClick = onDone) { Text(tr("close"), color = Teal, fontWeight = FontWeight.Bold) }
            } else {
                TextButton(enabled = !busy && text.trim().length >= 10, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { api.reportProblem(text.trim()); sent = true }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = errText(e) }
                        busy = false
                    }
                }) { Text(tr("report_problem_send"), color = Teal, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            if (!sent) TextButton(enabled = !busy, onClick = onDone) { Text(tr("cancel"), color = Color.White) }
        }
    )
}

/** Shows which Google account is signed in (read from Firebase on the phone; nothing is sent anywhere). */
@Composable
fun GoogleAccountRow() {
    val email = remember {
        try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email } catch (e: Exception) { null }
    }
    if (!email.isNullOrBlank()) {
        Text(tr("google_account"), color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(email, color = Color.White.copy(.7f), fontSize = 13.sp)
    }
}
