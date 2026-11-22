package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LayoutList
import com.ketvoice.app.data.LayoutSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.chrome.TabDestination
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.SwipeAction
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.TitleMenuLayout
import com.ketvoice.app.ui.home.HomeSection
import com.ketvoice.app.ui.library.LibraryLayout
import com.ketvoice.app.ui.player.MiniAction
import com.ketvoice.app.ui.player.PlayerAction

/**
 * Settings › Personnalisation › « Disposition » (9.0, owner 2026-11-22: « rends complètement customisable l'app »):
 * the tabs of the bar, Home's sections, the player's and mini-player's buttons, the artwork's size, the Canvas in
 * the player, the library's views and orders, the title menu and the swipes of a title row. Every choice is kept at
 * once; « Rétablir » brings back the layout of 8.4.
 */
internal object LayoutEditors {
    /** One of the ordered lists the editor sheet changes: its state, its rules and how its items read. */
    class Spec(val key: String, val title: String, val subtitle: String, val rule: String, val list: LayoutList,
               val state: MutableState<String>, val label: (String) -> String, val icon: (String) -> ImageVector?)

    val tabs get() = Spec("tabs", "Onglets à la carte",
        "Choisissez les onglets de la barre et faites-les glisser dans l'ordre voulu.",
        "De 2 à 5 onglets. L'Accueil ou la Bibliothèque reste toujours dans la barre.",
        LayoutSettings.tabList, LayoutSettings.tabs, { TabDestination.of(it)?.label ?: it }, { TabDestination.of(it)?.icon })
    val home get() = Spec("home", "Accueil modulable",
        "Affichez, masquez et faites glisser les sections de l'accueil.",
        "Le message d'accueil reste en haut de la page.",
        LayoutSettings.homeList, LayoutSettings.home, { HomeSection.of(it)?.label ?: it }, { null })
    val player get() = Spec("player", "Boutons du lecteur",
        "La rangée sous la barre de lecture : un bouton à chaque bout, les autres dans la capsule de verre.",
        "De 2 à 5 boutons. La file d'attente reste toujours dans la rangée.",
        LayoutSettings.playerList, LayoutSettings.player, { PlayerAction.of(it)?.label ?: it }, { null })
    val mini get() = Spec("mini", "Boutons du mini-lecteur",
        "À côté de lecture/pause, dans le mini-lecteur.",
        "Jusqu'à deux boutons. Précédent se place avant lecture/pause.",
        LayoutSettings.miniList, LayoutSettings.mini, { MiniAction.of(it)?.label ?: it }, { null })
    val menu get() = Spec("menu", "Menu des titres",
        "Le menu d'un titre, d'un appui long ou du bouton « … ».",
        "« Plus » reste toujours à la fin du menu.",
        LayoutSettings.menuList, LayoutSettings.menu, TitleMenuLayout::label,
        { id -> TitleMenuLayout.items.firstOrNull { it.first == id }?.third })

    fun of(key: String): Spec? = listOf(tabs, home, player, mini, menu).firstOrNull { it.key == key }

    /** What a list row of Settings says: the items shown, in order. */
    fun summary(spec: Spec): String = spec.list.shown(spec.state.value).joinToString(" · ") { AppText.t(spec.label(it)) }

    val artworkLabels = listOf("Standard", "Plus petite", "Plus grande")
}

