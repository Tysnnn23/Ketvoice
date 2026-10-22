package com.ketvoice.app.ui.settings

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import coil.compose.AsyncImage

@Composable
fun PublisherScreen(target: Track?, onClose: () -> Unit) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = theme.accent,
        unfocusedBorderColor = theme.secondary.copy(alpha = .45f),
        focusedLabelColor = theme.accent,
        unfocusedLabelColor = theme.secondary,
        cursorColor = theme.accent,
        focusedTextColor = theme.onBackground,
        unfocusedTextColor = theme.onBackground,
        focusedContainerColor = theme.card.copy(alpha = .55f),
        unfocusedContainerColor = theme.card.copy(alpha = .35f)
    )
    val allowed by PublisherSession.allowed.collectAsState()
    val scope = rememberCoroutineScope()
    val folder = remember { File(context.cacheDir, "publisher/${UUID.randomUUID()}").apply { mkdirs() } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf("") }
    var audio by remember { mutableStateOf<File?>(null) }
    var cover by remember { mutableStateOf<File?>(null) }
    var lyricFile by remember { mutableStateOf<File?>(null) }
    var imported by remember { mutableStateOf<ImportedLyrics?>(null) }
    var title by remember { mutableStateOf(target?.title.orEmpty()) }
    var artist by remember { mutableStateOf(target?.artist.orEmpty()) }
    var album by remember { mutableStateOf("") }
    var duration by remember { mutableLongStateOf(0L) }
    var rights by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf(false) }
    var uploadedTrack by remember { mutableStateOf<Track?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    val progressPoster = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    BackHandler { if (!busy) onClose() }
    DisposableEffect(folder) { onDispose { folder.deleteRecursively() } }

    fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""; info = ""
        scope.launch {
            try { block() }
            catch (failure: Exception) { error = failure.message?.take(250) ?: "Publication impossible" }
            finally { busy = false }
        }
    }
    suspend fun copy(uri: Uri, max: Long, extensions: Set<String>): File = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: error("Nom de fichier inaccessible")
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in extensions) { "Format de fichier non pris en charge" }
        val file = File(folder, "${UUID.randomUUID()}.$extension")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Fichier inaccessible")
            input.use { source -> file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024); var size = 0L
                while (true) { val count = source.read(buffer); if (count < 0) break
                    size += count; require(size <= max) { "Fichier trop volumineux" }; out.write(buffer, 0, count) }
            } }
            require(file.length() > 0) { "Fichier vide" }
            file
        } catch (failure: Exception) { file.delete(); throw failure }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val file = copy(uri, 25L * 1024 * 1024, setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus"))
            val meta = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") { "Choisissez un fichier audio, pas une vidéo" }
                    listOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
                } finally { retriever.release() }
            }
            audio?.delete(); audio = file
            title = meta[0].orEmpty(); artist = meta[1].orEmpty(); album = meta[2].orEmpty()
            duration = meta[3]?.toLongOrNull() ?: 0L
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val file = copy(uri, 4L * 1024 * 1024, setOf("png", "jpg", "jpeg", "webp"))
            cover?.delete(); cover = file
        }
    }
    val lyricPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val file = copy(uri, 1024L * 1024, setOf("ttml", "lrc"))
            val parsed = withContext(Dispatchers.IO) { ImportedLyrics.read(file).also { file.writeText(it.content, Charsets.UTF_8) } }
            lyricFile?.delete(); lyricFile = file; imported = parsed
        }
    }

    Box(Modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (target == null) "Importer un son" else "Importer des lyrics", color = theme.onBackground,
                    fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, "Fermer", tint = theme.secondary,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(enabled = !busy, onClick = onClose).padding(12.dp))
            }
            if (!allowed) {
                Text("Vérification du compte de publication", color = theme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(PublisherAccess.EMAIL, localize = false, color = theme.accent)
                Text("Un lien de connexion à usage unique sera envoyé à cette adresse. Aucun cookie YouTube n'est envoyé au serveur de publication.", color = theme.secondary)
                PublishButton("Envoyer le lien de connexion", !busy) {
                    operation { PublisherSession.sendLink(); info = "Lien envoyé. Copiez le lien reçu par email et collez-le ci-dessous sans l'ouvrir, ou saisissez son code." }
                }
                OutlinedTextField(verification, { verification = it }, enabled = !busy, label = { Text("Code ou lien reçu par email") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors)
                PublishButton("Vérifier mon accès", !busy && verification.isNotBlank()) {
                    operation { PublisherSession.verify(verification); verification = "" }
                }
            } else if (published) {
                Text("Publication réussie", color = theme.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Ce contenu est maintenant public dans Ketvoice API.", color = theme.onBackground)
                uploadedTrack?.let { song -> PublishButton("Écouter maintenant", !busy) { Player.playTrack(song); onClose() } }
                PublishButton("Fermer", true, onClick = onClose)
            } else {
                Text("Publication publique · Compte vérifié", color = theme.accent, fontWeight = FontWeight.SemiBold)
                if (target == null) {
                    PublishButton(if (audio == null) "Choisir le fichier audio" else "Changer le fichier audio", !busy) { audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) }
                    Text("MP3, M4A, AAC, FLAC, WAV, OGG ou Opus · 25 Mo maximum", color = theme.secondary, fontSize = 12.sp)
                    OutlinedTextField(title, { title = it.take(200) }, label = { Text("Titre") }, enabled = !busy, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                    OutlinedTextField(artist, { artist = it.take(200) }, label = { Text("Artiste") }, enabled = !busy, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                    OutlinedTextField(album, { album = it.take(200) }, label = { Text("Album") }, enabled = !busy, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                    PublishButton(if (cover == null) "Ajouter une pochette" else "Changer la pochette", !busy) { coverPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
                    cover?.let { image -> AsyncImage(image, null, Modifier.size(160.dp).clip(RoundedCornerShape(18.dp))) }
                    Text("Pochette facultative · JPEG, PNG, WebP · 4 Mo maximum", color = theme.secondary, fontSize = 12.sp)
                } else {
                    Text(target.title, localize = false, color = theme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(target.artist, localize = false, color = theme.secondary)
                }
                PublishButton(if (imported == null) "Choisir les paroles TTML ou LRC" else "Changer les paroles", !busy) { lyricPicker.launch(arrayOf("text/*", "application/ttml+xml", "application/xml", "application/octet-stream")) }
                Text("TTML ou LRC · 1 Mo maximum · Les timings du fichier sont conservés", color = theme.secondary, fontSize = 12.sp)
                imported?.let { sample ->
                    Text("${sample.lines.size} lignes", color = theme.accent)
                    Text(sample.lines.take(3).joinToString("\n") { it.text }, localize = false, color = theme.onBackground,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.card).padding(16.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rights, { rights = it }, enabled = !busy,
                        colors = CheckboxDefaults.colors(checkedColor = theme.accent, uncheckedColor = theme.secondary))
                    Text("Je possède les droits nécessaires et j'autorise la diffusion publique de ces fichiers.", color = theme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                PublishButton("Publier pour tous les utilisateurs", !busy && rights && (if (target != null) imported != null else audio != null && title.isNotBlank() && artist.isNotBlank())) {
                    operation {
                        if (target != null) PublicationApi.publishLyrics(target, imported!!)
                        else uploadedTrack = PublicationApi.publishSong(Track("pending", title.trim(), artist.trim(), album.trim(), duration),
                            audio!!, cover, lyricFile) { progressPoster.post { progress = it } }
                        published = true
                    }
                }
                PublishButton("Déconnecter la publication", !busy, quiet = true) { PublisherSession.signOut() }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), color = theme.accent, strokeWidth = 2.dp)
                Text(if (progress > 0) "Publication en cours · $progress %" else "Chargement…", color = theme.secondary, modifier = Modifier.padding(start = 12.dp))
            }
            if (info.isNotBlank()) Text(info, color = theme.secondary)
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF453A))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PublishButton(label: String, enabled: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val background = if (quiet) theme.card.copy(alpha = .55f)
        else theme.accent.copy(alpha = if (enabled) 1f else .3f)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(background)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (quiet) theme.secondary else Color.White, fontWeight = FontWeight.SemiBold)
    }
}
