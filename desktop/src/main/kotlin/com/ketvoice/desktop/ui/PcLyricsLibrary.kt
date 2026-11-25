package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.stems.ModelPack
import com.ketvoice.desktop.ketmix.KetmixPower
import com.ketvoice.desktop.library.LibraryWatch
import com.ketvoice.desktop.library.OutsidePlaylists
import com.ketvoice.desktop.lyrics.AiSync
import com.ketvoice.desktop.lyrics.AlignModels
import com.ketvoice.desktop.lyrics.LyricsEngine
import com.ketvoice.desktop.lyrics.LyricsPcSettings
import com.ketvoice.desktop.platform.WindowStore
import com.ketvoice.desktop.ui.glass.GlassBackdrop
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.CoroutineScope

/**
 * Windows 2.0 lyrics and library, started behind the window ([start]): the lyrics engine and the word sync by AI, the
 * import page, the followed outside playlists and their card, « Glisser-déposer partout », the watched library, and
 * their two Settings sections (« Paroles sur PC », « Bibliothèque sur PC »).
 */
object PcLyricsLibrary {
    fun start(context: android.content.Context, scope: CoroutineScope) {
        LyricsPcSettings.load(context)
        runCatching { com.ketvoice.app.data.LocalLyrics.init(context) }
        LyricsEngine.install()
        AiSync.start(context, scope)
        OutsidePlaylists.start(context, scope)
        LibraryWatch.start(scope)
        // The registries are the window's state: changed on its thread.
        java.awt.EventQueue.invokeLater { register() }
    }

    /** Pages, overlays and settings (also what the tests call without starting anything). */
    fun register() {
        ImportPage.register()
        DropAnywhere.register()
        ShellOverlays.register(ShellOverlays.Overlay("outside-playlists", order = 20) { backdrop -> OutsideUpdateCard(backdrop) })
        SettingsSections.register(SettingsSections.Section("lyrics-pc", "Paroles sur PC", after = "Windows",
            rows = listOf("Synchronisation des mots par IA", "Traduction sous chaque ligne", "Paroles flottantes sur le bureau",
                "Verrouiller les paroles flottantes", "Écran des paroles flottantes")) { LyricsPcSection() })
        SettingsSections.register(SettingsSections.Section("library-pc", "Bibliothèque sur PC", after = "Windows",
            rows = listOf("Importer une playlist", "Ajouter automatiquement les nouveaux titres", "Playlists suivies")) { LibraryPcSection() })
    }
}

@Composable
private fun LyricsPcSection() {
    val ket = LocalKet.current
    val model by AlignModels.pack.state.collectAsState()
    val status by AiSync.status.collectAsState()
    val maximum = KetmixPower.current() == KetmixPower.Tier.Maximum
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card)) {
        ToggleRow("Synchronisation des mots par IA", LyricsPcSettings.aiSync.value, "ai-sync") {
            LyricsPcSettings.aiSync.value = it; LyricsPcSettings.save(); AiSync.refreshStatus()
        }
        val detail = when {
            !maximum -> "Sur un PC fixe en « Puissance de Ketmix » Maximum (ou Auto) : les paroles synchronisées ligne par ligne le sont mot par mot, sur la voix séparée."
            model is ModelPack.State.Downloading -> AppText.t("Téléchargement du modèle") + " · ${((model as ModelPack.State.Downloading).fraction * 100).toInt()} %"
            status is AiSync.Status.Working -> "Synchronisation des mots en cours…"
            AlignModels.ready -> "Modèle installé (Whisper small, licence MIT). Tourne en arrière-plan, au repos ou pendant l'écoute."
            else -> "Le modèle (970 Mo) se télécharge à la demande."
        }
        KText(detail, Ket27Desk.Type.footnote, Modifier.padding(horizontal = 16.dp), color = ket.secondary, maxLines = 3)
        if (maximum && !AlignModels.ready && model !is ModelPack.State.Downloading) Row(Modifier.padding(16.dp)) {
            CapsuleButton("Télécharger le modèle", null, { AiSync.download() }, filled = false, accent = ket.accent, modifier = Modifier.testTag("ai-sync-download"))
        }
        Hairline()
        ToggleRow("Traduction sous chaque ligne", LyricsPcSettings.translateEvery.value, "translate-every") { LyricsPcSettings.translateEvery.value = it; LyricsPcSettings.save() }
        Hairline()
        ToggleRow("Paroles flottantes sur le bureau", LyricsPcSettings.floating.value, "floating-lyrics") { if (it != LyricsPcSettings.floating.value) FloatingLyrics.toggle() }
        ToggleRow("Verrouiller les paroles flottantes", LyricsPcSettings.floatingLocked.value, "floating-locked") { FloatingLyrics.setLocked(it) }
        val screens = WindowStore.awtScreens()
        if (screens.size > 1) Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            KText("Écran des paroles flottantes", Ket27Desk.Type.subheadline, Modifier.weight(1f))
            screens.indices.forEach { i ->
                val on = LyricsPcSettings.floatingMonitor.value == i
                KText("${i + 1}", Ket27Desk.Type.subheadline, Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) ket.accent.copy(alpha = .16f) else ket.fill).clickable { LyricsPcSettings.floatingMonitor.value = i; LyricsPcSettings.save() }
                    .padding(horizontal = 12.dp, vertical = 4.dp).testTag("floating-monitor:$i"), color = if (on) ket.accent else ket.label, translate = false)
            }
        }
    }
}

