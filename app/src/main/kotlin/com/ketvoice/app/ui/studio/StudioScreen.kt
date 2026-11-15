package com.ketvoice.app.ui.studio

import com.ketvoice.app.theme.pageBackground
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.GlassBackButton
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.GlassSegmentedControl
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.player.ArtworkCanvas
import com.ketvoice.app.ui.player.formatSecs
import kotlinx.coroutines.launch
import java.io.File

/** Where the Studio is: its home, the three-step publishing, one title, one album, or lyrics for any title. */
internal sealed interface StudioRoute {
    data object Home : StudioRoute
    data object New : StudioRoute
    data class Song(val track: Track) : StudioRoute
    data class Album(val artist: String, val album: String) : StudioRoute
    data class Lyrics(val track: Track) : StudioRoute
    data class Visuals(val targets: List<VisualTarget>) : StudioRoute
}

/** The first page of the Studio for what opened it. */
internal fun studioStart(target: Track?, edit: Boolean, visuals: List<VisualTarget> = emptyList()): StudioRoute = when {
    visuals.isNotEmpty() -> StudioRoute.Visuals(visuals)
    target == null -> StudioRoute.Home
    edit && ImportedSong.id(target) != null -> StudioRoute.Song(target)
    else -> StudioRoute.Lyrics(target)
}

/** Published titles grouped by album, largest first; titles without an album are left out. */
internal data class StudioAlbum(val artist: String, val album: String, val songs: List<PublicationApi.EditableSong>) {
    val cover: String? get() = songs.firstNotNullOfOrNull { it.track.coverUrl }
}

internal fun studioAlbums(songs: List<PublicationApi.EditableSong>): List<StudioAlbum> = songs
    .filter { it.track.album.isNotBlank() }
    .groupBy { it.track.artist to it.track.album }
    .map { (key, group) -> StudioAlbum(key.first, key.second, group) }
    .sortedWith(compareByDescending<StudioAlbum> { it.songs.size }.thenBy { it.album.lowercase() })

/** Shared state of one Studio visit: its files, its busy flag and its messages. */
internal class StudioState(val files: StudioFiles, private val scope: kotlinx.coroutines.CoroutineScope) {
    var busy by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var error by mutableStateOf("")
    var info by mutableStateOf("")
    var songs by mutableStateOf<List<PublicationApi.EditableSong>?>(null)
    private val poster = android.os.Handler(android.os.Looper.getMainLooper())

    fun onProgress(value: Int) { poster.post { progress = value } }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""; info = ""; progress = 0
        scope.launch {
            try { block() }
            catch (failure: Exception) { error = failure.message?.take(250) ?: "Publication impossible" }
            finally { busy = false; progress = 0 }
        }
    }

    fun reload() = run { songs = PublicationApi.songs() }
}

@Composable
fun PublisherScreen(target: Track?, onClose: () -> Unit) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allowed by PublisherSession.allowed.collectAsState()
    val restoring by PublisherSession.restoring.collectAsState()
    val state = remember { StudioState(StudioFiles(context), scope) }
    DisposableEffect(state) { onDispose { state.files.clear() } }
    val start = remember { studioStart(target, AppUi.editImportedSong.value, AppUi.publisherVisuals.value) }
    val stack = remember { mutableStateListOf(start) }
    val route = stack.last()
    fun back() { if (state.busy) return; if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose() }
    fun open(next: StudioRoute) { state.error = ""; state.info = ""; stack.add(next) }
    BackHandler { back() }
    LaunchedEffect(allowed) { if (allowed && state.songs == null) state.reload() }
    LaunchedEffect(AppUi.publisherLink.value, state.busy) {
        if (state.busy) return@LaunchedEffect
        val link = AppUi.publisherLink.value ?: return@LaunchedEffect
        AppUi.publisherLink.value = null
        state.run { PublisherSession.verify(link) }
    }

    Box(Modifier.fillMaxSize().pageBackground(theme).testTag("studio"), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassBackButton { back() }
                Spacer(Modifier.weight(1f))
                if (allowed && route == StudioRoute.Home) Text("Se déconnecter", color = theme.secondary, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !state.busy, role = Role.Button) {
                        PublisherSession.signOut()
                    }.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 10.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !allowed && restoring -> StudioWaiting()
                    !allowed -> StudioSignIn(state)
                    else -> AnimatedContent(route, transitionSpec = {
                        fadeIn(tween(Motion.duration(220))).togetherWith(fadeOut(tween(Motion.duration(160))))
                    }, label = "studio-route") { shown ->
                        when (shown) {
                            StudioRoute.Home -> StudioHome(state, onNew = { open(StudioRoute.New) },
                                onSong = { open(StudioRoute.Song(it)) }, onAlbum = { open(StudioRoute.Album(it.artist, it.album)) },
                                onVisual = { open(StudioRoute.Visuals(listOf(it))) })
                            StudioRoute.New -> StudioNewTitle(state, onDone = { published ->
                                stack.clear(); stack.add(StudioRoute.Home); state.reload()
                                if (published != null) stack.add(StudioRoute.Song(published))
                            })
                            is StudioRoute.Song -> StudioSong(state, shown.track) { state.reload() }
                            is StudioRoute.Album -> StudioAlbumPage(state, shown.artist, shown.album, onSong = { open(StudioRoute.Song(it)) })
                            is StudioRoute.Lyrics -> StudioLyrics(state, shown.track)
                            is StudioRoute.Visuals -> StudioVisuals(state, shown.targets)
                        }
                    }
                }
            }
            StudioStatus(state)
        }
    }
}

