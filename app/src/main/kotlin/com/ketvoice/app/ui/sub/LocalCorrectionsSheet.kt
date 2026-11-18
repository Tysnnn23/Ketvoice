package com.ketvoice.app.ui.sub

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LocalCorrections
import com.ketvoice.app.data.LocalFileWriter
import com.ketvoice.app.data.LocalMatch
import com.ketvoice.app.data.LocalMusic
import com.ketvoice.app.data.LocalTags
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.KetCheckbox
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * « Corrections proposées » (7.0): what the catalogue would change in the listener's tags, per album, accepted
 * per album or all at once. Accepted corrections stay in the app; « Écrire dans le fichier » (off unless the
 * listener ticks it) writes them into MP3 and FLAC files too, when Android allows it.
 */
@Composable
internal fun LocalCorrectionsSheet(onDismiss: () -> Unit) {
    val proposals by LocalCorrections.proposals.collectAsState()
    val tags by LocalMusic.tags.collectAsState()
    var intoFiles by remember { mutableStateOf(false) }
    val accept = rememberAccept { intoFiles }
    LocalSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("local-corrections-sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CorrectionsIntro(proposals.size, intoFiles, { intoFiles = it }) { accept(proposals.map { it.localId }) }
            byAlbum(proposals, tags).forEach { (album, list) -> CorrectionAlbum(album, list, tags, accept) }
            if (proposals.isEmpty()) Text("Aucune correction à revoir", color = LocalTheme.current.secondary, fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 24.dp))
        }
    }
}

/** The Ket 27 sheet on a phone (detents, spring); a centred panel on Quest, where a pointer does not drag a sheet well. */
@Composable
private fun LocalSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val theme = LocalTheme.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(com.ketvoice.app.theme.Ket27.Metrics.alertRadius)
    @Composable fun Panel(max: androidx.compose.ui.unit.Dp) = Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
        .heightIn(max = max).clip(shape).background(theme.card).pointerInput(Unit) { detectTapGestures { } }) {
        androidx.compose.foundation.layout.Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(36.dp, 5.dp)
            .clip(androidx.compose.foundation.shape.CircleShape).background(theme.secondary.copy(alpha = .4f)))
        content()
    }
    if (com.ketvoice.app.ui.common.springSheets) {
        com.ketvoice.app.ui.common.SpringSheet(onDismiss, insets = Modifier.statusBarsPadding().navigationBarsPadding()) { room ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) { Panel(room - 16.dp) }
        }
        return
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .45f))
        .clickable(onClick = onDismiss), contentAlignment = Alignment.Center) { Panel(650.dp) }
}

/** Proposals grouped under the album the file says it belongs to (« Sans album » otherwise). */
private fun byAlbum(proposals: List<LocalMatch.Proposal>, tags: Map<String, LocalTags>): List<Pair<String?, List<LocalMatch.Proposal>>> =
    proposals.groupBy { tags[it.localId]?.album }.toList().sortedBy { it.first?.lowercase() ?: "￿" }

@Composable
private fun CorrectionsIntro(count: Int, intoFiles: Boolean, onIntoFiles: (Boolean) -> Unit, onAll: () -> Unit) {
    val theme = LocalTheme.current
    Text("Corrections proposées", color = theme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Trouvées en comparant tes fichiers au catalogue (titre, artiste et durée). Rien ne change sans ton accord, et les corrections restent dans l'app.",
        color = theme.secondary, fontSize = 13.sp, lineHeight = 18.sp)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { onIntoFiles(!intoFiles) }.testTag("local-write-files"),
        verticalAlignment = Alignment.CenterVertically) {
        KetCheckbox(intoFiles, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("Écrire dans le fichier", color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("MP3 et FLAC seulement, si Android l'autorise. Le son du fichier n'est jamais touché.", color = theme.secondary, fontSize = 12.sp)
        }
    }
    if (count > 1) KetTextButton("Tout accepter", onAll, primary = true, modifier = Modifier.testTag("local-accept-all"))
}

