package com.ketvoice.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.cristalMenuShadow
import com.ketvoice.app.glass.cristalVeil
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.growContainer
import com.ketvoice.app.ui.motion.growContent
import com.ketvoice.app.ui.motion.rememberHaptics
import kotlinx.coroutines.launch

/**
 * Ket 27's context menu: held, a row lifts off the page as a preview of itself and a menu of
 * glass unfolds from it on a spring, as on iOS. One request at a time; the host is drawn once,
 * at the root of the app.
 */
internal object ContextMenus {
    /** [anchor] is where the menu comes from; null opens it in the middle of the window. */
    class Request(val track: Track, val anchor: Rect?, val extra: List<Item> = emptyList(), val only: Boolean = false,
                  val headline: String? = null, val subline: String? = null)

    /**
     * An action a page adds to the menu (7.3, Downloads): [extra] items come first; with `only` they
     * replace the title's own actions (a menu for a whole selection), [headline]/[subline] then name it.
     */
    class Item(val label: String, val icon: ImageVector, val destructive: Boolean = false, val tag: String? = null,
               val run: () -> Unit)

    val request = mutableStateOf<Request?>(null)

    /**
     * Opens the menu of [track] from [anchor] (6.0.0: the glass menu is the only menu of a title,
     * from a long press or a "…" button; the full sheet opens from its "More" item).
     */
    fun open(track: Track, anchor: Rect?, extra: List<Item> = emptyList(), only: Boolean = false,
             headline: String? = null, subline: String? = null) {
        request.value = Request(track, anchor?.takeIf { it.width > 0f && it.height > 0f }, extra, only, headline, subline)
    }

    /** The anchor of a menu opened from nowhere in particular: a row's width, centred, a third of the way down. */
    fun centred(width: Float, height: Float, rowWidth: Float, rowHeight: Float): Rect {
        val w = rowWidth.coerceAtMost(width)
        val top = height * .3f
        return Rect((width - w) / 2f, top, (width + w) / 2f, top + rowHeight)
    }

    /** Where the preview and the menu go, in px from the top-left of the window. */
    data class Placement(val previewLeft: Float, val previewTop: Float, val previewWidth: Float,
                         val menuLeft: Float, val menuTop: Float, val menuFromEnd: Boolean)

    /**
     * The preview stays where the row was pressed, moved only as far as needed for the menu to fit
     * under it between [top] and [bottom]; the menu hangs from the side of the preview nearest to
     * the edge the row sits against. Sizes and positions are in px.
     */
    fun place(anchor: Rect, previewHeight: Float, minPreviewWidth: Float, menuWidth: Float, menuHeight: Float,
              width: Float, top: Float, bottom: Float, margin: Float, gap: Float): Placement {
        val room = (width - 2f * margin).coerceAtLeast(1f)
        val previewWidth = maxOf(anchor.width, minPreviewWidth).coerceAtMost(room)
        val previewLeft = anchor.left.coerceIn(margin, (width - margin - previewWidth).coerceAtLeast(margin))
        val lowest = bottom - margin - menuHeight - gap - previewHeight
        val previewTop = anchor.top.coerceIn(top + margin, maxOf(top + margin, lowest))
        val fromEnd = anchor.center.x > width / 2f && previewWidth < room
        val menuLeft = (if (fromEnd) previewLeft + previewWidth - menuWidth else previewLeft)
            .coerceIn(margin, (width - margin - menuWidth).coerceAtLeast(margin))
        return Placement(previewLeft, previewTop, previewWidth, menuLeft, previewTop + previewHeight + gap, fromEnd)
    }

    /** The menu while it unfolds: from a fifth of its size at 0 to its size at 1 (a spring may pass it slightly). */
    fun unfold(progress: Float): Float = .2f + .8f * (if (progress.isFinite()) progress.coerceIn(0f, 1.15f) else 1f)

    /** The preview lifts by 3 % at most. */
    fun lift(progress: Float): Float = 1f + .03f * (if (progress.isFinite()) progress.coerceIn(0f, 1.15f) else 1f)
}

/**
 * Remembers where a row is, at no cost while it scrolls: the coordinates are only kept, and the
 * rectangle is worked out when the row is held.
 */
internal class RowAnchor {
    var coordinates: LayoutCoordinates? = null
    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()

