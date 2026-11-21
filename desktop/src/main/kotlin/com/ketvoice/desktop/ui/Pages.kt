package com.ketvoice.desktop.ui

import com.ketvoice.app.ui.common.KetIcons
import androidx.compose.foundation.layout.fillMaxHeight
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
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.ketvoice.desktop.ui.theme.DeskGlyphs

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
    val history by LocalLibrary.current.history.collectAsState()
    val loader = rememberLoad(Unit, empty = { it.forYou.isEmpty() && it.shelves.isEmpty() }) { home() }
    Page("Accueil", "home") {
        // Offline, what was heard here still shows above the offline state.
        if (loader.state is Load.Failed && history.isNotEmpty()) {
            item("rec-h") { SectionHeader("Écoutés récemment") }
            item("rec") { TrackShelf(history.take(20)) }
        }
        loadStates(loader)
        val home = loader.value ?: return@Page
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

/** The search page's scope, as on the phone: the whole catalogue, or the listener's library only. */
object SearchScope { val library = mutableStateOf(false) }

@Composable
fun SearchPage() {
    val search = LocalSearch.current
    val catalog = LocalCatalog.current
    val library = LocalLibrary.current
    val nav = LocalNavigator.current
    val query = search.query.trim()
    var inLibrary by SearchScope.library
    var results by remember { mutableStateOf<Pair<String, SearchResults>?>(null) }
    var offline by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(query, inLibrary, attempt) {
        if (query.isEmpty() || inLibrary) return@LaunchedEffect
        delay(220) // live results: wait for a pause in typing
        val r = runCatching { catalog.search(query) }.getOrDefault(SearchResults(emptyList(), emptyList(), emptyList()))
        offline = r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && !catalog.online()
        results = query to r
    }
    // A search that found something is kept in the recent searches (the phone's SearchHistory).
    val latest by androidx.compose.runtime.rememberUpdatedState(results)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { latest?.let { (q, r) -> if (q.length >= 2 && r.tracks.isNotEmpty()) com.ketvoice.app.data.SearchHistory.record(q) } }
    }
    if (query.isEmpty()) { SearchStart(); return }
    val playlists by library.playlists.collectAsState()
    Page(null, "search") {
        item("head") {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                KText(com.ketvoice.app.data.AppText.t("Résultats pour") + " « $query »", Ket27Desk.Type.title1, Modifier.weight(1f), translate = false)
                Segments(listOf("Tout", "Bibliothèque"), if (inLibrary) 1 else 0, "search-scope") { inLibrary = it == 1 }
            }
        }
        val lists = playlists.filter { com.ketvoice.app.data.SearchRank.fold(it.name).contains(com.ketvoice.app.data.SearchRank.fold(query)) }
        if (inLibrary) {
            val mine = LibraryViews.search(library, query)
            if (mine.isEmpty() && lists.isEmpty()) {
                item { EmptyMessage("Aucun résultat", "Rien dans votre bibliothèque ne correspond. Cherchez dans tout le catalogue avec « Tout ».") }
                return@Page
            }
            if (mine.isNotEmpty()) {
                item("lt-h") { SectionHeader("Titres") }
                item("lt") { TrackColumns(mine, columns = 3, rows = (mine.size + 2) / 3) }
            }
            playlistResults(lists, nav)
            return@Page
        }
        val r = results?.takeIf { it.first == query }?.second
        if (r == null) { item { Loading() }; return@Page }
        if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && lists.isEmpty()) {
            item { if (offline) OfflineMessage { attempt++ } else EmptyMessage("Aucun résultat", "Vérifiez l'orthographe ou essayez d'autres mots.", icon = KetIcons.Search) }
            return@Page
        }
        if (r.tracks.isNotEmpty()) {
            item("t-h") { SectionHeader("Titres") }
            item("t") { TrackColumns(r.tracks, columns = 3, rows = 4) }
        }
        if (r.artists.isNotEmpty()) {
            item("ar-h") { SectionHeader("Artistes") }
            item("ar") {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(Ket27Desk.Space.l)) {
                    items(r.artists.size) { i -> val a = r.artists[i]; ArtistCard(a, 150.dp) { nav.push(Route.Artist(a)) } }
                }
            }
        }
        if (r.albums.isNotEmpty()) {
            item("a-h") { SectionHeader("Albums") }
            item("a") { AlbumShelf(r.albums) }
        }
        if (r.tracks.size > 12) {
            item("more-h") { SectionHeader("Autres titres") }
            item("more") { TrackColumns(r.tracks.drop(12), columns = 3, rows = minOf(6, (r.tracks.size - 12 + 2) / 3)) }
        }
        playlistResults(lists, nav)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlistResults(lists: List<UserPlaylist>, nav: Navigator) {
    if (lists.isEmpty()) return
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

/** Ket 27's segmented control, desktop size. */
@Composable
internal fun Segments(options: List<String>, selected: Int, tag: String, onPick: (Int) -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).background(ket.fill).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(if (on) ket.raised else Color.Transparent)
                .clickable { onPick(i) }.padding(horizontal = 14.dp).testTag("$tag:$i"), contentAlignment = Alignment.Center) {
                KText(o, Ket27Desk.Type.footnote, color = if (on) ket.label else ket.secondary, weight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

/** 7.3's search start page: recent searches, recently played titles, then the catalogue's categories as tiles. */
@Composable
private fun SearchStart() {
    val nav = LocalNavigator.current
    val search = LocalSearch.current
    val ket = LocalKet.current
    val history by LocalLibrary.current.history.collectAsState()
    val recentQueries by com.ketvoice.app.data.SearchHistory.queries.collectAsState()
    val loader = rememberLoad(Unit, empty = { it.isEmpty() }) { categories() }
    Page("Rechercher", "search-start") {
        if (recentQueries.isNotEmpty()) {
            item("q-h") {
                SectionHeader("Recherches récentes") {
                    KetTextLink("Effacer", "clear-searches") { com.ketvoice.app.data.SearchHistory.clear() }
                }
            }
            item("q") {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentQueries.take(12).forEach { q ->
                        Row(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(ket.fill).clickable { search.query = q }
                            .padding(horizontal = 12.dp).testTag("recent-search"), verticalAlignment = Alignment.CenterVertically) {
                            com.ketvoice.app.ui.common.Icon(KetIcons.Clock, null, Modifier.size(12.dp), tint = ket.secondary)
                            Spacer(Modifier.width(6.dp))
                            KText(q, Ket27Desk.Type.footnote, translate = false)
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item("h-h") { SectionHeader("Écoutés récemment") }
            item("h") { TrackColumns(history, columns = 3, rows = 2) }
        }
        item("c-h") { SectionHeader("Catégories") }
        loadStates(loader)
        val list = loader.value ?: return@Page
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
    val loader = rememberLoad(category, empty = { it.isEmpty() }) { category(category) }
    Page(category.title, "category") {
        loadStates(loader)
        val list = loader.value ?: return@Page
        if (list.isEmpty()) item { EmptyMessage("Aucun résultat", "Cette catégorie est vide pour le moment.") }
        else item { TrackColumns(list, columns = 3, rows = (list.size + 2) / 3) }
    }
}

/* ── Explorer ── */

@Composable
fun ExplorePage() {
    val nav = LocalNavigator.current
    val loader = rememberLoad(Unit, empty = { it.isEmpty() }) { categories() }
    Page("Explorer", "explore") {
        item { SectionHeader("Catégories") }
        loadStates(loader)
        val list = loader.value ?: return@Page
        item { CategoryTiles(list) { nav.push(Route.Category(it)) } }
    }
}

/* ── Réglages ── */

@Composable
fun SettingsPage() {
    val ket = LocalKet.current
    var about by remember { mutableStateOf(false) }
    Page("Réglages", "settings") {
        accountSettings()
        item { SectionHeader("Général") }
        item {
            SettingsCard {
                LanguageRow()
                Hairline()
                SettingRow("Apparence", com.ketvoice.app.data.AppText.t(if (ket.dark) "Sombre (suit Windows)" else "Clair (suit Windows)"))
            }
        }
        item { SectionHeader("Ketmix") }
        item { KetmixSettings() }
        item { SectionHeader("Séparation des voix") }
        item { StemSettings() }
        item { SectionHeader("Lecteur") }
        item {
            SettingsCard {
                ToggleRow("Activer les Canvas vidéo", AppSettings.animatedArtwork.value, "canvas-player") { AppSettings.animatedArtwork.value = it }
                Hairline()
                ToggleRow("Canvas sur les pages d'album", AppSettings.albumHeroCanvas.value, "canvas-album") { AppSettings.albumHeroCanvas.value = it }
                Hairline()
                EqualizerRow()
            }
        }
        item { SectionHeader("Windows") }
        item {
            SettingsCard {
                ToggleRow("Fermer dans la zone de notification", DesktopPrefs.closeToTray.value, "close-to-tray") { DesktopPrefs.setCloseToTray(it) }
                Hairline()
                ToggleRow("Démarrer avec Windows", DesktopPrefs.startWithWindows.value, "start-with-windows") { DesktopPrefs.setStartWithWindows(it) }
                Hairline()
                ToggleRow("Mini-lecteur", MiniPlayer.open.value, "mini-player") { MiniPlayer.open.value = it }
            }
        }
        item { SectionHeader("Raccourcis clavier") }
        item { ShortcutsCard() }
        localMusicSettings()
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

/** Ketmix (the phone's settings and words): on/off, style, length of the transitions, analysis, skips, rhythm model. */
@Composable
private fun KetmixSettings() {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var downloading by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(com.ketvoice.app.automix.BeatModel.ready) }
    SettingsCard {
        ToggleRow("Ketmix", AppSettings.automix.value, "ketmix") { AppSettings.automix.value = it }
        Hairline()
        ChoiceRow("Style de transition", AppSettings.ketmixStyle.value, listOf("Automatique", "Calé sur le tempo", "Écho", "Filtres", "Fondu",
            "Montée", "Coupe", "Sur le drop", "Relais des voix"), "ketmix-style") { AppSettings.ketmixStyle.value = it }
        Hairline()
        ChoiceRow("Intensité du mix", AppSettings.automixIntensity.value, listOf("Discret", "Équilibré", "DJ"), "ketmix-intensity") {
            AppSettings.automixIntensity.value = it
        }
        Hairline()
        ChoiceRow("Analyse des titres", AppSettings.ketmixAnalysis.value, listOf("Légère", "Équilibrée", "Maximale"), "ketmix-analysis") {
            AppSettings.ketmixAnalysis.value = it
        }
        Hairline()
        ToggleRow("Mixer quand je passe au suivant", AppSettings.ketmixOnSkip.value, "ketmix-skip") { AppSettings.ketmixOnSkip.value = it }
        Hairline()
        if (ready) {
            ToggleRow("Calage précis du rythme", AppSettings.ketmixBeatModel.value, "ketmix-beat") { AppSettings.ketmixBeatModel.value = it }
        } else {
            val ket = LocalKet.current
            Row(Modifier.fillMaxWidth().height(44.dp).clickable(enabled = downloading == null) {
                failed = false; downloading = 0f
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ok = com.ketvoice.app.automix.BeatModel.download({ downloading = it })
                    downloading = null; failed = !ok; ready = ok
                    if (ok) AppSettings.ketmixBeatModel.value = true
                }
            }.padding(horizontal = 16.dp).testTag("ketmix-beat-download"), verticalAlignment = Alignment.CenterVertically) {
                KText("Calage précis du rythme", Ket27Desk.Type.subheadline, Modifier.weight(1f))
                val p = downloading
                KText(when { p != null -> "${(p * 100).toInt()} %"; failed -> "Échec du téléchargement"; else -> "Télécharger · 10 Mo" },
                    Ket27Desk.Type.subheadline, color = if (p == null && !failed) ket.accent else ket.secondary, weight = FontWeight.Normal,
                    translate = p == null)
            }
        }
    }
}

/**
 * The interface language: the system's, or one of the app's 11 (each in its own name). Every page reads
 * `AppText.t` through `AppSettings.language`, so the window changes language at once, without a restart.
 */
@Composable
private fun LanguageRow() {
    val options = listOf(com.ketvoice.app.data.AppText.t("Langue du système")) + com.ketvoice.app.data.AppLanguage.all.map { it.nativeName }
    val chosen = AppSettings.languageChosen.value
    val selected = if (!chosen) 0 else 1 + com.ketvoice.app.data.AppLanguage.all.indexOf(com.ketvoice.app.data.AppLanguage.fromLabel(AppSettings.language.value)).coerceAtLeast(0)
    ChoiceRow("Langue", selected, options, "language", translate = false) { i ->
        if (i == 0) { AppSettings.languageChosen.value = false; AppSettings.language.value = com.ketvoice.app.data.AppLocales.systemLanguage().label }
        else { AppSettings.language.value = com.ketvoice.app.data.AppLanguage.all[i - 1].label; AppSettings.languageChosen.value = true }
    }
}

/** Lecteur › Égaliseur: what it plays now, and its page. */
@Composable
private fun EqualizerRow() {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    Row(Modifier.fillMaxWidth().height(44.dp).clickable { nav.push(Route.Equalizer) }.padding(horizontal = 16.dp).testTag("open-equalizer"),
        verticalAlignment = Alignment.CenterVertically) {
        KText("Égaliseur", Ket27Desk.Type.subheadline, Modifier.weight(1f))
        KText(if (!AppSettings.equalizerEnabled.value) "Désactivé" else EqLogic.preset(AppSettings.equalizerGains.value) ?: "Personnalisé",
            Ket27Desk.Type.subheadline, color = ket.secondary, weight = FontWeight.Normal)
        com.ketvoice.app.ui.common.Icon(com.ketvoice.app.ui.common.KetIcons.ChevronRight, null, Modifier.padding(start = 6.dp).size(12.dp), tint = ket.tertiary)
    }
}

/**
 * « Séparation des voix » (the phone's rows): the voices in Ketmix's transitions, the two models (downloaded once,
 * checked against their pinned SHA-256) and which one separates. The voice level beside the lyrics uses them too.
 */
@Composable
private fun StemSettings() {
    val fast by com.ketvoice.app.stems.StemModel.fast.state.collectAsState()
    val hq by com.ketvoice.app.stems.StemModel.hq.state.collectAsState()
    var confirm by remember { mutableStateOf<com.ketvoice.app.stems.ModelPack?>(null) }
    SettingsCard {
        ToggleRow("Séparer les voix", AppSettings.ketmixStems.value, "ketmix-stems") { AppSettings.ketmixStems.value = it }
        Hairline()
        ModelRow("Modèle de séparation", com.ketvoice.app.stems.StemModel.fast, fast, "stems-fast") { confirm = it }
        Hairline()
        ModelRow("Modèle haute qualité", com.ketvoice.app.stems.StemModel.hq, hq, "stems-hq") { confirm = it }
        Hairline()
        ChoiceRow("Qualité de séparation", AppSettings.ketmixStemsQuality.value, listOf("Automatique", "Toujours la meilleure", "Toujours rapide"),
            "stems-quality") { AppSettings.ketmixStemsQuality.value = it }
    }
    confirm?.let { pack ->
        ConfirmDialog(com.ketvoice.app.data.AppText.t("Supprimer le modèle de séparation ?"),
            com.ketvoice.app.data.AppText.t("Les mix atténueront la voix au lieu de la retirer. Les titres déjà séparés restent."),
            "Supprimer", { pack.delete() }, { confirm = null })
    }
}

/** A model: « Télécharger · n Mo », the progress (a click cancels), « Prêt » (a click offers to delete it), or the failure. */
@Composable
private fun ModelRow(label: String, pack: com.ketvoice.app.stems.ModelPack, state: com.ketvoice.app.stems.ModelPack.State, tag: String,
                     onDelete: (com.ketvoice.app.stems.ModelPack) -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(44.dp).clickable {
        when (state) {
            com.ketvoice.app.stems.ModelPack.State.Ready -> onDelete(pack)
            is com.ketvoice.app.stems.ModelPack.State.Downloading -> pack.cancel()
            else -> pack.download()
        }
    }.padding(horizontal = 16.dp).testTag("model:$tag"), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.weight(1f))
        val t = com.ketvoice.app.data.AppText
        val (text, accent) = when (state) {
            com.ketvoice.app.stems.ModelPack.State.Missing -> t.t("Télécharger") + " · ${pack.totalBytes / 1_000_000L} Mo" to true
            is com.ketvoice.app.stems.ModelPack.State.Downloading -> "${(state.fraction * 100).toInt()} %" to false
            com.ketvoice.app.stems.ModelPack.State.Ready -> t.t("Prêt") to false
            is com.ketvoice.app.stems.ModelPack.State.Failed -> t.t("Échec · réessayer") to true
        }
        KText(text, Ket27Desk.Type.subheadline, color = if (accent) ket.accent else ket.secondary, weight = FontWeight.Normal, translate = false)
    }
}

/** A setting with a few named values: the current one on the right, the others in the window's menu. */
@Composable
private fun ChoiceRow(label: String, selected: Int, options: List<String>, tag: String, translate: Boolean = true, onPick: (Int) -> Unit) {
    val ket = LocalKet.current
    val menu = LocalMenu.current
    val origin = remember { arrayOf(androidx.compose.ui.geometry.Offset.Zero) }
    Row(Modifier.fillMaxWidth().height(44.dp)
        .onGloballyPositioned { origin[0] = it.positionInWindow() + androidx.compose.ui.geometry.Offset(it.size.width.toFloat() - 240f, it.size.height.toFloat()) }
        .clickable {
            menu.open(options.mapIndexed { i, o -> MenuItem(o, if (i == selected) com.ketvoice.app.ui.common.KetIcons.Check else null, tag = "$tag:$i", translate = translate) { onPick(i) } },
                androidx.compose.ui.unit.IntOffset(origin[0].x.toInt(), origin[0].y.toInt()))
        }.padding(horizontal = 16.dp).testTag("choice:$tag"), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.weight(1f))
        KText(options.getOrElse(selected) { options.first() }, Ket27Desk.Type.subheadline, color = ket.secondary, weight = FontWeight.Normal, translate = translate)
        com.ketvoice.app.ui.common.Icon(DeskGlyphs.ChevronDown, null, Modifier.padding(start = 6.dp).size(12.dp), tint = ket.tertiary)
    }
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    val ket = LocalKet.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card)) { content() }
}

@Composable
internal fun Hairline() {
    val ket = LocalKet.current
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(.5.dp).background(ket.hairline))
}

@Composable
internal fun SettingRow(label: String, value: String) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.weight(1f))
        KText(value, Ket27Desk.Type.subheadline, color = ket.secondary, translate = false, weight = FontWeight.Normal)
    }
}

/** A setting that is on or off: the whole row toggles, the switch shows it. */
@Composable
internal fun ToggleRow(label: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
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
