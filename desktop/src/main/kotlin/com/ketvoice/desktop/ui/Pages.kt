package com.ketvoice.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import com.ketvoice.desktop.platform.About
import com.ketvoice.desktop.platform.DesktopPrefs
import kotlinx.coroutines.delay

/** The search field's text, shared by the sidebar and the search page. */
@Stable
class SearchState { var query by mutableStateOf("") }
val LocalSearch = staticCompositionLocalOf { SearchState() }

/** A page: its large title, then [content], with the window's page margins. */
@Composable
fun Page(title: String?, tag: String, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("page:$tag"),
        contentPadding = PaddingValues(start = Ket27Desk.Space.page, end = Ket27Desk.Space.page, top = 22.dp, bottom = 48.dp)) {
        if (title != null) item("title") { KText(title, Ket27Desk.Type.largeTitle, Modifier.padding(bottom = 4.dp)) }
        content()
    }
}

/** Ket 27's activity spinner (ActivitySpinner's eight spokes), for the desktop. */
@Composable
fun Spinner(modifier: Modifier = Modifier) {
    val ket = LocalKet.current
    val turn by rememberInfiniteTransition().animateFloat(0f, 8f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart))
    Canvas(modifier.size(22.dp)) {
        val step = if (Motion.reduced) 0 else turn.toInt()
        for (i in 0 until 8) rotate(i * 45f) {
            val a = .2f + .8f * (((i - step) % 8 + 8) % 8) / 7f
            drawLine(ket.secondary.copy(alpha = a), Offset(size.width / 2, size.height * .12f), Offset(size.width / 2, size.height * .32f),
                2.2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun Loading() { Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) { Spinner() } }

/* ── Accueil ── */

@Composable
fun HomePage() {
    val catalog = LocalCatalog.current
    val history by LocalLibrary.current.history.collectAsState()
    val data by produceState<HomeData?>(null) { value = runCatching { catalog.home() }.getOrNull() ?: HomeData(emptyList(), emptyList(), emptyList()) }
    Page("Accueil", "home") {
        val home = data
        if (home == null) { item { Loading() }; return@Page }
        val recent = (history + home.recent).distinctBy { it.id }
        if (home.forYou.isEmpty() && recent.isEmpty() && home.shelves.isEmpty()) {
            item { EmptyMessage("Accueil", "Écoutez quelques titres : Ketvoice prépare ici vos sélections.") }
            return@Page
        }
        if (home.forYou.isNotEmpty()) {
            item("fy-h") { SectionHeader("Pour toi") }
            item("fy") { TrackShelf(home.forYou, 220.dp) }
        }
        if (recent.isNotEmpty()) {
            item("rec-h") { SectionHeader("Écoutés récemment") }
            item("rec") { TrackShelf(recent.take(20)) }
        }
        home.shelves.forEach { s ->
            item("s-${s.title}") {
                Column {
                    SectionHeader(s.title)
                    if (s.subtitle.isNotBlank()) KText(s.subtitle, Ket27Desk.Type.subheadline, Modifier.padding(bottom = 10.dp), color = LocalKet.current.secondary)
                    if (s.albums.isNotEmpty()) AlbumShelf(s.albums) else TrackShelf(s.tracks)
                }
            }
        }
    }
}

/* ── Rechercher ── */

@Composable
fun SearchPage() {
    val search = LocalSearch.current
    val catalog = LocalCatalog.current
    val library = LocalLibrary.current
    val nav = LocalNavigator.current
    val query = search.query.trim()
    var results by remember { mutableStateOf<Pair<String, SearchResults>?>(null) }
    LaunchedEffect(query) {
        if (query.isEmpty()) return@LaunchedEffect
        delay(220) // live results: wait for a pause in typing
        results = query to runCatching { catalog.search(query) }.getOrDefault(SearchResults(emptyList(), emptyList(), emptyList()))
    }
    if (query.isEmpty()) { SearchStart(); return }
    val playlists by library.playlists.collectAsState()
    Page(null, "search") {
        item { KText(com.ketvoice.app.data.AppText.t("Résultats pour") + " « $query »", Ket27Desk.Type.title1, Modifier.padding(bottom = 4.dp), translate = false) }
        val r = results?.takeIf { it.first == query }?.second
        if (r == null) { item { Loading() }; return@Page }
        val lists = playlists.filter { it.name.contains(query, ignoreCase = true) }
        if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && lists.isEmpty()) {
            item { EmptyMessage("Aucun résultat", "Vérifiez l'orthographe ou essayez d'autres mots.") }
            return@Page
        }
        if (r.tracks.isNotEmpty()) {
            item("t-h") { SectionHeader("Titres") }
            item("t") { TrackColumns(r.tracks, columns = 3, rows = 4) }
        }
        if (r.albums.isNotEmpty()) {
            item("a-h") { SectionHeader("Albums") }
            item("a") { AlbumShelf(r.albums) }
        }
        if (r.artists.isNotEmpty()) {
            item("ar-h") { SectionHeader("Artistes") }
            item("ar") {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(Ket27Desk.Space.l)) {
                    items(r.artists.size) { i -> val a = r.artists[i]; ArtistCard(a, 150.dp) { nav.push(Route.Artist(a)) } }
                }
            }
        }
        if (lists.isNotEmpty()) {
            item("p-h") { SectionHeader("Playlists") }
            item("p") {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(Ket27Desk.Space.l)) {
                    items(lists.size) { i -> val p = lists[i]
                        AlbumCard(AlbumRef(p.id, p.name, "", p.tracks.firstOrNull()?.coverUrl), 176.dp, { nav.push(Route.Playlist(p.id)) },
                            subtitle = com.ketvoice.app.data.AppText.t("${p.tracks.size} titres"))
                    }
                }
            }
        }
    }
}