    /** For a "…" button: put on the button, the menu of its title unfolds from it. */
    val modifier: Modifier = Modifier.onGloballyPositioned { coordinates = it }

    /** Opens the glass menu of [track] from where this anchor is. */
    fun open(track: Track) = ContextMenus.open(track, bounds())
}

@Composable
internal fun rememberRowAnchor(): RowAnchor = remember { RowAnchor() }

internal fun Modifier.rowAnchor(anchor: RowAnchor): Modifier = onGloballyPositioned { anchor.coordinates = it }

private class MenuAction(val label: String, val icon: ImageVector, val enabled: Boolean = true, val tint: Color? = null,
                         val tag: String? = null, val run: () -> Unit)

/** Draws the context menu that was asked for, over everything else. Place it once, at the root. */
@Composable
internal fun ContextMenuHost(modifier: Modifier = Modifier) {
    com.ketvoice.app.ui.sub.SingleUpgradeHost()
    val request = ContextMenus.request.value ?: return
    // 8.4: the Cristal look lifts the title as a plate and opens its own menu.
    if (com.ketvoice.app.glass.GlassLook.cristal) { CristalHeldMenuHost(request, modifier); return }
    val theme = LocalTheme.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    // 8.0: the menu grows out of the row or the "…" button it was opened from (GrowFromSource), and folds back into it.
    val root = com.ketvoice.app.ui.motion.rootOnScreen()
    val corner = with(density) { 22.dp.toPx() }
    val grow = com.ketvoice.app.ui.motion.rememberGrowTransition(request) {
        request.anchor?.let { com.ketvoice.app.ui.motion.GrowSource(it.translate(root), minOf(it.height / 2f, corner)) }
    }
    val progress = grow.progress
    var closing by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request) { grow.open() }
    fun close(then: () -> Unit = {}) {
        if (closing) return
        closing = true
        scope.launch {
            grow.close()
            ContextMenus.request.value = null
            then()
        }
    }
    androidx.activity.compose.BackHandler { close() }
    val track = request.track
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl)
    val actions = menuActions(request, haptic)
    val closeLabel = AppText.t("Fermer")
    BoxWithConstraints(modifier.fillMaxSize().testTag("context-menu")) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val insetTop = WindowInsets.statusBars.getTop(density).toFloat()
        val insetBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val menuWidth = with(density) { 250.dp.toPx() }.coerceAtMost(width - with(density) { 32.dp.toPx() })
        var menuHeight by remember(request) { mutableIntStateOf(0) }
        val previewHeight = with(density) { 68.dp.toPx() }
        val anchor = request.anchor ?: ContextMenus.centred(width, height, with(density) { 320.dp.toPx() }, previewHeight)
        val placement = ContextMenus.place(anchor, previewHeight, with(density) { 280.dp.toPx() }, menuWidth,
            menuHeight.toFloat(), width, insetTop, height - insetBottom, with(density) { 16.dp.toPx() }, with(density) { 10.dp.toPx() })
        // The page steps back behind a veil; no blur of its own, so the menu costs the same in performance mode.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = if (theme.isDark) .55f else .32f))
            .semantics { contentDescription = closeLabel }
            .clickable(remember { MutableInteractionSource() }, null) { close() })
        // The preview: the row itself, lifted off the page where it was pressed.
        Row(Modifier
            .offset { IntOffset(placement.previewLeft.toInt(),
                (anchor.top + (placement.previewTop - anchor.top) * progress.value.coerceIn(0f, 1f)).toInt()) }
            .width(with(density) { placement.previewWidth.toDp() }).height(68.dp)
            .graphicsLayer {
                val lifted = ContextMenus.lift(progress.value)
                scaleX = lifted; scaleY = lifted
                alpha = (progress.value * 3f).coerceIn(0f, 1f)
            }
            .shadow(18.dp, RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = .35f))
            .clip(RoundedCornerShape(20.dp)).background(theme.card).padding(horizontal = 10.dp).testTag("context-menu-preview"),
            verticalAlignment = Alignment.CenterVertically) {
            Cover(track.title, cover, size = 48.dp, radius = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(request.headline ?: track.title, localize = false, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(request.subline ?: track.artist, localize = false, color = theme.secondary, style = Ket27.Type.subheadline, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        // The menu unfolds from the corner that touches the preview, on a still copy of the page (8.3).
        com.ketvoice.app.glass.FrozenBackdrop { GlassPanel(Modifier
            .offset { IntOffset(placement.menuLeft.toInt(), placement.menuTop.toInt()) }
            .width(with(density) { menuWidth.toDp() })
            .heightIn(max = with(density) { (height - insetBottom - placement.menuTop).coerceAtLeast(120.dp.toPx()).toDp() })
            .onSizeChanged { menuHeight = it.height }
            .growContainer(grow, 22.dp).testTag("context-menu-actions"),
            RoundedCornerShape(22.dp), GlassParams.sheet(with(density) { 22.dp.toPx() }, dark = theme.isDark)) {
            Column(Modifier.fillMaxWidth().background(theme.card.copy(alpha = if (theme.isDark) .34f else .5f))
                .growContent(grow).verticalScroll(rememberScrollState())) {
                actions.forEachIndexed { index, action ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = action.enabled, role = Role.Button) {
                        haptic(Haptics.Kind.Press); close(action.run)
                    }.then(if (action.tag != null) Modifier.testTag(action.tag) else Modifier).heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(action.label, color = (action.tint?.takeIf { it == DESTRUCTIVE } ?: theme.onBackground).copy(alpha = if (action.enabled) 1f else .45f),
                            style = Ket27.Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(action.icon, null, tint = (action.tint ?: theme.onBackground).copy(alpha = if (action.enabled) 1f else .45f),
                            modifier = Modifier.padding(start = 12.dp).size(20.dp))
                    }
                    if (index != actions.lastIndex) Box(Modifier.fillMaxWidth().height(.5.dp).background(theme.hairline))
                }
            }
        } }
    }
}

/** The actions of a title's menu: the page's own first, then the title's (both looks). */
@Composable
private fun menuActions(request: ContextMenus.Request, haptic: (Haptics.Kind) -> Unit): List<MenuAction> {
    val theme = LocalTheme.current
    val track = request.track
    val favorite = track.id in AppSettings.favoriteIds.value
    val downloaded = Downloader.itemFor(track.id) != null
    val together = com.ketvoice.app.together.Ketogether.ui.collectAsState().value.active
    val extra = request.extra.map { MenuAction(it.label, it.icon, tint = if (it.destructive) DESTRUCTIVE else null, tag = it.tag, run = it.run) }
    if (request.only) return extra
    val own = mapOf(
        "playNext" to listOf(MenuAction("Lire ensuite", Icons.AutoMirrored.Filled.PlaylistPlay) { Player.playNext(track) }),
        "enqueue" to listOf(MenuAction("Ajouter à la file", Icons.AutoMirrored.Filled.PlaylistAdd) { Player.enqueue(listOf(track)) }),
        "favorite" to listOf(MenuAction(if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
            if (favorite) Ket27Glyphs.HeartFilled else Ket27Glyphs.Heart, tint = if (favorite) theme.accent else null) {
            if (!favorite) haptic(Haptics.Kind.Confirm)
            AppSettings.favoriteIds.value = if (favorite) AppSettings.favoriteIds.value - track.id else AppSettings.favoriteIds.value + track.id
            SettingsStore.save()
        }),
        "download" to listOfNotNull(MenuAction(if (downloaded) "Téléchargé" else "Télécharger", if (downloaded) KetIcons.Check else KetIcons.Download,
            enabled = !downloaded) { Downloader.download(track) },
            // 7.5: a saved title is checked and upgraded alone, without selecting it first.
            if (downloaded) MenuAction("Améliorer la qualité", KetIcons.Download, tag = "menu-upgrade") {
                com.ketvoice.app.ui.sub.UpgradeChecks.single.value = track.id
            } else null),
        "playlist" to listOf(MenuAction("Ajouter à une playlist", KetIcons.Plus) { AppUi.addToPlaylist.value = track }),
        "radio" to listOf(MenuAction("Lancer la radio", Icons.Filled.Radio) { Player.startRadio(track) }),
        "share" to listOf(MenuAction("Partager", KetIcons.Share) { AppUi.shareCard.value = track }),
        // The Ketmix DJ editor (6.1): where this title is left and entered in a mix.
        "mixPoint" to listOf(MenuAction("Point de mix…", KetIcons.Sliders) { com.ketvoice.app.ui.player.KetmixEditor.openFor(track) }),
        "about" to listOf(MenuAction("À propos de ce titre", KetIcons.Info) { TrackCredits.open.value = track }))
    // 9.0 « Bibliothèque et menus »: the listener's order, the hidden items left out; « Plus » always closes the menu.
    return extra + listOfNotNull(
        // Ketogether (6.1): in a session, the title goes to the shared queue (the host adds it, a guest proposes it).
        if (together) MenuAction("Ajouter à Ketogether", com.ketvoice.app.ui.together.KetogetherIcon) { com.ketvoice.app.together.Ketogether.add(track) } else null) +
        TitleMenuLayout.shown(com.ketvoice.app.data.LayoutSettings.menu.value).flatMap { own[it].orEmpty() } +
        MenuAction("Plus", KetIcons.More) { AppUi.trackMenuTrack.value = track }
}

/**
 * A title's menu in the Cristal look (8.4): the page blurs and dims, the title is lifted out of it as a plate (to the
 * width of the window, less 12 dp each side) and the menu opens from the plate's nearest corner, from 55 % of its
 * size, on the held menu's spring ([CristalMenuMotion]); both come from where the title was.
 */
@Composable
private fun CristalHeldMenuHost(request: ContextMenus.Request, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val reduced = Motion.reduced
    val progress = remember(request) { Animatable(0f) }
    var closing by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request) { progress.animateTo(1f, CristalMenuMotion.heldOpen(reduced)) }
    val close: (() -> Unit) -> Unit = close@{ then ->
        if (closing) return@close
        closing = true
        scope.launch {
            progress.animateTo(0f, CristalMenuMotion.heldClose(reduced))
            ContextMenus.request.value = null
            then()
        }
    }
    androidx.activity.compose.BackHandler { close {} }
    val actions = menuActions(request, haptic)
    com.ketvoice.app.glass.FrozenBackdrop(scale = com.ketvoice.app.glass.Cristal.MENU_SCALE) {
        CristalHeldMenuLayout(request, progress, reduced, modifier, { close {} }) {
            val ink = CristalMenuInk.of(LocalTheme.current.isDark)
            actions.forEach { action ->
                CristalMenuRow(action.label, action.icon, ink, tint = action.tint?.takeIf { it == DESTRUCTIVE },
                    iconTint = action.tint, enabled = action.enabled,
                    modifier = if (action.tag != null) Modifier.testTag(action.tag) else Modifier) {
                    haptic(Haptics.Kind.Press); close(action.run)
                }
            }
        }
    }
}

