package com.sociax.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Translucent glass panel used across the whole app. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Int = 24,
    fill: Color = GlassFill,
    borderColor: Color = GlassBorder,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(fill)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(corner.dp)),
        content = content
    )
}

/**
 * The dark "inset" pill of the reference: the For you / Following switch, the search field and the message bars.
 * Darker than the background so the white selected part and the white text stand out.
 */
@Composable
fun InsetPill(modifier: Modifier = Modifier, corner: Int = 50, content: @Composable BoxScope.() -> Unit) {
    GlassPanel(modifier, corner = corner, fill = InsetFill, borderColor = InsetBorder, content = content)
}

/** Round frosted button (+, bell, chat, back, send ...). Same glass on the blue background and on photos / videos. */
@Composable
fun GlassIconButton(icon: ImageVector, desc: String, onClick: () -> Unit = {}, size: Int = 40, onMedia: Boolean = false) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(ControlFill)
            .border(1.dp, ControlBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size((size * 0.5f).dp)) }
}

/** Soft-white pill button (Follow, Continue ...). The not-filled version is frosted glass with white text. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) PaperWhite else ControlFill)
            .border(1.dp, if (filled) Color.Transparent else ControlBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (filled) Navy else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Emoji reaction chip like the reference: 😮 164. Brighter when I used it. */
@Composable
fun EmojiChip(emoji: String, count: Int, mine: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (mine) Color(0x99FFFFFF) else ControlFill)
            .border(1.dp, if (mine) Color.White else ControlBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(emoji, fontSize = 14.sp)
        Text(count.toString(), color = Color.White, fontSize = 13.sp)
    }
}

/** Frosted chip with an icon and a number: ♥ 216 */
@Composable
fun ReactionChip(icon: ImageVector, count: String, tint: Color = Color.White, onMedia: Boolean = false, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ControlFill)
            .border(1.dp, ControlBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(count, color = Color.White, fontSize = 13.sp)
    }
}
