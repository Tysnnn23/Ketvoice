package com.ketvoice.app.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.account.DeviceCode
import com.ketvoice.app.data.AppText
import com.ketvoice.app.together.QrCode
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.delay

/**
 * « Se connecter avec le téléphone »: a QR code of the website's approval page and the same code in letters, a
 * countdown, and nothing to type on the TV. Once approved, the TV has the phone's account and goes back.
 */
@Composable
fun TvSignInPage(deviceName: String = com.ketvoice.app.sync.DeviceIdentity.name()) {
    val actions = LocalTvActions.current
    // The account's own scope: a sign-in under way finishes even if the page is left at that moment.
    val scope = com.ketvoice.app.account.AccountSession.scope
    val state by TvSignIn.state.collectAsState()
    LaunchedEffect(Unit) {
        val s = TvSignIn.state.value
        if (s !is TvSignIn.State.Showing && s != TvSignIn.State.Starting && s != TvSignIn.State.SigningIn) TvSignIn.start(scope, deviceName)
    }
    DisposableEffect(Unit) {
        onDispose { TvSignIn.state.value.let { if (it != TvSignIn.State.Done && it != TvSignIn.State.SigningIn) TvSignIn.cancel() } }
    }
    val activity = androidx.activity.compose.LocalActivity.current
    // A new profile (1.1): the TV restarts into it; the profile in use signed in again: back where the listener was.
    LaunchedEffect(state) {
        if (state != TvSignIn.State.Done) return@LaunchedEffect
        delay(1_600)
        if (activity == null || !TvProfiles.finishAdd(activity)) actions.nav.back()
    }
    Box(Modifier.fillMaxSize().testTag("tv-sign-in"), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(28.dp)).background(TvDesign.Ink.glass).padding(36.dp), verticalAlignment = Alignment.CenterVertically) {
            QrPanel(state, 236.dp)
            Spacer(Modifier.width(40.dp))
            Column(Modifier.width(420.dp)) {
                // Signed in already: this code adds another listener's profile to the TV (1.1).
                val adding = com.ketvoice.app.account.AccountSession.state.collectAsState().value.signedIn
                Text(if (adding) "Ajouter un profil" else "Se connecter avec le téléphone", style = TvDesign.Type.title, color = TvDesign.Ink.primary)
                Spacer(Modifier.height(14.dp))
                Instructions(state)
                Spacer(Modifier.height(22.dp))
                Buttons(state, onNew = { TvSignIn.start(scope, deviceName) }, onCancel = { actions.nav.back() })
            }
        }
    }
}

@Composable
private fun QrPanel(state: TvSignIn.State, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
        when (state) {
            is TvSignIn.State.Showing -> QrImage(DeviceCode.url(state.code), size)
            TvSignIn.State.Done -> Text("✓", localize = false, style = TvDesign.Type.display, color = TvDesign.Ink.primary)
            else -> Text("QR", localize = false, style = TvDesign.Type.display, color = TvDesign.Ink.tertiary)
        }
    }
}

/** [text] as a QR code: dark modules on white with a 4-module quiet zone, sharp at any size (the shared encoder). */
@Composable
fun QrImage(text: String, size: Dp) {
    val qr = remember(text) { QrCode.encode(text) } ?: return
    Canvas(Modifier.size(size).clip(RoundedCornerShape(20.dp)).background(Color.White).testTag("qr")) {
        val cells = qr.size + 8
        val cell = this.size.minDimension / cells
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr[x, y]) drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + .5f, cell + .5f))
        }
    }
}

@Composable
private fun Instructions(state: TvSignIn.State) {
    when (state) {
        is TvSignIn.State.Showing -> {
            Text("Scannez ce code avec votre téléphone, ou ouvrez ketvoice-web.vercel.app/link et tapez ce code :", style = TvDesign.Type.body,
                color = TvDesign.Ink.secondary)
            Spacer(Modifier.height(12.dp))
            Text(DeviceCode.format(state.code), localize = false, color = TvDesign.Ink.primary, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, modifier = Modifier.testTag("sign-in-code"))
            Countdown(state.expiresAt)
        }
        TvSignIn.State.Starting, TvSignIn.State.Idle -> Text("Préparation du code…", style = TvDesign.Type.body, color = TvDesign.Ink.secondary)
        TvSignIn.State.SigningIn -> Text("Connexion…", style = TvDesign.Type.body, color = TvDesign.Ink.secondary)
        TvSignIn.State.Done -> Text("Connexion réussie.", style = TvDesign.Type.body, color = TvDesign.Ink.primary)
        TvSignIn.State.Expired -> Text("Ce code a expiré. Affichez-en un nouveau.", style = TvDesign.Type.body, color = TvDesign.Ink.secondary)
        is TvSignIn.State.Failed -> Text(state.message, style = TvDesign.Type.body, color = Color(0xFFFF6B6B), modifier = Modifier.testTag("sign-in-error"))
    }
    if (state is TvSignIn.State.Showing || state == TvSignIn.State.Starting) {
        Spacer(Modifier.height(14.dp))
        Text("Votre mot de passe n'est jamais tapé sur le téléviseur. Le compte de publication ne peut pas se connecter ainsi.",
            style = TvDesign.Type.caption, color = TvDesign.Ink.tertiary)
    }
}

@Composable
private fun Countdown(expiresAt: Long) {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) { while (now.longValue < expiresAt) { delay(1_000); now.longValue = System.currentTimeMillis() } }
    Text(AppText.t("Expire dans") + " " + TvSignIn.countdown(TvSignIn.secondsLeft(expiresAt, now.longValue)), localize = false,
        style = TvDesign.Type.caption, color = TvDesign.Ink.secondary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Buttons(state: TvSignIn.State, onNew: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            TvSignIn.State.Done, TvSignIn.State.SigningIn -> Unit
            is TvSignIn.State.Showing, TvSignIn.State.Starting, TvSignIn.State.Idle -> {
                TvButton("Nouveau code", KetIcons.Refresh, onNew, Modifier.pageItem("sign-in", "new"), tag = "sign-in-new")
                TvButton("Annuler", null, onCancel, Modifier.pageItem("sign-in", "cancel", first = true), tag = "sign-in-cancel")
            }
            else -> {
                TvButton("Afficher un QR code", KetIcons.Refresh, onNew, Modifier.pageItem("sign-in", "retry", first = true), primary = true,
                    tag = "sign-in-retry")
                TvButton("Annuler", null, onCancel, Modifier.pageItem("sign-in", "cancel"), tag = "sign-in-cancel")
            }
        }
    }
}
