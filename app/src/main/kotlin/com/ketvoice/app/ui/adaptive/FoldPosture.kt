package com.ketvoice.app.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** A half-opened horizontal fold: the screen stands like a small laptop. Pixels, window coordinates. */
data class Tabletop(val hingeTopPx: Int, val hingeBottomPx: Int)

/** Player split for a tabletop posture, in dp: the scene above the hinge, the controls below it. */
data class TabletopSplit(val sceneHeightDp: Float, val controlsHeightDp: Float)

/**
 * Returns null unless the hinge really divides the window into two usable halves:
 * each side must keep at least 180 dp, otherwise the normal layout reads better.
 */
fun tabletopSplit(hingeTopDp: Float, hingeBottomDp: Float, windowHeightDp: Float, bottomInsetDp: Float): TabletopSplit? {
    if (!(hingeTopDp.isFinite() && hingeBottomDp.isFinite() && windowHeightDp.isFinite() && bottomInsetDp.isFinite())) return null
    if (hingeBottomDp < hingeTopDp || hingeTopDp <= 0f) return null
    val controls = windowHeightDp - hingeBottomDp - bottomInsetDp.coerceAtLeast(0f)
    if (hingeTopDp < 180f || controls < 180f) return null
    return TabletopSplit(hingeTopDp, controls)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Observes the fold. Null on devices without a hinge, when flat, when closed, and in previews/tests. */
@Composable
fun rememberTabletop(): Tabletop? {
    val activity = LocalContext.current.findActivity() ?: return null
    return produceState<Tabletop?>(null, activity) {
        runCatching {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                    it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL
                }?.let { Tabletop(it.bounds.top, it.bounds.bottom) }
            }
        }
    }.value
}

/** A vertical fold that splits the window into a left and a right page (a book held open). Pixels, window coordinates. */
data class BookFold(val hingeLeftPx: Int, val hingeRightPx: Int)

/**
 * The left page of a book posture, in dp: how wide the pane before the hinge may be once [startDp] of the
 * window is used before it. Null unless both pages keep at least 280 dp, otherwise one layout reads better.
 */
fun bookPane(hingeLeftDp: Float, hingeRightDp: Float, windowWidthDp: Float, startDp: Float): Float? {
    if (!(hingeLeftDp.isFinite() && hingeRightDp.isFinite() && windowWidthDp.isFinite() && startDp.isFinite())) return null
    if (hingeRightDp < hingeLeftDp || hingeLeftDp <= startDp) return null
    val left = hingeLeftDp - startDp
    if (left < 280f || windowWidthDp - hingeRightDp < 280f) return null
    return left
}

/** Observes a vertical fold, half-opened or separating (two screens). Null without one, and in previews/tests. */
@Composable
fun rememberBookFold(): BookFold? {
    val activity = LocalContext.current.findActivity() ?: return null
    return produceState<BookFold?>(null, activity) {
        runCatching {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                    it.orientation == FoldingFeature.Orientation.VERTICAL &&
                        (it.state == FoldingFeature.State.HALF_OPENED || it.isSeparating)
                }?.let { BookFold(it.bounds.left, it.bounds.right) }
            }
        }
    }.value
}
