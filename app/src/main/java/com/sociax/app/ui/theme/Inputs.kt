package com.sociax.app.ui.theme

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Step 56 (plan v3.10.1, keyboard and text input reliability): ONE message box used by private / group Chat, AI Chat,
 * comments and the story reply, so they all behave the same way:
 *  - the keyboard shows a Send key and that key does exactly what the round Send button does;
 *  - a long message grows up to [maxLines] lines and then scrolls INSIDE the box, so the Send button never leaves the screen;
 *  - the first letter of a sentence is capitalised;
 *  - the text lives in the caller (a `rememberSaveable` draft), this box never clears or recreates it by itself.
 */
@Composable
fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 5,
    fontSize: TextUnit = 14.sp
) {
    TextField(
        value, onValueChange, modifier,
        placeholder = { Text(placeholder, color = Ink.copy(.6f), fontSize = fontSize) },
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = Ink, unfocusedTextColor = Ink,
            cursorColor = Ink
        )
    )
}

/** True while the soft keyboard is on screen (used to scroll the newest message into view and to guard the Back button). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberImeVisible(): Boolean = WindowInsets.isImeVisible

/** Hides the soft keyboard without touching the text. Returns what [LocalSoftwareKeyboardController] gives (may be null in previews). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rememberKeyboardHider(): () -> Unit {
    val controller = LocalSoftwareKeyboardController.current
    return { controller?.hide() }
}
