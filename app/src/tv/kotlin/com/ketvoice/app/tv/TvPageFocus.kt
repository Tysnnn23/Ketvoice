package com.ketvoice.app.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * A page's focus (Ketvoice TV): where it was ([FocusMemory], kept while the page is in the back stack) and who takes
 * it when the page appears — the remembered item when it is on screen, else the page's first item. Items register
 * with [Modifier.pageItem].
 */
class PageFocus(val memory: FocusMemory) {
    /** Set once an item took the focus on arrival: nobody else tries. */
    var claimed by mutableStateOf(false)
    internal var first: FocusRequester? = null
    internal val byKey = HashMap<String, FocusRequester>()

    /** Asks the remembered item (or the first one) to take the focus again, e.g. when coming back from the side menu. */
    fun restore(): Boolean {
        val target = memory.restore()?.let { (row, item) -> byKey["$row/$item"] } ?: first ?: return false
        return runCatching { target.requestFocus() }.isSuccess
    }
}

val LocalPageFocus = staticCompositionLocalOf<PageFocus?> { null }

/**
 * Registers an item of the page: it remembers being focused, and takes the focus on arrival when it is the item
 * remembered for the page (or, with [first], the page's default when nothing is remembered).
 */
fun Modifier.pageItem(row: String, item: String, first: Boolean = false): Modifier = composed {
    val page = LocalPageFocus.current ?: return@composed this
    val requester = remember { FocusRequester() }
    val key = "$row/$item"
    androidx.compose.runtime.DisposableEffect(key) {
        page.byKey[key] = requester
        if (first) page.first = requester
        onDispose {
            if (page.byKey[key] === requester) page.byKey.remove(key)
            if (page.first === requester) page.first = null
        }
    }
    val target = page.memory.restore()
    val wanted = !page.claimed && (if (target != null) target == (row to item) else first)
    LaunchedEffect(wanted) {
        if (!wanted) return@LaunchedEffect
        // One frame for the layout to place the item, then it takes the focus.
        delay(16)
        if (!page.claimed && runCatching { requester.requestFocus() }.isSuccess) page.claimed = true
    }
    this.focusRequester(requester).onFocusChanged { if (it.isFocused) { page.memory.focused(row, item); page.claimed = true } }
}

/** When the remembered item is no longer on the page (removed, or off screen), the page's first item takes the focus. */
@Composable
fun PageFocusFallback(page: PageFocus) {
    LaunchedEffect(page) {
        delay(450)
        if (!page.claimed) page.first?.let { runCatching { it.requestFocus() }.onSuccess { page.claimed = true } }
    }
}

/**
 * Rows and pages scroll so that the focused element stays at the same place ([TvFocusOrder.pivotScroll]): the row
 * moves under a still focus, as on Google TV.
 */
@OptIn(ExperimentalFoundationApi::class)
private class PivotSpec(private val pivot: Float, private val slack: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        TvFocusOrder.pivotScroll(offset, size, containerSize, pivot, slack = slack)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvPivot(pivot: Float = TvFocusOrder.PIVOT, slack: Float = 0f, content: @Composable () -> Unit) {
    val spec = remember(pivot, slack) { PivotSpec(pivot, slack) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
