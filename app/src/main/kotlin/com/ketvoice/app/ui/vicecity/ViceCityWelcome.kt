package com.ketvoice.app.ui.vicecity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.ViceCityEdition
import com.ketvoice.app.theme.CoverLegibility
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.motion.Motion

/** Whether the welcome screen is up: the app under it is then hidden from TalkBack. */
object ViceCityWelcomeGate {
    val visible = mutableStateOf(false)
}

/**
 * The « GTA VI » welcome of the Vice City edition (6.1): at the first launch of each version up to 20 November
 * 2026 ([ViceCityEdition.welcomeDue]), once [ready] (the splash gone and nothing mandatory on screen). It is
 * marked as shown when it opens, so it never comes back before the next update. It is drawn in the listener's own
 * light or dark theme, with the edition's accent.
 */
@Composable
fun ViceCityWelcomeHost(ready: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(ready) {
        if (ready && !ViceCityWelcomeGate.visible.value && ViceCityEdition.welcomeDue(context)) {
            ViceCityEdition.welcomeShown(context)
            ViceCityWelcomeGate.visible.value = true
        }
    }
    AnimatedVisibility(ViceCityWelcomeGate.visible.value,
        enter = fadeIn(tween(Motion.duration(360))), exit = fadeOut(tween(Motion.duration(280)))) {
        val mode = AppSettings.themeMode.value
        KetvoiceTheme(useDark = if (mode == 1) false else if (mode == 2) true else null, viceCity = true) {
            ViceCityWelcome(
                onEnjoy = {
                    AppSettings.viceCityTheme.value = true
                    SettingsStore.save()
                    ViceCityWelcomeGate.visible.value = false
                },
                onKeep = { ViceCityWelcomeGate.visible.value = false })
        }
    }
}

/**
 * The welcome screen itself, on the page's own background: the edition's illustration across the top, then the
 * title, a word on the theme, and at the bottom « Profiter » (the Vice City theme on), « Garder mon thème » (nothing
 * changes; also the back gesture) and the small print. On a tablet or in a landscape window (Quest) the words stay
 * centred and at most 520 dp wide; with large text the screen scrolls.
 */
@Composable
fun ViceCityWelcome(onEnjoy: () -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier) {
    androidx.activity.compose.BackHandler(onBack = onKeep)
    val theme = LocalTheme.current
    BoxWithConstraints(modifier.fillMaxSize().background(theme.background)
        // The app under the screen gets no touch.
        .pointerInput(Unit) { detectTapGestures { } }
        .testTag("vicecity-welcome")) {
        // A larger share on a tablet or in a landscape window, so the words and the actions do not float in a void.
        val sceneHeight = (maxHeight * (if (maxWidth >= 600.dp) .55f else .46f)).coerceIn(180.dp, 680.dp)
        val window = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = window),
            horizontalAlignment = Alignment.CenterHorizontally) {
            ViceCityScene(Modifier.fillMaxWidth().height(sceneHeight), sunAt = .46f, tag = "vicecity-welcome-scene")
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Profitez de GTA VI en écoutant vos musiques sur Ketvoice !", color = theme.onBackground,
                    style = Ket27.Type.title2, textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading().testTag("vicecity-title"))
                Text("Nouveau : le thème Vice City, en édition limitée jusqu'au 10 décembre.",
                    color = theme.secondary, style = Ket27.Type.subheadline, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.weight(1f))
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 12.dp).testTag("vicecity-panel"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                EnjoyButton(onEnjoy)
                KetTextButton("Garder mon thème", onKeep, Modifier.padding(top = 4.dp).fillMaxWidth().testTag("vicecity-keep"))
                Text("Ketvoice n'est pas affilié à Rockstar Games.", color = theme.secondary,
                    style = Ket27.Type.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** « Profiter »: a capsule filled with the accent, its label black or white, whichever reads better on it. */
@Composable
private fun EnjoyButton(onClick: () -> Unit) {
    val accent = LocalTheme.current.accent
    Text("Profiter", color = CoverLegibility.onFill(accent), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(50)).background(accent)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp)
            .testTag("vicecity-enjoy"))
}