@Composable
private fun StudioWaiting() {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), color = theme.accent, strokeWidth = 2.dp)
        Text("Reconnexion au Studio…", color = theme.secondary, modifier = Modifier.padding(top = 14.dp))
    }
}

/** Progress, errors and confirmations, always at the same place at the bottom. */
@Composable
private fun StudioStatus(state: StudioState) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.busy) {
            if (state.progress > 0) LinearProgressIndicator({ state.progress / 100f }, Modifier.fillMaxWidth().height(4.dp)
                .clip(RoundedCornerShape(2.dp)), color = theme.accent, trackColor = theme.card)
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = theme.accent,
                trackColor = theme.card)
            Text(if (state.progress > 0) "Envoi · ${state.progress} %" else "Chargement…", color = theme.secondary, fontSize = 12.sp)
        }
        if (state.info.isNotBlank()) Text(state.info, color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("studio-info"))
        if (state.error.isNotBlank()) Text(state.error, color = Color(0xFFFF453A), fontSize = 13.sp, modifier = Modifier.testTag("studio-error"))
    }
}

@Composable
internal fun studioFieldColors(): TextFieldColors {
    val theme = LocalTheme.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = theme.accent, unfocusedBorderColor = theme.secondary.copy(alpha = .45f),
        focusedLabelColor = theme.accent, unfocusedLabelColor = theme.secondary, cursorColor = theme.accent,
        focusedTextColor = theme.onBackground, unfocusedTextColor = theme.onBackground,
        focusedContainerColor = theme.card.copy(alpha = .55f), unfocusedContainerColor = theme.card.copy(alpha = .35f))
}

@Composable
internal fun StudioTitle(title: String, subtitle: String? = null) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(title, color = theme.onBackground, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp,
            modifier = Modifier.asHeading())
        if (subtitle != null) Text(subtitle, color = theme.secondary, fontSize = 14.sp)
    }
}

// ------------------------------------------------------------------ sign in

@Composable
private fun StudioSignIn(state: StudioState) {
    val theme = LocalTheme.current
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StudioTitle("Studio", "Publiez vos titres, leurs pochettes, Canvas et paroles.")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connexion", color = theme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(PublisherAccess.EMAIL, localize = false, color = theme.accent)
            Text("Un lien à usage unique est envoyé à cette adresse. Ouvrez-le sur cet appareil : le Studio se connecte et s'en souvient.",
                color = theme.secondary, fontSize = 13.sp)
            GlassCapsuleButton("Envoyer le lien de connexion", null, {
                state.run { PublisherSession.sendLink(); state.info = "Lien envoyé. Ouvrez-le depuis cet appareil." }
            }, Modifier.fillMaxWidth(), prominent = true, enabled = !state.busy, tag = "studio-send-link", minHeight = 52.dp)
            OutlinedTextField(code, { code = it }, enabled = !state.busy, label = { Text("Lien reçu par email (ou code)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = studioFieldColors(),
                visualTransformation = if (code.trim().all { it in '0'..'9' }) VisualTransformation.None else PasswordVisualTransformation())
            GlassCapsuleButton("Vérifier mon accès", null, { state.run { PublisherSession.verify(code); code = "" } },
                Modifier.fillMaxWidth(), enabled = !state.busy && code.isNotBlank(), minHeight = 48.dp)
        }
    }
}

