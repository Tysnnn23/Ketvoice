package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.LocalMusic
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.GlassBackButton
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.gradientFor
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
    val player by Player.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Dossier musique locale (SAF) : permission persistante, scan de l'arbre.
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        LocalMusic.acceptFolder(context, uri)
        LocalMusic.scan(context)
    }

    LaunchedEffect(Unit) { LocalMusic.scan(context) }

    Column(Modifier.fillMaxSize().background(theme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Musique locale", color = theme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        scanning -> "Analyse en cours…"
                        failed -> "Analyse impossible : dossier inaccessible"
                        !scanned -> "Prêt à analyser"
                        tracks.isEmpty() -> "Aucun fichier audio trouvé"
                        else -> "${tracks.size} titre(s) · ${source.ifBlank { "Appareil" }}"
                    },
                    localize = false, color = theme.secondary, fontSize = 13.sp
                )
            }
            Icon(Icons.Filled.Refresh, "Rafraîchir", tint = theme.onBackground,
                modifier = Modifier.size(30.dp).clickable {
                    LocalMusic.scan(context)
                }.padding(4.dp))
        }

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
                Text(if (folderName == null) "Choisir un dossier de musique"
                else "Dossier : $folderName",
                    localize = false, color = theme.onBackground, fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (folderName == null) "Tous les fichiers audio de l'appareil sont analysés"
                else "Touchez pour changer de dossier (MP3, FLAC, WAV, AAC, M4A, OGG, OPUS…)",
                    localize = false, color = theme.secondary, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (folderName != null) {
                Icon(Icons.Filled.Close, "Revenir à tout l'appareil", tint = theme.secondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { LocalMusic.clearFolder(); LocalMusic.scan(context) }
                        .padding(6.dp)
                        .size(18.dp))
            }
        }

        // Mosaïque 2x2 des 4 premières pochettes, comme les autres collections
        if (tracks.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.size(168.dp).clip(RoundedCornerShape(18.dp))) {
                    for (i in 0 until 4) {
                        val url = tracks.getOrNull(i)?.coverUrl
                        Box(Modifier.weight(1f).fillMaxHeight().background(
                            Brush.linearGradient(gradientFor("local$i"))
                        )) {
                            if (url != null) {
                                AsyncImage(model = url, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Text(tracks.getOrNull(i)?.title?.take(1)?.uppercase() ?: "♪",
                                    localize = false, color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 26.sp, fontWeight = FontWeight.Black,
                                    modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
        }

        if (!scanning && (failed || (tracks.isEmpty() && scanned))) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Folder, null, tint = theme.secondary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (failed) "Dossier inaccessible ou illisible."
                        else "Aucun fichier audio trouvé dans cette source.",
                        color = theme.secondary, lineHeight = 20.sp,
                        modifier = Modifier.padding(4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Text(
                        "Choisissez un dossier au-dessus pour élargir la recherche.",
                        color = theme.accent, fontSize = 13.sp,
                        modifier = Modifier.clickable { folderPicker.launch(null) }.padding(8.dp)
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                    val active = player.current?.id == track.id
                    Row(Modifier.fillMaxWidth().clickable { Player.playQueue(tracks, index) }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(gradientFor(track.id)))) {
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
                        if (track.durationMs > 0) Text(formatSecs(track.durationMs), localize = false,
                            color = theme.secondary,
                            fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 12.sp)
                        Icon(Icons.Filled.MoreVert, "Options", tint = theme.secondary,
                            modifier = Modifier.size(36.dp).clickable { AppUi.trackMenuTrack.value = track }.padding(6.dp))
                    }
                }
            }
        }
    }
}


