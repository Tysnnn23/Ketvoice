package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CustomSource
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.SourceCatalogue
import com.ketvoice.app.data.SourcePriority
import com.ketvoice.desktop.audio.ExclusiveOutput
import com.ketvoice.desktop.audio.PlaybackPrefs
import com.ketvoice.desktop.audio.StreamCache
import com.ketvoice.desktop.player.CacheWarmer
import com.ketvoice.desktop.player.SourceTester
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * Windows 2.0 « Sources » and « Lecture » (sources and playback). Laid out with the settings' own pieces; the
 * redesign restyles them. Hooked into Settings with one line (`sourcesSettings()`).
 *
 * Sources: every source the player tries, in its order (drag to reorder, arrows for the keyboard), each with its
 * switch and its status; custom sources can be tested (search, playback, quality) and removed; a manifest is added by
 * its address, or from the catalogue on the website in one click. Lecture: the smart cache's size and statistics,
 * favourites warmed ahead, « Lecture sans blanc » and « Mode exclusif » with why it is not exclusive when it is not.
 */

internal fun LazyListScope.sourcesSettings() {
    item("h:sources") { SectionHeader("Sources") }
    item("sources-order") { SettingsCard { SourceOrder() } }
    item("sources-add") { SettingsCard { AddSource() } }
    item("h:catalogue") { SectionHeader("Catalogue de sources") }
    item("sources-catalogue") { SettingsCard { Catalogue() } }
    item("h:playback") { SectionHeader("Lecture") }
    item("playback-cache") { SettingsCard { CacheCard() } }
    item("playback-modes") { SettingsCard { PlaybackModes() } }
}

/** One test's outcome per custom source id, kept while the window lives. */
private object SourceTests {
    val running = mutableStateMapOf<String, Boolean>()
    val results = mutableStateMapOf<String, SourceTester.Result>()
}