/** The rows of the « Disposition » section; one entry point, drawn by Settings › Personnalisation. */
@Composable
internal fun ColumnScope.LayoutCustomizationRows() {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<ChoiceSpec?>(null) }
    var library by rememberSaveable { mutableStateOf(false) }
    val homeShown = LayoutSettings.homeList.shown(LayoutSettings.home.value).size
    SRow(com.ketvoice.app.ui.common.Ket27Glyphs.Home, "Onglets à la carte",
        sub = "Les onglets de la barre et leur ordre ; la barre latérale des tablettes suit le même ordre",
        value = LayoutEditors.summary(LayoutEditors.tabs)) { sheet = "tabs" }
    SRow(KetIcons.Library, "Accueil modulable", sub = "Afficher, masquer et réordonner les sections de l'accueil",
        value = AppText.t("$homeShown sections sur ${HomeSection.entries.size}")) { sheet = "home" }
    SRow(KetIcons.Audio, "Boutons du lecteur", sub = "La rangée de boutons sous la barre de lecture",
        value = LayoutEditors.summary(LayoutEditors.player)) { sheet = "player" }
    SRow(KetIcons.Music, "Boutons du mini-lecteur", sub = "À côté de lecture/pause, jusqu'à deux",
        value = LayoutEditors.summary(LayoutEditors.mini).ifEmpty { AppText.t("Aucun") }) { sheet = "mini" }
    SRow(KetIcons.Album, "Taille de la pochette", sub = "Dans le lecteur, toujours bord à bord",
        value = AppText.t(LayoutEditors.artworkLabels[LayoutSettings.artwork.intValue.coerceIn(0, LayoutSettings.ARTWORK_MAX)])) {
        choice = artworkChoice()
    }
    SToggle(KetIcons.Canvas, "Canvas dans le lecteur",
        "Désactivé, le lecteur montre la pochette. Les Canvas restent sur l'appareil et ailleurs dans l'app.",
        LayoutSettings.canvas.value) { LayoutSettings.canvas.value = it; SettingsStore.save() }
    SRow(KetIcons.Playlist, "Bibliothèque", sub = "Liste ou grille, et l'ordre de chaque section à l'ouverture",
        value = LibraryLayout.viewSections.joinToString(" · ") { (id, name) ->
            AppText.t(name) + " " + AppText.t(if (LibraryLayout.grid(id)) "grille" else "liste") }) { library = true }
    SRow(KetIcons.More, "Menu des titres", sub = "Ordre et éléments du menu d'un titre ; « Plus » reste à la fin",
        value = AppText.t("${LayoutSettings.menuList.shown(LayoutSettings.menu.value).size} éléments")) { sheet = "menu" }
    SRow(KetIcons.ChevronRight, "Balayer vers la droite", sub = "Sur une ligne de titre",
        value = AppText.t(SwipeAction.toEnd().label)) { choice = swipeChoice(true) }
    SRow(KetIcons.Refresh, "Balayer vers la gauche", sub = "Sur une ligne de titre",
        value = AppText.t(SwipeAction.toStart().label)) { choice = swipeChoice(false) }
    SRow(KetIcons.Refresh, "Rétablir la disposition", sub = "Onglets, accueil, lecteur, bibliothèque, menu et gestes d'origine",
        value = if (LayoutSettings.isDefault()) AppText.t("Disposition d'origine") else "") {
        LayoutSettings.resetAll(); SettingsStore.save()
    }
    sheet?.let { key -> LayoutEditors.of(key)?.let { spec -> LayoutListSheet(spec) { sheet = null } } }
    if (library) LibraryLayoutSheet { library = false }
    choice?.let { spec -> ChoiceDialog(spec) { choice = null } }
}

private fun artworkChoice() = ChoiceSpec("Taille de la pochette",
    "La pochette et le Canvas gardent toute la largeur, sans bandes ni image étirée.",
    LayoutSettings.artwork.intValue.toString(),
    listOf(ChoiceOption("0", "Standard", "La hauteur suit le format de l'image"),
        ChoiceOption("1", "Plus petite", "Plus d'espace sous la pochette"),
        ChoiceOption("2", "Plus grande", "Les images larges gagnent en hauteur"))) {
    LayoutSettings.artwork.intValue = (it.toIntOrNull() ?: 0).coerceIn(0, LayoutSettings.ARTWORK_MAX); SettingsStore.save()
}

private fun swipeChoice(toEnd: Boolean) = ChoiceSpec(if (toEnd) "Balayer vers la droite" else "Balayer vers la gauche",
    "Ce que fait un balayage sur une ligne de titre, dans toutes les listes.",
    (if (toEnd) SwipeAction.toEnd() else SwipeAction.toStart()).id,
    SwipeAction.entries.map { ChoiceOption(it.id, it.label) }) { id ->
    if (id in LayoutSettings.swipeActions) {
        if (toEnd) LayoutSettings.swipeEnd.value = id else LayoutSettings.swipeStart.value = id
        SettingsStore.save()
    }
}

/** « Modifier l'accueil », opened from a long press on Home's greeting: the same editor as in Settings. */
@Composable
internal fun HomeSectionsSheet(onClose: () -> Unit) = LayoutListSheet(LayoutEditors.home, onClose)

@Composable
internal fun LayoutListSheet(spec: LayoutEditors.Spec, onClose: () -> Unit) {
    var entries by remember(spec.key) { mutableStateOf(spec.list.parse(spec.state.value)) }
    fun keep(next: List<LayoutList.Entry>) {
        entries = next
        spec.state.value = spec.list.encode(next)
        SettingsStore.save()
    }
    SettingsSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight()).verticalScroll(rememberScrollState())) {
            LayoutListContent(spec, entries, ::keep, onClose)
        }
    }
}

/** What the editor shows; stateless, so the host tests draw it as it is. */
@Composable
internal fun LayoutListContent(spec: LayoutEditors.Spec, entries: List<LayoutList.Entry>, onChange: (List<LayoutList.Entry>) -> Unit,
                               onClose: () -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).testTag("layout-editor-" + spec.key),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSheetHeader(spec.title, AppText.t(spec.subtitle), onClose = onClose)
        Text(spec.rule, color = theme.secondary, style = Ket27.Type.footnote)
        var dragging by remember { mutableStateOf<String?>(null) }
        var offset by remember { mutableFloatStateOf(0f) }
        var rowHeight by remember { mutableIntStateOf(1) }
        val latest by rememberUpdatedState(entries)
        Column(Modifier.fillMaxWidth().settingsGroup()) {
            entries.forEachIndexed { index, entry ->
                LayoutListRow(spec, entries, index, dragging == entry.id, { if (dragging == entry.id) offset else 0f },
                    onHeight = { rowHeight = it }, onChange = onChange, drag = Modifier.pointerInput(entry.id) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = entry.id; offset = 0f },
                            onVerticalDrag = { change, delta ->
                                change.consume()
                                offset += delta
                                val at = latest.indexOfFirst { it.id == entry.id }
                                val target = (at + kotlin.math.round(offset / rowHeight).toInt()).coerceIn(0, latest.lastIndex)
                                if (target != at) { offset -= (target - at) * rowHeight; onChange(spec.list.move(latest, at, target)) }
                            },
                            onDragEnd = { dragging = null; offset = 0f },
                            onDragCancel = { dragging = null; offset = 0f })
                    })
                if (index != entries.lastIndex) SettingsCellDivider()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            KetTextButton("Rétablir", { onChange(spec.list.defaults) }, enabled = entries != spec.list.defaults,
                modifier = Modifier.testTag("layout-reset-" + spec.key))
        }
    }
}

