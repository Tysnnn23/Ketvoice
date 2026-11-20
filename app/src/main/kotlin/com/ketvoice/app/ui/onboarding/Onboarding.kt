package com.ketvoice.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.FirstRun
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.pageBackground
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.motion.Motion

/**
 * The way into the playlist import (Spotify, Deezer…), which another part of the app provides. The welcome only
 * calls it through here, so it compiles and works whether the import exists or not:
 *  - [open], when the import sets it (`PlaylistImportEntry.open = { AppUi.openPlaylistImport() }`);
 *  - otherwise `AppUi.openPlaylistImport()` itself, found by name when it exists (R8 is off, see AGENTS.md);
 *  - otherwise nothing: the welcome shows « Bientôt » instead of the button.
 */
object PlaylistImportEntry {
    @Volatile var open: (() -> Unit)? = null

    fun resolve(): (() -> Unit)? = open ?: byName()

    private fun byName(): (() -> Unit)? = runCatching {
        val method = com.ketvoice.app.ui.AppUi::class.java.getMethod("openPlaylistImport")
        val action: () -> Unit = { method.invoke(com.ketvoice.app.ui.AppUi) }
        action
    }.getOrNull()
}

/** The three pages of the welcome. */
internal enum class OnboardingPage { Language, Playlists, Features }

/**
 * Shows the welcome of a new install (6.2, [FirstRun]) once [ready] (the splash gone, no crash report). It is
 * drawn over the app, before any season's offer.
 */
@Composable
fun OnboardingHost(ready: Boolean) {
    val context = LocalContext.current
    AnimatedVisibility(ready && FirstRun.pending.value,
        enter = fadeIn(tween(Motion.duration(420))), exit = fadeOut(tween(Motion.duration(360)))) {
        Onboarding(onFinish = { choices -> finish(context, choices) })
    }
}

/** What the listener chose in the welcome. */
internal data class OnboardingChoices(val ketmix: Boolean, val voice: Boolean, val importPlaylists: Boolean)

private fun finish(context: android.content.Context, choices: OnboardingChoices) {
    AppSettings.automix.value = choices.ketmix
    SettingsStore.save()
    // The voice level works on every title once the quick separation model is on the phone: it is fetched now.
    if (choices.voice) runCatching {
        com.ketvoice.app.stems.StemModel.init(context)
        if (!com.ketvoice.app.stems.StemModel.fast.ready) com.ketvoice.app.stems.StemModel.fast.download()
    }
    FirstRun.finish(context)
    if (choices.importPlaylists) runCatching { PlaylistImportEntry.resolve()?.invoke() }
}

/**
 * The welcome: language, playlists, Ketmix and voice, each skippable (« Passer » ends it at once, keeping what
 * was chosen so far). On a phone the pages are one column; in a wide window (tablet in landscape, Quest's
 * landscape window) the illustration stands on the left and the page on the right; with large text it scrolls.
 */
@Composable
internal fun Onboarding(onFinish: (OnboardingChoices) -> Unit, modifier: Modifier = Modifier, startPage: Int = 0,
                        importAvailable: Boolean = PlaylistImportEntry.resolve() != null) {
    var page by rememberSaveable { mutableIntStateOf(startPage) }
    var ketmix by rememberSaveable { mutableStateOf(AppSettings.automix.value) }
    var voice by rememberSaveable { mutableStateOf(false) }
    var importPlaylists by rememberSaveable { mutableStateOf(false) }
    val done = { onFinish(OnboardingChoices(ketmix, voice, importPlaylists)) }
    androidx.activity.compose.BackHandler(enabled = page > 0) { page-- }
    val th = LocalTheme.current
    BoxWithConstraints(modifier.fillMaxSize().pageBackground(th).pointerInput(Unit) { detectTapGestures { } }
        .testTag("onboarding")) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        val art = if (wide) minOf(maxHeight * .5f, 260.dp) else 150.dp
        val state = PageState(OnboardingPage.entries[page.coerceIn(0, 2)], ketmix, voice, importPlaylists, importAvailable,
            onKetmix = { ketmix = it }, onVoice = { voice = it }, onImport = { importPlaylists = it })
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(page, onSkip = done)
            Box(Modifier.weight(1f).fillMaxWidth()) { Pages(state, wide, art) }
            BottomBar(page, wide, onNext = { if (page < 2) page++ else done() })
        }
    }
}

/** What a page shows and changes. */
internal class PageState(
    val page: OnboardingPage, val ketmix: Boolean, val voice: Boolean, val importPlaylists: Boolean,
    val importAvailable: Boolean, val onKetmix: (Boolean) -> Unit, val onVoice: (Boolean) -> Unit, val onImport: (Boolean) -> Unit,
)