/** 7.3's search start page: recently played titles, then the catalogue's categories as tiles. */
@Composable
private fun SearchStart() {
    val catalog = LocalCatalog.current
    val nav = LocalNavigator.current
    val history by LocalLibrary.current.history.collectAsState()
    val categories by produceState<List<Category>?>(null) { value = runCatching { catalog.categories() }.getOrDefault(emptyList()) }
    Page("Rechercher", "search-start") {
        if (history.isNotEmpty()) {
            item("h-h") { SectionHeader("Écoutés récemment") }
            item("h") { TrackColumns(history, columns = 3, rows = 2) }
        }
        item("c-h") { SectionHeader("Catégories") }
        val list = categories
        if (list == null) { item { Loading() }; return@Page }
        item("c") { CategoryTiles(list) { nav.push(Route.Category(it)) } }
    }
}

@Composable
fun CategoryTiles(list: List<Category>, onOpen: (Category) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 220.dp).toInt().coerceIn(2, 6)
        Column(verticalArrangement = Arrangement.spacedBy(Ket27Desk.Space.m)) {
            list.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Ket27Desk.Space.m)) {
                    row.forEachIndexed { i, c -> CategoryTile(c, list.indexOf(c), Modifier.weight(1f)) { onOpen(c) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A category: a flat Ket 27 tile colour, its name, the cover tucked in the corner. */
@Composable
private fun CategoryTile(c: Category, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val tile = TILES[Math.floorMod(index, TILES.size)]
    Box(modifier.height(112.dp).clip(RoundedCornerShape(Ket27Desk.Radius.artwork)).background(if (hovered) tile.copy(alpha = .88f) else tile)
        .hoverable(source).clickable(onClick = onClick).testTag("category:${c.title}")) {
        KText(c.title, Ket27Desk.Type.headline, Modifier.padding(14.dp), color = Color.White, translate = false)
        if (c.cover != null) Box(Modifier.align(Alignment.BottomEnd).padding(10.dp)) { Artwork(c.cover, 60.dp, radius = 8.dp) }
    }
}

/** Ket 27's tile colours (Ket27.Colors.tiles), a little deeper so white text stays legible. */
private val TILES = listOf(Color(0xFF0071E3), Color(0xFFE0284A), Color(0xFF248A3D), Color(0xFFC9601B), Color(0xFF5146D6), Color(0xFF6E6E73))

@Composable
fun CategoryPage(category: Category) {
    val catalog = LocalCatalog.current
    val tracks by produceState<List<Track>?>(null, category) { value = runCatching { catalog.category(category) }.getOrDefault(emptyList()) }
    Page(category.title, "category") {
        val list = tracks
        when {
            list == null -> item { Loading() }
            list.isEmpty() -> item { EmptyMessage("Aucun résultat", "Cette catégorie est vide pour le moment.") }
            else -> item { TrackColumns(list, columns = 3, rows = (list.size + 2) / 3) }
        }
    }
}

/* ── Explorer ── */

@Composable
fun ExplorePage() {
    val catalog = LocalCatalog.current
    val nav = LocalNavigator.current
    val categories by produceState<List<Category>?>(null) { value = runCatching { catalog.categories() }.getOrDefault(emptyList()) }
    Page("Explorer", "explore") {
        item { SectionHeader("Catégories") }
        val list = categories
        if (list == null) item { Loading() } else item { CategoryTiles(list) { nav.push(Route.Category(it)) } }
    }
}

/* ── Statistiques (phase 2) ── */

@Composable
fun StatsPage() {
    Page("Statistiques", "stats") {
        item { EmptyMessage("Statistiques", "Vos statistiques d'écoute arrivent dans une prochaine version de Ketvoice pour Windows.") }
    }
}

/* ── Réglages ── */

@Composable
fun SettingsPage() {
    val ket = LocalKet.current
    var about by remember { mutableStateOf(false) }
    Page("Réglages", "settings") {
        item { SectionHeader("Général") }
        item {
            SettingsCard {
                SettingRow("Langue", AppSettings.language.value)
                Hairline()
                SettingRow("Apparence", com.ketvoice.app.data.AppText.t(if (ket.dark) "Sombre (suit Windows)" else "Clair (suit Windows)"))
            }
        }
        item { SectionHeader("Lecteur") }
        item {
            SettingsCard {
                ToggleRow("Activer les Canvas vidéo", AppSettings.animatedArtwork.value, "canvas-player") { AppSettings.animatedArtwork.value = it }
                Hairline()
                ToggleRow("Canvas sur les pages d'album", AppSettings.albumHeroCanvas.value, "canvas-album") { AppSettings.albumHeroCanvas.value = it }
            }
        }
        item { SectionHeader("Windows") }
        item {
            SettingsCard {
                ToggleRow("Fermer dans la zone de notification", DesktopPrefs.closeToTray.value, "close-to-tray") { DesktopPrefs.setCloseToTray(it) }
                Hairline()
                ToggleRow("Démarrer avec Windows", DesktopPrefs.startWithWindows.value, "start-with-windows") { DesktopPrefs.setStartWithWindows(it) }
            }
        }
        item { SectionHeader("À propos") }
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth().height(44.dp).clickable { about = !about }.padding(horizontal = 16.dp).testTag("about"),
                    verticalAlignment = Alignment.CenterVertically) {
                    KText("Version", Ket27Desk.Type.subheadline, Modifier.weight(1f))
                    KText(BuildConfig.VERSION_NAME, Ket27Desk.Type.subheadline, color = ket.secondary, translate = false, weight = FontWeight.Normal)
                }
                if (about) {
                    Hairline()
                    androidx.compose.material3.Text(About.text(), Modifier.fillMaxWidth().padding(16.dp).testTag("about-text"),
                        color = ket.secondary, style = Ket27Desk.Type.footnote.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val ket = LocalKet.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card)) { content() }
}

@Composable
private fun Hairline() {
    val ket = LocalKet.current
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(.5.dp).background(ket.hairline))
}

@Composable
private fun SettingRow(label: String, value: String) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.weight(1f))
        KText(value, Ket27Desk.Type.subheadline, color = ket.secondary, translate = false, weight = FontWeight.Normal)
    }
}

/** A setting that is on or off: the whole row toggles, the switch shows it. */
@Composable
private fun ToggleRow(label: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val ket = LocalKet.current
    val knob by androidx.compose.animation.core.animateDpAsState(if (on) 18.dp else 2.dp,
        if (Motion.reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .8f, stiffness = 700f))
    Row(Modifier.fillMaxWidth().height(44.dp).clickable { onChange(!on) }.padding(horizontal = 16.dp).testTag("toggle:$tag"),
        verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.weight(1f))
        Box(Modifier.size(38.dp, 22.dp).clip(RoundedCornerShape(11.dp)).background(if (on) ket.accent else ket.hairline)) {
            Box(Modifier.padding(start = knob, top = 2.dp).size(18.dp).clip(RoundedCornerShape(9.dp)).background(Color.White))
        }
    }
}

@Composable
internal fun Gap(h: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(h))
