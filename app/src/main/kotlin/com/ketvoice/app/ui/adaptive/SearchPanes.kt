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
 * On a wide pane ([LargeScreen.searchPane]) the query stays on the left with the recent searches
 * under it, and the results fill the right; before anything is typed, the right shows the genres
 * to browse, as the search of Apple Music does.
 */
@Composable
fun SearchPanes(
    modifier: Modifier,
    idle: Boolean,
    recent: List<String>,
    onRecent: (String) -> Unit,
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
                com.ketvoice.app.ui.common.PageTitle("Rechercher", 28.sp, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp))
                head()
                if (recent.isNotEmpty()) {
                    Text("Recherches récentes", style = T.section, color = theme.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    recent.forEach { previous ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(12.dp)).hoverRow()
                            .clickable(role = Role.Button) { onRecent(previous) }.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(KetIcons.Search, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
                            Text(previous, localize = false, color = theme.onBackground, fontSize = 16.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(top = 24.dp, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp).testTag("search-results-pane")) {
                if (idle) BrowseGenres() else results()
            }
        }
    }
}

/** The genres to browse, in as many columns as the pane holds. */
@Composable
private fun BrowseGenres() {
    var genres by remember { mutableStateOf<List<CatalogGenre>>(emptyList()) }
    LaunchedEffect(Unit) { genres = runCatching { CatalogRepo.genres() }.getOrDefault(emptyList()) }
    val theme = LocalTheme.current
    Text("Parcourir les catégories", style = T.section, color = theme.onBackground,
        modifier = Modifier.padding(start = 12.dp, end = 20.dp, bottom = 12.dp))
    if (genres.isEmpty()) {
        Text("Les résultats apparaîtront ici.", color = theme.secondary, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp).testTag("search-browse")) {
        val columns = LargeScreen.columns(maxWidth.value, minCellDp = 200f, gapDp = 12f, min = 2, max = 5)
        val cell = LargeScreen.cellWidth(maxWidth.value, columns, 12f).dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            genres.chunked(columns).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    line.forEach { genre ->
                        Box(Modifier.width(cell)) {
                            com.ketvoice.app.ui.explorer.GenreCategoryCard(genre, height = 120.dp) {
                                AppUi.openCatalog(CatalogRequest("genre", genre.id, genre.title))
                            }
                        }
                    }
                }
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
