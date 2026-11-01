package com.ketvoice.app.ui.account

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.discord.Discord
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text

/**
 * Discord lives in Settings → Account. Presence through the installed Discord app
 * is the default path; an OAuth2 bearer token can be pasted as an advanced
 * fallback, and the last SDK log line is shown so a silent failure is visible.
 */
@Composable
internal fun DiscordSettings(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val discord by Discord.state.collectAsState()
    var advanced by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }

    fun openDiscord() {
        val launch = context.packageManager.getLaunchIntentForPackage("com.discord")
        if (launch != null) runCatching { context.startActivity(launch) }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Discord", color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !Discord.available -> "Indisponible dans cette version"
                        discord.busy -> "En attente de l'app Discord…"
                        discord.ready -> discord.userName ?: "Présence connectée"
                        discord.linked -> discord.userName ?: "Compte lié"
                        else -> "Affiche le morceau en cours sur ton profil"
                    },
                    color = theme.secondary, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
            when {
                discord.busy -> CircularProgressIndicator(Modifier.size(18.dp), color = theme.accent, strokeWidth = 2.dp)
                discord.ready || discord.linked -> Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF34C759)))
                else -> Box(Modifier.size(9.dp).clip(CircleShape).background(theme.secondary.copy(alpha = .45f)))
            }
        }

        if (Discord.available && discord.linked) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Afficher ce que j'écoute", color = theme.onBackground, fontSize = 14.sp)
                    Text("Morceau, artiste et boutons sur votre profil Discord.",
                        color = theme.secondary, fontSize = 11.5.sp, lineHeight = 16.sp)
                }
                Switch(
                    checked = AppSettings.discordPresence.value,
                    onCheckedChange = {
                        AppSettings.discordPresence.value = it
                        SettingsStore.save()
                        if (!it) Discord.clearPresence()
                    })
            }
            Text("Déconnecter Discord", color = theme.accent, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { Discord.unlink() }.padding(top = 8.dp, bottom = 2.dp))
            Text("Réessayer la connexion", color = theme.accent.copy(alpha = .82f), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !discord.busy) {
                    Discord.connect()
                }.padding(bottom = 4.dp))
        } else if (Discord.available) {
            Text("Utilise l'app Discord installée sur cet appareil. Aucun mot de passe Discord " +
                "n'est demandé ni conservé.", color = theme.secondary, fontSize = 11.5.sp,
                lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Text(if (discord.busy) "Connexion à Discord…" else "Activer via l'app Discord",
                color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !discord.busy) {
                    Discord.connect()
                }.padding(top = 10.dp, bottom = 4.dp))
            Text("Ouvrir l'app Discord", color = theme.accent.copy(alpha = .82f), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { openDiscord() }.padding(bottom = 4.dp))
            Text("Connexion par jeton (avancé)", color = theme.secondary, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(bottom = 4.dp))
            if (advanced) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim().take(512) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    label = { Text("Jeton OAuth2", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp)
                )
                Text("Jeton OAuth2 émis pour l'application Ketvoice. Les jetons d'utilisateur " +
                    "Discord classiques sont refusés par le SDK.",
                    color = theme.secondary, fontSize = 10.5.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 4.dp))
                Text("Connecter avec ce jeton", color = theme.accent, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !discord.busy && token.length >= 20) {
                        Discord.connectWithToken(token)
                        token = ""
                    }.padding(top = 6.dp, bottom = 2.dp))
                Text("Lier mon compte Discord (optionnel)", color = theme.accent.copy(alpha = .82f),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !discord.busy) {
                        Discord.link()
                    }.padding(top = 4.dp, bottom = 2.dp))
            }
        } else {
            Text("Cette version a été compilée sans le SDK Discord.", color = theme.secondary,
                fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }

        discord.error?.let {
            Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
        discord.log?.takeIf { discord.error != null || advanced }?.let {
            Text("Journal SDK · $it", color = theme.secondary, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 13.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
