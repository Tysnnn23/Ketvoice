package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LyricsOrder
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PlaylistFollow
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.library.PlaylistWatch
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/* ── Paroles: « Ordre des sources de paroles » (8.4, `lyricsOrder`, the shared LyricsOrder) ── */

internal fun LazyListScope.lyricsSettings() {
    item("h:lyrics") { SectionHeader("Paroles") }
    item("lyrics-order") { SettingsCard { LyricsOrderRows() } }
}

@Composable
private fun LyricsOrderRows() {
    val ket = LocalKet.current
    var open by remember { mutableStateOf(false) }
    val stored = AppSettings.lyricsOrder.value
    val custom = LyricsOrder.custom(stored)
    Row(Modifier.fillMaxWidth().settingTarget("Ordre des sources de paroles").clickable { open = !open }
        .padding(horizontal = 16.dp, vertical = 9.dp).testTag("lyrics-order"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            KText("Ordre des sources de paroles", Ket27Desk.Type.subheadline)
            KText("Les sources sont essayées dans cet ordre : la première qui a des paroles gagne", Ket27Desk.Type.footnote,
                color = ket.secondary, maxLines = 2)
        }
        KText(if (custom) "Personnalisé" else "Par défaut", Ket27Desk.Type.subheadline, color = ket.secondary, weight = FontWeight.Normal)
        Icon(if (open) DeskGlyphs.ChevronUp else DeskGlyphs.ChevronDown, null, Modifier.padding(start = 6.dp).size(12.dp), tint = ket.tertiary)
    }
    if (!open) return
    if (AppSettings.lyricsSource.value != "auto") {
        KText("Une source choisie dans « Source des paroles » est utilisée seule : cet ordre sert à la source Automatique.",
            Ket27Desk.Type.footnote, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = ket.secondary, maxLines = 3)
    }
    val order = LyricsOrder.parse(stored)
    order.forEachIndexed { i, id -> SourceRow(i, id, order) }
    Hairline()
    Row(Modifier.fillMaxWidth().height(44.dp).clickable(enabled = custom) { AppSettings.lyricsOrder.value = "" }
        .padding(horizontal = 16.dp).testTag("lyrics-order-reset"), verticalAlignment = Alignment.CenterVertically) {
        KText("Rétablir l'ordre par défaut", Ket27Desk.Type.subheadline, color = if (custom) ket.accent else ket.tertiary)
    }
}

@Composable
private fun SourceRow(index: Int, id: String, order: List<String>) {
    val ket = LocalKet.current
    fun move(to: Int) { AppSettings.lyricsOrder.value = LyricsOrder.encode(LyricsOrder.move(order, index, to)) }
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 16.dp, end = 16.dp).testTag("lyrics-source:$id"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(ket.fill), contentAlignment = Alignment.Center) {
            KText("${index + 1}", Ket27Desk.Type.caption, translate = false, weight = FontWeight.SemiBold)
        }
        KText(MusicApi.sourceName(id), Ket27Desk.Type.subheadline, Modifier.weight(1f).padding(start = 12.dp), translate = false)
        KetIconButton(DeskGlyphs.ChevronUp, "Monter", { move(index - 1) }, size = 26.dp, glyph = 12.dp, enabled = index > 0,
            modifier = Modifier.testTag("lyrics-up:$id"))
        KetIconButton(DeskGlyphs.ChevronDown, "Descendre", { move(index + 1) }, size = 26.dp, glyph = 12.dp, enabled = index < order.lastIndex,
            modifier = Modifier.testTag("lyrics-down:$id"))
    }
}

/* ── Téléchargements: « Playlists téléchargées à jour » (8.4, `playlistUpdates`) ── */

internal fun LazyListScope.downloadSettings() {
    item("h:downloads") { SectionHeader("Téléchargements") }
    item("download-settings") {
        SettingsCard {
            ToggleRow("Playlists téléchargées à jour", AppSettings.playlistUpdates.value, "playlist-updates") { AppSettings.playlistUpdates.value = it }
        }
    }
}

/** What [PlaylistWatch] found: « Nouveaux sons dans X », with Télécharger and Plus tard, at the bottom right of the window. */
@Composable
fun BoxScope.PlaylistUpdateCard(pending: List<PlaylistFollow.Update> = PlaylistWatch.pending.collectAsState().value,
                                onDownload: (PlaylistFollow.Update) -> Unit = { PlaylistWatch.download(it) },
                                onLater: (PlaylistFollow.Update) -> Unit = PlaylistWatch::dismiss) {
    val update = pending.firstOrNull() ?: return
    val ket = LocalKet.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp).widthIn(max = 320.dp).shadow(12.dp, shape).clip(shape)
        .background(ket.raised).border(.5.dp, ket.hairline, shape).padding(16.dp).testTag("playlist-update")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Download, null, Modifier.size(16.dp), tint = ket.accent)
            Spacer(Modifier.width(8.dp))
            KText(AppText.t("Nouveaux sons dans {0}").replace("{0}", update.name), Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold,
                translate = false)
        }
        KText(newTitles(update.trackIds.size), Ket27Desk.Type.footnote, Modifier.padding(top = 4.dp), color = ket.secondary, translate = false)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapsuleButton("Télécharger", null, { onDownload(update) }, filled = true, accent = ket.accent, modifier = Modifier.testTag("playlist-update-download"))
            CapsuleButton("Plus tard", null, { onLater(update) }, filled = false, accent = ket.accent, modifier = Modifier.testTag("playlist-update-later"))
        }
    }
}

/** « 1 nouveau titre » / « n nouveaux titres ». */
internal fun newTitles(count: Int): String =
    if (count == 1) AppText.t("1 nouveau titre") else AppText.t("{0} nouveaux titres").replace("{0}", count.toString())
