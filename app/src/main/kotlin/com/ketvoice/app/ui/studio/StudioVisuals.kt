package com.ketvoice.app.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.MediaOverride
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.VisualTarget
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.GlassSegmentedControl
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import java.io.File

/** Names of the visuals each kind of catalogue item can take, as the server knows them. */
internal fun visualFields(kind: String): List<String> = when (kind) {
    MediaOverrides.ARTIST -> listOf("hero", "cover")
    MediaOverrides.ALBUM -> listOf("cover", "canvas", "frame")
    else -> listOf("cover", "canvas")
}

internal fun visualKindLabel(kind: String): String = when (kind) {
    MediaOverrides.TRACK -> "Titre"
    MediaOverrides.ALBUM -> "Album"
    else -> "Artiste"
}

/** What saving would send: new files, and the originals to bring back. */
internal data class VisualChange(val cover: File? = null, val canvas: File? = null, val hero: File? = null,
                                 val restore: Set<String> = emptySet(), val canvasOff: Boolean? = null,
                                 /** A Canvas given by its address: streamed from there, never stored. */
                                 val canvasLink: String? = null,
                                 /** A cover, portrait or artist photo given by its address: shown from there, never stored. */
                                 val coverLink: String? = null, val heroLink: String? = null) {
    val isEmpty: Boolean get() = cover == null && canvas == null && hero == null && restore.isEmpty() && canvasOff == null &&
        canvasLink == null && coverLink == null && heroLink == null
    val links: Map<String, String> get() = buildMap {
        canvasLink?.let { put("canvas", it) }; coverLink?.let { put("cover", it) }; heroLink?.let { put("hero", it) }
    }
}

/**
 * Covers, Canvas and artist photos of catalogue titles, albums and artists. The
 * catalogue itself is never changed: every choice can be undone with "Rétablir".
 */
