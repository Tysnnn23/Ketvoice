package com.ketvoice.app.ui.common

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.ui.motion.Motion

@Composable
fun TrackTitle(text: String, color: Color, modifier: Modifier = Modifier, selected: Boolean = false,
               fontSize: TextUnit = 16.sp, maxLines: Int = 2) {
    val scroll = selected && !Motion.reduced
    Text(text, localize = false, color = color, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
        maxLines = if (scroll) 1 else maxLines, overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(if (scroll) Modifier.basicMarquee(iterations = Int.MAX_VALUE,
            initialDelayMillis = 1800, repeatDelayMillis = 3000, velocity = 22.dp) else Modifier))
}
