package com.ketvoice.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun rememberSeenCollections(): MutableSet<String> = rememberSaveable(
    saver = listSaver<MutableSet<String>, String>(save = { it.toList() }, restore = { it.toMutableSet() })
) { mutableSetOf<String>() }

fun Modifier.collectionEntry(key: String, seen: MutableSet<String>, ordinal: Int): Modifier = composed {
    val reduced = Motion.reduced
    val progress = remember(key) { Animatable(if (key in seen || reduced) 1f else 0f) }
    LaunchedEffect(key, reduced) {
        val first = seen.add(key)
        if (reduced || !first) progress.snapTo(1f) else {
            delay(ordinal.coerceIn(0, 4) * 45L)
            progress.animateTo(1f, tween(Motion.FADE))
        }
    }
    this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 8.dp.toPx()
    }
}
