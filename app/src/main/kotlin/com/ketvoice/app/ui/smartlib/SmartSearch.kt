package com.ketvoice.app.ui.smartlib

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.Track
import com.ketvoice.app.smartlib.GenreFilter
import com.ketvoice.app.smartlib.NaturalQuery
import com.ketvoice.app.smartlib.QueryFilter
import com.ketvoice.app.smartlib.SmartFacts
import com.ketvoice.app.smartlib.SmartFilter
import com.ketvoice.app.smartlib.TrackFacts
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Natural search in the search page: the request is read on the device, its filters are applied to
 * the library and to the catalogue's results, and a search that is not understood stays a normal one.
 */
object SmartSearch {
    /** The request in [text] when more than a name was understood, without the chips in [dismissed]; null otherwise. */
    suspend fun understand(text: String, dismissed: Set<String>): NaturalQuery? = withContext(Dispatchers.Default) {
        val artists = SmartLibrary.pool().map { it.artist.substringBefore(',').substringBefore(" feat").trim() }.distinct()
        NaturalQuery.parse(text, artists).without(dismissed).takeIf { it.natural }
    }

    /** The titles of the library answering [query], those answering most first. */
    suspend fun library(query: NaturalQuery): List<Track> {
        val pool = SmartLibrary.pool()
        val facts = SmartLibrary.facts(pool)
        return withContext(Dispatchers.Default) { SmartFilter.apply(pool, query, strict = true) { facts[it.id] ?: TrackFacts() } }
    }

    /** The catalogue's [tracks] filtered by what is already known of them; all of them when nothing would remain. */
    fun refineNow(tracks: List<Track>, query: NaturalQuery): List<Track> {
        val facts = SmartFacts.build(tracks)
        return SmartFilter.apply(tracks, query, strict = false) { facts[it.id] ?: TrackFacts() }.ifEmpty { tracks }
    }

    /** [refineNow], after asking the catalogue for the styles and years of the first results' albums. */
    suspend fun refine(tracks: List<Track>, query: NaturalQuery): List<Track> {
        runCatching { SmartFacts.learnAlbums(tracks.take(20), 20) }
        return withContext(Dispatchers.Default) { refineNow(tracks, query) }
    }

    /** The catalogue's chart of each style asked for: titles that are of that style for sure. */
    fun chartSearches(query: NaturalQuery): List<suspend () -> List<Track>> = query.filters.filterIsInstance<GenreFilter>()
        .filter { it.genre.catalogueId > 0 }.map { filter ->
            suspend {
                val tracks = CatalogRepo.page(CatalogRequest("genre", filter.genre.catalogueId.toString(), filter.genre.label))?.tracks.orEmpty()
                SmartFacts.noteChart(filter.genre, tracks)
                tracks
            }
        }
}

/** What was understood of a search, as chips the listener can remove: « Rap · Français · Calme · 2010–2019 ». */
@Composable
fun UnderstoodChips(query: NaturalQuery?, onRemove: (QueryFilter) -> Unit, modifier: Modifier = Modifier) {
    query ?: return
    val theme = LocalTheme.current
    Column(modifier.fillMaxWidth().testTag("search-understood")) {
        Text("Recherche comprise", color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(query.filters, key = { it.key }) { filter -> FilterChip(filter) { onRemove(filter) } }
        }
    }
}

@Composable
private fun FilterChip(filter: QueryFilter, onRemove: () -> Unit) {
    val theme = LocalTheme.current
    val label = if (filter.localized) AppText.t(filter.label) else filter.label
    Row(Modifier.clip(RoundedCornerShape(50)).background(theme.accent.copy(alpha = .14f))
        .clickable(role = Role.Button, onClickLabel = AppText.t("Retirer ce filtre"), onClick = onRemove)
        .heightIn(min = 36.dp).padding(start = 14.dp, end = 10.dp).testTag("chip-" + filter.key), verticalAlignment = Alignment.CenterVertically) {
        Text(label, localize = false, color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Icon(KetIcons.Close, "Retirer ce filtre", tint = theme.accent, modifier = Modifier.padding(start = 6.dp).size(14.dp))
    }
}
