package com.ketvoice.app.ui.settings

import com.ketvoice.app.theme.pageBackground
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.glass.*
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text

internal val LocalSettingsCategory = staticCompositionLocalOf<SettingsCategory?> { null }
internal val LocalSettingsTopInset = staticCompositionLocalOf { 0.dp }
/** The row a search result asked for (8.4): it scrolls into view and lights up once, then the mark is cleared. */
internal val LocalSettingsFocus = staticCompositionLocalOf<MutableState<String?>> { mutableStateOf(null) }

/** The row titled [title] answers [LocalSettingsFocus]: brought into view, then a short highlight (still under reduced motion). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.settingsTarget(title: String): Modifier {
    val focus = LocalSettingsFocus.current
    if (focus.value != title) return this
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val glow = remember { androidx.compose.animation.core.Animatable(1f) }
    val accent = LocalTheme.current.accent
    LaunchedEffect(title) {
        // After the page's first layout, so the scroll knows where the row is.
        androidx.compose.runtime.withFrameNanos { }
        runCatching { requester.bringIntoView() }
        if (com.ketvoice.app.ui.motion.Motion.reduced) kotlinx.coroutines.delay(1_600)
        else { kotlinx.coroutines.delay(500); glow.animateTo(0f, androidx.compose.animation.core.tween(1_400)) }
        if (focus.value == title) focus.value = null
    }
    return this.bringIntoViewRequester(requester)
        .drawBehind { drawRect(accent.copy(alpha = .20f * glow.value)) }.testTag("settings-focus")
}

@Composable
internal fun SettingsLayout(category: SettingsCategory?, onBack: () -> Unit, focus: MutableState<String?> = remember { mutableStateOf(null) },
                            content: @Composable () -> Unit) {
    val theme = LocalTheme.current
    val background = rememberGlassBackdrop(theme.background)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var headerHeight by remember(category) { mutableIntStateOf(0) }
    val inset = with(density) { headerHeight.toDp() }.coerceAtLeast(
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp)
    Box(Modifier.fillMaxSize().pageBackground(theme), contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalSettingsCategory provides category, LocalSettingsTopInset provides inset, LocalSettingsFocus provides focus) {
            BackdropCapture(background, Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                content()
            }
            Ket27SettingsBar(category, onBack, Modifier.widthIn(max = 840.dp).fillMaxWidth().onSizeChanged { headerHeight = it.height })
        }
    }
}

/**
 * Ket 27: the bar of an iOS 27 page once content scrolls under it — one uniform, nearly solid
 * bar, a glass back button and the title of the page in its middle. The root page keeps its
 * title large, in the content.
 */
@Composable
private fun Ket27SettingsBar(category: SettingsCategory?, onBack: () -> Unit, modifier: Modifier) {
    val theme = LocalTheme.current
    Column(modifier.background(theme.background.copy(alpha = .94f)).testTag("ket27-settings-bar")) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(54.dp).padding(horizontal = Ket27.Metrics.margin)) {
            com.ketvoice.app.ui.common.LiquidGlassButton(onBack, Modifier.align(Alignment.CenterStart).size(44.dp)) {
                Icon(KetIcons.ChevronRight, "Retour", tint = theme.onBackground,
                    modifier = Modifier.size(20.dp).graphicsLayer { scaleX = -1f })
            }
            if (category != null) Text(category.title, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).semantics { heading() })
        }
        Box(Modifier.fillMaxWidth().height(.5.dp).background(theme.hairline))
    }
}

/**
 * A row that answers the finger the way a list row does on iOS: it darkens at once under the
 * touch and lets go slowly, instead of giving no sign at all.
 */
@Composable
internal fun Modifier.rowPress(role: Role = Role.Button, onClick: () -> Unit): Modifier {
    // The same highlight as the rows of titles (drawn without recomposing, at once under reduced motion).
    val ink = LocalTheme.current.onBackground
    val highlight = remember(ink) { com.ketvoice.app.ui.common.RowHighlight(ink) }
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return clickable(source, highlight, role = role, onClick = onClick)
}