@Composable
private fun CristalHeldMenuLayout(request: ContextMenus.Request, progress: Animatable<Float, *>, reduced: Boolean, modifier: Modifier,
                                  onDismiss: () -> Unit, rows: @Composable () -> Unit) {
    val theme = LocalTheme.current
    val dark = theme.isDark
    val density = LocalDensity.current
    val reduceBlur = AppSettings.reduceDynamicBlur.value
    val plate = (if (dark) Color.Black else Color.White).copy(alpha = if (reduceBlur) com.ketvoice.app.glass.Cristal.PLATE_ALPHA_SOLID
        else com.ketvoice.app.glass.Cristal.PLATE_ALPHA)
    val hairline = com.ketvoice.app.glass.Cristal.menuRim(dark)
    val scrim = Color.Black.copy(alpha = when {
        reduceBlur && dark -> .62f
        reduceBlur -> .36f
        dark -> .30f
        else -> .10f
    })
    val plateShape = RoundedCornerShape(CristalMenuMotion.PLATE_CORNER_DP.dp)
    val menuShape = RoundedCornerShape(CristalMenuMotion.CORNER_DP.dp)
    val insetTop = WindowInsets.safeDrawing.getTop(density)
    val insetBottom = WindowInsets.safeDrawing.getBottom(density)
    val closeLabel = AppText.t("Fermer")
    val track = request.track
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl)
    androidx.compose.ui.layout.Layout(modifier = modifier.fillMaxSize().testTag("context-menu"), content = {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            .then(if (reduceBlur) Modifier else Modifier.cristalVeil())
            .background(scrim)
            .semantics { contentDescription = closeLabel }
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss() })
        Box(Modifier.then(if (reduced) Modifier else Modifier.shadow(CristalMenuMotion.PLATE_SHADOW_DP.dp, plateShape, clip = false))
            .clip(plateShape).background(plate).border(.5.dp, hairline, plateShape)
            .padding(CristalMenuMotion.PLATE_PADDING_DP.dp).testTag("context-menu-preview")) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(track.title, cover, size = 48.dp, radius = 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(request.headline ?: track.title, localize = false, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(request.subline ?: track.artist, localize = false, color = theme.secondary, style = Ket27.Type.subheadline, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.pointerInput(Unit) {}.cristalMenuShadow(menuShape).clip(menuShape).background(plate)
            .border(.5.dp, hairline, menuShape).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)
            .testTag("context-menu-actions")) { rows() }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = CristalMenuMotion.HELD_MARGIN_DP.dp.roundToPx()
        val gap = CristalMenuMotion.HELD_GAP_DP.dp.roundToPx()
        val pad = CristalMenuMotion.PLATE_PADDING_DP.dp.roundToPx()
        val top = insetTop + CristalMenuMotion.HELD_EDGE_DP.dp.roundToPx()
        val bottom = height - insetBottom - CristalMenuMotion.HELD_EDGE_DP.dp.roundToPx()
        val veil = measurables[0].measure(Constraints.fixed(width, height))
        val itemH = 68.dp.toPx()
        val anchor = request.anchor ?: ContextMenus.centred(width.toFloat(), height.toFloat(), 320.dp.toPx(), itemH)
        val itemW = maxOf(anchor.width, 280.dp.toPx()).coerceAtMost(width.toFloat())
        val item = Rect(anchor.center.x - itemW / 2f, anchor.center.y - itemH / 2f, anchor.center.x + itemW / 2f, anchor.center.y + itemH / 2f)
        val plateW = (item.width.roundToInt() + pad * 2).coerceAtLeast(1)
        val plateH = (item.height.roundToInt() + pad * 2).coerceAtLeast(1)
        val card = measurables[1].measure(Constraints.fixed(plateW, plateH))
        val lift = ((width - margin * 2f) / plateW).coerceAtMost(1f)
        val menuW = (width - margin * 2).coerceAtMost(CristalMenuMotion.WIDTH_DP.dp.roundToPx()).coerceAtLeast(0)
        val menu = measurables[2].measure(Constraints(minWidth = menuW, maxWidth = menuW,
            maxHeight = (bottom - top - (plateH * lift).roundToInt() - gap).coerceAtLeast(0)))
        val at = CristalMenuMotion.placeHeld(item, width, plateW, plateH, menuW, menu.height, margin, gap, top, bottom)
        layout(width, height) {
            veil.place(0, 0)
            card.placeWithLayer(at.plateX, at.plateY) { plateLayer(progress.value, at, reduced) }
            menu.placeWithLayer(at.menuX, at.menuY) { heldMenuLayer(progress.value, at, reduced) }
        }
    }
}