@Composable
private fun SourceOrder() {
    val ket = LocalKet.current
    val order by SourcePriority.playbackOrder.collectAsState()
    val disabled by SourcePriority.playbackDisabled.collectAsState()
    val custom by CustomSources.sources.collectAsState()
    val entries = remember(order, disabled, custom) { SourcePriority.entries(SourcePriority.Mode.PLAYBACK) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    KText("Les sources sont essayées dans cet ordre pour lire un titre. Glissez pour changer l'ordre.", Ket27Desk.Type.footnote,
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = ket.secondary, maxLines = 2)
    fun move(from: Int, to: Int) {
        if (to == from || to !in entries.indices) return
        val ids = entries.map { it.id }.toMutableList().apply { add(to, removeAt(from)) }
        SourcePriority.setOrder(ids)
        CustomSources.reorder(ids.filter { it.startsWith("cs:") }.map { it.removePrefix("cs:") })
    }
    entries.forEachIndexed { index, entry ->
        val source = custom.firstOrNull { "cs:${it.id}" == entry.id }
        Hairline()
        Row(Modifier.fillMaxWidth().heightIn(min = ROW).graphicsLayer { translationY = if (dragging == entry.id) offset else 0f }
            .background(if (dragging == entry.id) ket.fill else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 12.dp).testTag("source:${entry.id}"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp, ROW).pointerInput(entry.id, entries) {
                detectDragGestures(
                    onDragStart = { dragging = entry.id; offset = 0f },
                    onDragEnd = { move(index, (index + (offset / rowPx).roundToInt()).coerceIn(0, entries.lastIndex)); dragging = null; offset = 0f },
                    onDragCancel = { dragging = null; offset = 0f },
                ) { change, drag -> change.consume(); offset += drag.y }
            }.testTag("source-drag:${entry.id}"), contentAlignment = Alignment.Center) {
                KText("≡", Ket27Desk.Type.headline, color = ket.tertiary, translate = false)
            }
            Box(Modifier.size(22.dp).clip(CircleShape).background(ket.fill), contentAlignment = Alignment.Center) {
                KText("${index + 1}", Ket27Desk.Type.caption, translate = false, weight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
                KText(entry.name, Ket27Desk.Type.subheadline, translate = source == null)
                KText(status(entry.enabled, source), Ket27Desk.Type.footnote, color = ket.secondary, translate = false, maxLines = 2)
            }
            if (source != null) SourceActions(source)
            KetIconButton(com.ketvoice.desktop.ui.theme.DeskGlyphs.ChevronUp, "Monter", { move(index, index - 1) }, size = 26.dp, glyph = 12.dp,
                enabled = index > 0, modifier = Modifier.testTag("source-up:${entry.id}"))
            KetIconButton(com.ketvoice.desktop.ui.theme.DeskGlyphs.ChevronDown, "Descendre", { move(index, index + 1) }, size = 26.dp, glyph = 12.dp,
                enabled = index < entries.lastIndex, modifier = Modifier.testTag("source-down:${entry.id}"))
            Box(Modifier.clickable { SourcePriority.setEnabled(entry.id, !entry.enabled) }.padding(start = 8.dp).testTag("source-switch:${entry.id}")) {
                KetSwitch(entry.enabled)
            }
        }
    }
}

private val ROW = 52.dp

/** The status line of a source: off, its last test, its manifest's state. */
private fun status(enabled: Boolean, source: CustomSource?): String {
    if (!enabled) return AppText.t("Désactivée")
    if (source == null) return AppText.t("Intégrée")
    if (SourceTests.running[source.id] == true) return AppText.t("Test en cours…")
    SourceTests.results[source.id]?.let { r ->
        val head = when {
            r.playable -> AppText.t("Lecture OK") + " · " + r.format?.let(SourceTester::describe).orEmpty()
            r.searchOk -> AppText.t("Recherche OK, lecture impossible")
            else -> r.problem?.let(AppText::t) ?: AppText.t("Ne répond pas")
        }
        val extras = r.extras.joinToString(", ") { AppText.t(EXTRA_NAMES.getValue(it)) }
        return listOf(head, "${r.results} " + AppText.t("résultats") + " · ${r.searchMs} ms", extras).filter { it.isNotBlank() }.joinToString(" · ")
    }
    source.error?.let { return AppText.t(it) }
    if (source.manifestJson.isBlank()) return AppText.t("Manifeste en attente")
    return AppText.t("Prête") + " · " + source.manifestUrl.substringAfter("://").substringBefore('/')
}

private val EXTRA_NAMES = mapOf("lyrics" to "paroles", "canvas" to "Canvas", "cover" to "pochettes")

@Composable
private fun SourceActions(source: CustomSource) {
    val scope = rememberCoroutineScope()
    KetTextLink("Tester", "source-test:${source.id}") {
        if (SourceTests.running[source.id] == true) return@KetTextLink
        SourceTests.running[source.id] = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SourceTester.test(source) }.getOrElse { SourceTester.Result(false, problem = "Ne répond pas") } }
            SourceTests.results[source.id] = result
            SourceTests.running[source.id] = false
        }
    }
    KetTextLink("Supprimer", "source-remove:${source.id}") { CustomSources.remove(source.id); SourceTests.results.remove(source.id) }
}

@Composable
private fun AddSource() {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun add() {
        val url = address.trim()
        if (busy || url.isEmpty()) return
        busy = true; message = AppText.t("Vérification du manifeste…")
        scope.launch {
            val result = CustomSources.add(url)
            busy = false
            message = result.fold({ address = ""; AppText.t("Source ajoutée :") + " " + it.name }, { AppText.t(it.message ?: "Ajout impossible") })
        }
    }
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        KText("Ajouter une source", Ket27Desk.Type.subheadline, Modifier.width(170.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (address.isEmpty()) KText("https://…/manifest.json", Ket27Desk.Type.subheadline, color = ket.tertiary, translate = false)
            BasicTextField(address, { address = it.take(500) }, Modifier.fillMaxWidth().textEntry().testTag("source-address"), singleLine = true,
                textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
                cursorBrush = SolidColor(ket.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }))
        }
        KetTextLink(if (busy) "Vérification…" else "Ajouter", "source-add") { add() }
    }
    message?.let {
        KText(it, Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), color = ket.secondary, translate = false, maxLines = 3)
    }
}

