package com.sociax.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sociax.app.R

/**
 * The real Sociax logo (the supplied artwork: purple chat bubble with a white play triangle + orange-pink play shape),
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
            Text("Sociax", color = Color.White, fontSize = (size.value * 0.17f).sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/**
 * The small header mark of the reference Home screen: the colourful Sociax logo followed by the word "Sociax" in bold white.
 */
@Composable
fun SociaxMark(modifier: Modifier = Modifier, textSize: Int = 24) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.sociax_mark),
            contentDescription = null,
            modifier = Modifier.size((textSize * 1.05f).dp),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.width(6.dp))
        Text("Sociax", color = Color.White, fontSize = textSize.sp, fontWeight = FontWeight.ExtraBold)
    }
}
