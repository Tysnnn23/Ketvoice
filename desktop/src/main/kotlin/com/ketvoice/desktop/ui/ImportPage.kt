package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.smartlib.ImportMatch
import com.ketvoice.app.smartlib.ImportPreview
import com.ketvoice.app.smartlib.ImportReport
import com.ketvoice.app.smartlib.PlaylistImport
import com.ketvoice.app.smartlib.YouTubeImport
import com.ketvoice.desktop.library.OutsidePlaylists
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The import page's importer: the shared one, YouTube links too, and the catalogue search used to fix a title by hand. */
interface PcPlaylistImporter : PlaylistImporter {
    suspend fun search(query: String): List<Track>
}

object PcImporter : PcPlaylistImporter {
    override suspend fun preview(link: String) = if (YouTubeImport.isLink(link)) YouTubeImport.preview(link) else PlaylistImport.preview(link)
    override suspend fun resolve(preview: ImportPreview, progress: (Int, Int) -> Unit) =
        PlaylistImport.resolve(preview, { q: String -> MusicApi.searchAll(q) }, progress = progress)
    override suspend fun search(query: String): List<Track> = MusicApi.searchAll(query).take(8)
}

/**
 * « Importer en un clic » (Windows 2.0): a link pasted (or dropped on the window), the playlist read and every title
 * looked for at once, then the match report — the share that will play, the titles found, uncertain and missing —
 * where any title can be fixed by hand with a search before the playlist is created (and, if wanted, followed).
 */
@Stable
class ImportFlow(link: String = "") {
    enum class Filter { All, Uncertain, Missing }
    var link by mutableStateOf(link)
    var preview by mutableStateOf<ImportPreview?>(null)
    var matches by mutableStateOf<List<ImportMatch>>(emptyList())
    var done by mutableStateOf(0)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var filter by mutableStateOf(Filter.All)
    var follow by mutableStateOf(false)
    /** The title being fixed, its search and what it found. */
    var fixing by mutableStateOf<Int?>(null)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Track>>(emptyList())
    val fixes = mutableStateMapOf<Int, Track?>()

    val report: ImportReport.Summary get() = ImportReport.summary(matches, fixes)
    val final: List<ImportMatch> get() = ImportReport.applied(matches, fixes)

    fun visible(): List<Pair<Int, ImportMatch>> = final.withIndex().map { it.index to it.value }.filter { (_, m) ->
        when (filter) { Filter.All -> true; Filter.Uncertain -> ImportReport.kind(m) == ImportReport.Kind.Uncertain; Filter.Missing -> ImportReport.kind(m) == ImportReport.Kind.Missing }
    }

    /** One click: the playlist read, then every title looked for. */
    fun analyse(scope: CoroutineScope, importer: PlaylistImporter) {
        if (busy || link.isBlank()) return
        busy = true; error = null; preview = null; matches = emptyList(); fixes.clear(); done = 0; fixing = null
        scope.launch {
            try {
                val p = importer.preview(link.trim())
                preview = p
                matches = importer.resolve(p) { d, _ -> done = d }
            } catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (e: IllegalArgumentException) { error = e.message }
            catch (e: IllegalStateException) { error = e.message }
            catch (_: java.io.IOException) { error = "Import impossible. Vérifiez votre connexion." }
            catch (_: Exception) { error = "Import impossible. Réessayez plus tard." }
            finally { busy = false }
        }
    }

    fun startFix(index: Int) {
        val song = matches.getOrNull(index)?.song ?: return
        fixing = index; query = "${song.title} ${song.artist}"; results = emptyList()
    }

    fun search(scope: CoroutineScope, importer: PcPlaylistImporter) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        scope.launch { results = runCatching { importer.search(q) }.getOrDefault(emptyList()) }
    }

    fun pick(track: Track?) { fixing?.let { fixes[it] = track }; fixing = null; results = emptyList() }
}

/** Registers the page (id `import`; « Connexions » and the drop overlay link to it, the argument being a link). */
object ImportPage {
    const val ID = "import"
    fun register() = PageRegistry.register(PageRegistry.Page(ID, "Importer une playlist", com.ketvoice.app.ui.common.KetIcons.Link, sidebar = false) { arg ->
        ImportPageContent(remember(arg) { ImportFlow(arg.orEmpty()) }, autoStart = arg != null)
    })
}

