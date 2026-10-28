package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.Palette
import com.ketvoice.app.theme.T
import java.security.MessageDigest

val LocalCovers = staticCompositionLocalOf { listOf<List<Color>>() }

fun gradientFor(key: String): List<Color> {
    var h = 0L
    key.forEach { c -> h = h * 31 + c.code }
    val hue1 = (h and 0xFFFF).toInt() % 360
    val hue2 = (hue1 + 40 + (h ushr 16).toInt() % 50) % 360
    val sat = 45 + (h ushr 24).toInt() % 25
    val l1 = 0.42f + (h ushr 8).toInt() % 12 / 100f
    val l2 = 0.12f + (h ushr 16).toInt() % 10 / 100f
    return listOf(
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue1.toFloat(), sat / 100f, l1))),
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue2.toFloat(), sat / 100f, l2)))
    )
}

@Composable
fun Cover(
    key: String,
    url: String? = null,
    size: Dp,
    radius: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = modifier.size(size).clip(RoundedCornerShape(radius))
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(radius))
                .background(Brush.linearGradient(gradientFor(key)))
        ) {
            Text(
                text = key.take(1).uppercase(),
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Black,
                fontSize = (size.value / 2.6f).sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
fun EBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (LocalTheme.current.isDark) Color(0xFF8E8E93) else Palette.Black)
    ) {
        Text(
            "E", color = if (LocalTheme.current.isDark) Palette.Black else Color.White,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    topPad: Dp = 24.dp,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = topPad, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = T.section, color = LocalTheme.current.onBackground)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun AccountAvatar(initial: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val session = com.ketvoice.app.data.YouTubeMusicSession.profile.collectAsState().value
    val label = if (session.connected && session.name.isNotBlank()) session.name.take(1).uppercase() else initial
    // Badge "mise à jour disponible" : visible quand l'utilisateur a reporté la
    // popup avec "Plus tard" ; un clic rouvre la popup via UpdateHost.
    val updateBadge = com.ketvoice.app.ui.update.UpdateBadge.postponedTag
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Palette.Teal),
            contentAlignment = Alignment.Center
        ) {
            if (session.connected && !session.photo.isNullOrBlank()) {
                AsyncImage(
                    model = session.photo,
                    contentDescription = session.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    label,
                    color = Color.White,
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (updateBadge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF30D158))
                    .border(2.dp, if (LocalTheme.current.isDark) Color(0xFF0B0B0C) else Color.White, CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { com.ketvoice.app.ui.update.UpdateBadge.requestShow() },
                contentAlignment = Alignment.Center
            ) {}
        }
    }
}

@Composable
fun Chevron(isDark: Boolean) {
    Text("›", color = if (isDark) Palette.SecondaryTextDark else Color(0xFF3C3C43).copy(alpha = 0.3f),
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun GlyphPlaceholder() {}
