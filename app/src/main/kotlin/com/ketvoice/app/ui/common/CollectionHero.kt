package com.ketvoice.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.GlassFusionLayer
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.glass.LocalGlassFusion
import com.ketvoice.app.glass.rememberGlassFusion
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.motion.Motion

/** Side of the floating artwork for a header [width] dp wide: large, never wider than the text needs. */
internal fun heroArtworkSide(width: Float, beside: Boolean): Float =
    if (beside) (width * .32f).coerceIn(120f, 300f) else (width * .62f).coerceIn(168f, 300f)

/**
 * Scale the artwork starts from when its page was opened from a card: the size of
 * that card relative to the artwork, so the cover seems to keep growing into place.
 */
internal fun heroEntryScale(origin: Rect?, restSidePx: Float): Float =
    if (origin == null || restSidePx <= 0f || origin.width <= 0f) .92f
    else (origin.width / restSidePx).coerceIn(.45f, .98f)

/**
 * The header shared by album, playlist, artist and Downloads: the cover's colours
 * blurred edge to edge, the artwork floating over them, the name and the actions
 * centred below. On a wide pane the artwork sits beside the text.
 */
@Composable
internal fun CollectionHero(
    cover: String?,
    top: Dp,
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    titleTag: String? = null,
    subtitle: String? = null,
    onSubtitle: (() -> Unit)? = null,
    meta: String? = null,
    explicit: Boolean = false,
    round: Boolean = false,
    beside: Boolean = false,
    artworkTag: String? = null,
    artworkSide: Dp? = null,
    bleed: Dp = 0.dp,
    titleSlot: (@Composable (centred: Boolean) -> Unit)? = null,
    artwork: @Composable BoxScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalTheme.current
    val density = LocalDensity.current
    CollectionAmbient(cover, modifier.fillMaxWidth(), bleed = bleed) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = artworkSide ?: heroArtworkSide(maxWidth.value, beside).dp
            val shape: Shape = if (round) CircleShape else RoundedCornerShape(if (side < 140.dp) 16.dp else 24.dp)
            // The page opened from a card: the artwork continues that movement into its place.
            val origin = remember { AppUi.revealOrigin.value }
            val entry = remember { Animatable(if (Motion.reduced) 1f else heroEntryScale(origin, with(density) { side.toPx() })) }
            LaunchedEffect(Unit) { entry.animateTo(1f, Motion.gentleSpring()) }
            val frame: @Composable () -> Unit = {
                Box(Modifier.size(side).graphicsLayer { scaleX = entry.value; scaleY = entry.value }
                    .shadow(if (side < 140.dp) 10.dp else 26.dp, shape, ambientColor = Color.Black.copy(alpha = .40f),
                        spotColor = Color.Black.copy(alpha = .55f))
                    .clip(shape).background(theme.card)
                    .then(if (artworkTag != null) Modifier.testTag(artworkTag) else Modifier),
                    contentAlignment = Alignment.Center, content = artwork)
            }
            val identity: @Composable ColumnScope.(Boolean) -> Unit = { centred ->
                val align = if (centred) TextAlign.Center else TextAlign.Start
                if (eyebrow != null) Text(eyebrow, color = theme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp, textAlign = align)
                // An artist may be named by a signature or a logo instead of plain text.
                if (titleSlot != null) titleSlot(centred)
                else Text(title, localize = false, color = theme.onBackground, fontSize = if (side < 140.dp) 22.sp else 28.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp, lineHeight = if (side < 140.dp) 27.sp else 34.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = align,
                    modifier = Modifier.asHeading().then(if (titleTag != null) Modifier.testTag(titleTag) else Modifier))
                if (!subtitle.isNullOrBlank()) Text(subtitle, localize = false, color = theme.accent, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = align,
                    modifier = Modifier.then(if (onSubtitle != null) Modifier.clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onSubtitle) else Modifier)
                        .heightIn(min = if (onSubtitle != null) 48.dp else 0.dp).wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = if (onSubtitle != null) 8.dp else 0.dp))
                if (!meta.isNullOrBlank() || explicit) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!meta.isNullOrBlank()) Text(meta, localize = false, color = theme.secondary, fontSize = 13.sp,
                        lineHeight = 19.sp, textAlign = align)
                    if (explicit) EBadge()
                }
            }
            if (beside) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = top + 8.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                frame()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    identity(false)
                    Spacer(Modifier.height(8.dp))
                    actions()
                }
            } else Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = top + 4.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                frame()
                Spacer(Modifier.height(14.dp))
                identity(true)
                Spacer(Modifier.height(10.dp))
                actions()
            }
        }
    }
}

/**
 * Play and Shuffle as one control group. In Liquid 27 the two capsules are a single
 * body of glass: pressing either one pulls a bridge of liquid between them, and they
 * part again when the finger lifts.
 */