@Composable
fun ImportPageContent(flow: ImportFlow, autoStart: Boolean = false, importer: PcPlaylistImporter = PcImporter) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(flow) { if (autoStart) flow.analyse(scope, importer) }
    Page("Importer une playlist", ImportPage.ID) {
        item("link") { LinkStep(flow) { flow.analyse(scope, importer) } }
        val preview = flow.preview
        if (flow.busy && preview != null) item("progress") { Progress(flow, preview) }
        if (preview != null && flow.matches.isNotEmpty()) {
            item("report") { ReportCard(flow, preview) }
            item("filters") { Filters(flow) }
            itemsIndexed(flow.visible(), key = { _, (i, _) -> "m$i" }) { _, (i, m) -> MatchRow(flow, i, m, importer) }
            item("create") { CreateRow(flow, preview) }
        }
    }
}

@Composable
private fun LinkStep(flow: ImportFlow, go: () -> Unit) {
    val ket = LocalKet.current
    KText("Collez le lien d'une playlist Spotify, Apple Music, Deezer, Tidal, SoundCloud ou YouTube : Ketvoice la lit, retrouve chaque titre et vous montre ce qui manque.",
        Ket27Desk.Type.subheadline, Modifier.padding(bottom = 14.dp), color = ket.secondary, maxLines = 3)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).widthIn(max = 640.dp).height(40.dp).clip(RoundedCornerShape(20.dp)).background(ket.fill).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart) {
            if (flow.link.isEmpty()) KText("Lien de la playlist", Ket27Desk.Type.subheadline, color = ket.tertiary)
            BasicTextField(flow.link, { flow.link = it.take(500) }, Modifier.fillMaxWidth().textEntry().testTag("import-page-link"), singleLine = true,
                cursorBrush = SolidColor(ket.accent), textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }))
        }
        Spacer(Modifier.width(10.dp))
        CapsuleButton("Importer", KetIconsLink, go, filled = true, accent = if (flow.busy || flow.link.isBlank()) ket.accent.copy(alpha = .45f) else ket.accent,
            modifier = Modifier.testTag("import-page-go"))
        if (flow.busy) { Spacer(Modifier.width(10.dp)); Spinner() }
    }
    flow.error?.let { KText(it, Ket27Desk.Type.footnote, Modifier.padding(top = 8.dp).testTag("import-page-error"), color = destructiveRed, maxLines = 2) }
}

private val KetIconsLink get() = com.ketvoice.app.ui.common.KetIcons.Link

@Composable
private fun Progress(flow: ImportFlow, preview: ImportPreview) {
    val ket = LocalKet.current
    val total = preview.songs.size.coerceAtLeast(1)
    Column(Modifier.padding(top = 18.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ket.fill)) {
            Box(Modifier.fillMaxWidth(flow.done.toFloat() / total).height(4.dp).clip(RoundedCornerShape(2.dp)).background(ket.accent))
        }
        KText("${flow.done} / ${preview.songs.size} titres analysés", Ket27Desk.Type.footnote, Modifier.padding(top = 6.dp), color = ket.secondary, translate = false)
    }
}

/** The report: the playlist, the share that will play in large type, and the three counts (each a filter). */
@Composable
private fun ReportCard(flow: ImportFlow, preview: ImportPreview) {
    val ket = LocalKet.current
    val r = flow.report
    Row(Modifier.fillMaxWidth().padding(top = 24.dp).clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card).padding(20.dp)
        .testTag("import-report"), verticalAlignment = Alignment.CenterVertically) {
        Artwork(preview.cover, 84.dp, radius = 12.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            KText(preview.name, Ket27Desk.Type.title2, weight = FontWeight.Bold, translate = false)
            KText("${preview.service.label} · ${AppText.t("${preview.songs.size} titres")}", Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
            if (!preview.complete) KText("Ce service ne montre publiquement qu'une partie de cette playlist. Seuls ces titres seront importés.",
                Ket27Desk.Type.caption, Modifier.padding(top = 6.dp), color = ket.tertiary, maxLines = 2)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Count("Retrouvés", r.matched, Color(0xFF2FB36A), "import-count-matched") { flow.filter = ImportFlow.Filter.All }
                Count("Incertains", r.uncertain, Color(0xFFE0A030), "import-count-uncertain") { flow.filter = ImportFlow.Filter.Uncertain }
                Count("Manquants", r.missing, destructiveRed, "import-count-missing") { flow.filter = ImportFlow.Filter.Missing }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            KText("${r.rate} %", Ket27Desk.Type.display, Modifier.testTag("import-rate"), weight = FontWeight.Bold, translate = false)
            KText("seront jouables", Ket27Desk.Type.footnote, color = ket.secondary)
        }
    }
}

