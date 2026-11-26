package com.ketvoice.app.tv

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.CatalogAlbum
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.SearchGroups
import com.ketvoice.app.data.SearchHistory
import com.ketvoice.app.data.SearchRank
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.YouTubeCatalog
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The keys of the TV keyboard (French first: its accents and apostrophe), 8 to a row. */
object TvKeys {
    const val COLUMNS = 8
    val letters: List<String> = ("abcdefghijklmnopqrstuvwxyz0123456789".map(Char::toString) + listOf("é", "è", "'", "-"))

    /** [query] after a key: a letter is added, never past [MAX] characters. */
    fun type(query: String, key: String): String = (query + key).take(MAX)
    fun backspace(query: String): String = query.dropLast(1)
    fun space(query: String): String = if (query.isEmpty() || query.endsWith(" ")) query else type(query, " ")
    const val MAX = 80
}

/** What a search found, shown as rows. */
data class TvSearchResults(val tracks: List<Track> = emptyList(), val artists: List<MusicArtist> = emptyList(),
                           val albums: List<CatalogAlbum> = emptyList(), val loading: Boolean = false)

/**
 * Search: the keyboard on the left (the remote's arrows, the select key; a keyboard's letters and the TV's own voice
 * recognizer work too), the results on the right as rows — titles, artists, albums — from the same sources as the
 * phone's search.
 */
@Composable
fun TvSearchPage(initial: String = "") {
    val actions = LocalTvActions.current
    val library = rememberTvLibrary()
    var query by rememberSaveable { mutableStateOf(initial) }
    var results by remember { mutableStateOf(TvSearchResults()) }
    val history by SearchHistory.queries.collectAsState()
    LaunchedEffect(initial) { if (initial.isNotBlank()) query = initial }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = TvSearchResults(); return@LaunchedEffect }
        results = results.copy(loading = true)
        delay(350)
        search(q) { results = it }
    }
    Row(Modifier.fillMaxSize().padding(top = TvDesign.Safe.vertical).onKeyEvent { event ->
        if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
        when {
            event.key == Key.Backspace -> { query = TvKeys.backspace(query); true }
            event.utf16CodePoint in 'a'.code..'z'.code || event.utf16CodePoint in 'A'.code..'Z'.code || event.utf16CodePoint in '0'.code..'9'.code ->
                { query = TvKeys.type(query, event.utf16CodePoint.toChar().lowercase()); true }
            else -> false
        }
    }) {
        Column(Modifier.width(456.dp).padding(start = TvDesign.Safe.horizontal, end = 18.dp)) {
            TvPageTitle("Rechercher", inset = false)
            Spacer(Modifier.height(14.dp))
            QueryLine(query)
            Spacer(Modifier.height(16.dp))
            TvKeyboard(onKey = { query = TvKeys.type(query, it) }, onSpace = { query = TvKeys.space(query) },
                onBackspace = { query = TvKeys.backspace(query) }, onClear = { query = "" }, onVoice = { query = it.take(TvKeys.MAX) })
        }
        TvSearchResultsPane(query, results, history, library, onHistory = { query = it }, modifier = Modifier.weight(1f).fillMaxSize(),
            onOpen = { item -> if (query.isNotBlank()) SearchHistory.record(query); actions.open(item) })
    }
}

@Composable
private fun QueryLine(query: String) {
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .1f))
        .padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) Text("Titres, artistes, albums", style = TvDesign.Type.body, color = TvDesign.Ink.tertiary)
        else Text(query + "▏", localize = false, style = TvDesign.Type.row, color = TvDesign.Ink.primary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("search-query"))
    }
}

/** The on-screen keyboard: letters and digits in a grid that the arrows walk exactly ([TvFocusOrder.grid]), then the actions. */
@Composable
fun TvKeyboard(onKey: (String) -> Unit, onSpace: () -> Unit, onBackspace: () -> Unit, onClear: () -> Unit, onVoice: (String) -> Unit) {
    val keys = TvKeys.letters
    val requesters = remember { List(keys.size) { FocusRequester() } }
    Column(Modifier.testTag("tv-keyboard"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(TvKeys.COLUMNS).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { c, key ->
                    val index = r * TvKeys.COLUMNS + c
                    KeyCap(key, { onKey(key) }, Modifier.focusRequester(requesters[index]).focusProperties {
                        TvFocusOrder.grid(index, keys.size, TvKeys.COLUMNS, TvFocusOrder.Move.Down)?.let { down = requesters[it] }
                        TvFocusOrder.grid(index, keys.size, TvKeys.COLUMNS, TvFocusOrder.Move.Up)?.let { up = requesters[it] }
                    }.pageItem("keyboard", key, first = index == 0))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyCap("Espace", onSpace, Modifier.pageItem("keyboard", "space"), wide = true, localize = true)
            KeyCap("Effacer", onBackspace, Modifier.pageItem("keyboard", "backspace"), icon = Icons.AutoMirrored.Filled.Backspace)
            KeyCap("Tout effacer", onClear, Modifier.pageItem("keyboard", "clear"), wide = true, localize = true)
            VoiceKey(onVoice)
        }
    }
}

/** The microphone key: the TV's own speech recognizer (its remote's microphone), when it has one. */
@Composable
private fun VoiceKey(onVoice: (String) -> Unit) {
    val context = LocalContext.current
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, AppText.t("Dites un titre, un artiste ou un album"))
    }
    val available = remember { runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onVoice)
    }
    if (available) KeyCap("Recherche vocale", { runCatching { launcher.launch(intent) } }, Modifier.pageItem("keyboard", "voice"),
        icon = Icons.Filled.Mic)
}

