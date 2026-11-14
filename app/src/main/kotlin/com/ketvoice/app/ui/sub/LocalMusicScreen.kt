package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LocalMusic
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.CollectionFilter
import com.ketvoice.app.ui.common.CollectionPlaybackActions
import com.ketvoice.app.ui.common.CompactSearchField
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.GlassBackButton
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.SwipeTrackActions
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.common.gradientFor
import com.ketvoice.app.ui.common.trackRowClick
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.player.NowPlayingBars
import com.ketvoice.app.ui.player.formatSecs

/** Musique locale : fichiers audio de l'appareil ou du dossier choisi (SAF). */
@Composable
fun LocalMusicScreen() {
    val theme = LocalTheme.current
    val tracks by LocalMusic.tracks.collectAsState()
    val scanning by LocalMusic.scanning.collectAsState()
    val scanned by LocalMusic.scanned.collectAsState()
    val failed by LocalMusic.failed.collectAsState()
    val source by LocalMusic.source.collectAsState()
    val needsPermission by LocalMusic.needsPermission.collectAsState()
    val player by Player.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(LocalSort.Title.name) }
    var view by rememberSaveable { mutableStateOf(0) }
    var groupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val sort = LocalSort.entries.firstOrNull { it.name == sortName } ?: LocalSort.Title

    // Dossier musique locale (SAF) : permission persistante, scan de l'arbre.
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        LocalMusic.acceptFolder(context, uri)
        LocalMusic.scan(context)
    }
    // The whole-device scan reads the system library: Android asks the listener once.
    val permissionRequest = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        refused = !granted
        LocalMusic.scan(context)
    }

    LaunchedEffect(Unit) { LocalMusic.scan(context) }

    val matching = remember(tracks, query) { filterLocal(tracks, query) }
    val albums = remember(matching) { localAlbums(matching) }
    val artists = remember(matching) { localArtists(matching) }
    val group = when (view) {
        1 -> albums.firstOrNull { it.key == groupKey }
        2 -> artists.firstOrNull { it.key == groupKey }
        else -> null
    }
    val shown = remember(matching, sort, group) { sortLocal(group?.tracks ?: matching, sort) }
    val showTitles = view == 0 || group != null

    Column(Modifier.fillMaxSize().background(theme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Musique locale", color = theme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.asHeading())
                Text(
                    when {
                        needsPermission -> "Accès à la musique requis"
                        scanning -> "Analyse en cours…"
                        failed -> "Analyse impossible : dossier inaccessible"
                        !scanned -> "Prêt à analyser"
                        tracks.isEmpty() -> "Aucun fichier audio trouvé"
                        // The whole-device source is a word of the interface; a folder keeps its name.
                        else -> "${tracks.size} titre(s) · ${if (source.isBlank() || source == "Appareil") AppText.t("Appareil") else source}"
                    },
                    color = theme.secondary, fontSize = 13.sp
                )
            }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { LocalMusic.scan(context) },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Refresh, "Rafraîchir", tint = theme.onBackground, modifier = Modifier.size(24.dp))
            }
        }

        LazyColumn(Modifier.fillMaxSize().testTag("local-list"),
            contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
            item(key = "source") {
                // Sélecteur de dossier : MediaStore (appareil entier) ou dossier précis.
                val folderSetting = com.ketvoice.app.data.AppSettings.localMusicFolder.value
                val folderName = remember(folderSetting) { LocalMusic.folderName() }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(theme.card)
                        .clickable { folderPicker.launch(null) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Folder, null, tint = theme.accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (folderName == null) AppText.t("Choisir un dossier de musique")
                        else AppText.t("Dossier : {0}").replace("{0}", folderName),
                            localize = false, color = theme.onBackground, fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (folderName == null) "Tous les fichiers audio de l'appareil sont analysés"
                        else "Touchez pour changer de dossier (MP3, FLAC, WAV, AAC, M4A, OGG, OPUS…)",
                            color = theme.secondary, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (folderName != null) {
                        Box(Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = Role.Button) { LocalMusic.clearFolder(); LocalMusic.scan(context) },
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, "Revenir à tout l'appareil", tint = theme.secondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (needsPermission) item(key = "permission") {
                LocalNotice(
                    title = "Autorisez l'accès à votre musique",
                    text = if (refused) "L'accès a été refusé. Vous pouvez l'accorder dans les réglages de l'application, ou choisir un dossier au-dessus."
                    else "Ketvoice lit seulement les fichiers audio de l'appareil pour les afficher et les lire ici. Rien n'est envoyé.",
                    action = if (refused) "Ouvrir les réglages" else "Autoriser l'accès",
                    tag = "local-permission") {
                    if (refused) runCatching {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else permissionRequest.launch(LocalMusic.permission)
                }
            } else if (!scanning && (failed || (tracks.isEmpty() && scanned))) item(key = "empty") {
                LocalNotice(
                    title = if (failed) "Dossier inaccessible ou illisible." else "Aucun fichier audio trouvé dans cette source.",
                    text = "Choisissez un dossier au-dessus pour élargir la recherche.",
                    action = "Choisir un dossier de musique", tag = "local-empty") { folderPicker.launch(null) }
            } else if (tracks.isNotEmpty()) {
                item(key = "tools") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (shown.isNotEmpty()) CollectionPlaybackActions(shown, Modifier.fillMaxWidth())
                        CompactSearchField(query, { query = it; groupKey = null }, { keyboard?.hide() }, Modifier.testTag("local-search"))
                        DownloadViewSwitch(view, tag = "local-view") { view = it; groupKey = null }
                        if (showTitles && shown.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("local-sort")) {
                            item { Text("Trier", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp)) }
                            items(LocalSort.entries) { option ->
                                CollectionFilter(option.label, sort == option, { sortName = option.name })
                            }
                        }
                        if (group != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { groupKey = null }, contentPadding = PaddingValues(end = 10.dp)) {
                                Icon(KetIcons.ArrowLeft, null, tint = theme.accent, modifier = Modifier.size(18.dp))
                                Text(if (view == 1) "Albums" else "Artistes", color = theme.accent, modifier = Modifier.padding(start = 6.dp))
                            }
                            Text(group.title, localize = false, color = theme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!showTitles) {
                    val groups = if (view == 1) albums else artists
                    items(groups, key = { "group:$view:${it.key}" }) { entry ->
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            DownloadGroupRow(
                                title = entry.title, subtitle = entry.subtitle,
                                meta = listOf(AppText.t(if (entry.tracks.size == 1) "1 titre" else "${entry.tracks.size} titres"),
                                    playtimeLabel(entry.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
                                cover = entry.tracks.firstNotNullOfOrNull { it.coverUrl }, round = view == 2, checked = null) {
                                groupKey = entry.key
                            }
                        }
                    }
                    if (groups.isEmpty()) item(key = "no-group") {
                        Text("Aucun résultat", color = theme.secondary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(32.dp))
                    }
                } else {
                    itemsIndexed(shown, key = { _, t -> t.id }) { index, track ->
                        val active = player.current?.id == track.id
                        SwipeTrackActions(track, if (Motion.reduced) Modifier else Modifier.animateItem()) {
                            LocalTrackRow(track, active, player.isPlaying) { Player.playQueue(shown, index) }
                        }
                    }
                    if (shown.isEmpty()) item(key = "no-match") {
                        Text("Aucun résultat", color = theme.secondary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalNotice(title: String, text: String, action: String, tag: String, onAction: () -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Folder, null, tint = theme.secondary, modifier = Modifier.size(44.dp))
        Text(title, color = theme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(text, color = theme.secondary, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp)).background(theme.accent)
            .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(action, color = if (theme.isDark) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LocalTrackRow(track: Track, active: Boolean, playing: Boolean, onPlay: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().trackRowClick(track, onClick = onPlay).padding(start = 18.dp, end = 6.dp, top = 7.dp, bottom = 7.dp)
        .testTag("local-track"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(gradientFor(track.id)))) {
            if (track.coverUrl != null) {
                AsyncImage(model = track.coverUrl, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp))
            } else {
                Text(track.title.take(1).uppercase(), localize = false,
                    color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp,
                    fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, localize = false,
                color = if (active) theme.accent else theme.onBackground,
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, localize = false, color = theme.secondary,
                fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (active) NowPlayingBars(playing, Modifier.padding(horizontal = 8.dp))
        else if (track.durationMs > 0) Text(formatSecs(track.durationMs), localize = false,
            color = theme.secondary,
            fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 12.sp)
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { AppUi.trackMenuTrack.value = track },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MoreVert, "Options", tint = theme.secondary, modifier = Modifier.size(22.dp))
        }
    }
}