private fun androidx.compose.ui.graphics.GraphicsLayerScope.plateLayer(p: Float, at: CristalMenuMotion.HeldPlace, reduced: Boolean) {
    if (reduced) { scaleX = at.scale; scaleY = at.scale; alpha = p.coerceIn(0f, 1f); return }
    val s = androidx.compose.ui.util.lerp(1f, at.scale, p)
    scaleX = s; scaleY = s
    translationX = androidx.compose.ui.util.lerp(at.fromX, 0f, p)
    translationY = androidx.compose.ui.util.lerp(at.fromY, 0f, p)
    alpha = CristalMenuMotion.plateAlpha(p)
}

private fun androidx.compose.ui.graphics.GraphicsLayerScope.heldMenuLayer(p: Float, at: CristalMenuMotion.HeldPlace, reduced: Boolean) {
    alpha = CristalMenuMotion.heldMenuAlpha(p)
    if (reduced) return
    transformOrigin = TransformOrigin(if (at.alignEnd) 1f else 0f, if (at.below) 0f else 1f)
    val s = CristalMenuMotion.heldMenuScale(p)
    scaleX = s; scaleY = s
    translationX = androidx.compose.ui.util.lerp(at.fromX, 0f, p)
    translationY = androidx.compose.ui.util.lerp(at.fromY, 0f, p)
}