/** A symbol on its coloured tile, as in the Settings of iOS. */
@Composable
internal fun Ket27Tile(icon: ImageVector, index: Int) {
    Box(Modifier.size(Ket27.Metrics.tile).clip(RoundedCornerShape(Ket27.Metrics.tileRadius)).background(Ket27.tile(index)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
    }
}

/** The shape and colour of a group of rows: iOS's inset grouped list (Ket 27). */
@Composable
internal fun Modifier.settingsGroup(): Modifier {
    val theme = LocalTheme.current
    return clip(RoundedCornerShape(Ket27.Metrics.groupRadius)).background(theme.card)
}

@Composable
internal fun SettingsHome(connected: Boolean, name: String, onResult: (SettingEntry) -> Unit = {}, onCategory: (SettingsCategory) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxSize().testTag("settings-home").verticalScroll(rememberScrollState())
        .navigationBarsPadding().padding(horizontal = Ket27.Metrics.margin).padding(top = LocalSettingsTopInset.current + 8.dp, bottom = 32.dp)) {
        Text("Réglages", color = theme.onBackground, style = Ket27.Type.largeTitle,
            modifier = Modifier.padding(start = 4.dp, bottom = 14.dp).semantics { heading() }.testTag("ket27-large-title"))
        // « Recherche dans les réglages » (8.4): every row of every page, from here.
        var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        com.ketvoice.app.ui.common.CompactSearchField(query, { query = it.take(80) }, {},
            Modifier.padding(bottom = 14.dp).testTag("settings-search"), placeholder = "Rechercher un réglage")
        if (query.isNotBlank()) { SettingsSearchResults(query, onResult); return@Column }
        Row(Modifier.fillMaxWidth().settingsGroup()
            .clickable(role = Role.Button) { onCategory(SettingsCategory.Account) }
            .heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Account, null, tint = theme.onBackground.copy(alpha = .8f), modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                Text(if (connected) name.ifBlank { "YouTube Music" } else "Compte YouTube Music", localize = !connected,
                    color = theme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (connected) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(theme.accent))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(if (connected) "Connecté" else "Se connecter", color = theme.secondary,
                        fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
        }
        // 7.6: the welcome's last page, again — the optional models and the local scan.
        var enhance by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().settingsGroup().testTag("settings-enhance")
            .rowPress { enhance = true }.heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Ket27Tile(KetIcons.Download, 7)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 14.dp)) {
                Text("Améliorez votre expérience d'écoute", color = theme.onBackground, fontSize = 17.sp, lineHeight = 22.sp)
                Text("Séparation des voix, rythme Ketmix, musique de l'appareil", color = theme.secondary, fontSize = 13.sp,
                    lineHeight = 18.sp, modifier = Modifier.padding(top = 1.dp))
            }
            Icon(KetIcons.ChevronRight, null, tint = theme.secondary.copy(alpha = .8f), modifier = Modifier.size(18.dp))
        }
        if (enhance) com.ketvoice.app.ui.onboarding.EnhanceSheet(onClose = { enhance = false })
        SettingsGroupHeading("Apparence et lecture")
        SettingsCategoryGroup(listOf(SettingsCategory.Appearance, SettingsCategory.Personalization, SettingsCategory.Playback, SettingsCategory.Lyrics, SettingsCategory.Canvas), onCategory)
        SettingsGroupHeading("Votre musique")
        SettingsCategoryGroup(listOf(SettingsCategory.Sources, SettingsCategory.Downloads, SettingsCategory.LocalMusic, SettingsCategory.Storage), onCategory)
        SettingsGroupHeading("Ketvoice")
        SettingsCategoryGroup(listOf(SettingsCategory.About), onCategory)
        Text("KETVOICE  ${BuildConfig.VERSION_NAME}", localize = false, color = theme.secondary,
            fontSize = 11.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 4.dp, top = 24.dp))
    }
}

/** What the search finds, each row with the page it is on; touched, it opens that page at the row. */
@Composable
private fun SettingsSearchResults(query: String, onResult: (SettingEntry) -> Unit) {
    val theme = LocalTheme.current
    val language = com.ketvoice.app.data.AppSettings.language.value
    val found = remember(query, language) { SettingsSearch.search(query) }
    if (found.isEmpty()) {
        Text(com.ketvoice.app.data.AppText.t("Aucun réglage ne correspond à « {0} »").replace("{0}", query.trim()), localize = false,
            color = theme.secondary, fontSize = 15.sp, lineHeight = 21.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth().testTag("settings-search-empty"))
        return
    }
    Column(Modifier.fillMaxWidth().settingsGroup().testTag("settings-search-results")) {
        found.forEachIndexed { index, entry ->
            Row(Modifier.fillMaxWidth().rowPress { onResult(entry) }.heightIn(min = Ket27.Metrics.row)
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("settings-result"), verticalAlignment = Alignment.CenterVertically) {
                Ket27Tile(entry.category.icon(), entry.category.ordinal)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
                    Text(entry.title, color = theme.onBackground, fontSize = 17.sp, lineHeight = 22.sp)
                    Text(com.ketvoice.app.data.AppText.t(entry.category.title) +
                        (if (entry.sub.isNotEmpty()) " · " + com.ketvoice.app.data.AppText.t(entry.sub) else ""), localize = false,
                        color = theme.secondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
                Icon(KetIcons.ChevronRight, null, tint = theme.secondary.copy(alpha = .8f), modifier = Modifier.size(17.dp))
            }
            if (index != found.lastIndex) SettingsCellDivider()
        }
    }
}

@Composable
internal fun SettingsGroupHeading(title: String, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    // iOS 27 writes its section headers in sentence case, larger and heavier.
    Text(ket27Heading(com.ketvoice.app.data.AppText.t(title)), localize = false, color = theme.onBackground,
        style = Ket27.Type.headline, modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 9.dp).semantics { heading() })
}