@Composable
internal fun StudioVisuals(state: StudioState, targets: List<VisualTarget>) {
    var index by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("studio-visuals"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioTitle("Visuels", "Pochettes, Canvas et photos d'artiste")
        if (targets.size > 1) GlassSegmentedControl(targets.map { visualKindLabel(it.kind) }, index, { if (!state.busy) index = it },
            Modifier.padding(bottom = 4.dp), tag = "studio-visuals-kind")
        val target = targets[index.coerceIn(targets.indices)]
        key(target.kind, target.key) { VisualEditor(state, target) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun VisualEditor(state: StudioState, target: VisualTarget) {
    val theme = LocalTheme.current
    val saved: MediaOverride? = MediaOverrides.get(target.kind, target.key)
    var change by remember { mutableStateOf(VisualChange()) }
    var rights by remember { mutableStateOf(false) }
    val pickCover = coverPicker(state) { file -> change.cover?.delete(); change = change.copy(cover = file, coverLink = null, restore = change.restore - "cover") }
    val pickHero = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.run {
            val file = state.files.copy(uri, 8L * 1024 * 1024, setOf("png", "jpg", "jpeg", "webp"))
            change.hero?.delete(); change = change.copy(hero = file, heroLink = null, restore = change.restore - "hero")
        }
    }
    fun linkCover(url: String?) { change.cover?.delete(); change = change.copy(cover = null, coverLink = url, restore = if (url != null) change.restore - "cover" else change.restore) }
    fun linkHero(url: String?) { change.hero?.delete(); change = change.copy(hero = null, heroLink = url, restore = if (url != null) change.restore - "hero" else change.restore) }
    fun restore(field: String) { change = change.copy(restore = change.restore + field) }
    fun state(field: String, chosen: File?): String = when {
        chosen != null -> "Nouvelle image · à envoyer"
        (if (field == "hero") change.heroLink else change.coverLink) != null -> "Lien · affiché depuis son adresse, sans limite de taille"
        field in change.restore -> "Original rétabli à l'envoi"
        when (field) { "cover" -> saved?.cover; "hero" -> saved?.hero; else -> saved?.canvas } != null -> "Personnalisé"
        else -> "Original du catalogue"
    }
    val coverShown: Any? = change.cover ?: change.coverLink ?: saved?.cover.takeIf { "cover" !in change.restore } ?: target.cover
    Text(target.title, localize = false, color = theme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (target.subtitle.isNotBlank()) Text(target.subtitle, localize = false, color = theme.secondary, fontSize = 14.sp)
    if (target.kind == MediaOverrides.ARTIST) {
        val heroShown: Any? = change.hero ?: change.heroLink ?: saved?.hero.takeIf { "hero" !in change.restore }
        ArtistVisualPreview(target.title, heroShown ?: coverShown, coverShown)
        VisualPart(KetIcons.Account, "Photo de l'artiste", "En haut de la page de l'artiste · JPEG, PNG ou WebP, 8 Mo maximum",
            state("hero", change.hero), saved?.hero != null && "hero" !in change.restore, state.busy, "studio-visual-hero",
            onPick = { pickHero.launch(IMAGE_TYPES) }, onRestore = { restore("hero") }, state = state, linked = change.heroLink, onLink = ::linkHero)
        VisualPart(KetIcons.Account, "Portrait", "Rond, dans les listes et la recherche · 4 Mo maximum",
            state("cover", change.cover), saved?.cover != null && "cover" !in change.restore, state.busy, "studio-visual-portrait",
            onPick = { pickCover.launch(IMAGE_TYPES) }, onRestore = { restore("cover") }, state = state, linked = change.coverLink, onLink = ::linkCover)
    } else {
        val canvasOff = change.canvasOff ?: (saved?.canvasOff == true && "canvas" !in change.restore)
        val canvasShown = if (canvasOff) null else change.canvas?.absolutePath ?: change.canvasLink ?: saved?.canvas.takeIf { "canvas" !in change.restore }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            StudioPreview(target.sample?.title ?: target.title, target.subtitle, coverShown, canvasShown)
        }
        VisualPart(KetIcons.Album, "Pochette", if (target.kind == MediaOverrides.ALBUM) "Pour l'album et chacun de ses titres" else "Pour ce titre",
            state("cover", change.cover), saved?.cover != null && "cover" !in change.restore, state.busy, "studio-visual-cover",
            onPick = { pickCover.launch(IMAGE_TYPES) }, onRestore = { restore("cover") }, state = state, linked = change.coverLink, onLink = ::linkCover)
        CanvasEditor(state, change.canvas, onChosen = { file ->
                change.canvas?.delete()
                change = if (file != null) change.copy(canvas = file, canvasLink = null, restore = change.restore - "canvas", canvasOff = null)
                    else change.copy(canvas = null)
            },
            published = saved?.canvas != null && "canvas" !in change.restore && !canvasOff,
            onRemove = { change = change.copy(restore = change.restore + "canvas", canvasOff = null, canvasLink = null) }, removeEnabled = true,
            linked = change.canvasLink, onLink = { url ->
                change = if (url != null) change.copy(canvas = null, canvasLink = url, restore = change.restore - "canvas", canvasOff = null)
                    else change.copy(canvasLink = null)
            })
        // The Canvas of this title or album can be switched off: nothing plays behind it, whatever the catalogue offers.
        GlassCapsuleButton(if (canvasOff) "Réactiver le Canvas" else "Désactiver le Canvas", null, {
            change.canvas?.delete()
            change = if (canvasOff) change.copy(canvas = null, canvasLink = null, canvasOff = false, restore = change.restore - "canvas")
                else change.copy(canvas = null, canvasLink = null, canvasOff = true, restore = change.restore - "canvas")
        }, Modifier.fillMaxWidth(), enabled = !state.busy, tag = "studio-canvas-off", minHeight = 48.dp)
        if (target.kind == MediaOverrides.ALBUM && target.tracks.isNotEmpty()) StudioTitleCanvas(state, target, rights)
    }
    Text("Le catalogue d'origine reste intact : rétablissez l'original quand vous voulez.", color = theme.secondary, fontSize = 12.sp)
    RightsRow(rights, !state.busy) { rights = it }
    GlassCapsuleButton("Enregistrer", null, {
        val sending = change
        state.run {
            PublicationApi.saveOverride(target.kind, target.key, target.label, sending.cover, sending.canvas, sending.hero,
                sending.restore.toList(), state::onProgress, canvasOff = sending.canvasOff, links = sending.links)
            listOfNotNull(sending.cover, sending.canvas, sending.hero).forEach { it.delete() }
            change = VisualChange()
            state.info = if (sending.restore.isNotEmpty() && sending.cover == null && sending.canvas == null && sending.hero == null &&
                    sending.canvasOff == null && sending.links.isEmpty())
                "Original rétabli" else "Visuels enregistrés"
        }
    }, Modifier.fillMaxWidth(), prominent = true, enabled = !state.busy && rights && !change.isEmpty,
        tag = "studio-visual-save", minHeight = 56.dp)
    if (saved != null) GlassCapsuleButton("Tout rétablir", KetIcons.Refresh, {
        state.run {
            PublicationApi.saveOverride(target.kind, target.key, target.label, restore = visualFields(target.kind))
            change = VisualChange()
            state.info = "Original rétabli"
        }
    }, Modifier.fillMaxWidth(), enabled = !state.busy && rights, tag = "studio-visual-restore-all")
}

/** One visual with its state, a button to choose a new one and, once changed, one to go back. */
@Composable
private fun VisualPart(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String, value: String, canRestore: Boolean, busy: Boolean, tag: String,
                       onPick: () -> Unit, onRestore: () -> Unit, state: StudioState? = null, linked: String? = null,
                       onLink: ((String?) -> Unit)? = null) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(theme.card).padding(16.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = theme.accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(value, color = theme.secondary, fontSize = 12.sp)
            }
        }
        Text(hint, color = theme.secondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassCapsuleButton("Choisir une image", null, onPick, Modifier.weight(1f), enabled = !busy, tag = "$tag-pick", minHeight = 48.dp)
            if (canRestore) GlassCapsuleButton("Rétablir", null, onRestore, Modifier.weight(1f), enabled = !busy,
                tag = "$tag-restore", minHeight = 48.dp)
        }
        // Or an address: the image is shown from there, nothing is stored on Ketvoice's server.
        if (state != null && onLink != null) {
            var link by remember { mutableStateOf("") }
            if (linked != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(linked, localize = false, color = theme.onBackground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text("Annuler", color = theme.secondary, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onLink(null) }.heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 10.dp))
            } else {
                androidx.compose.material3.OutlinedTextField(link, { link = it.take(2048) }, singleLine = true, enabled = !busy,
                    label = { Text("Ou un lien vers l'image") }, modifier = Modifier.fillMaxWidth().testTag("$tag-link"), colors = studioFieldColors())
                GlassCapsuleButton("Utiliser ce lien", null, {
                    state.run { onLink(com.ketvoice.app.data.StudioImageLink.check(link)); link = "" }
                }, Modifier.fillMaxWidth(), enabled = !busy && link.isNotBlank(), tag = "$tag-use-link", minHeight = 48.dp)
            }
        }
    }
}