@Composable
private fun CorrectionAlbum(album: String?, list: List<LocalMatch.Proposal>, tags: Map<String, LocalTags>, accept: (List<String>) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(album ?: AppText.t("Sans album"), localize = false, color = theme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            KetTextButton("Accepter pour cet album", { accept(list.map { it.localId }) }, modifier = Modifier.testTag("local-accept-album"))
        }
        list.forEach { CorrectionLine(it, tags[it.localId]) }
        KetTextButton("Ignorer", { LocalCorrections.dismiss(list.map { it.localId }) })
    }
}

@Composable
private fun CorrectionLine(proposal: LocalMatch.Proposal, now: LocalTags?) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("local-correction")) {
        Text(now?.title ?: proposal.catalog.title, localize = false, color = theme.onBackground, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        proposal.changes.forEach { (field, value) ->
            val before = when (field) {
                LocalMatch.Field.Title -> now?.title; LocalMatch.Field.Artist -> now?.artist
                LocalMatch.Field.Album -> now?.album; LocalMatch.Field.AlbumArtist -> now?.albumArtist
            }
            Text(AppText.t(fieldLabel(field)) + " : " + (before ?: "—") + " → " + value, localize = false,
                color = theme.secondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun fieldLabel(field: LocalMatch.Field): String = when (field) {
    LocalMatch.Field.Title -> "Titre"
    LocalMatch.Field.Artist -> "Artiste"
    LocalMatch.Field.Album -> "Album"
    LocalMatch.Field.AlbumArtist -> "Artiste de l'album"
}

/**
 * Accepts the proposals of these ids; with « Écrire dans le fichier », writes them into the files too — after
 * Android's confirmation for files of the whole device.
 */
@Composable
private fun rememberAccept(intoFiles: () -> Boolean): (List<String>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<List<LocalMatch.Proposal>>(emptyList()) }
    fun write(chosen: List<LocalMatch.Proposal>) = scope.launch {
        val outcomes = withContext(Dispatchers.IO) {
            chosen.mapNotNull { p -> LocalMusic.uriOf(p.localId)?.let { LocalFileWriter.write(context, it, LocalFileWriter.update(p.changes)) } }
        }
        runCatching { Toast.makeText(context, writeSummary(outcomes), Toast.LENGTH_LONG).show() }
    }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val chosen = waiting; waiting = emptyList()
        if (result.resultCode == android.app.Activity.RESULT_OK) write(chosen)
        else runCatching { Toast.makeText(context, AppText.t("Android n'a pas autorisé l'écriture : les corrections restent dans l'app"), Toast.LENGTH_LONG).show() }
    }
    return remember(context) { { ids ->
        val chosen = LocalCorrections.accept(ids)
        LocalMusic.publish()
        if (intoFiles() && chosen.isNotEmpty()) {
            val request = LocalFileWriter.writeRequest(context, chosen.mapNotNull { LocalMusic.uriOf(it.localId) })
            if (request != null) { waiting = chosen; grant.launch(IntentSenderRequest.Builder(request).build()) } else write(chosen)
        }
    } }
}

private fun writeSummary(outcomes: List<LocalFileWriter.Outcome>): String {
    val written = outcomes.count { it == LocalFileWriter.Outcome.Written }
    val parts = mutableListOf(AppText.t("Fichiers modifiés : {0}").replace("{0}", written.toString()))
    if (LocalFileWriter.Outcome.Unsupported in outcomes) parts += AppText.t("certains formats ne peuvent pas être modifiés, leurs corrections restent dans l'app")
    if (LocalFileWriter.Outcome.Denied in outcomes) parts += AppText.t("Android a refusé l'écriture de certains fichiers")
    if (LocalFileWriter.Outcome.Failed in outcomes) parts += AppText.t("certains fichiers n'ont pas pu être écrits et sont restés intacts")
    return parts.joinToString(" · ")
}
