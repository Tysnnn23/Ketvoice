package com.ketvoice.app.ui.account

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.discord.Discord
import com.ketvoice.app.discord.DiscordOAuth
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.LiquidGlassSwitch

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
    var diagnostics by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    // Discord does not always expose a plain launch intent (Android 11+, work
    // profiles, non-standard installs). Try the launcher, then its scheme, then a
    // web hand-off, and only report a failure if every route failed.
    fun launchDiscord(): Boolean {
        val pm = context.packageManager
        val candidates = listOfNotNull(
            pm.getLaunchIntentForPackage("com.discord"),
            pm.getLeanbackLaunchIntentForPackage("com.discord"),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("discord://")).setPackage("com.discord"),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://discord.com/app"))
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    fun openDiscord() {
        if (launchDiscord()) {
            localError = null
        } else {
            localError = "Impossible d'ouvrir Discord sur cet appareil."
        }
    }

    /**
     * The account is authorised inside Ketvoice's own WebView. The native SDK
     * connect() path relied on Discord being installed and reaching IPC, which
     * failed on most devices, so the WebView link is now the only account path.
     */
    fun connectInWebView() {
        if (discord.busy) return
        localError = null
        if (!Discord.available) {
            localError = "Cette version a été compilée sans le module Discord."
            return
        }
        Discord.link()
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Discord", color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !Discord.available -> "Indisponible dans cette version"
                        discord.busy && discord.linked -> "Compte lié · connexion de la présence…"
                        discord.busy -> "En attente de l'app Discord…"
                        discord.ready -> discord.userName ?: "Présence active"
                        discord.linked -> "Compte lié · présence inactive"
                        else -> "Affiche le morceau en cours sur ton profil"
                    },
                    color = theme.secondary, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
            when {
                discord.busy -> CircularProgressIndicator(Modifier.size(18.dp), color = theme.accent, strokeWidth = 2.dp)
                discord.ready -> Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF34C759)))
                discord.linked -> Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFFB020)))
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
                val label = com.ketvoice.app.data.AppText.t("Afficher ce que j'écoute")
                LiquidGlassSwitch(
                    checked = AppSettings.discordPresence.value,
                    onCheckedChange = {
                        AppSettings.discordPresence.value = it
                        SettingsStore.save()
                        if (!it) Discord.clearPresence()
                    }, modifier = Modifier.semantics { contentDescription = label })
            }
            Text("Déconnecter Discord", color = theme.accent, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { Discord.unlink() }.padding(top = 8.dp, bottom = 2.dp))
            if (!discord.ready) {
                Text(
                    if (discord.busy) "La présence s'active dès que Discord répond."
                    else "La présence n'est pas active : ouvre l'app Discord, vérifie que tu y es " +
                        "connecté, puis réessaie.",
                    color = theme.secondary, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
                Text("Vérifier la présence maintenant", color = theme.accent.copy(alpha = .82f), fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !discord.busy) {
                        Discord.onForeground()
                    }.padding(bottom = 4.dp))
            }
            Text("Rouvrir la connexion Discord", color = theme.accent.copy(alpha = .82f), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !discord.busy) {
                    connectInWebView()
                }.padding(bottom = 4.dp))
        } else if (Discord.available) {
            Text("Se connecter avec Discord", color = theme.accent, fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !discord.busy) {
                    connectInWebView()
                }.padding(top = 10.dp, bottom = 4.dp))
            Text("La connexion s'ouvre dans une fenêtre Ketvoice (WebView) : aucun mot de passe " +
                "Discord n'est demandé ni conservé par l'application.", color = theme.secondary,
                fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
            Text("Ouvrir l'app Discord", color = theme.accent.copy(alpha = .82f), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { openDiscord() }.padding(bottom = 4.dp))
            Text("OAuth2 · redirect à déclarer : https://ketvoice.netlify.app/discord.html",
                color = theme.secondary, fontSize = 10.sp, lineHeight = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
            localError?.let {
                Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            Text("Connexion par jeton (avancé)", color = theme.secondary, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(bottom = 4.dp))
            if (advanced) {
                Text("Jeton OAuth2", color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                val label = com.ketvoice.app.data.AppText.t("Jeton OAuth2")
                BasicTextField(
                    value = token,
                    onValueChange = { token = it.trim().take(512) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = label }
                        .clip(RoundedCornerShape(12.dp)).background(theme.onBackground.copy(alpha = .07f))
                        .heightIn(min = 48.dp).padding(12.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = theme.onBackground, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(theme.accent),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
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

            }
        } else {
            Text("Cette version a été compilée sans le SDK Discord.", color = theme.secondary,
                fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }

        discord.error?.let {
            Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (Discord.available) {
            Text(if (diagnostics) "Masquer le diagnostic" else "Diagnostic Discord",
                color = theme.secondary, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { diagnostics = !diagnostics }
                    .padding(top = 10.dp, bottom = 4.dp))
            if (diagnostics) {
                val report = buildString {
                    appendLine("Ketvoice · Discord")
                    appendLine("lié=" + discord.linked + " prêt=" + discord.ready + " occupé=" + discord.busy)
                    appendLine("authentifié=" + Discord.authenticated())
                    appendLine("utilisateur=" + (discord.userName ?: "-"))
                    appendLine("erreur=" + (discord.error ?: "-"))
                    appendLine("journal=" + (discord.log ?: "-"))
                    appendLine("redirect=" + DiscordOAuth.REDIRECT_URI)
                    append("scope=" + DiscordOAuth.SCOPES)
                }
                Text(report, color = theme.secondary, fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(theme.card).padding(12.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Copier le journal", color = theme.accent, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Ketvoice Discord", report))
                            localError = "Journal copié."
                        }.padding(vertical = 4.dp))
                    Text("Relancer la connexion", color = theme.accent, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(enabled = !discord.busy) { connectInWebView() }
                            .padding(vertical = 4.dp))
                }
            }
        }
        discord.log?.takeIf { discord.error != null || advanced }?.let {
            Text("Journal SDK · $it", color = theme.secondary, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 13.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
