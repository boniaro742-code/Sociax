package com.sociax.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.ui.theme.*
import androidx.compose.foundation.layout.Box

/**
 * Step 84 (plan v5.0.0 section 6): a caption the way social apps show it. The FULL text is always what is stored and what is given to
 * this function; the preview is display only. A long caption (several paragraphs, line breaks, emojis) shows at most [previewLines] lines and a
 * clearly visible "See more"; "See more" opens the whole text in place and "See less" closes it again. Line breaks and spacing are kept as written.
 *
 * [key] is the post's id: the open / closed choice stays with the post while the feed scrolls. Opening or closing only changes the height of this
 * one row; the photo and the Like / Comment / Share row below it keep their own place (nothing overlaps them).
 */
@Composable
fun ExpandableCaption(text: String, key: Any, modifier: Modifier = Modifier, previewLines: Int = 4) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var cut by remember(key, text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(
            text, color = Ink, fontSize = 15.sp, lineHeight = 21.sp,
            maxLines = if (expanded) Int.MAX_VALUE else previewLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) cut = it.hasVisualOverflow }
        )
        if (cut || expanded) {
            // a comfortable touch target (48 dp high) for the link
            Box(Modifier.defaultMinSize(minHeight = 40.dp).clickable(role = Role.Button) { expanded = !expanded }, contentAlignment = Alignment.CenterStart) {
                Text(
                    if (expanded) tr("show_less") else tr("read_more"),
                    color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                )
            }
        }
    }
}

/** The author + caption block at the top of a post's detail (comments) page. A very long caption scrolls inside its own box, so the comments stay reachable. */
@Composable
fun PostCaptionHeader(username: String, caption: String, key: Any) {
    if (caption.isBlank()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
        Text(username, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        ExpandableCaption(caption, key)
    }
}
