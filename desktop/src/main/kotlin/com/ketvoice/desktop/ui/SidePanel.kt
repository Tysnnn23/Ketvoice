package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.glass.GlassBackdrop
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import java.awt.Cursor

/**
 * The right glass panel (Windows 2.0): the tabs of [PanelTabs] (Paroles, File d'attente, Artiste, Ketmix and those
 * other features add) over the open one's content. Its left edge resizes it ([ShellMetrics.PANEL_MIN]–[ShellMetrics.PANEL_MAX]).
 */
@Composable
internal fun SidePanel(backdrop: GlassBackdrop?, width: Dp, modifier: Modifier = Modifier) {
    val ket = LocalKet.current
    val shell = LocalShell.current
    val density = LocalDensity.current
    // While it slides away the panel keeps showing its last tab.
    val last = remember { androidx.compose.runtime.mutableStateOf(shell.panel) }
    if (shell.panel != null) last.value = shell.panel
    Box(modifier.width(width).fillMaxHeight().glassShadow(26.dp)
        .glass(backdrop, 26.dp, ket.glassTint, GlassStyle.Panel, rim = ket.glassRim).testTag("side-panel")) {
        Column(Modifier.fillMaxSize()) {
            PanelTabStrip(backdrop)
            AnimatedContent(last.value, Modifier.fillMaxSize(), transitionSpec = {
                if (Motion.reduced) fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap())
                else fadeIn(Motion.Springs.SMOOTH.spec()) togetherWith fadeOut(Motion.Springs.DISMISS.spec())
            }, label = "panel") { tab ->
                Box(Modifier.fillMaxSize()) { PanelTabs.find(tab)?.content?.invoke() }
            }
        }
        // The resize handle along the left edge.
        Box(Modifier.width(8.dp).fillMaxHeight().align(Alignment.CenterStart).pointerHoverIcon(PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR)))
            .pointerInput(Unit) { detectHorizontalDragGestures { _, d -> shell.dragPanel(with(density) { d.toDp() }) } }
            .testTag("panel-resize"))
    }
}

/** The tabs as a segmented glass strip: a drop slides on a spring under the open one. */
@Composable
private fun PanelTabStrip(backdrop: GlassBackdrop?) {
    val ket = LocalKet.current
    val shell = LocalShell.current
    val tabs = PanelTabs.all
    val places = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(17.dp)).background(ket.fill.copy(alpha = ket.fill.alpha * .7f))) {
            val place = places[shell.panel]
            val reduced = Motion.reduced
            val x by animateDpAsState(with(density) { (place?.first ?: 0f).toDp() },
                if (reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = .66f, stiffness = 480f))
            val w by animateDpAsState(with(density) { (place?.second ?: 0f).toDp() },
                if (reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = .72f, stiffness = 520f))
            if (place != null) Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(w).fillMaxHeight().padding(2.dp)
                .glass(backdrop, 15.dp, ket.raised.copy(alpha = if (ket.dark) .55f else .9f), GlassStyle.Drop, rim = ket.glassRim))
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    val on = shell.panel == tab.id
                    // The open tab shows its name; the others keep their symbol (named in a tooltip).
                    // Room for the open tab's whole name (« Activité des amis » with six tabs), in the listener's language.
                    val weight by androidx.compose.animation.core.animateFloatAsState(if (on) TabStrip.openWeight(AppText.t(tab.label)) else 1f,
                        if (Motion.reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = .78f, stiffness = 460f))
                    Box(Modifier.weight(weight).fillMaxHeight().onGloballyPositioned { places[tab.id] = it.positionInParent().x to it.size.width.toFloat() }) {
                      Tooltip(tab.label) {
                        val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Row(Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)).clickable(source, androidx.compose.foundation.LocalIndication.current) { shell.open(tab.id) }
                            .focusRing(source, RoundedCornerShape(15.dp)).testTag("panel-tab:${tab.id}"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(tab.icon, null, Modifier.size(15.dp), tint = if (on) ket.accent else ket.secondary)
                            if (on) KText(tab.label, Ket27Desk.Type.footnote, Modifier.padding(start = 6.dp), color = ket.label,
                                weight = FontWeight.SemiBold, translate = tab.id != "ketmix")
                        }
                      }
                    }
                }
            }
        }
        KetIconButton(KetIcons.Close, "Fermer", { shell.close() }, Modifier.padding(start = 4.dp).testTag("panel-close"), size = 30.dp, glyph = 12.dp)
    }
}

