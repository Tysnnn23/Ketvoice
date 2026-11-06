package com.ketvoice.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.R
import com.ketvoice.app.ui.motion.Motion

/**
 * Animated launch mark shown while the first frame settles.
 *
 * It uses the same light canvas as the launcher icon, so the Android 12+
 * system splash hands over without a colour jump. The mark springs in and the
 * wordmark follows; reduced-motion users get the final state immediately.
 */
@Composable
fun SplashBrand(modifier: Modifier = Modifier) {
    val reduced = Motion.reduced
    val markScale = remember { Animatable(if (reduced) 1f else .86f) }
    val markAlpha = remember { Animatable(if (reduced) 1f else 0f) }
    val wordAlpha = remember { Animatable(if (reduced) 1f else 0f) }

    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        markAlpha.animateTo(1f, tween(200))
        markScale.animateTo(1f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessLow))
        wordAlpha.animateTo(1f, tween(280))
    }

    Box(
        modifier.fillMaxSize().background(Color(0xFFF4F4F5)).testTag("splash-brand"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.logo_main),
                contentDescription = null,
                modifier = Modifier.size(172.dp).graphicsLayer {
                    scaleX = markScale.value
                    scaleY = markScale.value
                    alpha = markAlpha.value
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Ketvoice",
                color = Color(0xFF111113),
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-.4).sp,
                modifier = Modifier.graphicsLayer {
                    alpha = wordAlpha.value
                    translationY = (1f - wordAlpha.value) * 10.dp.toPx()
                }
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.width(44.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF111113))
                    .graphicsLayer { alpha = wordAlpha.value * .9f }
            )
        }
    }
}