// ------------------------------------------------------------------ home

@Composable
private fun StudioHome(state: StudioState, onNew: () -> Unit, onSong: (Track) -> Unit, onAlbum: (StudioAlbum) -> Unit,
                       onVisual: (VisualTarget) -> Unit = {}) {
    val theme = LocalTheme.current
    var view by rememberSaveableInt()
    val songs = state.songs
    val albums = remember(songs) { studioAlbums(songs.orEmpty()) }
    LazyColumn(Modifier.fillMaxSize().testTag("studio-home"), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "title") {
            StudioTitle("Studio", songs?.let { if (it.size == 1) "1 titre publié" else "${it.size} titres publiés" })
        }
        item(key = "new") {
            GlassCapsuleButton("Nouveau titre", KetIcons.Plus, onNew, Modifier.fillMaxWidth(), prominent = true,
                tag = "studio-new", minHeight = 58.dp)
        }
        item(key = "view") {
            GlassSegmentedControl(listOf("Titres", "Albums", "Visuels"), view, { view = it }, Modifier.padding(vertical = 8.dp), tag = "studio-view")
        }
        when {
            view == 2 -> item(key = "visuals") { StudioVisualList(onVisual) }
            songs == null -> item(key = "loading") {
                GlassCapsuleButton("Actualiser", KetIcons.Refresh, { state.reload() }, Modifier.fillMaxWidth(), enabled = !state.busy)
            }
            songs.isEmpty() -> item(key = "empty") {
                Text("Aucune publication pour l'instant. Touchez Nouveau titre pour commencer.", color = theme.secondary,
                    modifier = Modifier.padding(vertical = 24.dp))
            }
            view == 0 -> items(songs, key = { it.track.id }) { song -> StudioSongRow(song.track) { onSong(song.track) } }
            else -> {
                if (albums.isEmpty()) item(key = "no-album") {
                    Text("Aucun album : donnez un nom d'album à vos titres pour les regrouper.", color = theme.secondary,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
                items(albums, key = { "${it.artist}\u0000${it.album}" }) { album ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(theme.card)
                        .clickable(role = Role.Button) { onAlbum(album) }.padding(12.dp).testTag("studio-album"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Cover(album.album, album.cover, 64.dp, 12.dp)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(album.album, localize = false, color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(album.artist, localize = false, color = theme.secondary, fontSize = 13.sp, maxLines = 1)
                            Text(if (album.songs.size == 1) "1 titre" else "${album.songs.size} titres", color = theme.secondary, fontSize = 12.sp)
                        }
                        Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun rememberSaveableInt(): MutableState<Int> = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }

@Composable
private fun StudioSongRow(track: Track, checked: Boolean? = null, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .clickable(role = if (checked != null) Role.Checkbox else Role.Button, onClick = onClick)
        .padding(vertical = 8.dp, horizontal = 4.dp).testTag("studio-song"), verticalAlignment = Alignment.CenterVertically) {
        if (checked != null) Checkbox(checked, null, colors = CheckboxDefaults.colors(checkedColor = theme.accent, uncheckedColor = theme.secondary))
        Cover(track.title, track.coverUrl, 52.dp, 10.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, localize = false, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), localize = false,
                color = theme.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.canvasUrl != null) StudioChip("Canvas")
        if (checked == null) Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
}

@Composable
internal fun StudioChip(label: String) {
    val theme = LocalTheme.current
    Text(label, color = theme.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(theme.accent.copy(alpha = .12f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

// ------------------------------------------------------------------ shared pieces

/** The player as listeners will see it: the Canvas when there is one, else the cover. */
@Composable
internal fun StudioPreview(title: String, artist: String, cover: Any?, canvas: String?, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val coverModel = when (cover) { is File -> cover.absolutePath; is String -> cover; else -> null }
    Column(modifier.width(196.dp).clip(RoundedCornerShape(26.dp)).background(Color.Black)
        .border(1.dp, theme.onBackground.copy(alpha = .10f), RoundedCornerShape(26.dp)).testTag("studio-preview")) {
        Box(Modifier.fillMaxWidth().height(250.dp)) {
            ArtworkCanvas(Track("studio:preview", title.ifBlank { "—" }, artist, "", coverUrl = coverModel), canvas, playing = true,
                modifier = Modifier.fillMaxSize(), forceCanvas = true)
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title.ifBlank { "Titre" }, localize = title.isBlank(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist.ifBlank { "Artiste" }, localize = artist.isBlank(), color = Color.White.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1)
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) {
                Box(Modifier.fillMaxWidth(.32f).fillMaxHeight().background(Color.White))
            }
        }
    }
}

/** One part of a title (audio, cover, Canvas, lyrics) with its state and its action. */
@Composable
internal fun StudioPart(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, action: String,
                       enabled: Boolean, tag: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(theme.card).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = theme.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(value, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        Text(action, color = if (enabled) theme.accent else theme.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 12.dp).testTag(tag))
    }
}

@Composable
internal fun RightsRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }.padding(vertical = 4.dp).testTag("studio-rights"),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = theme.accent, uncheckedColor = theme.secondary))
        Text("Je possède les droits nécessaires et j'autorise la diffusion publique de ces fichiers.", color = theme.onBackground,
            fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

private fun durationLabel(ms: Long): String = if (ms > 0) formatSecs(ms) else "—"

@Composable
private fun audioPicker(state: StudioState, onFile: (File, StudioFiles.AudioTags) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.run {
            val file = state.files.copy(uri, 25L * 1024 * 1024, setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus"))
            onFile(file, state.files.readAudio(file))
        }
    }

@Composable
internal fun coverPicker(state: StudioState, onFile: (File) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.run { onFile(state.files.copy(uri, 4L * 1024 * 1024, setOf("png", "jpg", "jpeg", "webp"))) }
    }

private val AUDIO_TYPES = arrayOf("audio/*", "application/octet-stream")
internal val IMAGE_TYPES = arrayOf("image/jpeg", "image/png", "image/webp")

// ------------------------------------------------------------------ new title, three steps

@Composable
private fun StudioNewTitle(state: StudioState, onDone: (Track?) -> Unit) {
    val theme = LocalTheme.current
    var step by remember { mutableIntStateOf(0) }
    var audio by remember { mutableStateOf<File?>(null) }
    var cover by remember { mutableStateOf<File?>(null) }
    var canvas by remember { mutableStateOf<File?>(null) }
    var lyrics by remember { mutableStateOf<File?>(null) }
    var lyricLines by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var duration by remember { mutableLongStateOf(0L) }
    var rights by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf<Track?>(null) }
    val pickAudio = audioPicker(state) { file, tags ->
        audio?.delete(); audio = file; duration = tags.durationMs
        // The tags fill the form; anything already typed is kept.
        if (title.isBlank()) title = tags.title
        if (artist.isBlank()) artist = tags.artist
        if (album.isBlank()) album = tags.album
        if (cover == null && tags.cover != null) { cover = tags.cover; state.info = "Pochette trouvée dans le fichier" }
    }
    val pickCover = coverPicker(state) { cover?.delete(); cover = it }
    val pickLyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.run {
            val file = state.files.copy(uri, 1024L * 1024, setOf("ttml", "lrc"))
            val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ImportedLyrics.read(file).also { file.writeText(it.content, Charsets.UTF_8) }
            }
            lyrics?.delete(); lyrics = file; lyricLines = parsed.lines.size
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("studio-new-title"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioTitle("Nouveau titre")
        StudioSteps(step)
        if (published != null) {
            val song = published!!
            Text("Publié", color = theme.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${song.title} est maintenant disponible pour tous.", localize = false, color = theme.onBackground)
            GlassCapsuleButton("Écouter maintenant", KetIcons.Play, { Player.playTrack(song) }, Modifier.fillMaxWidth(), prominent = true)
            GlassCapsuleButton("Ouvrir sa fiche", null, { onDone(song) }, Modifier.fillMaxWidth())
            GlassCapsuleButton("Retour au Studio", null, { onDone(null) }, Modifier.fillMaxWidth())
            return@Column
        }
        when (step) {
            0 -> {
                StudioPart(KetIcons.Music, "Audio", audio?.let { "${state.files.nameOf(it) ?: it.name} · ${durationLabel(duration)}" }
                    ?: "MP3, M4A, AAC, FLAC, WAV, OGG ou Opus · 25 Mo maximum",
                    if (audio == null) "Choisir" else "Changer", !state.busy, "studio-pick-audio", { pickAudio.launch(AUDIO_TYPES) })
                if (audio != null) Text("Titre, artiste et album sont lus dans le fichier : vérifiez-les.", color = theme.secondary, fontSize = 12.sp)
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("Titre") }, enabled = !state.busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("studio-title"), colors = studioFieldColors())
                OutlinedTextField(artist, { artist = it.take(200) }, label = { Text("Artiste") }, enabled = !state.busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = studioFieldColors())
                OutlinedTextField(album, { album = it.take(200) }, label = { Text("Album") }, enabled = !state.busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = studioFieldColors())
                GlassCapsuleButton("Continuer", null, { step = 1 }, Modifier.fillMaxWidth(), prominent = true,
                    enabled = !state.busy && audio != null && title.isNotBlank() && artist.isNotBlank(), tag = "studio-next")
            }
            1 -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    StudioPreview(title, artist, cover, canvas?.absolutePath)
                }
                StudioPart(KetIcons.Album, "Pochette", cover?.let { state.files.nameOf(it) ?: "Image choisie" }
                    ?: "Facultative · JPEG, PNG, WebP · 4 Mo maximum", if (cover == null) "Choisir" else "Changer", !state.busy,
                    "studio-pick-cover", { pickCover.launch(IMAGE_TYPES) })
                CanvasEditor(state, canvas, onChosen = { canvas?.delete(); canvas = it })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassCapsuleButton("Retour", null, { step = 0 }, Modifier.weight(1f), enabled = !state.busy)
                    GlassCapsuleButton("Continuer", null, { step = 2 }, Modifier.weight(1f), prominent = true, enabled = !state.busy,
                        tag = "studio-next")
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(theme.card).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Cover(title, cover?.absolutePath, 72.dp, 12.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(title, localize = false, color = theme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · "), localize = false,
                            color = theme.secondary, fontSize = 13.sp)
                        Text(listOfNotNull(durationLabel(duration), if (canvas != null) "Canvas" else null,
                            if (lyrics != null) "$lyricLines lignes de paroles" else null).joinToString(" · "),
                            localize = false, color = theme.secondary, fontSize = 12.sp)
                    }
                }
                StudioPart(KetIcons.Lyrics, "Paroles", if (lyrics != null) "$lyricLines lignes" else "Facultatives · TTML ou LRC · 1 Mo maximum",
                    if (lyrics == null) "Ajouter" else "Changer", !state.busy, "studio-pick-lyrics",
                    { pickLyrics.launch(arrayOf("text/*", "application/ttml+xml", "application/xml", "application/octet-stream")) })
                RightsRow(rights, !state.busy) { rights = it }
                GlassCapsuleButton("Publier pour tous", null, {
                    state.run {
                        published = PublicationApi.publishSong(Track("pending", title.trim(), artist.trim(), album.trim(), duration),
                            audio!!, cover, lyrics, state::onProgress, canvas)
                    }
                }, Modifier.fillMaxWidth(), prominent = true, enabled = !state.busy && rights && audio != null, tag = "studio-publish",
                    minHeight = 58.dp)
                GlassCapsuleButton("Retour", null, { step = 1 }, Modifier.fillMaxWidth(), enabled = !state.busy)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StudioSteps(step: Int) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().testTag("studio-steps"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Audio", "Visuels", "Publier").forEachIndexed { index, label ->
            val done = index < step
            val active = index == step
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (done || active) theme.accent else theme.card))
                Text("${index + 1}. " + com.ketvoice.app.data.AppText.t(label), localize = false,
                    color = if (active) theme.onBackground else theme.secondary, fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ one title

@Composable
private fun StudioSong(state: StudioState, initial: Track, onChanged: () -> Unit) {
    val theme = LocalTheme.current
    var editable by remember { mutableStateOf<PublicationApi.EditableSong?>(null) }
    var audio by remember { mutableStateOf<File?>(null) }
    var duration by remember { mutableLongStateOf(0L) }
    var cover by remember { mutableStateOf<File?>(null) }
    var canvas by remember { mutableStateOf<File?>(null) }
    var title by remember { mutableStateOf(initial.title) }
    var artist by remember { mutableStateOf(initial.artist) }
    var album by remember { mutableStateOf(initial.album) }
    var rights by remember { mutableStateOf(false) }
    var lyricsFor by remember { mutableStateOf(false) }
    fun load() = state.run {
        val song = PublicationApi.editableSong(initial)
        editable = song; duration = song.track.durationMs
        title = song.track.title; artist = song.track.artist; album = song.track.album
    }
    LaunchedEffect(initial.id) { load() }
    val current = editable?.track ?: initial
    val pickAudio = audioPicker(state) { file, tags -> audio?.delete(); audio = file; duration = tags.durationMs }
    val pickCover = coverPicker(state) { cover?.delete(); cover = it }
    val info = PublicationApi.SongInfo(title.trim().takeIf { it != current.title }, artist.trim().takeIf { it != current.artist },
        album.trim().takeIf { it != current.album })
    val changed = audio != null || cover != null || canvas != null || info != PublicationApi.SongInfo()
    if (lyricsFor) { StudioLyrics(state, current, onDone = { lyricsFor = false }); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("studio-song-page"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            StudioPreview(title, artist, cover ?: current.coverUrl, canvas?.absolutePath ?: current.canvasUrl)
        }
        StudioPart(KetIcons.Music, "Audio", audio?.let { "${state.files.nameOf(it) ?: it.name} · ${durationLabel(duration)} · à envoyer" }
            ?: durationLabel(current.durationMs), "Remplacer", !state.busy, "studio-replace-audio", { pickAudio.launch(AUDIO_TYPES) })
        StudioPart(KetIcons.Album, "Pochette", if (cover != null) "Nouvelle image · à envoyer" else if (current.coverUrl != null) "En ligne" else "Aucune",
            "Changer", !state.busy, "studio-replace-cover", { pickCover.launch(IMAGE_TYPES) })
        CanvasEditor(state, canvas, onChosen = { canvas?.delete(); canvas = it }, published = current.canvasUrl != null,
            onRemove = {
                state.run {
                    PublicationApi.applyMedia(listOf(current.id), null, null, removeCanvas = true)
                    state.info = "Canvas retiré"; load(); onChanged()
                }
            }, removeEnabled = rights)
        StudioPart(KetIcons.Lyrics, "Paroles", "TTML ou LRC", "Importer", !state.busy, "studio-lyrics", { lyricsFor = true })
        Text("Infos", color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        OutlinedTextField(title, { title = it.take(200) }, label = { Text("Titre") }, enabled = !state.busy, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = studioFieldColors())
        OutlinedTextField(artist, { artist = it.take(200) }, label = { Text("Artiste") }, enabled = !state.busy, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = studioFieldColors())
        OutlinedTextField(album, { album = it.take(200) }, label = { Text("Album") }, enabled = !state.busy, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = studioFieldColors())
        RightsRow(rights, !state.busy) { rights = it }
        GlassCapsuleButton("Enregistrer", null, {
            val song = editable ?: return@GlassCapsuleButton
            state.run {
                PublicationApi.updateSong(song, audio, cover, duration, state::onProgress, canvas, info)
                audio = null; cover = null; canvas = null
                state.info = "Modifications enregistrées"
                load(); onChanged()
            }
        }, Modifier.fillMaxWidth(), prominent = true, enabled = !state.busy && rights && changed && editable != null,
            tag = "studio-save", minHeight = 56.dp)
        GlassCapsuleButton("Écouter", KetIcons.Play, { Player.playTrack(current) }, Modifier.fillMaxWidth(), enabled = !state.busy)
        if (Downloader.itemFor(current.id) != null && audio != null) Text(
            "La version hors ligne garde l'ancien audio : téléchargez le titre à nouveau après l'envoi.", color = theme.secondary, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ one album, a selection

@Composable
private fun StudioAlbumPage(state: StudioState, artist: String, album: String, onSong: (Track) -> Unit) {
    val theme = LocalTheme.current
    val songs = state.songs.orEmpty().filter { it.track.artist == artist && it.track.album == album }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var cover by remember { mutableStateOf<File?>(null) }
    var canvas by remember { mutableStateOf<File?>(null) }
    var rights by remember { mutableStateOf(false) }
    val pickCover = coverPicker(state) { cover?.delete(); cover = it }
    val targets = if (selecting && selected.isNotEmpty()) songs.filter { it.track.id in selected } else songs
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("studio-album-page"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(album, cover?.absolutePath ?: songs.firstNotNullOfOrNull { it.track.coverUrl }, 96.dp, 16.dp)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(album, localize = false, color = theme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.asHeading())
                Text(artist, localize = false, color = theme.secondary, fontSize = 14.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (songs.size == 1) "1 titre" else "${songs.size} titres", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (selecting) Text("Tout sélectionner", color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { selected = songs.map { it.track.id }.toSet() }
                    .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 10.dp))
            Text(if (selecting) "Terminer" else "Sélectionner", color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { selecting = !selecting; selected = emptySet() }
                    .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 10.dp).testTag("studio-select"))
        }
        songs.forEach { song ->
            StudioSongRow(song.track, checked = if (selecting) song.track.id in selected else null) {
                if (selecting) selected = if (song.track.id in selected) selected - song.track.id else selected + song.track.id
                else onSong(song.track)
            }
        }
        // What the next change touches: the whole album, or the titles picked above.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.card).padding(16.dp).testTag("studio-apply"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (targets.size == songs.size) "Pour tout l'album" else if (targets.size == 1) "Pour 1 titre sélectionné"
                else "Pour ${targets.size} titres sélectionnés", color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            StudioPart(KetIcons.Album, "Pochette", if (cover != null) "Nouvelle image" else "Inchangée", "Choisir", !state.busy,
                "studio-album-cover", { pickCover.launch(IMAGE_TYPES) })
            CanvasEditor(state, canvas, onChosen = { canvas?.delete(); canvas = it }, published = targets.any { it.track.canvasUrl != null },
                onRemove = {
                    state.run {
                        PublicationApi.applyMedia(targets.map { it.track.id }, null, null, removeCanvas = true)
                        state.info = "Canvas retiré"; state.songs = PublicationApi.songs()
                    }
                }, removeEnabled = rights)
            RightsRow(rights, !state.busy) { rights = it }
            GlassCapsuleButton("Appliquer", null, {
                state.run {
                    PublicationApi.applyMedia(targets.map { it.track.id }, cover, canvas, onProgress = state::onProgress)
                    cover = null; canvas = null
                    state.info = "Modifications enregistrées"; state.songs = PublicationApi.songs()
                }
            }, Modifier.fillMaxWidth(), prominent = true, enabled = !state.busy && rights && targets.isNotEmpty() && (cover != null || canvas != null),
                tag = "studio-album-apply", minHeight = 56.dp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ lyrics for any title

@Composable
private fun StudioLyrics(state: StudioState, track: Track, onDone: (() -> Unit)? = null) {
    val theme = LocalTheme.current
    var parsed by remember { mutableStateOf<ImportedLyrics?>(null) }
    var rights by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.run {
            val file = state.files.copy(uri, 1024L * 1024, setOf("ttml", "lrc"))
            parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ImportedLyrics.read(file) }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("studio-lyrics-page"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioTitle("Paroles")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(track.title, track.coverUrl, 56.dp, 10.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(track.title, localize = false, color = theme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(track.artist, localize = false, color = theme.secondary, fontSize = 13.sp)
            }
        }
        StudioPart(KetIcons.Lyrics, "Fichier de paroles", parsed?.let { "${it.lines.size} lignes" } ?: "TTML ou LRC · les timings sont conservés",
            if (parsed == null) "Choisir" else "Changer", !state.busy, "studio-pick-lyrics",
            { pick.launch(arrayOf("text/*", "application/ttml+xml", "application/xml", "application/octet-stream")) })
        parsed?.let { sample ->
            Text(sample.lines.take(4).joinToString("\n") { it.text }, localize = false, color = theme.onBackground,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.card).padding(16.dp))
        }
        RightsRow(rights, !state.busy && !done) { rights = it }
        GlassCapsuleButton(if (done) "Paroles publiées" else "Publier les paroles", null, {
            state.run { PublicationApi.publishLyrics(track, parsed!!); done = true; state.info = "Paroles publiées" }
        }, Modifier.fillMaxWidth(), prominent = true, enabled = !state.busy && rights && parsed != null && !done, tag = "studio-publish-lyrics")
        if (onDone != null) GlassCapsuleButton("Retour à la fiche", null, onDone, Modifier.fillMaxWidth(), enabled = !state.busy)
    }
}