/** One item of the editor: its symbol, its name, whether it is shown, and the handle that moves it. */
@Composable
private fun LayoutListRow(spec: LayoutEditors.Spec, entries: List<LayoutList.Entry>, index: Int, dragged: Boolean, shift: () -> Float,
                          onHeight: (Int) -> Unit, onChange: (List<LayoutList.Entry>) -> Unit, drag: Modifier) {
    val theme = LocalTheme.current
    val entry = entries[index]
    val canToggle = spec.list.canToggle(entries, index)
    val up = AppText.t("Monter"); val down = AppText.t("Descendre")
    Row(Modifier.fillMaxWidth().heightIn(min = Ket27.Metrics.row).onSizeChanged { onHeight(it.height.coerceAtLeast(1)) }
        .graphicsLayer { translationY = shift(); shadowElevation = if (dragged) 12f else 0f }
        .background(if (dragged) theme.card else Color.Transparent)
        .toggleable(entry.shown, enabled = canToggle, role = Role.Switch) { onChange(spec.list.toggle(entries, index)) }
        .semantics { customActions = listOfNotNull(
            if (index > 0) CustomAccessibilityAction(up) { onChange(spec.list.move(entries, index, index - 1)); true } else null,
            if (index < entries.lastIndex) CustomAccessibilityAction(down) { onChange(spec.list.move(entries, index, index + 1)); true } else null) }
        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag("layout-item-" + entry.id),
        verticalAlignment = Alignment.CenterVertically) {
        spec.icon(entry.id)?.let { Icon(it, null, tint = if (entry.shown) theme.accent else theme.secondary, modifier = Modifier.size(22.dp)) }
            ?: Text((index + 1).toString(), localize = false, color = theme.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(22.dp))
        Text(spec.label(entry.id), color = if (entry.shown) theme.onBackground else theme.secondary, fontSize = 17.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
        Box(Modifier.alpha(if (canToggle) 1f else .45f)) { com.ketvoice.app.ui.common.LiquidGlassSwitch(entry.shown, onCheckedChange = null) }
        Icon(Icons.Filled.DragHandle, AppText.t("Déplacer"), tint = theme.secondary,
            modifier = Modifier.size(44.dp).padding(10.dp).testTag("layout-handle").then(drag))
    }
}

/** « Bibliothèque »: list or grid for Playlists, Albums and Artists, and the order each section opens in. */
@Composable
internal fun LibraryLayoutSheet(onClose: () -> Unit) {
    SettingsSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight()).verticalScroll(rememberScrollState())) {
            LibraryLayoutContent(onClose)
        }
    }
}

@Composable
internal fun LibraryLayoutContent(onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).testTag("layout-library"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSheetHeader("Bibliothèque", AppText.t("Comment chaque section s'ouvre. Le bouton « … » d'une section change encore la vue le temps d'une visite."), onClose = onClose)
        SettingsGroupHeading("Vue")
        LibraryLayout.viewSections.forEach { (id, name) ->
            LibraryChoiceRow(name, listOf("list" to "Liste", "grid" to "Grille"), LayoutSettings.libraryView(id), "layout-view-$id") {
                LayoutSettings.setLibraryView(id, it); SettingsStore.save()
            }
        }
        SettingsGroupHeading("Ordre")
        LibraryLayout.sortSections.forEach { (id, name) ->
            LibraryChoiceRow(name, LibraryLayout.sortChoices(id), LayoutSettings.librarySort(id), "layout-sort-$id") {
                LayoutSettings.setLibrarySort(id, it); SettingsStore.save()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            KetTextButton("Rétablir", {
                LayoutSettings.libraryViews.value = ""; LayoutSettings.librarySorts.value = ""; SettingsStore.save()
            }, enabled = LayoutSettings.libraryViews.value.isNotEmpty() || LayoutSettings.librarySorts.value.isNotEmpty())
        }
    }
}

/** A section's name and its choices as pills, the one in use filled. */
@Composable
private fun LibraryChoiceRow(name: String, choices: List<Pair<String, String>>, current: String, tag: String, onPick: (String) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Text(name, color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, label) ->
                val on = value == current
                Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (on) theme.accent else theme.onBackground.copy(alpha = .08f))
                    .clickable(role = Role.RadioButton) { onPick(value) }
                    .semantics { selected = on }
                    .padding(horizontal = 16.dp, vertical = 10.dp).testTag("$tag:$value"), contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) Color.White else theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
