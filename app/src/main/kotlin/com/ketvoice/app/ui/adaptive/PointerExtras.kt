package com.ketvoice.app.ui.adaptive

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme

/**
 * What a mouse, a trackpad or a stylus adds on a large screen: rows light up under the pointer, a
 * secondary click opens the row's menu, and a title can be dragged onto a playlist of the sidebar.
 * On a phone (no [LocalLargeScreen]) every one of these returns the modifier untouched.
 */
object PointerExtras {
    /** The label of a dragged title's clip: the sidebar accepts only these. */
    const val TRACK_CLIP_LABEL = "ketvoice-track"

    /** Whether a drag that carries [label] with [mimeTypes] is a Ketvoice title a playlist may take. */
    fun acceptsDrag(mimeTypes: Set<String>, label: CharSequence?): Boolean =
        ClipDescription.MIMETYPE_TEXT_PLAIN in mimeTypes && label?.toString() == TRACK_CLIP_LABEL

    /** The title being dragged in this process (the clip carries only its id). */
    @Volatile internal var dragged: Track? = null

    /** Light of a row under the pointer. */
    const val HOVER_ALPHA = .065f
}

/** A row lights up while the pointer is over it. */
@Composable
fun Modifier.hoverRow(shape: Shape = RectangleShape): Modifier {
    if (LocalLargeScreen.current == null) return this
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val ink = LocalTheme.current.onBackground
    return this.hoverable(interactions).then(if (hovered) Modifier.background(ink.copy(alpha = PointerExtras.HOVER_ALPHA), shape) else Modifier)
}

/** A button whose own [interactions] say it is hovered lights up within [shape]. */
@Composable
fun Modifier.hoverHighlight(interactions: MutableInteractionSource, shape: Shape, ink: Color = LocalTheme.current.onBackground): Modifier {
    if (LocalLargeScreen.current == null) return this
    val hovered by interactions.collectIsHoveredAsState()
    return if (hovered) this.background(ink.copy(alpha = .10f), shape) else this
}

/** A secondary click (right button, two-finger click on a trackpad) runs [onSecondary] instead of a click. */
@Composable
fun Modifier.secondaryClick(onSecondary: () -> Unit): Modifier {
    if (LocalLargeScreen.current == null) return this
    val action by rememberUpdatedState(onSecondary)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    action()
                }
            }
        }
    }
}

/**
 * Lets a mouse drag [track] out of its row (onto a playlist of the sidebar). Only a mouse starts it: a
 * finger keeps scrolling the list and a long press keeps opening the menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.trackDragSource(track: Track): Modifier {
    if (LocalLargeScreen.current == null) return this
    val current by rememberUpdatedState(track)
    val tint = LocalTheme.current.accent
    return this.dragAndDropSource(drawDragDecoration = {
        drawRoundRect(tint.copy(alpha = .22f), cornerRadius = CornerRadius(14f * density))
        drawRoundRect(tint, size = size.copy(width = 4f * density), cornerRadius = CornerRadius(2f * density))
    }, block = {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop * 2f) break
            }
            val dragged = current
            PointerExtras.dragged = dragged
            startTransfer(DragAndDropTransferData(ClipData.newPlainText(PointerExtras.TRACK_CLIP_LABEL, dragged.id), localState = dragged))
        }
    })
}

/** A place a dragged title can be dropped on: [onTrack] receives it, [onOver] says whether one hovers here. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.trackDropTarget(onOver: (Boolean) -> Unit, onTrack: (Track) -> Unit): Modifier {
    val over by rememberUpdatedState(onOver)
    val take by rememberUpdatedState(onTrack)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                over(false)
                val track = event.toAndroidDragEvent().localState as? Track ?: PointerExtras.dragged ?: return false
                PointerExtras.dragged = null
                take(track)
                return true
            }
            override fun onEntered(event: DragAndDropEvent) = over(true)
            override fun onExited(event: DragAndDropEvent) = over(false)
            override fun onEnded(event: DragAndDropEvent) = over(false)
        }
    }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { event ->
        PointerExtras.acceptsDrag(event.mimeTypes(), event.toAndroidDragEvent().clipDescription?.label)
    }, target = target)
}

/**
 * The row helpers a track list puts together: secondary click for [onMenu], drag of [track]. The row's own
 * click indication already lights it under the pointer; a second light would double it.
 */
@Composable
fun Modifier.largeScreenTrackRow(track: Track, onMenu: () -> Unit): Modifier {
    if (LocalLargeScreen.current == null) return this
    return this.secondaryClick(onMenu).trackDragSource(track)
}
