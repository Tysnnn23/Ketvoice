package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CustomLyrics
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.library.DropKinds
import com.ketvoice.desktop.library.LocalFolders
import com.ketvoice.desktop.library.OpenFiles
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * « Glisser-déposer partout » (Windows 2.0): anything dropped on the window is understood ([DropKinds]) — a playlist
 * link opens the import page, a Ketvoice share link or a music service's link plays the title, a source manifest is
 * offered to Settings › Sources, audio files play or their folders join the library, a lyrics file goes to the title
 * playing — after a question when it changes something. While something is dragged over the window a glass sheet says
 * what will happen.
 */
object DropAnywhere {
    /** What is being dragged over the window (null: nothing). */
    val hovering = mutableStateOf(false)
    /** The question a drop asks before acting, then the short message after it. */
    data class Ask(val title: String, val message: String, val yes: String, val no: String = "Annuler", val other: String? = null,
                   val onOther: () -> Unit = {}, val onYes: () -> Unit)
    val ask = mutableStateOf<Ask?>(null)
    val note = mutableStateOf<String?>(null)
    /** Another feature (smart share links) may open Ketvoice links itself: true when it did. */
    @Volatile var shareLinks: ((String) -> Boolean)? = null

    fun register() = ShellOverlays.register(ShellOverlays.Overlay("drop", order = 10) { backdrop -> DropLayer(backdrop) })

    /** What a drop holds: files first, else text. */
    fun read(t: Transferable): DropKinds.Drop {
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = runCatching { (t.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>() }.getOrDefault(emptyList())
            if (files.isNotEmpty()) return DropKinds.ofFiles(files)
        }
        if (t.isDataFlavorSupported(DataFlavor.stringFlavor))
            return runCatching { DropKinds.ofText(t.getTransferData(DataFlavor.stringFlavor) as String) }.getOrDefault(DropKinds.Nothing)
        return DropKinds.Nothing
    }

    /** Acts on [drop]: [navigate] opens a page of the window. */
    fun handle(drop: DropKinds.Drop, navigate: (Route) -> Unit) {
        when (drop) {
            is DropKinds.PlaylistLink -> navigate(Route.Extra(ImportPage.ID, drop.url))
            is DropKinds.ShareLink -> if (shareLinks?.invoke(drop.url) != true) playShared(drop)
            is DropKinds.ServiceLink -> background { LinkLookup.describe(drop.url)?.let { playSearch(it) } ?: say("Lien non reconnu") }
            is DropKinds.ManifestLink -> ask.value = Ask("Ajouter cette source ?", drop.url, "Ajouter") {
                background { say(if (CustomSources.add(drop.url).isSuccess) "Source ajoutée" else "Source impossible à ajouter") }
            }
            is DropKinds.AudioFiles -> audio(drop)
            is DropKinds.LyricsFile -> lyrics(drop.file)
            DropKinds.Nothing -> say("Rien à importer ici")
        }
    }

