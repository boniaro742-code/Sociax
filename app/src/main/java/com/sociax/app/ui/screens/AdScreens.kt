package com.sociax.app.ui.screens

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sociax.app.data.LoadedAd
import com.sociax.app.ui.theme.MediaBackdrop
import com.sociax.app.ui.theme.bottomBarClearance
import com.sociax.app.ui.theme.tr
import kotlinx.coroutines.delay

/**
 * Step 41 (plan v3.8 section 28.2, Start.io since v0.82.0): one full-screen Start.io NATIVE ad page between ordinary Short Video items.
 * Rules kept here: the ad is ALWAYS labelled ("Ad", in the chosen language) so it can never pass as a user's video;
 * it has no Sociax like / comment / follow buttons; the person leaves it by swiping, like any other page;
 * nothing forces a click. An invisible view on top is registered with the Start.io SDK (registerViewForInteraction) so
 * Start.io sees the impression and the click.
 *
 * [active] = this page is the one on screen. After one second on screen [onSeen] is called, and the server counts ONE impression.
 */
@Composable
fun AdPage(loaded: LoadedAd, active: Boolean, onSeen: () -> Unit) {
    val label = tr("ad_label")
    LaunchedEffect(loaded.key, active) {
        if (active) { delay(1_000); onSeen() }
    }
    val ad = loaded.ad
    val title = ad.title ?: ""
    val body = ad.description ?: ""
    val cta = ad.callToAction ?: ""
    val picture = ad.imageBitmap

    Box(Modifier.fillMaxSize().background(MediaBackdrop)) {
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp, bottom = bottomBarClearance())) {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                // the required "Ad" label (always visible, never removable by the ad)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label, color = Color(0xFF1A1530), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE0B14A)).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                // the picture takes the free height
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    if (picture != null) {
                        Image(
                            bitmap = picture.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (title.isNotBlank()) {
                    Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(body, color = Color(0xFFD5DDEB), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                // call to action (comes from the ad; the person may ignore it, nothing forces a click)
                if (cta.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        cta, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF5838D8)).padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
            // The invisible view on top: registered with the Start.io SDK so the ad's impression and click are valid.
            key(loaded.key) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val v = View(ctx)
                        ad.registerViewForInteraction(v)
                        v
                    }
                )
            }
        }
    }
}

/**
 * v0.86.0: ONE full-screen Start.io native ad that the person can close at once. It is used (a) in the new-account onboarding, after
 * "Where did you hear about SociaX?", and (b) right after a correct App Lock PIN. Rules kept here:
 *  - always labelled "Ad" (chosen language); a visible Close (X) button from the first moment; Back also closes it; nothing is required;
 *  - only the ad's own button (call to action) is registered with Start.io as clickable, never the whole screen, and it ignores taps for the
 *    first 0.8 s, so a tap that was meant for the screen underneath cannot become an ad click;
 *  - the screen underneath does not receive touches while the ad is open;
 *  - [onSeen] is called once, about one second after the ad appeared (the server then counts ONE impression).
 */
@Composable
fun FullScreenAdOverlay(loaded: LoadedAd, onSeen: () -> Unit, onClose: () -> Unit) {
    val label = tr("ad_label")
    val closeText = tr("close")
    var guard by remember(loaded.key) { mutableStateOf(true) }
    LaunchedEffect(loaded.key) { delay(800); guard = false; delay(300); onSeen() }
    BackHandler(onBack = onClose)
    val ad = loaded.ad
    val title = ad.title ?: ""
    val body = ad.description ?: ""
    val cta = (ad.callToAction ?: "").ifBlank { label }
    val picture = ad.imageBitmap

    Box(Modifier.fillMaxSize().background(MediaBackdrop).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, color = Color(0xFF1A1530), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE0B14A)).padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClose)
                        .semantics { contentDescription = closeText },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Close, null, tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                if (picture != null) {
                    Image(bitmap = picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            if (title.isNotBlank()) Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(body, color = Color(0xFFD5DDEB), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            // the ONLY clickable part: the ad's own button (the invisible view on top of it is what Start.io registers)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF5838D8))) {
                Text(
                    cta, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                )
                if (!guard) {
                    key(loaded.key) {
                        AndroidView(
                            modifier = Modifier.matchParentSize(),
                            factory = { ctx -> View(ctx).also { ad.registerViewForInteraction(it) } }
                        )
                    }
                }
            }
        }
    }
}
