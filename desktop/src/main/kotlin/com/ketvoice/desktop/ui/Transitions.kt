package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import com.ketvoice.app.ui.motion.Motion

/*
 * Windows 2.0's continuous motion: the island unfolds into the full player with its cover, title and buttons
 * travelling (one shared transition), and a cover opened from a grid grows into the page's header and folds back.
 * The shell provides the shared scope ([LocalSharedScope]) and the scope of each side ([LocalPlayerScope] for the
 * island and the full player, [LocalPageScope] for pages); [shared] does nothing without them or under reduced motion.
 */

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The island's or the full player's side of their transition. */
val LocalPlayerScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** The page's side of a page transition. */
val LocalPageScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

object SharedMotion {
    /** The springs a shared element travels on: unfolding a little bouncy, never overshooting past the target much. */
    val travel = Motion.Tuning(.86f, 330f)

    @OptIn(ExperimentalSharedTransitionApi::class)
    val bounds = BoundsTransform { _: Rect, _: Rect -> spring(dampingRatio = travel.damping, stiffness = travel.stiffness) }

    /** The key of an album's cover in a grid and in its page's header. */
    fun albumKey(ref: AlbumRef): String = "cover:album:" + (ref.id ?: (ref.title + "|" + ref.artist))

    /**
     * A page transition: deeper slides in from the right on a spring, back the other way, sideways (the sidebar)
     * a quick grow and fade; nothing at all under reduced motion.
     */
    fun pages(direction: Int, reduced: Boolean): ContentTransform = when {
        reduced -> ContentTransform(EnterTransition.None, ExitTransition.None)
        direction == 1 -> (slideInHorizontally(Motion.Springs.PUSH.spec()) { it / 8 } + fadeIn(Motion.Springs.SMOOTH.spec())) togetherWith
            (slideOutHorizontally(Motion.Springs.PUSH.spec()) { -it / 14 } + fadeOut(Motion.Springs.DISMISS.spec()))
        direction == -1 -> (slideInHorizontally(Motion.Springs.PUSH.spec()) { -it / 14 } + fadeIn(Motion.Springs.SMOOTH.spec())) togetherWith
            (slideOutHorizontally(Motion.Springs.PUSH.spec()) { it / 8 } + fadeOut(Motion.Springs.DISMISS.spec()))
        else -> (fadeIn(Motion.Springs.SMOOTH.spec()) + scaleIn(Motion.Springs.APPEAR.spec(), initialScale = .975f)) togetherWith
            (fadeOut(Motion.Springs.DISMISS.spec()) + scaleOut(Motion.Springs.DISMISS.spec(), targetScale = 1.01f))
    }

    /** The island and the full player: both fade over each other while their shared parts travel. */
    fun player(reduced: Boolean): ContentTransform =
        if (reduced) ContentTransform(EnterTransition.None, ExitTransition.None)
        else fadeIn(Motion.Springs.SMOOTH.spec()) togetherWith fadeOut(Motion.Springs.DISMISS.spec())
}

/**
 * Marks this as the shared element [key] of [visibility]'s side: its bounds travel to the element with the same key
 * on the other side. [text] scales the content to the travelling bounds instead of laying it out again.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.shared(key: String, visibility: AnimatedVisibilityScope?, text: Boolean = false): Modifier {
    val scope = LocalSharedScope.current ?: return this
    if (visibility == null || Motion.reduced) return this
    with(scope) {
        val state = rememberSharedContentState(key)
        return if (text) this@shared.sharedBounds(state, visibility, boundsTransform = SharedMotion.bounds,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.CenterStart))
        else this@shared.sharedElement(state, visibility, boundsTransform = SharedMotion.bounds)
    }
}

/** A cover shared between a grid and its page ([LocalPageScope]). */
@Composable
fun Modifier.sharedCover(key: String): Modifier = shared(key, LocalPageScope.current)
