package com.sociax.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.R

/**
 * The real Sociax logo (the supplied artwork: a blue-cyan and purple "X" with soft motion trails, background removed so it sits cleanly on light and dark screens),
 * shown as an image so it is identical to the reference. The artwork has no text, so [showName] adds the
 * "Sociax" name under it (opening / sign-in screens: plan "Sociax logo/name").
 */
@Composable
fun SociaxLogo(modifier: Modifier = Modifier, size: Dp = 200.dp, showName: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.sociax_logo),
            contentDescription = "Sociax",
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit
        )
        if (showName) {
            Spacer(Modifier.height(8.dp))
            Text("Sociax", color = Accent, fontSize = (size.value * 0.17f).sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/**
 * The header word of the new Home screen: "Sociax" in bold purple (text only, like the reference).
 */
@Composable
fun SociaxMark(modifier: Modifier = Modifier, textSize: Int = 26) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("Sociax", color = Accent, fontSize = textSize.sp, fontWeight = FontWeight.ExtraBold)
    }
}