/** The panel's tab strip: the open tab's share of the width, the others' being 1. */
internal object TabStrip {
    fun openWeight(label: String): Float = (1.2f + label.length * .14f).coerceIn(2.6f, 4f)
}

/** The built-in tabs, registered the first time the panel is read. */
internal object BuiltInTabs {
    @Volatile private var done = false

    fun ensure() {
        if (done) return
        done = true
        PanelTabs.registerDefault(PanelTabs.Tab("lyrics", "Paroles", KetIcons.Lyrics, 10) { LyricsPanel() })
        PanelTabs.registerDefault(PanelTabs.Tab("queue", "File d'attente", DeskGlyphs.Queue, 20) { QueuePanel() })
        PanelTabs.registerDefault(PanelTabs.Tab("artist", "Artiste", KetIcons.Account, 30) { ArtistPanel() })
        PanelTabs.registerDefault(PanelTabs.Tab("ketmix", "Ketmix", DeskGlyphs.Ketmix, 40) { KetmixPanel() })
    }
}

/** « Artiste »: who sings the title playing, their photo, a few words, their top titles and a link to their page. */
@Composable
internal fun ArtistPanel() {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    val state by LocalPlayer.current.state.collectAsState()
    val track = state.current
    if (track == null) { PanelEmpty(KetIcons.Account, "Artiste"); return }
    val ref = remember(track.artistId, track.artist) { ArtistRef(track.artistId, track.artist.substringBefore(',').trim()) }
    val loader = rememberLoad(ref, empty = { false }) { artist(ref) }
    val data = loader.value
    val photo = data?.ref?.picture ?: ref.picture
    val image = rememberCover(CoverSources.artistHero(ref.name, photo) ?: CoverSources.track(track), pixelsOf(420.dp))
    LazyColumn(Modifier.fillMaxSize().testTag("panel:artist"), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp)) {
        item("photo") {
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(ket.card)) {
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent, 1f to Color.Black.copy(alpha = .6f))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    KText(data?.ref?.name ?: ref.name, Ket27Desk.Type.title1, color = Color.White, translate = false)
                    data?.listeners?.takeIf { it.isNotBlank() }?.let { KText(it, Ket27Desk.Type.footnote, color = Color.White.copy(alpha = .78f), translate = false) }
                }
            }
        }
        item("open") {
            Row(Modifier.padding(top = 14.dp)) {
                CapsuleButton("Ouvrir sa fiche", KetIcons.Account, { nav.push(Route.Artist(data?.ref ?: ref)) }, filled = false, accent = ket.accent,
                    modifier = Modifier.testTag("artist-open"))
            }
        }
        val about = data?.about.orEmpty()
        if (about.isNotBlank()) item("about") {
            androidx.compose.material3.Text(about, Modifier.padding(top = 16.dp), color = ket.secondary, maxLines = 8,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline))
        }
        val top = data?.top.orEmpty()
        if (top.isNotEmpty()) {
            item("top-h") { KText("Top titres", Ket27Desk.Type.headline, Modifier.padding(top = 20.dp, bottom = 6.dp, start = 4.dp)) }
            top.take(6).forEachIndexed { i, t -> item("top$i-${t.id}") { CompactTrackRow(t, top, i, Modifier.fillMaxWidth()) } }
        }
    }
}

