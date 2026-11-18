package com.ketvoice.app.ui.vicecity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.R
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.ViceCityEdition
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.motion.Motion

/** Whether the welcome screen is up: the app under it is then hidden from TalkBack and its status bar turns light. */
object ViceCityWelcomeGate {
    val visible = mutableStateOf(false)
}

/**
 * The « GTA VI » welcome of the Vice City edition (6.1): at the first launch of each version up to 20 November
 * 2026 ([ViceCityEdition.welcomeDue]), once [ready] (the splash gone and nothing mandatory on screen). It is
 * marked as shown when it opens, so it never comes back before the next update.
 *
 * On Quest it opens too, in the headset's landscape window: the picture is cropped around its « VI » and the
 * panel stays centred and narrow, so the screen reads the same as on a phone.
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
        enter = fadeIn(tween(Motion.duration(480))),
        exit = fadeOut(tween(Motion.duration(360))) + scaleOut(tween(Motion.duration(360)), targetScale = 1.04f)) {
        KetvoiceTheme(useDark = true, viceCity = true) {
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

// The welcome keeps its own night and sunset (6.7, owner: « Remets l'ancien écran de Enjoy GTA VI »): the theme
// itself became one accent and one flat illustration in 6.6 and no longer has these colours.
private val WelcomeNight = Color(0xFF120A22)
private val WelcomeSunset = Brush.linearGradient(listOf(Color(0xFFFF3D9A), Color(0xFFFF5FA8), Color(0xFFFF8A4C)))

/** The edition's K, cropped from the owner's logo, in a rounded square. */
@Composable
private fun ViceCityLogo(size: androidx.compose.ui.unit.Dp) {
    Image(painterResource(R.drawable.vicecity_logo), null, Modifier.size(size).clip(RoundedCornerShape(size * .23f)),
        contentScale = ContentScale.Crop)
}

/** The veil inside the glass panel: white text keeps AA even over the sun of the picture (ViceCityWelcomeTest). */
internal const val WELCOME_VEIL = .66f
internal const val WELCOME_BODY_ALPHA = .84f
internal const val WELCOME_NOTE_ALPHA = .72f

/**
 * The welcome screen itself: the owner's sunset full-bleed, slowly drifting (still under reduced motion), and
 * at the bottom a glass panel that really blurs the picture behind it, with the edition's K, the title, a word
 * on the theme, « Profiter » (the Vice City theme on) and « Garder mon thème » (nothing changes; also the back
 * gesture). On a tablet or in a landscape window the panel stays centred and at most 520 dp wide; with large
 * text it scrolls.
 */
@Composable
fun ViceCityWelcome(onEnjoy: () -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier, animate: Boolean = !Motion.reduced) {
    androidx.activity.compose.BackHandler(onBack = onKeep)
    val backdrop = rememberGlassBackdrop(WelcomeNight)
    BoxWithConstraints(modifier.fillMaxSize().background(WelcomeNight)
        // The app under the screen gets no touch.
        .pointerInput(Unit) { detectTapGestures { } }
        .testTag("vicecity-welcome")) {
        val landscape = maxWidth > maxHeight
        val panelMax = maxHeight - 32.dp
        val shortWindow = maxHeight < 520.dp
        // A slow Ken Burns: the picture breathes in and drifts a little, over half a minute.
        val drift = if (animate) rememberInfiniteTransition(label = "vicecity-drift")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "kenburns").value
            else .5f
        BackdropCapture(backdrop, Modifier.fillMaxSize()) {
            Image(painterResource(R.drawable.vicecity_welcome), null,
                Modifier.fillMaxSize().graphicsLayer {
                    val zoom = 1.04f + .06f * drift
                    scaleX = zoom; scaleY = zoom
                    translationX = size.width * .012f * (drift - .5f) * 2f
                    translationY = -size.height * .008f * drift
                },
                contentScale = ContentScale.Crop,
                // In a landscape window the picture is cropped a little lower, so the « VI » stands above the panel.
                alignment = if (landscape) BiasAlignment(0f, .05f) else Alignment.Center)
            // The night comes back at the bottom, under the panel.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to WelcomeNight.copy(alpha = .28f), .14f to Color.Transparent, .55f to Color.Transparent,
                1f to WelcomeNight.copy(alpha = .6f))))
        }
        CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
            val shape = RoundedCornerShape(Ket27.Metrics.alertRadius)
            GlassPanel(Modifier.align(Alignment.BottomCenter).statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp).widthIn(max = 520.dp).fillMaxWidth().testTag("vicecity-panel"),
                shape, GlassParams.sheet(102f, dark = true)) {
                Column(Modifier.fillMaxWidth().clip(shape).background(WelcomeNight.copy(alpha = WELCOME_VEIL))
                    .heightIn(max = panelMax).verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ViceCityLogo(if (shortWindow) 44.dp else 56.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Profitez de GTA VI en écoutant vos musiques sur Ketvoice !", color = Color.White,
                        style = Ket27.Type.title2, textAlign = TextAlign.Center,
                        modifier = Modifier.asHeading().testTag("vicecity-title"))
                    Spacer(Modifier.height(8.dp))
                    Text("Nouveau : le thème Vice City, en édition limitée jusqu'au 10 décembre.",
                        color = Color.White.copy(alpha = WELCOME_BODY_ALPHA), style = Ket27.Type.subheadline, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    EnjoyButton(onEnjoy)
                    Spacer(Modifier.height(4.dp))
                    KetTextButton("Garder mon thème", onKeep, Modifier.fillMaxWidth().testTag("vicecity-keep"),
                        tint = Color.White.copy(alpha = .92f))
                    Text("Ketvoice n'est pas affilié à Rockstar Games.", color = Color.White.copy(alpha = WELCOME_NOTE_ALPHA),
                        style = Ket27.Type.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                }
            }
        }
    }
}

/** « Profiter »: a capsule filled with the sunset, its dark label at AA on every colour of it. */
@Composable
private fun EnjoyButton(onClick: () -> Unit) {
    val capsule = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().heightIn(min = 54.dp)
        .clip(capsule).background(WelcomeSunset)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = .22f), .5f to Color.Transparent))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)
        .testTag("vicecity-enjoy"), contentAlignment = Alignment.Center) {
        Text("Profiter", color = WelcomeNight, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
    }
}
