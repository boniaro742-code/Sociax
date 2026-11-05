package com.sociax.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Step 59: the file keeps its old name and the old function names (GlassPanel, InsetPill, GlassIconButton, PillButton ...) so every screen
// that already uses them gets the new look, but nothing here is glass any more: white cards with a thin edge, soft grey fields, purple buttons.

/** A white card with a thin edge (the old "glass panel"). Used across the whole app. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Int = 20,
    fill: Color = GlassFill,
    borderColor: Color = GlassBorder,
    lift: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            // plan v3.10.2: "subtle borders/shadows". A very soft lift for the big cards (posts, welcome card) in the light theme only;
            // small controls and long lists stay flat so a low-end phone does not pay for hundreds of shadows.
            .then(if (lift && !isDarkPalette()) Modifier.shadow(3.dp, RoundedCornerShape(corner.dp), ambientColor = Color(0x1A5838D8), spotColor = Color(0x1A5838D8)) else Modifier)
            .clip(RoundedCornerShape(corner.dp))
            .background(fill)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(corner.dp)),
        content = content
    )
}

/** The soft grey pill of search boxes, message bars and segmented switches. */
@Composable
fun InsetPill(modifier: Modifier = Modifier, corner: Int = 50, content: @Composable BoxScope.() -> Unit) {
    GlassPanel(modifier, corner = corner, fill = InsetFill, borderColor = InsetBorder, content = content)
}

/**
 * Round icon button. On a normal page: a white circle with a thin edge and a dark icon (the search / bell buttons of the new Home).
 * [onMedia] = it sits on a photo or a video: dark glass with a white icon, readable on anything.
 */
@Composable
fun GlassIconButton(icon: ImageVector, desc: String, onClick: () -> Unit = {}, size: Int = 40, onMedia: Boolean = false) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (onMedia) MediaGlass else CardFill)
            .border(1.dp, if (onMedia) MediaGlassBorder else Hairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = if (onMedia) Color.White else Ink, modifier = Modifier.size((size * 0.5f).dp)) }
}

/**
 * The standard button. Filled = the purple gradient with white text. Not filled = a white button with a purple edge and purple text
 * (the "Edit Profile" look). Also fine on photos and videos.
 */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .then(if (filled) Modifier.background(AccentBrush) else Modifier.background(CardFill).border(1.dp, Accent, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (filled) OnAccent else Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** A bigger button with an icon in front of the text (Edit Profile / Share Profile on the profile page). */
@Composable
fun IconActionButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(AccentBrush) else Modifier.background(CardFill).border(1.dp, Accent, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (filled) OnAccent else Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (filled) OnAccent else Accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
    }
}

/**
 * A small rounded chip: "Follow", "Following", the quick questions of the AI page ...
 * [active] = purple tint with purple text; otherwise a soft grey chip with dark text.
 */
@Composable
fun SmallChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) SoftAccent else FieldFill)
            .then(if (active) Modifier else Modifier.border(1.dp, Hairline, RoundedCornerShape(50)))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (active) Accent else Ink.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (active) Accent else Ink.copy(alpha = 0.8f), fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
    }
}

/** Emoji reaction chip: 😮 164. Tinted when I used it. [onMedia] = on a photo or video (dark glass, white number). */
@Composable
fun EmojiChip(emoji: String, count: Int, mine: Boolean, onMedia: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (onMedia) (if (mine) Color(0x99FFFFFF) else MediaGlass) else (if (mine) SoftAccent else FieldFill))
            .border(1.dp, if (onMedia) (if (mine) Color.White else MediaGlassBorder) else (if (mine) Accent else Hairline), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(emoji, fontSize = 13.sp)
        Text(count.toString(), color = if (onMedia) Color.White else Ink, fontSize = 12.sp)
    }
}

/** Chip with an icon and a number: ♥ 216. [onMedia] = on a photo or video. [tint] Unspecified = the normal icon colour. */
@Composable
fun ReactionChip(icon: ImageVector, count: String, tint: Color = Color.Unspecified, onMedia: Boolean = false, onClick: () -> Unit = {}) {
    val iconTint = if (tint != Color.Unspecified) tint else if (onMedia) Color.White else Ink
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (onMedia) MediaGlass else FieldFill)
            .border(1.dp, if (onMedia) MediaGlassBorder else Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text(count, color = if (onMedia) Color.White else Ink, fontSize = 13.sp)
    }
}

/** A round number badge (unread messages, notifications). */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, color: Color = Accent) {
    Box(
        modifier.defaultMinSize(minWidth = 20.dp).height(20.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (count > 99) "99+" else count.toString(), color = OnAccent, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp)
        )
    }
}

/** The green "online" dot that sits at the bottom-right corner of an avatar. */
@Composable
fun OnlineDot(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Box(modifier.size(size).clip(CircleShape).background(CardFill).padding(2.dp).clip(CircleShape).background(OnlineGreen))
}

/** 1234 -> "1.2K", 12400 -> "12.4K", 2000 -> "2K", 1500000 -> "1.5M". Used for like / comment counts like the new design. */
fun compactCount(n: Int): String {
    if (n < 1000) return n.toString()
    if (n < 1_000_000) {
        val t = n / 100                       // tenths of thousands
        return if (t % 10 == 0) "${t / 10}K" else "${t / 10}.${t % 10}K"
    }
    val t = n / 100_000                       // tenths of millions
    return if (t % 10 == 0) "${t / 10}M" else "${t / 10}.${t % 10}M"
}

/**
 * The Sociax AI orb of the new design: a glowing purple-blue sphere with a friendly face. Drawn with the Canvas, so it needs no picture
 * and stays sharp at any size.
 */
@Composable
fun AiOrb(size: Dp, modifier: Modifier = Modifier, glow: Boolean = true) {
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = this.size.minDimension / 2f
        val body = if (glow) r * 0.66f else r * 0.96f
        if (glow) {
            drawCircle(Brush.radialGradient(listOf(Color(0x555838D8), Color.Transparent), center = c, radius = r), radius = r, center = c)
            drawCircle(color = Color(0x225838D8), radius = r * 0.82f, center = c)
        }
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF9B8CFF), Color(0xFF5838D8), Color(0xFF3B2BC4)),
                center = Offset(c.x - body * 0.25f, c.y - body * 0.3f), radius = body * 1.6f
            ),
            radius = body, center = c
        )
        // face: two eyes and a smile
        val eyeW = body * 0.13f
        val eyeH = body * 0.26f
        val eyeY = c.y - body * 0.18f
        drawRoundRect(
            Color.White, topLeft = Offset(c.x - body * 0.34f - eyeW / 2f, eyeY - eyeH / 2f), size = Size(eyeW, eyeH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(eyeW / 2f, eyeW / 2f)
        )
        drawRoundRect(
            Color.White, topLeft = Offset(c.x + body * 0.34f - eyeW / 2f, eyeY - eyeH / 2f), size = Size(eyeW, eyeH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(eyeW / 2f, eyeW / 2f)
        )
        val smileW = body * 0.56f
        drawArc(
            color = Color.White, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(c.x - smileW / 2f, c.y + body * 0.02f), size = Size(smileW, smileW * 0.62f),
            style = Stroke(width = body * 0.09f, cap = StrokeCap.Round)
        )
    }
}
