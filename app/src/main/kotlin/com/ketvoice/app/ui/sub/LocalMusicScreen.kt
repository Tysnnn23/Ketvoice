package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.LifecycleResumeEffect
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

@Composable
fun LocalMusicScreen() {
    val theme = LocalTheme.current
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(LocalMusic.hasPermission(context)) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        LocalMusic.scan(context)
    }
    val tracks by LocalMusic.tracks.collectAsState()
    val scanning by LocalMusic.scanning.collectAsState()
    val scanned by LocalMusic.scanned.collectAsState()
    val failed by LocalMusic.failed.collectAsState()
    val player by Player.state.collectAsState()

    LifecycleResumeEffect(Unit) {
        allowed = LocalMusic.hasPermission(context)
        LocalMusic.scan(context)
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(theme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.back() }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Musique locale", color = theme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (scanning) "Analyse de l'appareil…" else "${tracks.size} titres",
                    color = theme.secondary, fontSize = 13.sp
                )
            }
            Icon(Icons.Filled.Refresh, "Rafraîchir", tint = theme.onBackground,
                modifier = Modifier.size(30.dp).clickable {
                    allowed = LocalMusic.hasPermission(context)
                    LocalMusic.scan(context)
                }.padding(4.dp))
        }

        if (!allowed) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Autorisez l'accès aux fichiers audio pour afficher votre musique locale.", color = theme.secondary)
                androidx.compose.material3.TextButton(onClick = { permissionRequest.launch(LocalMusic.permission) }) {
                    Text("Autoriser l'accès", color = theme.accent)
                }
                androidx.compose.material3.TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}")))
                }) { Text("Réglages", color = theme.accent) }
            }
            return@Column
        }

        if (tracks.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.size(168.dp).clip(RoundedCornerShape(18.dp))) {
                    for (column in 0 until 2) Column(Modifier.weight(1f).fillMaxHeight()) {
                    for (row in 0 until 2) {
                        val i = row * 2 + column
                        val url = tracks.getOrNull(i)?.coverUrl
                        Box(Modifier.weight(1f).fillMaxWidth().background(
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
        }

        if (failed) {
            com.ketvoice.app.ui.common.EmptyState("Impossible de lire les fichiers audio.",
                "Vérifiez les autorisations puis réessayez.", "Réessayer") { LocalMusic.scan(context) }
        } else if (!scanning && tracks.isEmpty() && scanned) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Folder, null, tint = theme.secondary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Aucun fichier audio trouvé sur cet appareil.", color = theme.secondary, lineHeight = 20.sp, modifier = Modifier.padding(4.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                bottom = com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
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