@Composable
private fun Count(label: String, count: Int, dot: Color, tag: String, onClick: () -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(ket.fill).clickable(onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Spacer(Modifier.width(7.dp))
        KText("${AppText.t(label)} · $count", Ket27Desk.Type.footnote, weight = FontWeight.Medium, translate = false)
    }
}

@Composable
private fun Filters(flow: ImportFlow) {
    val ket = LocalKet.current
    Row(Modifier.padding(top = 18.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(ImportFlow.Filter.All to "Tous", ImportFlow.Filter.Uncertain to "Incertains", ImportFlow.Filter.Missing to "Manquants").forEach { (f, label) ->
            val on = flow.filter == f
            Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) ket.accent.copy(alpha = .16f) else ket.fill)
                .clickable { flow.filter = f }.padding(horizontal = 14.dp).testTag("import-filter-${f.name}"), contentAlignment = Alignment.Center) {
                KText(label, Ket27Desk.Type.subheadline, color = if (on) ket.accent else ket.label, weight = FontWeight.Medium)
            }
        }
    }
}

/** One title: what the service calls it, what Ketvoice found (or not), « Corriger » and, while fixing, the search. */
@Composable
private fun MatchRow(flow: ImportFlow, index: Int, match: ImportMatch, importer: PcPlaylistImporter) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    val kind = ImportReport.kind(match)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(Ket27Desk.Radius.row)).background(ket.card.copy(alpha = .6f))
        .padding(horizontal = 14.dp, vertical = 10.dp).testTag("import-row:$index")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(when (kind) {
                ImportReport.Kind.Matched -> Color(0xFF2FB36A); ImportReport.Kind.Uncertain -> Color(0xFFE0A030); ImportReport.Kind.Missing -> destructiveRed }))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                KText(match.song.title, Ket27Desk.Type.subheadline, weight = FontWeight.Medium, translate = false)
                KText(match.song.artist, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
            }
            Column(Modifier.weight(1f)) {
                val t = match.track
                if (t == null) KText("Introuvable dans Ketvoice", Ket27Desk.Type.footnote, color = ket.tertiary)
                else {
                    KText(t.title, Ket27Desk.Type.subheadline, translate = false)
                    KText(t.artist, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
                }
            }
            KetTextLink(if (flow.fixing == index) "Annuler" else "Corriger", "import-fix:$index") {
                if (flow.fixing == index) flow.fixing = null else { flow.startFix(index); flow.search(scope, importer) }
            }
        }
        if (flow.fixing == index) FixPanel(flow, importer)
    }
}

@Composable
private fun FixPanel(flow: ImportFlow, importer: PcPlaylistImporter) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 10.dp, start = 20.dp)) {
        Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(17.dp)).background(ket.fill).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(flow.query, { flow.query = it.take(200) }, Modifier.fillMaxWidth().textEntry().testTag("import-fix-query"), singleLine = true,
                cursorBrush = SolidColor(ket.accent), textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.footnote).copy(color = ket.label),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { flow.search(scope, importer) }))
        }
        flow.results.forEachIndexed { k, t ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { flow.pick(t) }.padding(vertical = 6.dp).testTag("import-fix-result:$k"),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(t.coverUrl, 32.dp, radius = 6.dp)
                Spacer(Modifier.width(10.dp))
                KText("${t.title} — ${t.artist}", Ket27Desk.Type.footnote, Modifier.weight(1f), translate = false)
            }
        }
        KetTextLink("Laisser ce titre de côté", "import-fix-none") { flow.pick(null) }
    }
}

@Composable
private fun CreateRow(flow: ImportFlow, preview: ImportPreview) {
    val ket = LocalKet.current
    val library = LocalLibrary.current
    val nav = LocalNavigator.current
    Column(Modifier.padding(top = 20.dp, bottom = 40.dp)) {
        ToggleRow("Suivre la playlist d'origine", flow.follow, "import-follow") { flow.follow = it }
        KText("Ses nouveaux titres seront ajoutés ici ou proposés, selon Réglages › Bibliothèque sur PC.", Ket27Desk.Type.caption,
            Modifier.padding(horizontal = 16.dp), color = ket.tertiary, maxLines = 2)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
            CapsuleButton("Créer la playlist", null, {
                val follow = flow.follow
                library.importPlaylist(preview.name, PlaylistImport.entries(flow.final), preview.cover) { id ->
                    if (follow) OutsidePlaylists.follow(id, preview)
                    nav.root(Route.Playlist(id))
                }
            }, filled = true, accent = ket.accent, modifier = Modifier.testTag("import-page-create"))
        }
    }
}
