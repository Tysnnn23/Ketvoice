package com.ketvoice.app.ui.adaptive

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The page of a collection (an album): one column on every window, its [header] the first item of the
 * list under [headerKey], scrolling away with the titles. On a wide pane the header itself puts the
 * artwork beside the title (as Apple Music does on iPad and Mac) and the titles take the whole width
 * below it; the earlier still left pane made the page read as a stretched phone.
 */
@Composable
fun CollectionPanes(
    modifier: Modifier,
    listState: LazyListState,
    listTag: String,
    contentPadding: PaddingValues,
    headerKey: String,
    header: @Composable () -> Unit,
    rows: LazyListScope.() -> Unit
) {
    LazyColumn(modifier.fillMaxSize().testTag(listTag), state = listState, contentPadding = contentPadding) {
        item(key = headerKey, contentType = "collection-header") { header() }
        rows()
    }
}
