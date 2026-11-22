package com.ketvoice.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.LayoutList
import com.ketvoice.app.data.LayoutSettings
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/**
 * Settings › « Disposition » (the phone's 9.0 layout, see [DeskLayout]): the sidebar's entries, Home's sections, the
 * bar's buttons, the library's views and orders and a title's menu, each shown or hidden and moved up or down. Every
 * choice is the phone's key, so the account carries it to the listener's other devices.
 */
internal fun LazyListScope.layoutSettings() {
    item("h:layout") { SectionHeader("Disposition") }
    item("layout") { LayoutCard() }
}

@Composable
private fun LayoutCard() {
    SettingsCard {
        OrderGroup("Barre latérale", "De 2 à 5 entrées. L'Accueil ou la Bibliothèque reste toujours dans la barre latérale.",
            DeskLayout.sidebar, LayoutSettings.tabs, DeskLayout.sidebarLabels, "tabs")
        Hairline()
        OrderGroup("Accueil modulable", "Afficher, masquer et réordonner les sections de l'accueil",
            DeskLayout.home, LayoutSettings.home, DeskLayout.homeLabels, "home")
        Hairline()
        OrderGroup("Boutons du lecteur", "Les boutons à droite de la barre de lecture : de 2 à 5, la file d'attente reste toujours là.",
            DeskLayout.bar, LayoutSettings.player, DeskLayout.barLabels, "player")
        Hairline()
        LibraryGroup()
        Hairline()
        val menuLabels = if (TrackMenuSlots.lyricsEditor != null) DeskLayout.menuLabels else DeskLayout.menuLabels - "lyricsEditor"
        OrderGroup("Menu des titres", "Le menu d'un titre, d'un clic droit ou du bouton « … ».", DeskLayout.menu, LayoutSettings.menu, menuLabels, "menu")
        Hairline()
        ResetRow()
    }
}

/** A group's title row: a click opens or closes what it holds. */
@Composable
private fun GroupHeader(title: String, subtitle: String, open: Boolean, tag: String, onClick: () -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().settingTarget(title).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)
        .testTag("layout-group:$tag"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            KText(title, Ket27Desk.Type.subheadline)
            KText(subtitle, Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 2)
        }
        Icon(if (open) DeskGlyphs.ChevronUp else DeskGlyphs.ChevronDown, null, Modifier.padding(start = 8.dp).size(12.dp), tint = ket.tertiary)
    }
}

/** An ordered list of [list] stored in [state]: the ids the window draws ([labels]), each with its switch and arrows. */
@Composable
private fun OrderGroup(title: String, subtitle: String, list: LayoutList, state: MutableState<String>, labels: Map<String, String>, tag: String) {
    var open by remember { mutableStateOf(false) }
    GroupHeader(title, subtitle, open, tag) { open = !open }
    if (!open) return
    val rows = DeskLayout.rows(list, state.value, labels)
    rows.forEachIndexed { i, entry ->
        OrderRow(labels.getValue(entry.id), entry.shown, DeskLayout.canToggle(list, state.value, entry.id), i, rows.size, "$tag:${entry.id}",
            onToggle = { state.value = DeskLayout.toggle(list, state.value, entry.id) },
            onMove = { to -> state.value = DeskLayout.move(list, state.value, labels, i, to) })
    }
}

@Composable
private fun OrderRow(label: String, shown: Boolean, canToggle: Boolean, index: Int, count: Int, tag: String, onToggle: () -> Unit, onMove: (Int) -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 28.dp, end = 16.dp).testTag("layout-row:$tag"), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.subheadline, Modifier.weight(1f), color = if (shown) ket.label else ket.secondary)
        KetIconButton(DeskGlyphs.ChevronUp, "Monter", { onMove(index - 1) }, size = 26.dp, glyph = 12.dp, enabled = index > 0,
            modifier = Modifier.testTag("layout-up:$tag"))
        KetIconButton(DeskGlyphs.ChevronDown, "Descendre", { onMove(index + 1) }, size = 26.dp, glyph = 12.dp, enabled = index < count - 1,
            modifier = Modifier.testTag("layout-down:$tag"))
        androidx.compose.foundation.layout.Box(Modifier.padding(start = 8.dp).clickable(enabled = canToggle, onClick = onToggle)
            .testTag("layout-toggle:$tag")) { KetSwitch(shown, canToggle) }
    }
}

/** « Bibliothèque »: grid or list for Albums and Artists, and the order each page opens with. */
@Composable
private fun LibraryGroup() {
    var open by remember { mutableStateOf(false) }
    GroupHeader("Bibliothèque", "Liste ou grille, et l'ordre de chaque section à l'ouverture", open, "library") { open = !open }
    if (!open) return
    SubHeader("Vue")
    listOf("albums" to "Albums", "artists" to "Artistes").forEach { (id, label) ->
        ChoiceRow(label, if (DeskLayout.libraryGrid(id)) 0 else 1, listOf("Grille", "Liste"), "layout-view-$id") { i ->
            LayoutSettings.setLibraryView(id, if (i == 0) "grid" else "list")
        }
    }
    SubHeader("Ordre")
    listOf("playlists" to "Playlists", "albums" to "Albums", "artists" to "Artistes", "titles" to "Titres").forEach { (id, label) ->
        val choices = DeskLayout.sortChoices.getValue(id)
        ChoiceRow(label, choices.indexOfFirst { it.first == DeskLayout.librarySort(id) }.coerceAtLeast(0), choices.map { it.second },
            "layout-sort-$id") { i -> DeskLayout.setLibrarySort(id, choices[i].first) }
    }
}

@Composable
private fun SubHeader(text: String) {
    KText(text, Ket27Desk.Type.caption, Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp), color = LocalKet.current.secondary,
        weight = FontWeight.SemiBold)
}

/** « Rétablir la disposition »: what the window lays out, as it was. */
@Composable
private fun ResetRow() {
    val ket = LocalKet.current
    val changed = !DeskLayout.isDefault()
    Row(Modifier.fillMaxWidth().height(44.dp).settingTarget("Rétablir la disposition").clickable(enabled = changed) { DeskLayout.reset() }
        .padding(horizontal = 16.dp).testTag("layout-reset"), verticalAlignment = Alignment.CenterVertically) {
        KText("Rétablir la disposition", Ket27Desk.Type.subheadline, Modifier.weight(1f), color = if (changed) ket.accent else ket.tertiary)
    }
}