/** « Ketmix »: on or off, what it is doing, the next transition and its settings, and Ketmix Studio. */
@Composable
internal fun KetmixPanel() {
    val ket = LocalKet.current
    val state by LocalPlayer.current.state.collectAsState()
    val ui by com.ketvoice.desktop.ketmix.DesktopAutomix.ui.collectAsState()
    val on = AppSettings.automix.value
    val next = state.queue.getOrNull(state.index + 1)
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().testTag("panel:ketmix"), state = list, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp)) {
        item("switch") { SettingsCard { ToggleRow("Ketmix", on, "panel-ketmix") { AppSettings.automix.value = it } } }
        item("now") {
            Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card).padding(16.dp)) {
                KText(KetmixWords.phase(on, ui.phase), Ket27Desk.Type.headline, color = if (ui.phase == com.ketvoice.desktop.ketmix.DesktopAutomix.Phase.Mixing) ket.accent else ket.label)
                Spacer(Modifier.height(10.dp))
                TransitionLine(state.current, next)
            }
        }
        item("style") {
            Box(Modifier.padding(top = 14.dp)) {
                SettingsCard {
                    ChoiceRow("Style de transition", AppSettings.ketmixStyle.value, KetmixWords.styles, "panel-ketmix-style") { AppSettings.ketmixStyle.value = it }
                    Hairline()
                    ChoiceRow("Intensité du mix", AppSettings.automixIntensity.value, listOf("Discret", "Équilibré", "DJ"), "panel-ketmix-intensity") {
                        AppSettings.automixIntensity.value = it
                    }
                }
            }
        }
        item("studio") {
            Row(Modifier.padding(top = 16.dp)) {
                CapsuleButton("Ouvrir le Studio", DeskGlyphs.Ketmix, { com.ketvoice.desktop.ketmix.KetmixStudioDesk.openNext() }, filled = true, accent = ket.accent,
                    modifier = Modifier.testTag("panel-open-studio"))
            }
        }
    }
}

/** The two covers of the next transition, joined. */
@Composable
private fun TransitionLine(from: com.ketvoice.app.data.Track?, to: com.ketvoice.app.data.Track?) {
    val ket = LocalKet.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Artwork(CoverSources.track(from), 44.dp, radius = 10.dp)
        Box(Modifier.padding(horizontal = 8.dp).width(28.dp).height(2.dp).clip(RoundedCornerShape(1.dp))
            .background(Brush.horizontalGradient(listOf(ket.accent.copy(alpha = .2f), ket.accent))))
        Artwork(CoverSources.track(to), 44.dp, radius = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            KText(to?.title ?: AppText.t("Rien à suivre"), Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
            if (to != null) KText(to.artist, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
    }
}

/** The words of the Ketmix tab (French; translated by KText). */
internal object KetmixWords {
    /** By [com.ketvoice.app.automix.MixStyle] ordinal (« Pont en boucle » is 9, played on the desktop since 1.2). */
    val styles = listOf("Automatique", "Calé sur le tempo", "Écho", "Filtres", "Fondu", "Montée", "Coupe", "Sur le drop", "Relais des voix", "Pont en boucle")

    fun phase(on: Boolean, phase: com.ketvoice.desktop.ketmix.DesktopAutomix.Phase): String = when {
        !on -> "Ketmix désactivé"
        phase == com.ketvoice.desktop.ketmix.DesktopAutomix.Phase.Mixing -> "Mix en cours"
        phase == com.ketvoice.desktop.ketmix.DesktopAutomix.Phase.Preparing -> "Préparation du titre suivant…"
        else -> "Ketmix actif"
    }
}

@Composable
internal fun PanelEmpty(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    val ket = LocalKet.current
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(60.dp))
        Icon(icon, null, Modifier.size(34.dp), tint = ket.tertiary)
        Spacer(Modifier.height(14.dp))
        KText(label, Ket27Desk.Type.headline)
    }
}