/** The catalogue on the website: each manifest with « Installer » (the same as adding its address). */
@Composable
private fun Catalogue() {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    var catalogue by remember { mutableStateOf<SourceCatalogue.Catalogue?>(null) }
    var failed by remember { mutableStateOf(false) }
    val installing = remember { mutableStateMapOf<String, Boolean>() }
    var message by remember { mutableStateOf<String?>(null) }
    val sources by CustomSources.sources.collectAsState()
    LaunchedEffect(Unit) {
        catalogue = withContext(Dispatchers.IO) { runCatching { SourceCatalogueClient.fetch() }.getOrNull() }
        failed = catalogue == null
    }
    val list = catalogue?.entries
    if (list == null) {
        KText(if (failed) "Catalogue indisponible pour le moment" else "Chargement du catalogue…", Ket27Desk.Type.subheadline,
            Modifier.padding(16.dp), color = ket.secondary)
        return
    }
    val language = AppText.language().code.substringBefore('-')
    list.forEachIndexed { i, entry ->
        if (i > 0) Hairline()
        Row(Modifier.fillMaxWidth().heightIn(min = ROW).padding(horizontal = 16.dp, vertical = 6.dp).testTag("catalogue:${entry.id}"),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KText(entry.name, Ket27Desk.Type.subheadline, translate = false)
                    entry.quality?.let { q ->
                        Box(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(4.dp)).background(ket.fill).padding(horizontal = 5.dp, vertical = 1.dp)) {
                            KText(QUALITY.getValue(q), Ket27Desk.Type.caption, color = ket.secondary)
                        }
                    }
                    if (entry.official) KText("Officielle", Ket27Desk.Type.caption, Modifier.padding(start = 8.dp), color = ket.accent)
                }
                KText(entry.describe(language), Ket27Desk.Type.footnote, color = ket.secondary, translate = false, maxLines = 2)
            }
            when {
                SourceCatalogue.installed(entry, sources) -> KText("Installée", Ket27Desk.Type.subheadline, color = ket.secondary)
                installing[entry.id] == true -> KText("Installation…", Ket27Desk.Type.subheadline, color = ket.secondary)
                else -> KetTextLink("Installer", "catalogue-install:${entry.id}") {
                    installing[entry.id] = true
                    scope.launch {
                        val result = CustomSources.add(entry.manifestUrl)
                        installing[entry.id] = false
                        message = result.exceptionOrNull()?.let { AppText.t(it.message ?: "Ajout impossible") }
                    }
                }
            }
        }
    }
    message?.let { KText(it, Ket27Desk.Type.footnote, Modifier.padding(16.dp), color = ket.secondary, translate = false, maxLines = 3) }
}

private val QUALITY = mapOf("lossless" to "Sans perte", "high" to "Haute qualité", "low" to "Économique")

/** Reads the website's catalogue (at most once every 10 minutes per window). */
internal object SourceCatalogueClient {
    private var cached: Pair<Long, SourceCatalogue.Catalogue>? = null
    internal var get: (String) -> String? = { url ->
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "Ketvoice/2.0 (Windows)")
            if (c.responseCode in 200..299) c.inputStream.use { String(it.readNBytes(1_000_000), Charsets.UTF_8) } else null
        } finally { c.disconnect() }
    }

    @Synchronized fun fetch(now: Long = System.currentTimeMillis()): SourceCatalogue.Catalogue? {
        cached?.takeIf { now - it.first < 600_000 }?.let { return it.second }
        val catalogue = get(SourceCatalogue.URL)?.let(SourceCatalogue::parse) ?: return cached?.second
        cached = now to catalogue
        return catalogue
    }
}

