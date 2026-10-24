package com.ketvoice.app.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ketvoice.app.player.SonicMeter
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.delay

@Composable
internal fun SonicCanvas(playing: Boolean, color: Color) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduced = Motion.reduced
    DisposableEffect(lifecycle, playing, reduced) {
        fun update() { SonicMeter.observe(playing && !reduced && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); SonicMeter.observe(false) }
    }
    LaunchedEffect(playing, reduced) { if (playing && !reduced) while (true) { delay(250); SonicMeter.expire() } }
    val levels by SonicMeter.levels.collectAsState()
    val low by animateFloatAsState(if (playing && !reduced) levels.low else 0f, tween(Motion.duration(160)), label = "sonic-low")
    val mid by animateFloatAsState(if (playing && !reduced) levels.mid else 0f, tween(Motion.duration(160)), label = "sonic-mid")
    val high by animateFloatAsState(if (playing && !reduced) levels.high else 0f, tween(Motion.duration(160)), label = "sonic-high")
    Canvas(Modifier.fillMaxSize().background(Color(0xFF090A10))) {
        val radius = size.minDimension * .58f
        listOf(low, mid, high).forEachIndexed { index, energy ->
            val center = Offset(size.width * (.20f + index * .30f), size.height * (.35f + energy * .18f))
            val tint = if (index == 1) Color(color.blue, color.red, color.green) else color
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = .09f + energy * .28f), Color.Transparent),
                center, radius * (1f + energy * .40f)), radius * (1f + energy * .40f), center)
        }
        // Conservative veil: white lyrics and transport remain readable.
        drawRect(Color.Black.copy(alpha = .24f))
    }
}