/** The top of the artist page as listeners will see it, with the round portrait used in lists. */
@Composable
private fun ArtistVisualPreview(name: String, hero: Any?, portrait: Any?) {
    val theme = LocalTheme.current
    fun model(value: Any?) = when (value) { is File -> value.absolutePath; is String -> value; else -> null }
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp)).background(theme.card).testTag("studio-artist-preview")) {
        model(hero)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent,
            1f to Color.Black.copy(alpha = .62f))))
        Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(theme.background)
                .border(1.dp, Color.White.copy(alpha = .4f), CircleShape), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), localize = false, color = theme.secondary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                model(portrait)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Text(name, localize = false, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** Every catalogue item the owner gave its own visuals, newest first, to reopen or restore. */
@Composable
internal fun StudioVisualList(onOpen: (VisualTarget) -> Unit) {
    val theme = LocalTheme.current
    val items = MediaOverrides.all.values.sortedByDescending { it.updatedAt }
    if (items.isEmpty()) {
        Text("Aucun visuel personnalisé. Depuis le menu ••• d'un titre, une page d'album ou d'artiste, touchez Modifier les visuels.",
            color = theme.secondary, modifier = Modifier.padding(vertical = 24.dp))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val target = VisualTarget.saved(item)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onOpen(target) }
                .padding(vertical = 8.dp, horizontal = 4.dp).testTag("studio-visual-item"), verticalAlignment = Alignment.CenterVertically) {
                Cover(target.title, item.cover ?: item.hero, 52.dp, if (item.kind == MediaOverrides.ARTIST) 26.dp else 10.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(target.title, localize = false, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(target.subtitle.ifBlank { " " }, localize = false, color = theme.secondary, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                StudioChip(visualKindLabel(item.kind))
                if (item.canvas != null) Box(Modifier.padding(start = 4.dp)) { StudioChip("Canvas") }
                Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.padding(start = 6.dp).size(18.dp))
            }
        }
    }
}
