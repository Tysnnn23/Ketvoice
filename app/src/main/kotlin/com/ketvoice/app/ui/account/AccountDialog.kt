package com.ketvoice.app.ui.account

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ketvoice.app.YouTubeLoginActivity
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import kotlinx.coroutines.launch

/** Connexion réelle à YouTube Music, sans compte fabriqué ni profil factice. */
@Composable
fun AccountDialog(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val profile by YouTubeMusicSession.profile.collectAsState()
    val scope = rememberCoroutineScope()
    var cookieOpen by remember { mutableStateOf(false) }
    BackHandler { AppUi.showAccount.value = false }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)).clickable { AppUi.showAccount.value = false }) {
        Column(Modifier.padding(18.dp).padding(top = 82.dp).clip(RoundedCornerShape(26.dp)).background(theme.card)
            .clickable(enabled = false) {}.padding(22.dp)
            .verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YouTube Music", color = theme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(if (profile.connected) "Session confirmée" else "Mode local",
                        color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                if (profile.connected) {
                    Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFF34C759)))
                }
            }
            Text(if (profile.connected) profile.name else "Aucune session connectée",
                color = theme.onBackground, fontSize = 18.sp,
                modifier = Modifier.padding(top = 16.dp))
            if (profile.email.isNotBlank()) Text(profile.email, color = theme.secondary, fontSize = 13.sp)
            Text(profile.message, color = theme.secondary, fontSize = 13.sp,
                lineHeight = 18.sp, modifier = Modifier.padding(vertical = 12.dp))
            if (profile.connected && profile.syncedListens > 0) {
                Text("${profile.syncedListens} écoute(s) acceptée(s) par YouTube",
                    color = theme.accent, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text("La session web est stockée chiffrée sur cet appareil (Android Keystore). " +
                "L'historique dépend de vos réglages Google. Cette intégration utilise des interfaces non officielles " +
                "et peut être limitée par Google.", color = theme.secondary, fontSize = 12.sp, lineHeight = 17.sp)

            if (profile.connected) {
                Text("Actualiser mon accueil", color = theme.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable {
                        HomeFeed.refreshYouTube()
                        AppUi.showAccount.value = false
                    }.padding(vertical = 16.dp))
                Text("Se déconnecter", color = theme.accent,
                    modifier = Modifier.fillMaxWidth().clickable {
                        YouTubeMusicSession.signOut()
                    }.padding(vertical = 10.dp))
            } else {
                Text("Se connecter avec Google", color = theme.accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clickable {
                        AppUi.showAccount.value = false
                        context.startActivity(Intent(context, YouTubeLoginActivity::class.java))
                    }.padding(top = 18.dp, bottom = 12.dp))
                Text("Coller un cookie SAPISID", color = theme.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { cookieOpen = true }
                        .padding(vertical = 10.dp))
                Text("Si Google bloque la connexion intégrée, copiez l'en-tête Cookie de music.youtube.com " +
                    "depuis un navigateur où vous êtes connecté. Seule la validation par YouTube fait foi.",
                    color = theme.secondary, fontSize = 11.5.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            Text("Réglages de Ketvoice", color = theme.accent, modifier = Modifier.fillMaxWidth().clickable {
                AppUi.showAccount.value = false; AppUi.showPlayer.value = false; AppUi.overlay.value = AppUi.SETTINGS
            }.padding(top = 14.dp, bottom = 8.dp))
        }
    }

    if (cookieOpen) {
        var value by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        Dialog(onDismissRequest = { if (!busy) cookieOpen = false }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.background)
                .padding(20.dp)) {
                Text("Cookie YouTube Music", color = theme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Collez l'en-tête Cookie complet (il doit contenir SAPISID ou __Secure-3PAPISID) " +
                    "ou seulement la valeur SAPISID.", color = theme.secondary, fontSize = 12.sp,
                    lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
                BasicTextField(value, { value = it; error = null }, textStyle = TextStyle(
                    color = theme.onBackground, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(12.dp))
                        .background(theme.card).padding(12.dp),
                    decorationBox = { inner ->
                        if (value.isBlank()) Text("SAPISID=… ; __Secure-3PAPISID=…",
                            color = theme.secondary.copy(alpha = .65f), fontSize = 12.sp)
                        inner()
                    })
                if (error != null) Text(error.orEmpty(), color = Color(0xFFFF3B30), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 7.dp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    Text("Annuler", color = theme.secondary, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(enabled = !busy) { cookieOpen = false }.padding(10.dp))
                    Text(if (busy) "Validation…" else "Valider", color = theme.accent, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = !busy) {
                            if (value.isBlank()) return@clickable
                            busy = true
                            scope.launch {
                                val ok = runCatching { YouTubeMusicSession.connectCookie(value) }.getOrDefault(false)
                                busy = false
                                if (ok) cookieOpen = false
                                else error = "YouTube n'a pas confirmé cette session. Aucun compte n'est déclaré connecté."
                            }
                        }.padding(10.dp))
                }
            }
        }
    }
}
