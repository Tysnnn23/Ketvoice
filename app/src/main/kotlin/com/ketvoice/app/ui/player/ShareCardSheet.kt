package com.ketvoice.app.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.util.ShareCard
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where share cards are drawn: off the main thread; host tests draw in place (Robolectric's native graphics abort on a worker thread). */
internal object ShareCardDraw { @Volatile var dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default }

/** Mounted once by the root: the share card of the title in [AppUi.shareCard]. */
@Composable
internal fun ShareCardHost() {
    val track = AppUi.shareCard.value ?: return
    androidx.activity.compose.BackHandler { AppUi.shareCard.value = null }
    ShareCardSheet(track) { AppUi.shareCard.value = null }
}

/** Lines of the song offered for the card: those of the title playing, once each, when its lyrics are loaded. */
internal fun shareCardLines(track: Track): List<String> {
    if (com.ketvoice.app.player.Player.state.value.current?.id != track.id) return emptyList()
    return com.ketvoice.app.data.LyricsRepo.state.value.lines.map { it.text.trim() }.filter { it.isNotEmpty() }.distinct().take(60)
}

/**
 * « Partager » a title (7.3): its share card, ready for Instagram Stories, with a line of the song to add if
 * the listener wants one; Instagram's story composer when Instagram is installed, the system's share sheet
 * with the picture, or the link alone.
 */
@Composable
internal fun ShareCardSheet(track: Track, onDismiss: () -> Unit) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember(track.id) { shareCardLines(track) }
    var chosen by remember(track.id) { mutableIntStateOf(-1) }
    val coverUrl = com.ketvoice.app.data.ArtworkRepo.coverForTrack(track) ?: track.coverUrl
    val accent = com.ketvoice.app.theme.rememberCoverAccent(coverUrl)?.first?.toArgb()
    val cover by produceState<android.graphics.Bitmap?>(null, track.id) { value = ShareCard.loadCover(context, track) }
    val card by produceState<android.graphics.Bitmap?>(null, cover, chosen, accent) {
        value = withContext(ShareCardDraw.dispatcher) {
            runCatching { ShareCard.draw(cover, track.title, track.artist, lines.getOrNull(chosen).orEmpty(), accent) }.getOrNull()
        }
    }
    val instagram = remember { ShareCard.instagramInstalled(context) }
    fun send(action: (java.io.File) -> Unit) {
        val picture = card ?: return
        scope.launch {
            runCatching { action(ShareCard.save(context, picture)) }
            onDismiss()
        }
    }
    val shape = RoundedCornerShape(Ket27.Metrics.alertRadius)
    com.ketvoice.app.ui.common.SpringSheet(onDismiss, insets = Modifier.statusBarsPadding().navigationBarsPadding(), startFull = true) { room ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(8.dp).heightIn(max = room - 16.dp).clip(shape)
                .background(theme.card).pointerInput(Unit) { detectTapGestures { } }.padding(bottom = 16.dp).testTag("share-card-sheet"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 8.dp).size(36.dp, 5.dp).clip(CircleShape).background(theme.secondary.copy(alpha = .4f)))
                Text("Partager", color = theme.onBackground, style = Ket27.Type.title3, modifier = Modifier.padding(top = 12.dp))
                Text("${track.title} · ${track.artist}", localize = false, color = theme.secondary, style = Ket27.Type.footnote,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                // The card as it will be posted, 9:16.
                Box(Modifier.padding(top = 14.dp).height(((room.value - 330f).coerceIn(220f, 420f)).dp).aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(16.dp)).background(theme.onBackground.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
                    val picture = card
                    if (picture != null) Image(picture.asImageBitmap(), com.ketvoice.app.data.AppText.t("Carte de partage"),
                        Modifier.matchParentSize().testTag("share-card-preview"))
                    else com.ketvoice.app.ui.common.ActivitySpinner(size = 20.dp)
                }
                if (lines.isNotEmpty()) {
                    Text("Paroles", color = theme.secondary, style = Ket27.Type.footnote,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { LineChip(com.ketvoice.app.data.AppText.t("Sans paroles"), chosen < 0) { chosen = -1 } }
                        itemsIndexed(lines) { index, line -> LineChip(line, chosen == index) { chosen = index } }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (instagram) com.ketvoice.app.ui.account.AccountButton("Story Instagram", Modifier.fillMaxWidth().testTag("share-card-story"),
                        primary = true, enabled = card != null) {
                        send { file -> if (!ShareCard.shareToStory(context, file, track)) ShareCard.share(context, file, track) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.ketvoice.app.ui.account.AccountButton("Partager l'image", Modifier.weight(1f).testTag("share-card-image"),
                            primary = !instagram, enabled = card != null) { send { file -> ShareCard.share(context, file, track) } }
                        com.ketvoice.app.ui.account.AccountButton("Partager le lien", Modifier.weight(1f).testTag("share-card-link")) {
                            onDismiss(); com.ketvoice.app.util.ShareLinks.shareTrack(context, track)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Box(Modifier.heightIn(min = 36.dp).widthIn(max = 240.dp).clip(CircleShape)
        .background(if (selected) theme.accent else theme.onBackground.copy(alpha = .08f))
        .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, localize = false, color = if (selected) com.ketvoice.app.ui.common.onAccent(theme.accent) else theme.onBackground,
            style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
