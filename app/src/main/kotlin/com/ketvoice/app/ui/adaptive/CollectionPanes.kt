package com.ketvoice.app.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The page of a collection (album, playlist, artist). Narrow, it is the usual one column: [header]
 * is the first item of the list and scrolls away with it — exactly the list the page had before.
 * On a wide pane ([LargeScreen.collectionPane]) it becomes two panes: the header (artwork, title,
 * actions, Canvas) stays on the left while the titles scroll on the right. The list keeps an item
 * under [headerKey] at its top (a spacer of [listTop]), so that indices and keys stay those of the
 * single column.
 */
@Composable
fun CollectionPanes(
    modifier: Modifier,
    listState: LazyListState,
    listTag: String,
    contentPadding: PaddingValues,
    headerKey: String,
    listTop: Dp = 0.dp,
    header: @Composable () -> Unit,
    rows: LazyListScope.() -> Unit
) {
    BoxWithConstraints(modifier) {
        // Quest keeps its own landscape window, untouched.
        val pane = if (com.ketvoice.app.BuildConfig.IS_QUEST) null else LargeScreen.collectionPane(maxWidth.value, maxHeight.value)
        if (pane == null) {
            LazyColumn(Modifier.fillMaxSize().testTag(listTag), state = listState, contentPadding = contentPadding) {
                item(key = headerKey) { header() }
                rows()
            }
        } else {
            val direction = LocalLayoutDirection.current
            Row(Modifier.fillMaxSize()) {
                // The left pane holds still, a card of its own under the page's bar; it scrolls on its own only when the
                // window is too short for it.
                Box(Modifier.width(pane.dp).fillMaxHeight().padding(start = 12.dp, top = listTop, bottom = 12.dp)
                    .clip(RoundedCornerShape(28.dp)).background(com.ketvoice.app.theme.LocalTheme.current.onBackground.copy(alpha = .035f))
                    .verticalScroll(rememberScrollState()).testTag("$listTag-pane")) {
                    CompositionLocalProvider(LocalCollectionPane provides true) { header() }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag(listTag), state = listState,
                    contentPadding = PaddingValues(start = contentPadding.calculateStartPadding(direction) + 8.dp,
                        end = contentPadding.calculateEndPadding(direction) + 8.dp,
                        bottom = contentPadding.calculateBottomPadding())) {
                    item(key = headerKey) { Spacer(Modifier.fillMaxWidth().height(listTop)) }
                    rows()
                }
            }
        }
    }
}

/** True inside the still left pane of a two-pane collection page: what scrolls away on a phone holds still there. */
val LocalCollectionPane = staticCompositionLocalOf { false }
