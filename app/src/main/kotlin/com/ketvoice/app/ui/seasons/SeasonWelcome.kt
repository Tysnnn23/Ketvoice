package com.ketvoice.app.ui.seasons

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.ketvoice.app.data.SeasonalTheme
import com.ketvoice.app.data.Seasons
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.SeasonLook
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.motion.Motion

/** The season offered on screen now, or null: the app under it is hidden from TalkBack meanwhile. */
object SeasonWelcomeGate {
    val visible = mutableStateOf<SeasonalTheme?>(null)
}

/**
 * Offers the season that has just begun (6.2), once per occurrence ([Seasons.offerDue]), once [ready]: after the
 * splash and the first-launch welcome. With the owner's picture (`season_<id>_welcome`) it
 * is a full-screen welcome; until then a small card over the app.
 */
@Composable
fun SeasonWelcomeHost(ready: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(ready) {
        if (!ready || SeasonWelcomeGate.visible.value != null) return@LaunchedEffect
        val season = Seasons.offerDue(context) ?: return@LaunchedEffect
        Seasons.offered(context, season)
        SeasonWelcomeGate.visible.value = season
    }
    val shown = SeasonWelcomeGate.visible.value
    AnimatedVisibility(shown != null,
        enter = fadeIn(tween(Motion.duration(420))) + scaleIn(tween(Motion.duration(420)), initialScale = if (Motion.reduced) 1f else .96f),
        exit = fadeOut(tween(Motion.duration(300))) + scaleOut(tween(Motion.duration(300)), targetScale = if (Motion.reduced) 1f else 1.03f)) {
        val season = shown ?: return@AnimatedVisibility
        val look = SeasonLooks.of(season.id) ?: return@AnimatedVisibility
        KetvoiceTheme(season = look) {
            SeasonWelcome(season, look,
                onTry = {
                    Seasons.choose(season, true)
                    SettingsStore.save()
                    SeasonWelcomeGate.visible.value = null
                },
                onLater = { SeasonWelcomeGate.visible.value = null })
        }
    }
}

/** The veil of the panel over a picture. */
internal const val SEASON_VEIL = .66f

/**
 * The offer itself: the owner's picture full-bleed with a panel at the bottom when there is one, else a card
 * centred over a scrim, with the season's preview. « Essayer le thème » turns it on; « Plus tard » and the back
 * gesture leave everything as it was. At most 440 dp wide on a tablet, in a landscape window or on Quest.
 */
@Composable
fun SeasonWelcome(season: SeasonalTheme, look: SeasonLook, onTry: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    androidx.activity.compose.BackHandler(onBack = onLater)
    val picture = seasonImage(season.id, "welcome")
    BoxWithConstraints(modifier.fillMaxSize()
        .background(if (picture != 0) look.palette.night else look.palette.night.copy(alpha = .72f))
        .pointerInput(Unit) { detectTapGestures { } }
        .testTag("season-welcome")) {
        if (picture != 0) Image(painterResource(picture), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val panelMax = maxHeight - 32.dp
        val shape = RoundedCornerShape(Ket27.Metrics.alertRadius)
        Column(Modifier.align(if (picture != 0) Alignment.BottomCenter else Alignment.Center)
            .statusBarsPadding().navigationBarsPadding().padding(16.dp).widthIn(max = 440.dp).fillMaxWidth()
            .heightIn(max = panelMax).clip(shape)
            .background(if (picture != 0) look.palette.night.copy(alpha = SEASON_VEIL) else look.palette.card)
            .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp)
            .testTag("season-card"), horizontalAlignment = Alignment.CenterHorizontally) {
            WelcomeBody(season, look, picture == 0, onTry, onLater)
        }
    }
}

@Composable
private fun ColumnScope.WelcomeBody(season: SeasonalTheme, look: SeasonLook, preview: Boolean, onTry: () -> Unit, onLater: () -> Unit) {
    if (preview) { look.Preview(Modifier, 112.dp); Spacer(Modifier.height(16.dp)) }
    look.Emblem(48.dp, Modifier)
    Spacer(Modifier.height(12.dp))
    Text(season.headline, color = Color.White, style = Ket27.Type.title2, textAlign = TextAlign.Center,
        modifier = Modifier.asHeading().testTag("season-headline"))
    Spacer(Modifier.height(6.dp))
    Text(season.until, color = look.palette.secondary, style = Ket27.Type.subheadline, textAlign = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
    TryButton(look, onTry)
    Spacer(Modifier.height(4.dp))
    KetTextButton("Plus tard", onLater, Modifier.fillMaxWidth().testTag("season-later"), tint = Color.White.copy(alpha = .92f))
}

/** « Essayer le thème »: a capsule filled with the season's gradient, its dark label at AA on every colour of it. */
@Composable
private fun TryButton(look: SeasonLook, onClick: () -> Unit) {
    val capsule = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .then(with(look) { Modifier.seasonEdge(capsule, true) })
        .clip(capsule).background(look.fill)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = .22f), .5f to Color.Transparent))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)
        .testTag("season-try"), contentAlignment = Alignment.Center) {
        Text("Essayer le thème", color = look.palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
