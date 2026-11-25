package com.ketvoice.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.ketvoice.app.account.DeviceCode
import com.ketvoice.app.data.AppText
import com.ketvoice.app.together.QrCode
import com.ketvoice.desktop.connect.DeviceLink
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.delay

/* « Se connecter avec un QR code »: the code of DeviceLink as a QR code (the shared encoder) and in letters. */

/** [text] as a QR code: dark modules on white with a 4-module quiet zone, sharp at any scale. */
@Composable
internal fun QrImage(text: String, size: Dp, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encode(text) } ?: return
    Canvas(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).testTag("qr")) {
        val cells = qr.size + 8
        val cell = this.size.minDimension / cells
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr[x, y]) drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + .5f, cell + .5f))
        }
    }
}

@Composable
internal fun QrSignInCard() {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    val state by DeviceLink.state.collectAsState()
    Box(Modifier.padding(top = 16.dp)) {
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(16.dp).testTag("qr-sign-in"), verticalAlignment = Alignment.CenterVertically) {
                when (val s = state) {
                    is DeviceLink.State.Showing -> QrImage(DeviceCode.url(s.code), 168.dp)
                    else -> Box(Modifier.size(168.dp).clip(RoundedCornerShape(12.dp)).background(ket.fill), contentAlignment = Alignment.Center) {
                        if (s == DeviceLink.State.Starting || s == DeviceLink.State.SigningIn) Spinner()
                        else KText("QR", Ket27Desk.Type.title1, color = ket.tertiary, translate = false)
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    KText("Se connecter avec un QR code", Ket27Desk.Type.title3)
                    Spacer(Modifier.height(6.dp))
                    QrInstructions(state)
                    Spacer(Modifier.height(12.dp))
                    when (state) {
                        is DeviceLink.State.Showing, DeviceLink.State.Starting -> KetTextLink("Annuler", "qr-cancel") { DeviceLink.cancel() }
                        DeviceLink.State.SigningIn, DeviceLink.State.Done -> Unit
                        else -> CapsuleButton("Afficher un QR code", null, { DeviceLink.start(scope) }, filled = true, accent = ket.accent,
                            modifier = Modifier.testTag("qr-start"))
                    }
                }
            }
        }
    }
}

@Composable
private fun QrInstructions(state: DeviceLink.State) {
    val ket = LocalKet.current
    when (state) {
        is DeviceLink.State.Showing -> {
            KText("Scannez-le avec votre téléphone, ou ouvrez ketvoice-web.vercel.app/link et tapez ce code :", Ket27Desk.Type.subheadline, maxLines = 3)
            Spacer(Modifier.height(8.dp))
            KText(DeviceCode.format(state.code), Ket27Desk.Type.title2.copy(fontFamily = FontFamily.Monospace), Modifier.testTag("qr-code-text"),
                translate = false, weight = FontWeight.Bold)
            Countdown(state.expiresAt)
        }
        is DeviceLink.State.Failed -> KText(state.message, Ket27Desk.Type.subheadline, color = destructiveRed, maxLines = 3)
        DeviceLink.State.Expired -> KText("Ce code a expiré. Affichez-en un nouveau.", Ket27Desk.Type.subheadline, color = ket.secondary, maxLines = 2)
        DeviceLink.State.SigningIn -> KText("Connexion…", Ket27Desk.Type.subheadline, color = ket.secondary)
        DeviceLink.State.Done -> KText("Connexion réussie.", Ket27Desk.Type.subheadline, color = ket.secondary)
        else -> KText("Approuvez ce PC depuis un appareil déjà connecté à votre compte, sans taper de mot de passe ici.", Ket27Desk.Type.subheadline,
            color = ket.secondary, maxLines = 3)
    }
}

@Composable
private fun Countdown(expiresAt: Long) {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) { while (now.longValue < expiresAt) { delay(1_000); now.longValue = System.currentTimeMillis() } }
    val left = ((expiresAt - now.longValue) / 1000).coerceAtLeast(0)
    KText(AppText.t("Expire dans") + " %d:%02d".format(left / 60, left % 60), Ket27Desk.Type.footnote, color = LocalKet.current.secondary, translate = false)
}
