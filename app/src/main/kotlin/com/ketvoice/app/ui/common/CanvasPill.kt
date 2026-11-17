package com.ketvoice.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.delay

/** When the « Canvas… » pill shows (6.2.3). */
object CanvasPill {
    /** A Canvas that comes at once is never announced: the pill appears only after this long. */
    const val DELAY_MS = 1_200L

    /** The title is waiting for a Canvas: one is wanted here, being fetched, and not there yet. */
    fun waiting(loading: Boolean, hasCanvas: Boolean, wanted: Boolean): Boolean = wanted && loading && !hasCanvas
}

/**
 * A small glass pill « Canvas… » with a spinner over the artwork, while the Canvas of [track] is being fetched and
 * is still not there after [CanvasPill.DELAY_MS]. It fades out as the Canvas appears (no animation under reduced motion).
 * [wanted] is whether this screen plays Canvas at all (the player's or the album page's setting).
 */
@Composable
fun CanvasLoadingPill(track: Track?, wanted: Boolean, modifier: Modifier = Modifier) {
    val loading by CanvasRepo.loading.collectAsState()
    val paths by CanvasRepo.paths.collectAsState()
    val id = track?.id
    val waiting = id != null && CanvasPill.waiting(id in loading, paths[id] != null, wanted)
    var shown by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, waiting) {
        if (waiting) { delay(CanvasPill.DELAY_MS); shown = true } else shown = false
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f,
        if (Motion.reduced) snap() else tween(Motion.duration(260)), label = "canvas-pill")
    if (alpha <= .01f) return
    CanvasPillBody(modifier.graphicsLayer { this.alpha = alpha })
}

/** The pill itself. The artwork under it is dark or bright, so its glass is tinted dark and its ink stays white. */
@Composable
internal fun CanvasPillBody(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    val label = AppText.t("Canvas…")
    Box(modifier.heightIn(min = 30.dp).testTag("canvas-pill").semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        GlassPanel(Modifier.matchParentSize(), shape, GlassParams.pill(dark = true), enabled = AppSettings.liquidGlass.value) {}
        Box(Modifier.matchParentSize().clip(shape).background(Color.Black.copy(alpha = .34f))
            .border(.8.dp, Color.White.copy(alpha = .22f), shape))
        Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActivitySpinner(size = 14.dp, color = Color.White.copy(alpha = .92f))
            Spacer(Modifier.width(6.dp))
            Text(label, localize = false, color = Color.White.copy(alpha = .95f), fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
