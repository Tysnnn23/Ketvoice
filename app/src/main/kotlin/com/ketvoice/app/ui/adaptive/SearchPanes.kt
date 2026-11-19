package com.ketvoice.app.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.CatalogGenre
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text

/**
 * The search page. Narrow, it is the usual column: [head] (title, field, scopes) then [results].
 * On a wide pane ([LargeScreen.searchPane]) the query stays on the left and the results fill the right;
 * before anything is typed, the right shows the titles played last and the categories to browse (7.3).
 */
@Composable
fun SearchPanes(
    modifier: Modifier,
    idle: Boolean,
    head: @Composable ColumnScope.() -> Unit,
    results: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier) {
        // Quest keeps its own landscape window, untouched.
        val pane = if (com.ketvoice.app.BuildConfig.IS_QUEST) null else LargeScreen.searchPane(maxWidth.value, maxHeight.value)
        if (pane == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 12.dp)) {
                head()
                results()
            }
            return@BoxWithConstraints
        }
        val theme = LocalTheme.current
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(pane.dp).fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(top = 12.dp, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp).testTag("search-query-pane")) {
                com.ketvoice.app.ui.common.PageTitle("Rechercher", Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp))
                head()
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(top = 24.dp, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp).testTag("search-results-pane")) {
                if (idle) com.ketvoice.app.ui.overlay.SearchStart(columnsWide = true) else results()
            }
        }
    }
}

/**
 * Lets Ctrl/⌘ F put the cursor in the search field. Nothing on a phone. Placed on the field's modifier.
 */
@Composable
fun Modifier.searchFieldFocus(): Modifier {
    if (LocalLargeScreen.current == null) return this
    val requester = remember { FocusRequester() }
    val asked = LargeScreenNav.focusSearch.value
    LaunchedEffect(asked) {
        if (asked) {
            // One frame for the field to be placed before it takes the focus.
            kotlinx.coroutines.delay(32)
            runCatching { requester.requestFocus() }
            LargeScreenNav.focusSearch.value = false
        }
    }
    return this.focusRequester(requester)
}
