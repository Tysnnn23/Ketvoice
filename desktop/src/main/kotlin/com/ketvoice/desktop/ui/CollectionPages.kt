package com.ketvoice.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/**
 * Moves [c]'s lightness (HSL, hue and saturation kept) away from [ground] until it reads on it
 * (WCAG 3:1, large text and symbols), and keeps white text legible on it as a filled button.
 */
internal fun legible(c: Color, ground: Color, label: Color): Color {
    fun contrast(a: Color, b: Color): Float { val x = a.luminance() + .05f; val y = b.luminance() + .05f; return maxOf(x, y) / minOf(x, y) }
    val hsl = run {
        val max = maxOf(c.red, c.green, c.blue); val min = minOf(c.red, c.green, c.blue); val d = max - min
        val light = (max + min) / 2
        val sat = if (d == 0f) 0f else d / (1 - kotlin.math.abs(2 * light - 1))
        val hue = when { d == 0f -> 0f; max == c.red -> 60 * (((c.green - c.blue) / d).mod(6f)); max == c.green -> 60 * ((c.blue - c.red) / d + 2); else -> 60 * ((c.red - c.green) / d + 4) }
        floatArrayOf(hue, sat.coerceIn(0f, 1f), light)
    }
    val up = ground.luminance() < .5f
    var out = c
    var l = hsl[2]
    while (contrast(out, ground) < 3f && l in 0f..1f) {
        l += if (up) .04f else -.04f
        out = Color.hsl(hsl[0], hsl[1].coerceAtLeast(.35f), l.coerceIn(0f, 1f))
    }
    // A filled button carries white text: never lighter than 3:1 against white.
    while (contrast(out, Color.White) < 3f && l > 0f) { l -= .04f; out = Color.hsl(hsl[0], hsl[1].coerceAtLeast(.35f), l.coerceIn(0f, 1f)) }
    return if (contrast(out, ground) < 3f) label else out
}

/** A page's wash of the cover's colour: the ground, lightly tinted (flat, no gradient). */
@Composable
private fun coverTint(cover: String?): Pair<Color, Color> {
    val ket = LocalKet.current
    val colors = rememberCoverColors(cover)
    val wash = colors?.first?.copy(alpha = if (ket.dark) .16f else .10f)?.compositeOver(ket.ground) ?: ket.ground
    val accent = colors?.second?.let { legible(it, wash, ket.label) } ?: ket.accent
    val animWash by animateColorAsState(wash, Motion.Springs.SMOOTH.motion())
    val animAccent by animateColorAsState(accent, Motion.Springs.SMOOTH.motion())
    return animWash to animAccent
}

private fun durationLabel(tracks: List<Track>): String {
    val min = tracks.sumOf { it.durationMs } / 60_000
    val h = min / 60
    return if (h > 0) "${AppText.t("${tracks.size} titres")}, $h h ${min % 60} min" else "${AppText.t("${tracks.size} titres")}, $min min"
}

/** The header shared by albums and playlists: large cover with a soft shadow, title, link, Play/Shuffle. */
@Composable
private fun CollectionHeader(cover: @Composable () -> Unit, kind: String, title: String, subtitle: String, onSubtitle: (() -> Unit)?,
                             meta: String, accent: Color, tracks: List<Track>,
                             titleSlot: (@Composable () -> Unit)? = null, extraButtons: @Composable () -> Unit = {}) {
    val ket = LocalKet.current
    val actions = LocalTrackActions.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 26.dp), verticalAlignment = Alignment.Bottom) {
        cover()
        Spacer(Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            KText(kind, Ket27Desk.Type.caption2, color = ket.secondary)
            Spacer(Modifier.height(4.dp))
            if (titleSlot != null) titleSlot() else KText(title, Ket27Desk.Type.largeTitle, maxLines = 2, translate = false)
            KText(subtitle, Ket27Desk.Type.title3, Modifier.then(if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle) else Modifier),
                color = accent, translate = false, weight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            KText(meta, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CapsuleButton("Lecture", KetIcons.Play, { if (tracks.isNotEmpty()) actions.play(tracks, 0) }, filled = true, accent = accent, modifier = Modifier.testTag("play-all"))
                CapsuleButton("Aléatoire", KetIcons.Shuffle, { actions.playShuffled(tracks) }, filled = false, accent = accent, modifier = Modifier.testTag("shuffle-all"))
                extraButtons()
            }
        }
    }
}

