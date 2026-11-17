package com.ketvoice.app.ui.smartlib

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.smartlib.DuplicateGroup
import com.ketvoice.app.smartlib.Duplicates
import com.ketvoice.app.smartlib.MergePlan
import com.ketvoice.app.smartlib.SavedCopy
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.pageBackground
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.launch

/**
 * The review of duplicates: each recording saved several times, the copy to keep chosen by the
 * listener. Merging tidies the playlists at once; downloads are only deleted after a confirmation.
 * Versions of one song (live, remastered…) are listed apart and never merged unless asked.
 */
@Composable
fun DuplicatesScreen() {
    val theme = LocalTheme.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { SmartLibrary.init(context) }
    val downloads by Downloader.items.collectAsState()
    val lists by PlaylistStore.lists.collectAsState()
    val kept by SmartLibrary.kept.collectAsState()
    val groups = remember(downloads, lists, kept) { Duplicates.find(SmartLibrary.copies(), kept) }
    // The merge waiting for the listener's answer about its downloads, with its group.
    var pending by remember { mutableStateOf<Pair<String, MergePlan>?>(null) }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun merge(group: DuplicateGroup, keep: com.ketvoice.app.data.Track) {
        val plan = Duplicates.plan(group, keep)
        scope.launch {
            runCatching { SmartLibrary.mergePlaylists(plan) }.onFailure { notice = "Impossible de lire les playlists" }
            if (plan.extraDownloads.isNotEmpty()) pending = group.key to plan else notice = "Titres fusionnés"
        }
    }
    Box(Modifier.fillMaxSize().pageBackground(theme).testTag("duplicates-screen")) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(top = 60.dp, start = 16.dp, end = 16.dp, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "intro") {
                Text("Titres enregistrés plusieurs fois. Choisissez celui à garder : les playlists sont regroupées sur lui, et aucun téléchargement n'est supprimé sans votre accord.",
                    color = theme.secondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                if (notice.isNotBlank()) Text(notice, color = theme.accent, fontSize = 14.sp)
            }
            if (groups.isEmpty()) item(key = "empty") {
                EmptyState("Aucun doublon", "Aucun titre n'est enregistré deux fois dans votre bibliothèque.", "Retour",
                    icon = KetIcons.Check) { AppUi.closeOverlay() }
            }
            val (same, versions) = groups.partition { it.sameVersion }
            items(same, key = { it.key }) { group -> DuplicateCard(group, ::merge) }
            if (versions.isNotEmpty()) item(key = "versions") {
                com.ketvoice.app.ui.common.SectionHeader("Autres versions", Modifier.padding(top = 10.dp))
                Text("Live, remasterisé, remix… Ce sont des enregistrements différents : fusionnez-les seulement si vous le souhaitez.",
                    color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            items(versions, key = { it.key }) { group -> DuplicateCard(group, ::merge) }
        }
        com.ketvoice.app.ui.common.Ket27PageBar("Doublons", { AppUi.closeOverlay() }, Modifier.statusBarsPadding())
    }
    pending?.let { (key, plan) -> DeleteDownloadsAlert(plan, onDone = { deleted -> pending = null
        // Files kept on purpose: the group is not offered again.
        if (deleted == 0) SmartLibrary.keep(key)
        notice = if (deleted > 0) AppText.t("Titres fusionnés") + " · " + AppText.t(if (deleted == 1) "1 téléchargement supprimé" else "$deleted téléchargements supprimés") else AppText.t("Titres fusionnés") }) }
}

@Composable
private fun DeleteDownloadsAlert(plan: MergePlan, onDone: (Int) -> Unit) {
    val context = LocalContext.current
    val bytes = plan.extraDownloads.sumOf { it.downloadBytes }
    val size = android.text.format.Formatter.formatShortFileSize(context, bytes)
    AppAlertDialog(onDismissRequest = { onDone(0) },
        title = { Text("Supprimer les téléchargements en double ?") },
        text = { Text(AppText.t(if (plan.extraDownloads.size == 1) "1 fichier · $size" else "${plan.extraDownloads.size} fichiers · $size") + "\n" +
            AppText.t("Le titre gardé reste téléchargé. Les fichiers supprimés ne peuvent pas être récupérés."), localize = false) },
        confirmButton = { KetTextButton("Supprimer", { onDone(SmartLibrary.deleteDownloads(plan.extraDownloads)) }, destructive = true,
            modifier = Modifier.testTag("duplicates-delete")) },
        dismissButton = { KetTextButton("Garder les fichiers", { onDone(0) }) })
}

@Composable
private fun DuplicateCard(group: DuplicateGroup, onMerge: (DuplicateGroup, com.ketvoice.app.data.Track) -> Unit) {
    val theme = LocalTheme.current
    var keep by remember(group.key) { mutableStateOf(Duplicates.preferred(group).id) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(theme.card).padding(14.dp).testTag("duplicate-" + group.key)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(group.title, group.copies.firstNotNullOfOrNull { it.track.coverUrl }, 48.dp, 8.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(group.title, localize = false, color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(group.artist, localize = false, color = theme.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        group.copies.distinctBy { it.track.id }.forEach { copy -> CopyRow(copy, group, keep == copy.track.id) { keep = copy.track.id } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            KetTextButton("Garder tout", { SmartLibrary.keep(group.key) }, modifier = Modifier.testTag("duplicate-keep"))
            KetTextButton("Fusionner", { group.copies.firstOrNull { it.track.id == keep }?.let { onMerge(group, it.track) } },
                primary = true, modifier = Modifier.testTag("duplicate-merge"))
        }
    }
}

/** One title of a group: where it is saved, and whether it is the one kept. */
@Composable
private fun CopyRow(copy: SavedCopy, group: DuplicateGroup, chosen: Boolean, onChoose: () -> Unit) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val places = group.copies.filter { it.track.id == copy.track.id }
    val where = places.joinToString(" · ") { place ->
        if (place.downloaded) listOfNotNull(AppText.t("Téléchargé"), place.format, android.text.format.Formatter.formatShortFileSize(context, place.downloadBytes)
            .takeIf { place.downloadBytes > 0 }).joinToString(" ")
        else AppText.t("Playlist") + " « ${place.playlistName} »"
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClick = onChoose)
        .padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (chosen) theme.accent else theme.secondary.copy(alpha = .5f), CircleShape)
            .padding(5.dp).clip(CircleShape).background(if (chosen) theme.accent else theme.card))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(copy.track.title, localize = false, color = theme.onBackground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(where, com.ketvoice.app.ui.player.formatSecs(copy.track.durationMs).takeIf { copy.track.durationMs > 0 }.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · "), localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 2)
        }
        if (chosen) Text("Gardé", color = theme.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A line in the downloads page when some titles are downloaded twice. */
@Composable
fun DuplicatesHint(saved: List<Downloader.Item>) {
    val theme = LocalTheme.current
    val kept by SmartLibrary.kept.collectAsState()
    val count = remember(saved, kept) {
        Duplicates.find(saved.map { SavedCopy(it.track, downloadBytes = it.bytes, format = it.format) }, kept).count { it.sameVersion }
    }
    if (count == 0) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.accent.copy(alpha = .1f))
        .clickable(role = Role.Button) { AppUi.openDuplicates() }.padding(horizontal = 14.dp, vertical = 12.dp).testTag("downloads-duplicates"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(AppText.t(if (count == 1) "1 titre téléchargé en double" else "$count titres téléchargés en double"), localize = false,
            color = theme.onBackground, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("Vérifier", color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