@Composable
private fun CacheCard() {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<StreamCache.Stats?>(null) }
    val warm by CacheWarmer.status.collectAsState()
    val gb = PlaybackPrefs.cacheGb.value
    LaunchedEffect(gb, warm.running, warm.warmed) { stats = withContext(Dispatchers.IO) { runCatching { StreamCache.stats() }.getOrNull() } }
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp).settingTarget("Taille du cache"), verticalAlignment = Alignment.CenterVertically) {
        KText("Taille du cache", Ket27Desk.Type.subheadline, Modifier.weight(1f))
        StreamCache.SIZES_GB.forEach { size ->
            Box(Modifier.padding(start = 4.dp).clip(RoundedCornerShape(6.dp)).background(if (size == gb) ket.accent else ket.fill)
                .clickable { PlaybackPrefs.setCacheGb(size) }.padding(horizontal = 8.dp, vertical = 4.dp).testTag("cache-gb:$size")) {
                KText("$size " + AppText.t("Go"), Ket27Desk.Type.caption, color = if (size == gb) androidx.compose.ui.graphics.Color.White else ket.label, translate = false)
            }
        }
    }
    Hairline()
    val s = stats
    SettingRow("Utilisé", if (s == null) "…" else "${size(s.bytes)} / ${size(s.limit)} · ${s.titles} " + AppText.t("titres") +
        " (${s.complete} " + AppText.t("complets") + ")")
    Hairline()
    ToggleRow("Préparer les favoris et les playlists", PlaybackPrefs.warm.value, "cache-warm") { PlaybackPrefs.setWarm(it) }
    KText(if (warm.running) AppText.t("Préparation en cours :") + " ${warm.warmed} / ${warm.candidates}"
        else AppText.t("Le début de vos favoris et des titres de vos playlists est gardé sur ce PC pour qu'ils démarrent aussitôt."),
        Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), color = ket.secondary, translate = false, maxLines = 3)
    Hairline()
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        KetTextLink("Préparer maintenant", "cache-warm-now") { CacheWarmer.runNow(scope) }
        KetTextLink("Vider le cache", "cache-clear") {
            scope.launch { withContext(Dispatchers.IO) { StreamCache.clear() }; stats = withContext(Dispatchers.IO) { StreamCache.stats() } }
        }
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f".format(java.util.Locale.FRANCE, bytes / (1L shl 30).toDouble()) + " " + AppText.t("Go")
    else -> "${bytes shr 20} " + AppText.t("Mo")
}

@Composable
private fun PlaybackModes() {
    val ket = LocalKet.current
    ToggleRow("Lecture sans blanc", PlaybackPrefs.trueGapless.value, "true-gapless") { PlaybackPrefs.setTrueGapless(it) }
    KText("Aucun silence entre les titres d'un album : le titre suivant est préparé à l'avance et enchaîné à l'échantillon près.",
        Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), color = ket.secondary, maxLines = 3)
    Hairline()
    ToggleRow("Mode exclusif (bit-perfect)", PlaybackPrefs.exclusive.value, "exclusive") { PlaybackPrefs.setExclusive(it) }
    val status by ExclusiveOutput.status.collectAsState()
    val line = when {
        !status.wanted -> AppText.t("Ketvoice prend la carte son pour lui seul et lui envoie chaque titre à sa fréquence et sa résolution d'origine, sans rééchantillonnage.")
        status.exclusive -> AppText.t("Exclusif") + " · " + status.format?.let { f -> "%.1f".format(java.util.Locale.FRANCE, f.sampleRate / 1000.0).removeSuffix(",0") + " kHz · ${f.bits} " + AppText.t("bits") }.orEmpty()
        status.reason != null -> AppText.t("Partagé :") + " " + AppText.t(status.reason!!)
        else -> AppText.t("Partagé")
    }
    KText(line, Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).testTag("exclusive-status"),
        color = if (status.wanted && !status.exclusive) ket.accent else ket.secondary, translate = false, maxLines = 3)
}