    private fun audio(drop: DropKinds.AudioFiles) {
        val folders = (drop.folders + drop.files.mapNotNull { it.parentFile }).distinct()
        ask.value = Ask("Fichiers audio", AppText.t("{0} fichiers, {1} dossiers").replace("{0}", drop.files.size.toString())
            .replace("{1}", drop.folders.size.toString()), "Lire", other = "Ajouter à la bibliothèque",
            onOther = { val added = folders.count { LocalFolders.addFolder(it.path) }; say(if (added > 0) "Dossier ajouté à la bibliothèque" else "Déjà dans la bibliothèque") }) {
            background {
                val files = drop.files + drop.folders.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension.lowercase() in LocalFolders.EXTENSIONS }.take(500).toList() }
                val tracks = OpenFiles.tracks(files.map { it.path }, File(android.content.Context.app.cacheDir, "local-covers"))
                withContext(Dispatchers.Main) { if (tracks.isNotEmpty()) Player.playQueue(tracks, 0) else say("Aucun fichier audio lisible") }
            }
        }
    }

    private fun lyrics(file: File) {
        val track = Player.state.value.current ?: return say("Lancez le titre auquel associer ces paroles")
        ask.value = Ask("Associer ces paroles ?", "${file.name} → ${track.title} — ${track.artist}", "Associer") {
            background {
                val text = CustomLyrics.decode(file.readBytes().take(CustomLyrics.MAX_BYTES).toByteArray())
                if (CustomLyrics.parse(text).second.isEmpty()) return@background say("Ce fichier ne contient pas de paroles lisibles")
                CustomLyrics.saveText(android.content.Context.app, track.id, file.name, text)
                LyricsRepo.load(track, force = true)
                say("Paroles associées")
            }
        }
    }

    private fun playShared(drop: DropKinds.ShareLink) {
        val title = drop.title ?: return say("Lien Ketvoice sans titre")
        background { playSearch(LinkLookup.Named(title, drop.artist.orEmpty()), drop.trackId) }
    }

    private suspend fun playSearch(named: LinkLookup.Named, id: String? = null) {
        val found = runCatching { MusicApi.searchAll("${named.title} ${named.artist}".trim()) }.getOrDefault(emptyList())
        val track = found.firstOrNull { it.id == id } ?: LinkLookup.best(named, found)
        withContext(Dispatchers.Main) { if (track != null) Player.playQueue(listOf(track), 0) else say("Titre introuvable dans Ketvoice") }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private fun background(block: suspend () -> Unit) { scope.launch { runCatching { block() } } }
    private fun say(text: String) { note.value = text }
}

/** The glass sheet while something is dragged over the window, the question of a drop, and the short message after it. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.DropLayer(backdrop: com.ketvoice.desktop.ui.glass.GlassBackdrop?) {
    val nav = LocalNavigator.current
    val target = remember(nav) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { DropAnywhere.hovering.value = true }
            override fun onExited(event: DragAndDropEvent) { DropAnywhere.hovering.value = false }
            override fun onEnded(event: DragAndDropEvent) { DropAnywhere.hovering.value = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                DropAnywhere.hovering.value = false
                val t = (event.nativeEvent as? java.awt.dnd.DropTargetDropEvent)?.transferable ?: return false
                DropAnywhere.handle(DropAnywhere.read(t)) { nav.push(it) }
                return true
            }
        }
    }
    Box(Modifier.matchParentSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target).testTag("drop-target"))
    AnimatedVisibility(DropAnywhere.hovering.value, Modifier.matchParentSize(), enter = fadeIn(Motion.Springs.SMOOTH.spec()) + scaleIn(initialScale = .98f),
        exit = fadeOut(Motion.Springs.DISMISS.spec()) + scaleOut(targetScale = .98f)) { DropSheet(backdrop) }
    DropAnywhere.ask.value?.let { AskCard(it, backdrop) }
    DropNote(backdrop)
}

@Composable
private fun DropSheet(backdrop: com.ketvoice.desktop.ui.glass.GlassBackdrop?) {
    val ket = LocalKet.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (ket.dark) .35f else .18f)).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxSize().glass(backdrop, 28.dp, ket.glassTint, GlassStyle.Drop, rim = ket.glassRim)
            .border(2.dp, ket.accent.copy(alpha = .6f), RoundedCornerShape(28.dp)).testTag("drop-overlay"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(KetIcons.Download, null, Modifier.size(44.dp), tint = ket.accent)
            Spacer(Modifier.height(14.dp))
            KText("Déposez ici", Ket27Desk.Type.largeTitle, weight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            KText("Liens de playlists et de titres, fichiers audio et dossiers, paroles (.lrc, .ttml, .srt), manifestes de sources",
                Ket27Desk.Type.subheadline, Modifier.widthIn(max = 520.dp), color = ket.secondary, maxLines = 2)
        }
    }
}

@Composable
private fun BoxScope.AskCard(a: DropAnywhere.Ask, backdrop: com.ketvoice.desktop.ui.glass.GlassBackdrop?) {
    val ket = LocalKet.current
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .3f)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).glass(backdrop, 18.dp, ket.glassTint, GlassStyle.Panel, rim = ket.glassRim).padding(22.dp).testTag("drop-ask")) {
            KText(a.title, Ket27Desk.Type.headline, weight = FontWeight.SemiBold)
            KText(a.message, Ket27Desk.Type.footnote, Modifier.padding(top = 6.dp), color = ket.secondary, translate = false, maxLines = 3)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapsuleButton(a.no, null, { DropAnywhere.ask.value = null }, filled = false, accent = ket.accent, modifier = Modifier.testTag("drop-no"))
                a.other?.let { CapsuleButton(it, null, { DropAnywhere.ask.value = null; a.onOther() }, filled = false, accent = ket.accent, modifier = Modifier.testTag("drop-other")) }
                CapsuleButton(a.yes, null, { DropAnywhere.ask.value = null; a.onYes() }, filled = true, accent = ket.accent, modifier = Modifier.testTag("drop-yes"))
            }
        }
    }
}

@Composable
private fun BoxScope.DropNote(backdrop: com.ketvoice.desktop.ui.glass.GlassBackdrop?) {
    val ket = LocalKet.current
    val text = DropAnywhere.note.value
    LaunchedEffect(text) { if (text != null) { delay(3_000); DropAnywhere.note.value = null } }
    if (text != null) Box(Modifier.align(Alignment.TopCenter).padding(top = 18.dp).glass(backdrop, 18.dp, ket.glassTint, GlassStyle.Drop, rim = ket.glassRim)
        .padding(horizontal = 18.dp, vertical = 10.dp).testTag("drop-note")) { KText(text, Ket27Desk.Type.subheadline, weight = FontWeight.Medium) }
}

/**
 * The title and artist behind a music service's link, from what the service publishes openly without a key: Deezer's
 * public API, YouTube's and Spotify's oEmbed. Then the best catalogue match ([best], the import's scoring).
 */