@Composable
private fun KeyCap(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, wide: Boolean = false, localize: Boolean = false,
                   icon: ImageVector? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(if (focused) TvDesign.Ink.focusFill else Color.White.copy(alpha = .08f), label = "key")
    val ink = if (focused) TvDesign.Ink.onFocusFill else TvDesign.Ink.primary
    val description = if (localize || icon != null) AppText.t(label) else label
    Box(modifier
        .graphicsLayer { val s = if (focused) 1.1f else 1f; scaleX = s; scaleY = s }
        .size(width = if (wide) 98.dp else 42.dp, height = 42.dp).clip(RoundedCornerShape(10.dp)).background(fill)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.testTag("key-$label"),
        contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = ink)
        else Text(label, localize = localize, style = TvDesign.Type.label, color = ink, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun TvSearchResultsPane(query: String, results: TvSearchResults, history: List<String>, library: TvLibrary,
                                onHistory: (String) -> Unit, onOpen: (TvItem) -> Unit, modifier: Modifier = Modifier) {
    val actions = LocalTvActions.current
    val shelves = remember(results, library) { searchShelves(results, library) }
    TvPivot(pivot = .12f, slack = .15f) {
        LazyColumn(modifier.testTag("search-results"), contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(TvDesign.Size.rowSpacing)) {
            if (query.trim().length < 2) {
                if (history.isNotEmpty()) item(key = "history") { HistoryRow(history.take(8), onHistory) }
                item(key = "hint") {
                    TvEmpty("Rechercher dans Ketvoice", "Tapez quelques lettres avec la télécommande, ou appuyez sur le micro pour dicter.")
                }
            } else {
                items(shelves, key = { it.key }) { shelf -> TvShelfRow(shelf, onOpen, onMenu = actions::options) }
                if (shelves.isEmpty()) item(key = "status") {
                    Text(if (results.loading) "Recherche…" else "Aucun résultat", style = TvDesign.Type.body, color = TvDesign.Ink.secondary,
                        modifier = Modifier.padding(start = TvDesign.Safe.horizontal).testTag("search-status"))
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(history: List<String>, onPick: (String) -> Unit) {
    Column {
        Text("Recherches récentes", style = TvDesign.Type.row, color = TvDesign.Ink.primary,
            modifier = Modifier.padding(start = TvDesign.Safe.horizontal, bottom = 12.dp))
        Row(Modifier.padding(start = TvDesign.Safe.horizontal), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            history.take(4).forEach { q -> TvButton(q, null, { onPick(q) }, Modifier.pageItem("history", q), localize = false) }
        }
    }
}

private fun searchShelves(results: TvSearchResults, library: TvLibrary): List<TvShelf> {
    val tracks = results.tracks.take(TvRows.ROW_MAX)
    return listOfNotNull(
        tracks.takeIf { it.isNotEmpty() }?.let { list -> TvShelf("s-tracks", "Titres", list.mapIndexed { i, t -> TvRows.track(t, list, i, library::cover) }) },
        results.artists.takeIf { it.isNotEmpty() }?.let { list -> TvShelf("s-artists", "Artistes", list.take(TvRows.ROW_MAX).map { a ->
            TvItem("ar:${a.id}", TvItemKind.Artist, a.name, "", a.photo, TvRoute.Artist(a.id, a.name, a.photo)) }) },
        results.albums.takeIf { it.isNotEmpty() }?.let { list -> TvShelf("s-albums", "Albums", list.map(TvRows::album)) },
    )
}

/** The phone's sources, asked together; titles arrive as each source answers (blocked artists left out). */
private suspend fun search(q: String, publish: (TvSearchResults) -> Unit) = coroutineScope {
    var shown = TvSearchResults(loading = true)
    val lock = Any()
    fun update(change: (TvSearchResults) -> TvSearchResults) = synchronized(lock) { shown = change(shown); publish(shown) }
    val searches = mutableListOf<suspend () -> List<Track>>({ MusicApi.searchYtMusic(q) }, { PublicationApi.search(q) }, { MusicApi.searchAddons(q) })
    CustomSources.sources.value.filter { it.enabled }.forEach { source -> searches += { CustomSources.search(source, q) } }
    val taste = SearchRank.liveTaste()
    val jobs = listOf(
        launch {
            SearchGroups.collectProgressively(searches) { found ->
                val ranked = SearchRank.rank(q, found, taste).filter(ArtistPrefs::allows)
                update { it.copy(tracks = SearchGroups.group(ranked).map { g -> g.primary }) }
            }
        },
        launch {
            val artists = try { SearchRank.artistsFirst(q, YouTubeCatalog.searchArtists(q)) { it.name } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            update { it.copy(artists = artists.filter { a -> ArtistPrefs.allows(a.name) }) }
        },
        launch {
            val albums = try { CatalogRepo.searchAlbums(q) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            update { it.copy(albums = albums.filter { a -> ArtistPrefs.allows(a.artist) }) }
        },
    )
    jobs.forEach { it.join() }
    update { it.copy(loading = false) }
}
