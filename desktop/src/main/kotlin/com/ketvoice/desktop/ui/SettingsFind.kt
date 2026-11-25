package com.ketvoice.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.settings.SettingsMatch
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.delay

/**
 * « Rechercher un réglage » (8.4) on Windows: a field at the top of Settings finds rows by their name or their section
 * (as the listener reads them, without accents or case, with the phone's matching), and a result scrolls the page to
 * its row and lights it for a moment.
 */
internal object SettingsFind {
    /** A row: its French name and the section it is in. */
    data class Entry(val title: String, val section: String, val sectionKey: String = "h:$section")

    var query by mutableStateOf("")
    /** The row a result pointed at, lit until the page has shown it a moment. */
    var target by mutableStateOf<Entry?>(null)

    /** The rows [titles] of the section [name], whose header item on the page is [key]. */
    private fun section(name: String, vararg titles: String, key: String = "h:$name") = titles.map { Entry(it, name, key) }

    /** The rows of the page, in its order. Rows added elsewhere join through [more]. */
    val fixed: List<Entry> = section("Compte Ketvoice", "Compte Ketvoice", key = "account-header") +
        section("Général", "Langue", "Apparence") +
        section("Ketmix", "Ketmix", "Style de transition", "Intensité du mix", "Analyse des titres", "Mixer quand je passe au suivant",
            "Calage précis du rythme") +
        section("Séparation des voix", "Séparer les voix", "Modèle de séparation", "Modèle haute qualité", "Qualité de séparation",
            "Voix prêtes à l'avance") +
        // Windows 2.0 rows drawn on the page itself (not through SettingsSections, which index their own).
        section("Puissance de Ketmix", "Puissance de Ketmix", "Cet ordinateur", "Grands modèles", "Accélération GPU", "Carte graphique",
            "Préparation au repos", "Ketmix apprend de vos avis") +
        section("Lecteur", "Activer les Canvas vidéo", "Canvas sur les pages d'album", "Égaliseur") +
        section("Paroles", "Ordre des sources de paroles", key = "h:lyrics") +
        section("Téléchargements", "Playlists téléchargées à jour", key = "h:downloads") +
        section("Sources", "Sources", "Ajouter une source", key = "h:sources") +
        section("Catalogue de sources", "Catalogue de sources", key = "h:catalogue") +
        section("Lecture", "Taille du cache", "Vider le cache", "Préparer les favoris et les playlists", "Lecture sans blanc",
            "Mode exclusif (bit-perfect)", key = "h:playback") +
        section("Disposition", "Barre latérale", "Accueil modulable", "Boutons du lecteur", "Bibliothèque", "Menu des titres",
            "Rétablir la disposition", key = "h:layout") +
        section("Windows", "Fermer dans la zone de notification", "Démarrer avec Windows", "Mini-lecteur", "Verre Windows 11") +
        section("Raccourcis clavier", "Raccourcis clavier") +
        section("Musique locale", "Choisir un dossier", key = "local-header") +
        section("À propos", "Mises à jour", "Version")

    /** Rows another section of Settings adds (its titles and section header, as the page draws them). */
    val more = mutableListOf<Entry>()

    val entries: List<Entry> get() = fixed + more

    /** The rows [query] finds, best first ([SettingsMatch]: every word in the name or the section). */
    fun search(query: String, entries: List<Entry> = this.entries, translate: (String) -> String = AppText::t): List<Entry> {
        val words = SettingsMatch.words(query)
        if (words.isEmpty()) return emptyList()
        return entries.mapNotNull { e -> SettingsMatch.rank(query, words, translate(e.title), translate(e.section))?.let { e to it } }
            .sortedBy { it.second }.map { it.first }.distinct()
    }

    /** Opens [entry]: the field empties, the page scrolls to its section and lights its row. */
    fun open(entry: Entry) { query = ""; target = entry }