object LinkLookup {
    data class Named(val title: String, val artist: String)

    suspend fun describe(url: String): Named? = withContext(Dispatchers.IO) {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return@withContext null
        runCatching {
            when {
                host.endsWith("deezer.com") -> Regex("/track/(\\d{1,15})").find(url)?.groupValues?.get(1)?.let { id ->
                    get("https://api.deezer.com/track/$id")?.let { o -> Named(o.optString("title"), o.optJSONObject("artist")?.optString("name").orEmpty()) }
                }
                host in com.ketvoice.app.smartlib.YouTubeImport.hosts -> get("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8"))
                    ?.let { o -> Named(o.optString("title"), o.optString("author_name").removeSuffix(" - Topic")) }
                host == "open.spotify.com" -> get("https://open.spotify.com/oembed?url=" + java.net.URLEncoder.encode(url, "UTF-8"))?.let { o -> Named(o.optString("title"), "") }
                else -> null
            }
        }.getOrNull()?.takeIf { it.title.isNotBlank() }
    }

    fun best(named: Named, found: List<com.ketvoice.app.data.Track>): com.ketvoice.app.data.Track? {
        if (named.artist.isBlank()) return found.firstOrNull()
        val song = com.ketvoice.app.smartlib.ImportSong("", named.title, named.artist, 0L)
        return com.ketvoice.app.smartlib.PlaylistImport.best(song, found).track ?: found.firstOrNull()
    }

    private fun get(address: String): org.json.JSONObject? {
        val c = java.net.URL(address).openConnection() as java.net.HttpURLConnection
        return try {
            c.connectTimeout = 6_000; c.readTimeout = 8_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Ketvoice")
            if (c.responseCode != 200) null else org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText().take(200_000) })
        } finally { c.disconnect() }
    }
}