@Composable
internal fun CollectionActionPair(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    playLabel: String = "Lecture",
    enabled: Boolean = true,
    playTag: String? = null,
    shuffleTag: String? = null,
    minHeight: Dp = 56.dp,
    stacked: Boolean = false
) {
    val theme = LocalTheme.current
    val fusion = rememberGlassFusion()
    val playTouch = remember { MutableInteractionSource() }
    val shuffleTouch = remember { MutableInteractionSource() }
    val playPressed by playTouch.collectIsPressedAsState()
    val shufflePressed by shuffleTouch.collectIsPressedAsState()
    val reach = with(LocalDensity.current) { 22.dp.toPx() }
    val pull by animateFloatAsState(if ((playPressed || shufflePressed) && !Motion.reduced) 1f else 0f,
        if (Motion.reduced) snap() else spring(dampingRatio = .62f, stiffness = 300f), label = "action-pair-fusion")
    fusion.strengthPx = pull.coerceIn(0f, 1.15f) * reach
    Box(modifier.fillMaxWidth()) {
        GlassFusionLayer(fusion, theme.isDark, Modifier.matchParentSize())
        // Each capsule consumes the fusion on its own branch, so both report to the same body.
        val play: @Composable (Modifier) -> Unit = { place ->
            CompositionLocalProvider(LocalGlassFusion provides fusion) {
                GlassCapsuleButton(playLabel, KetIcons.Play, onPlay, place, prominent = true,
                    enabled = enabled, tag = playTag, minHeight = minHeight, interactions = playTouch)
            }
        }
        val shuffle: @Composable (Modifier) -> Unit = { place ->
            CompositionLocalProvider(LocalGlassFusion provides fusion) {
                GlassCapsuleButton("Aléatoire", KetIcons.Shuffle, onShuffle, place,
                    enabled = enabled, tag = shuffleTag, minHeight = minHeight, interactions = shuffleTouch)
            }
        }
        // Side by side, or one above the other when large text would not fit two labels.
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            play(Modifier.fillMaxWidth()); shuffle(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            play(Modifier.weight(1f)); shuffle(Modifier.weight(1f))
        }
    }
}

/**
 * The bar that floats over a collection page. Once the header has scrolled away
 * ([collapse] reaches 1) it shows the name in a glass capsule and a small Play, so
 * the page can still be named and started from anywhere in the list.
 */
@Composable
internal fun CollectionTopBar(
    title: String,
    collapse: Float,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    titleTag: String? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val theme = LocalTheme.current
    val shown = collapse.coerceIn(0f, 1f)
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassBackButton(onBack)
        Box(Modifier.weight(1f)) {
            if (shown > 0f) GlassPanel(Modifier.fillMaxWidth().graphicsLayer { alpha = shown }
                .then(if (titleTag != null) Modifier.testTag(titleTag) else Modifier)
                .then(if (shown < .5f) Modifier.clearAndSetSemantics {} else Modifier),
                RoundedCornerShape(24.dp), GlassParams.pill(dark = theme.isDark)) {
                Text(title, localize = false, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (onPlay != null) Box(Modifier.graphicsLayer { alpha = shown }
            .then(if (shown < .98f) Modifier.clearAndSetSemantics {} else Modifier)) {
            LiquidGlassButton(onPlay, Modifier.size(48.dp), enabled = shown >= .98f) {
                Icon(KetIcons.Play, "Lecture", tint = theme.accent, modifier = Modifier.size(22.dp))
            }
        }
        trailing()
    }
}

/**
 * A segmented choice whose selection is a lens of glass that slides from one segment
 * to the next, like the selection of the navigation bar.
 */
@Composable
internal fun GlassSegmentedControl(labels: List<String>, selected: Int, onSelect: (Int) -> Unit,
                                   modifier: Modifier = Modifier, tag: String? = null) {
    val theme = LocalTheme.current
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(theme.card).padding(4.dp)
        .selectableGroup().then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        val count = labels.size.coerceAtLeast(1)
        val segment = maxWidth / count
        val x by animateDpAsState(segment * selected.coerceIn(0, count - 1),
            if (Motion.reduced) snap() else spring(dampingRatio = .78f, stiffness = 420f), label = "segment-lens")
        // The lens: glass where glass is on, a quiet fill otherwise.
        GlassPanel(Modifier.offset(x = x).width(segment).height(44.dp), RoundedCornerShape(14.dp),
            GlassParams.selection(dark = theme.isDark), enabled = AppSettings.liquidGlass.value) {
            Box(Modifier.fillMaxSize().background(theme.onBackground.copy(alpha = .08f)))
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp))
                    .selectable(active, role = Role.Tab) { onSelect(index) }, contentAlignment = Alignment.Center) {
                    Text(label, color = if (active) theme.onBackground else theme.secondary, fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