    /**
     * Scrolls [state] until the item [key] is laid out (the page is a lazy list: an item far below is not composed
     * yet), then brings it to the top.
     */
    suspend fun scrollTo(state: LazyListState, key: String) {
        fun index() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index
        // The page first takes in what opened the result (the field emptied, the results gone).
        withFrameNanos { }
        if (index() == null) { state.scrollToItem(0); withFrameNanos { } }
        var guard = 0
        while (index() == null && state.canScrollForward && guard++ < 200) {
            state.scrollBy(state.layoutInfo.viewportSize.height * .8f)
            withFrameNanos { }
        }
        index()?.let { state.animateScrollToItem(it) }
    }
}

/** The search field and, while it holds text, what it finds. The first item of Settings. */
internal fun LazyListScope.settingsSearch() {
    item("settings-search") { SettingsSearchField() }
    if (SettingsFind.query.isNotBlank()) item("settings-results") { SettingsResults(SettingsFind.query) }
}

@Composable
private fun SettingsSearchField() {
    val ket = LocalKet.current
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(34.dp).clip(RoundedCornerShape(9.dp)).background(ket.fill)
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(KetIcons.Search, null, Modifier.size(14.dp), tint = ket.secondary)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (SettingsFind.query.isEmpty()) KText("Rechercher un réglage", Ket27Desk.Type.subheadline, color = ket.secondary)
            BasicTextField(SettingsFind.query, { SettingsFind.query = it }, Modifier.fillMaxWidth().textEntry().testTag("settings-search"),
                singleLine = true, cursorBrush = SolidColor(ket.accent),
                textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label))
        }
        if (SettingsFind.query.isNotEmpty()) KetIconButton(KetIcons.Close, "Effacer", { SettingsFind.query = "" }, size = 20.dp, glyph = 10.dp)
    }
}

@Composable
private fun SettingsResults(query: String) {
    val ket = LocalKet.current
    val language = com.ketvoice.app.data.AppSettings.language.value
    val found = remember(query, language) { SettingsFind.search(query) }
    Box(Modifier.padding(top = 10.dp)) {
        if (found.isEmpty()) {
            KText(AppText.t("Aucun réglage ne correspond à « {0} »").replace("{0}", query.trim()), Ket27Desk.Type.subheadline,
                Modifier.padding(vertical = 14.dp).testTag("settings-search-empty"), color = ket.secondary, translate = false)
            return@Box
        }
        SettingsCard {
            found.forEachIndexed { i, entry ->
                if (i > 0) Hairline()
                ResultRow(entry)
            }
        }
    }
}

@Composable
private fun ResultRow(entry: SettingsFind.Entry) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    Row(Modifier.fillMaxWidth().height(48.dp).background(if (hovered) ket.hover else Color.Transparent).hoverable(source)
        .clickable { SettingsFind.open(entry) }.padding(horizontal = 16.dp).testTag("settings-result"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            KText(entry.title, Ket27Desk.Type.subheadline)
            KText(entry.section, Ket27Desk.Type.caption, color = ket.secondary)
        }
        Icon(KetIcons.ChevronRight, null, Modifier.size(12.dp), tint = ket.tertiary)
    }
}

/** Settings follows a result: to its section, then the row lights up for a moment. */
@Composable
internal fun SettingsFindEffect(state: LazyListState) {
    val target = SettingsFind.target
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        SettingsFind.scrollTo(state, target.sectionKey)
        delay(2_500)
        if (SettingsFind.target == target) SettingsFind.target = null
    }
}

/** A row Settings' search can point at: [label] as the row shows it; lit and brought into view while it is the target. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.settingTarget(label: String): Modifier {
    val lit = SettingsFind.target?.title == label
    val ket = LocalKet.current
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(lit) { if (lit) { delay(350); runCatching { requester.bringIntoView() } } }
    return bringIntoViewRequester(requester).background(if (lit) ket.accent.copy(alpha = .16f) else Color.Transparent)
}
