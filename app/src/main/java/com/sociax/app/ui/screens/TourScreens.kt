package com.sociax.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.ui.theme.Accent
import com.sociax.app.ui.theme.CardFill
import com.sociax.app.ui.theme.Ink
import com.sociax.app.ui.theme.OnAccent
import com.sociax.app.ui.theme.tr

/*
 * Step 85 (plan v5.0.0 section 7): the first-time guided tour.
 *  - Shown ONCE, only to a brand-new account, after sign-in, the required onboarding and the App Lock set-up, on Home (MainActivity decides when).
 *  - Dimmed background, a clear outline around the current control, an arrow from the explanation to that control; the explanation card sits on the
 *    far side of the control, so it never covers the highlighted button.
 *  - Home, Short Video, AI, Chat, Profile (and where Settings is), then the single Create (+) button. Next, Back, Skip and Finish; Android Back = Skip.
 *  - No permission, no analytics, no network call except saving "finished" on the account. Replay: Settings > Help & legal > Replay app tour.
 */

/** Where the controls are on the screen (filled by [tourTarget] while the page is drawn). */
class TourTargets { val rects = mutableStateMapOf<String, Rect>() }

val LocalTourTargets = compositionLocalOf<TourTargets?> { null }

/** Marks a control as something the tour can point at. Costs nothing when no tour registry is provided. */
fun Modifier.tourTarget(key: String): Modifier = composed {
    val targets = LocalTourTargets.current
    if (targets == null) this else this.onGloballyPositioned { targets.rects[key] = it.boundsInRoot() }
}

/** Same as [tourTarget] but with the registry given directly (for a composable outside the page that provides it, like the tab bar). */
fun Modifier.tourTargetIn(targets: TourTargets?, key: String): Modifier =
    if (targets == null) this else this.onGloballyPositioned { targets.rects[key] = it.boundsInRoot() }

private class TourStep(val target: String, val textKey: String)

private val TOUR_STEPS = listOf(
    TourStep("tab_Home", "tour_home"),
    TourStep("tab_Video", "tour_video"),
    TourStep("tab_Ai", "tour_ai"),
    TourStep("tab_Chat", "tour_chat"),
    TourStep("tab_Profile", "tour_profile"),      // the text also says where Settings is
    TourStep("create", "tour_create")
)

@Composable
fun AppTourOverlay(targets: TourTargets, onFinished: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val last = TOUR_STEPS.lastIndex
    val step = TOUR_STEPS[index.coerceIn(0, last)]
    val tourName = tr("tour_a11y")
    val text = tr(step.textKey)
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val cardColor = CardFill
    BackHandler(onBack = onFinished)          // Android Back = Skip: the tour can never trap the person

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .semantics { contentDescription = tourName }
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val raw = targets.rects[step.target]
        val hole: Rect? = raw?.translate(-origin.x, -origin.y)?.inflate(with(density) { 6.dp.toPx() })
        val holeState by rememberUpdatedState(hole)
        val advance by rememberUpdatedState({ if (index >= last) onFinished() else index++ })

        // the dimmed page also blocks touches to the page underneath; a tap on the highlighted control simply goes to the next step
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { pos -> if (holeState?.contains(pos) == true) advance() }
            }
        ) {
            val corner = CornerRadius(14.dp.toPx())
            val dim = Color(0xB3000000)
            if (hole != null) {
                val cut = Path().apply { addRoundRect(RoundRect(hole, corner)) }
                clipPath(cut, clipOp = ClipOp.Difference) { drawRect(dim) }
                drawRoundRect(Color.White, hole.topLeft, hole.size, corner, style = Stroke(width = 3.dp.toPx()))
                // arrow from the card to the control
                val above = hole.center.y > heightPx / 2f
                val x = hole.center.x.coerceIn(32.dp.toPx(), widthPx - 32.dp.toPx())
                val baseY = if (above) hole.top - 22.dp.toPx() else hole.bottom + 22.dp.toPx()
                val tipY = if (above) hole.top - 6.dp.toPx() else hole.bottom + 6.dp.toPx()
                val arrow = Path().apply {
                    moveTo(x - 10.dp.toPx(), baseY); lineTo(x + 10.dp.toPx(), baseY); lineTo(x, tipY); close()
                }
                drawPath(arrow, cardColor, style = Fill)
            } else drawRect(dim)
        }

        val cardModifier = when {
            hole == null -> Modifier.align(Alignment.Center)
            hole.center.y > heightPx / 2f ->      // control in the lower half (the tab bar): card ABOVE it
                Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { (heightPx - hole.top).toDp() } + 22.dp)
            else ->                               // control in the upper half (Create +): card BELOW it
                Modifier.align(Alignment.TopCenter).padding(top = with(density) { hole.bottom.toDp() } + 22.dp)
        }
        Column(
            cardModifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(cardColor).padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Text("${index + 1} / ${TOUR_STEPS.size}", color = Ink.copy(.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(text, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) TextButton({ index-- }, Modifier.heightIn(min = 48.dp)) { Text(tr("back"), color = Accent, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                if (index < last) TextButton(onFinished, Modifier.heightIn(min = 48.dp)) { Text(tr("skip"), color = Ink.copy(.7f)) }
                Button(
                    { if (index >= last) onFinished() else index++ },
                    Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
                ) { Text(tr(if (index >= last) "tour_finish" else "next"), fontWeight = FontWeight.Bold) }
            }
        }
    }
}