@Composable
private fun SettingsCategoryGroup(categories: List<SettingsCategory>, onCategory: (SettingsCategory) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().settingsGroup()) {
        categories.forEachIndexed { index, category ->
            Row(Modifier.fillMaxWidth().testTag("settings-category-${category.name}")
                .rowPress { onCategory(category) }.heightIn(min = 60.dp)
                .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Ket27Tile(category.icon(), category.ordinal)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 14.dp)) {
                    Text(category.title, color = theme.onBackground, fontSize = 17.sp,
                        fontWeight = FontWeight.Normal, lineHeight = 22.sp)
                    Text(category.supportingText(), color = theme.secondary, fontSize = 13.sp,
                        lineHeight = 18.sp, modifier = Modifier.padding(top = 1.dp))
                }
                Icon(KetIcons.ChevronRight, null, tint = theme.secondary.copy(alpha = .8f), modifier = Modifier.size(18.dp))
            }
            if (index != categories.lastIndex) SettingsCellDivider()
        }
    }
}

@Composable
internal fun SettingsCellDivider() {
    val theme = LocalTheme.current
    // iOS aligns the separator with the text of the row and runs it to the edge of the group.
    Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
}

/** "APPARENCE" as iOS 27 writes a header: "Apparence". Names written in capitals on purpose keep them. */
internal fun ket27Heading(title: String): String {
    if (title.isEmpty() || title.any { it.isLowerCase() }) return title
    return title.split(' ').joinToString(" ") { word ->
        NAMES[word] ?: word.lowercase()
    }.replaceFirstChar { it.titlecase() }
}
private val NAMES = mapOf("API" to "API", "KETVOICE" to "Ketvoice", "KETMIX" to "Ketmix", "KET" to "Ket", "QUEST" to "Quest",
    "DISCORD" to "Discord", "STUDIO" to "Studio", "YOUTUBE" to "YouTube", "MUSIC" to "Music", "CANVAS" to "Canvas")

private fun SettingsCategory.supportingText(): String = when (this) {
    SettingsCategory.Account -> "Comptes, synchronisation et publications"
    SettingsCategory.Appearance -> "Thème, verre et personnalisation"
    SettingsCategory.Design -> "Chaque finition peut être retirée séparément"
    SettingsCategory.Personalization -> "Onglets, accueil, lecteur, bibliothèque et menus"
    SettingsCategory.Playback -> "Qualité audio, égaliseur et sortie"
    SettingsCategory.Lyrics -> "Sources, karaoké et traduction"
    SettingsCategory.Canvas -> "Vidéo et fonds réactifs à la musique"
    SettingsCategory.Sources -> "Services intégrés et sources personnalisées"
    SettingsCategory.Downloads -> "Qualité et musique hors ligne"
    SettingsCategory.LocalMusic -> "Dossiers et fichiers de l'appareil"
    SettingsCategory.Storage -> "Cache et espace utilisé"
    SettingsCategory.About -> "Version et mises à jour"
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.Account -> KetIcons.Account
    SettingsCategory.Appearance -> KetIcons.Appearance
    SettingsCategory.Design -> KetIcons.Sliders
    SettingsCategory.Personalization -> KetIcons.Library
    SettingsCategory.Playback -> KetIcons.Audio
    SettingsCategory.Lyrics -> KetIcons.Lyrics
    SettingsCategory.Canvas -> KetIcons.Canvas
    SettingsCategory.Sources -> KetIcons.Link
    SettingsCategory.Downloads -> KetIcons.Download
    SettingsCategory.LocalMusic -> KetIcons.Folder
    SettingsCategory.Storage -> KetIcons.Storage
    SettingsCategory.About -> KetIcons.Info
}