@Composable
private fun TopBar(page: Int, onSkip: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Dots(page, Modifier.weight(1f))
        KetTextButton("Passer", onSkip, Modifier.testTag("onboarding-skip"))
    }
}

/** Where the listener is: three dots, the current one long. */
@Composable
private fun Dots(page: Int, modifier: Modifier) {
    val th = LocalTheme.current
    val label = AppText.t("Étape") + " ${page + 1} / 3"
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val width by animateDpAsState(if (i == page) 22.dp else 7.dp, tween(Motion.duration(260)), label = "onboarding-dot")
            Box(Modifier.size(width = width, height = 7.dp).clip(RoundedCornerShape(50))
                .background(if (i == page) th.accent else th.onBackground.copy(alpha = .22f)))
        }
    }
}

@Composable
private fun BottomBar(page: Int, wide: Boolean, onNext: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        GlassCapsuleButton(if (page < 2) "Continuer" else "Commencer", null, onNext,
            Modifier.widthIn(max = if (wide) 360.dp else 520.dp).fillMaxWidth(), prominent = true, tag = "onboarding-next")
    }
}

/** The pages, sliding sideways one into the next (a cross-fade with reduced motion). */
@Composable
private fun Pages(state: PageState, wide: Boolean, art: Dp) {
    AnimatedContent(state.page, transitionSpec = {
        val forward = targetState.ordinal > initialState.ordinal
        if (Motion.reduced) fadeIn(tween(Motion.duration(200))).togetherWith(fadeOut(tween(Motion.duration(200))))
        else (slideInHorizontally(tween(Motion.duration(380))) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(Motion.duration(380))))
            .togetherWith(slideOutHorizontally(tween(Motion.duration(320))) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(tween(Motion.duration(240))))
    }, label = "onboarding-page", modifier = Modifier.fillMaxSize()) { shown ->
        val content = PageState(shown, state.ketmix, state.voice, state.importPlaylists, state.importAvailable,
            state.onKetmix, state.onVoice, state.onImport)
        if (wide) WidePage(content, art) else NarrowPage(content, art)
    }
}

@Composable
private fun NarrowPage(state: PageState, art: Dp) {
    Column(Modifier.fillMaxSize().verticalScrollIfNeeded().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            PageArt(state.page, Modifier.size(art))
            Spacer(Modifier.height(18.dp))
            PageText(state.page, center = true)
            Spacer(Modifier.height(18.dp))
            PageBody(state)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WidePage(state: PageState, art: Dp) {
    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(.42f).fillMaxHeight(), contentAlignment = Alignment.Center) { PageArt(state.page, Modifier.size(art)) }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(.58f).fillMaxHeight().verticalScrollIfNeeded(), verticalArrangement = Arrangement.Center) {
            Column(Modifier.widthIn(max = 520.dp)) {
                Spacer(Modifier.height(12.dp))
                PageText(state.page, center = false)
                Spacer(Modifier.height(16.dp))
                PageBody(state)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Large text never cuts a page: it scrolls. */
@Composable
private fun Modifier.verticalScrollIfNeeded(): Modifier = verticalScroll(rememberScrollState())

@Composable
private fun PageText(page: OnboardingPage, center: Boolean) {
    val th = LocalTheme.current
    val align = if (center) TextAlign.Center else TextAlign.Start
    val (title, body) = when (page) {
        OnboardingPage.Language -> "Bienvenue sur Ketvoice" to "Choisissez la langue de l'application. Vous pourrez la changer à tout moment dans les réglages."
        OnboardingPage.Playlists -> "Retrouvez vos playlists" to "Importez les playlists que vous écoutez déjà ailleurs, ou commencez votre bibliothèque ici."
        OnboardingPage.Features -> "Ketmix et voix" to "Deux façons d'écouter autrement. Elles restent modifiables dans les réglages."
    }
    Text(title, color = th.onBackground, style = Ket27.Type.largeTitle, textAlign = align,
        modifier = Modifier.fillMaxWidth().asHeading().testTag("onboarding-title"))
    Spacer(Modifier.height(8.dp))
    Text(body, color = th.secondary, style = Ket27.Type.body, textAlign = align, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PageBody(state: PageState) = when (state.page) {
    OnboardingPage.Language -> LanguageList()
    OnboardingPage.Playlists -> ImportChoice(state)
    OnboardingPage.Features -> FeatureSwitches(state)
}