@Composable
fun AlbumPage(ref: AlbumRef) {
    val catalog = LocalCatalog.current
    val nav = LocalNavigator.current
    val ket = LocalKet.current
    val data by produceState<AlbumData?>(null, ref) { value = runCatching { catalog.album(ref) }.getOrNull() }
    val cover = data?.ref?.cover ?: ref.cover
    val (wash, accent) = coverTint(cover)
    Box(Modifier.fillMaxSize().background(wash)) {
        Page(null, "album") {
            item("header") {
                val r = data?.ref ?: ref
                val tracks = data?.tracks.orEmpty()
                CollectionHeader({ Artwork(cover, 270.dp, radius = Ket27Desk.Radius.artwork, shadow = 24.dp) },
                    r.type, r.title, r.artist, { nav.push(Route.Artist(ArtistRef(null, r.artist))) },
                    listOfNotNull(r.year, if (tracks.isNotEmpty()) durationLabel(tracks) else null).joinToString(" · "), accent, tracks)
            }
            val d = data
            if (d == null) { item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Spinner() } }; return@Page }
            if (d.tracks.isEmpty()) { item { EmptyMessage("Aucun titre", "Les titres de cet album ne sont pas disponibles.") }; return@Page }
            item("rule") { Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline)) }
            val albumArtist = d.ref.artist.lowercase()
            d.tracks.forEachIndexed { i, t ->
                item("t$i-${t.id}") {
                    NumberedTrackRow(t, d.tracks, i, showArtist = !t.artist.lowercase().startsWith(albumArtist), accent = accent)
                }
            }
            if (d.recommended.isNotEmpty()) {
                item("rec-h") { SectionHeader("Titres recommandés") }
                item("rec") { TrackColumns(d.recommended, columns = 3, rows = 3) }
            }
        }
    }
}

@Composable
fun ArtistPage(ref: ArtistRef) {
    val catalog = LocalCatalog.current
    val actions = LocalTrackActions.current
    val nav = LocalNavigator.current
    val ket = LocalKet.current
    val data by produceState<ArtistData?>(null, ref) { value = runCatching { catalog.artist(ref) }.getOrNull() }
    val photo = data?.ref?.picture ?: ref.picture
    val image = rememberCover(photo)
    // The play button sits on the photo's dark scrim: legible on black in both themes.
    val accent = rememberCoverColors(photo)?.second?.let { legible(it, Color(0xFF1C1C1E), Color.White) } ?: ket.accent
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("page:artist")) {
        item("hero") {
            Box(Modifier.fillMaxWidth().height(380.dp).background(ket.card)) {
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                // A scrim under the name only, so it stays legible on any photo.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f)))))
                Row(Modifier.align(Alignment.BottomStart).padding(horizontal = Ket27Desk.Space.page, vertical = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                    val top = data?.top.orEmpty()
                    Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent)
                        .clickable { if (top.isNotEmpty()) actions.play(top, 0) }.testTag("artist-play"), contentAlignment = Alignment.Center) {
                        com.ketvoice.app.ui.common.Icon(KetIcons.Play, AppText.t("Lecture"), Modifier.size(20.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(16.dp))
                    KText(data?.ref?.name ?: ref.name, Ket27Desk.Type.largeTitle.copy(fontSize = Ket27Desk.Type.largeTitle.fontSize * 1.4f),
                        color = Color.White, translate = false)
                }
            }
        }
        val d = data
        if (d == null) { item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Spinner() } }; return@LazyColumn }
        val pad = Modifier.padding(horizontal = Ket27Desk.Space.page)
        if (d.top.isNotEmpty()) {
            item("top-h") { Box(pad) { SectionHeader("Top titres") } }
            item("top") { Box(pad) { TrackColumns(d.top, columns = 3, rows = 4) } }
        }
        if (d.albums.isNotEmpty()) {
            item("al-h") { Box(pad) { SectionHeader("Albums") } }
            item("al") { Box(pad) { AlbumShelf(d.albums) } }
        }
        if (d.singles.isNotEmpty()) {
            item("si-h") { Box(pad) { SectionHeader("Singles et EP") } }
            item("si") { Box(pad) { AlbumShelf(d.singles) } }
        }
        item("end") { Spacer(Modifier.height(48.dp)) }
    }
}