@Composable
private fun LibraryPcSection() {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    val followed by OutsidePlaylists.followed.collectAsState()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card)) {
        Row(Modifier.fillMaxWidth().height(44.dp).clickable { nav.push(Route.Extra(ImportPage.ID)) }.padding(horizontal = 16.dp).testTag("open-import"),
            verticalAlignment = Alignment.CenterVertically) {
            KText("Importer une playlist", Ket27Desk.Type.subheadline, Modifier.weight(1f))
            KText("Spotify, Apple Music, Deezer, Tidal, SoundCloud, YouTube", Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
        Hairline()
        ToggleRow("Ajouter automatiquement les nouveaux titres", OutsidePlaylists.autoAdd.value, "outside-auto") { OutsidePlaylists.setAutoAdd(it) }
        KText("Pour les playlists suivies : sinon, ils vous sont proposés.", Ket27Desk.Type.footnote, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = ket.secondary)
        if (followed.isNotEmpty()) {
            Hairline()
            KText("Playlists suivies", Ket27Desk.Type.footnote, Modifier.padding(start = 16.dp, top = 10.dp), color = ket.secondary, weight = FontWeight.SemiBold)
            followed.forEach { f ->
                Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    KText(f.name, Ket27Desk.Type.subheadline, Modifier.weight(1f), translate = false)
                    KetTextLink("Ne plus suivre", "unfollow:${f.playlistId}") { OutsidePlaylists.unfollow(f.playlistId) }
                }
            }
        }
        KText("Les dossiers de musique choisis sont surveillés : un fichier ajouté, modifié ou supprimé apparaît aussitôt, avec ses tags, sa pochette et ses paroles (intégrées ou .lrc).",
            Ket27Desk.Type.footnote, Modifier.padding(16.dp), color = ket.tertiary, maxLines = 3)
    }
}

/** « Nouveaux titres dans X » for a followed outside playlist, with Ajouter and Plus tard (above the playlist updates card). */
@Composable
private fun BoxScope.OutsideUpdateCard(backdrop: GlassBackdrop?) {
    val pending by OutsidePlaylists.pending.collectAsState()
    val offer = pending.firstOrNull() ?: return
    val ket = LocalKet.current
    Column(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = ShellMetrics.ISLAND_HEIGHT + ShellMetrics.ISLAND_FOOT * 2 + 150.dp).widthIn(max = 320.dp)
        .glass(backdrop, 16.dp, ket.glassTint, GlassStyle.Panel, rim = ket.glassRim).border(.5.dp, ket.hairline, RoundedCornerShape(16.dp)).padding(16.dp)
        .testTag("outside-update")) {
        KText(AppText.t("Nouveaux sons dans {0}").replace("{0}", offer.name), Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
        KText(newTitles(offer.tracks.size), Ket27Desk.Type.footnote, Modifier.padding(top = 4.dp), color = ket.secondary, translate = false)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapsuleButton("Ajouter", null, { OutsidePlaylists.accept(offer) }, filled = true, accent = ket.accent, modifier = Modifier.testTag("outside-add"))
            CapsuleButton("Plus tard", null, { OutsidePlaylists.dismiss(offer) }, filled = false, accent = ket.accent, modifier = Modifier.testTag("outside-later"))
        }
    }
}