/** A mosaic of the first four covers (a playlist without its own artwork). */
@Composable
fun Mosaic(tracks: List<Track>, size: androidx.compose.ui.unit.Dp) {
    val covers = tracks.mapNotNull { it.coverUrl }.distinct().take(4)
    if (covers.size < 4) { Artwork(covers.firstOrNull(), size, radius = Ket27Desk.Radius.artwork, shadow = if (size > 100.dp) 24.dp else 0.dp); return }
    Box(Modifier.size(size).clip(RoundedCornerShape(if (size > 100.dp) Ket27Desk.Radius.artwork else 6.dp))) {
        Column {
            for (r in 0..1) Row { for (c in 0..1) Artwork(covers[r * 2 + c], size / 2, radius = 0.dp) }
        }
    }
}

@Composable
fun PlaylistPage(id: String) {
    val library = LocalLibrary.current
    val playlists by library.playlists.collectAsState()
    val p = playlists.firstOrNull { it.id == id }
    val ket = LocalKet.current
    var renaming by remember(id) { mutableStateOf(false) }
    var deleting by remember(id) { mutableStateOf(false) }
    val nav = LocalNavigator.current
    if (p == null) { Page(null, "playlist") { item { EmptyMessage("Playlist introuvable", "Cette playlist a été supprimée.") } }; return }
    Page(null, "playlist") {
        item("header") {
            CollectionHeader({ Mosaic(p.tracks, 240.dp) }, "Playlist", p.name, AppText.t("Vous"), null,
                if (p.tracks.isEmpty()) AppText.t("Aucun titre") else durationLabel(p.tracks), ket.accent, p.tracks,
                titleSlot = if (!renaming) null else { { RenameField(p.name) { name -> renaming = false; if (name.isNotBlank() && name != p.name) library.renamePlaylist(p.id, name) } } },
                extraButtons = {
                    CapsuleButton("Renommer", null, { renaming = true }, filled = false, accent = ket.accent, modifier = Modifier.testTag("rename-playlist"))
                    CapsuleButton("Supprimer", KetIcons.Trash, { deleting = true }, filled = false, accent = destructiveRed, modifier = Modifier.testTag("delete-playlist"))
                })
            if (deleting) ConfirmDialog(AppText.t("Supprimer la playlist"),
                AppText.t("« {0} » sera supprimée de votre bibliothèque.").replace("{0}", p.name), "Supprimer",
                onConfirm = { library.deletePlaylist(p.id); if (!nav.back()) nav.root(Route.Home) }, onDismiss = { deleting = false })
        }
        if (p.tracks.isEmpty()) { item { EmptyMessage("Playlist vide", "Faites glisser des titres sur la playlist dans la barre latérale, ou ajoutez-les depuis leur menu.") }; return@Page }
        item("rule") { Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline)) }
        p.tracks.forEachIndexed { i, t ->
            item("t$i-${t.id}") {
                NumberedTrackRow(t, p.tracks, i, showArtist = true, accent = ket.accent, extraMenu = buildList {
                    add(MenuItem("-"))
                    if (i > 0) add(MenuItem("Monter", null, "move-up") { library.movePlaylistEntry(p.id, i, i - 1) })
                    if (i < p.tracks.lastIndex) add(MenuItem("Descendre", null, "move-down") { library.movePlaylistEntry(p.id, i, i + 1) })
                    add(MenuItem("Retirer de la playlist", KetIcons.Trash, "remove-from-playlist") { library.removeFromPlaylist(p.id, i) })
                })
            }
        }
    }
}

/** The playlist's name, edited in place: Enter or leaving the field keeps it, Escape gives up. */
@Composable
private fun RenameField(current: String, onDone: (String) -> Unit) {
    val ket = LocalKet.current
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(0, current.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.foundation.text.BasicTextField(
        text, { text = it },
        Modifier.fillMaxWidth().focusRequester(focus).testTag("rename-field")
            .onFocusChanged { if (focused && !it.isFocused) onDone(text.text.trim()); focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                else when (e.key) {
                    androidx.compose.ui.input.key.Key.Enter -> { onDone(text.text.trim()); true }
                    androidx.compose.ui.input.key.Key.Escape -> { onDone(current); true }
                    else -> false
                }
            },
        textStyle = Ket27Desk.Type.largeTitle.copy(color = ket.label),
        singleLine = true,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(ket.accent),
    )
}
