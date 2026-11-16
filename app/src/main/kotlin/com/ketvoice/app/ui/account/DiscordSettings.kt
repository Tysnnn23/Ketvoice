package com.ketvoice.app.ui.account

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
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
import com.ketvoice.app.theme.Ket27
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

    // Ket 27: a cell of the grouped list — Discord's tile and name, its switch as on iOS, its actions as
    // rows of text in the app's colour, and its notes in the list's footnote style.
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.ketvoice.app.ui.settings.Ket27Tile(com.ketvoice.app.ui.common.KetIcons.Globe, DISCORD_TILE)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
                Text("Discord", color = theme.onBackground, style = Ket27.Type.body)
                Text(
                    when {
                        !Discord.available -> "Indisponible dans cette version"
                        discord.presenceOnly && (discord.presenceActive || discord.ready) ->
                            "Présence active · via l'app Discord"
                        discord.presenceOnly -> "Présence via l'app Discord"
                        discord.busy && discord.linked -> "Compte lié · connexion de la présence…"
                        discord.busy -> "En attente de l'app Discord…"
                        discord.ready -> discord.userName ?: "Présence active"
                        discord.linked -> "Compte lié · présence inactive"
                        else -> "Affiche le morceau en cours sur ton profil"
                    },
                    color = theme.secondary, style = Ket27.Type.footnote
                )
            }
            when {
                discord.busy -> com.ketvoice.app.ui.common.ActivitySpinner(size = 18.dp)
                discord.presenceActive || discord.ready -> Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF34C759)))
                discord.presenceOnly || discord.linked -> Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFFB020)))
                else -> Box(Modifier.size(9.dp).clip(CircleShape).background(theme.secondary.copy(alpha = .45f)))
            }
        }

        if (Discord.available && discord.presenceOnly) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Afficher ce que j'écoute", color = theme.onBackground, style = Ket27.Type.body)
                    Text("Morceau, artiste et boutons sur votre profil Discord.",
                        color = theme.secondary, style = Ket27.Type.footnote)
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
            Text("Sans compte lié : l'app Discord doit être installée, ouverte et connectée. La présence apparaît dès qu'un morceau joue et se retire en pause.",
                color = theme.secondary, style = Ket27.Type.footnote,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            discord.presenceError?.let {
                Text("Discord n'a pas accepté la mise à jour : $it",
                    color = com.ketvoice.app.ui.common.DESTRUCTIVE, style = Ket27.Type.footnote,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            DiscordAction("Réessayer la présence") { Discord.enablePresenceOnly() }
            DiscordAction("Désactiver la présence via l'app Discord") { Discord.disablePresenceOnly() }
        } else if (Discord.available && discord.linked) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Afficher ce que j'écoute", color = theme.onBackground, style = Ket27.Type.body)
                    Text("Morceau, artiste et boutons sur votre profil Discord.",
                        color = theme.secondary, style = Ket27.Type.footnote)
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
            DiscordAction("Déconnecter Discord", destructive = true) { Discord.unlink() }
            if (!discord.ready) {
                Text(
                    if (discord.busy) "La présence s'active dès que Discord répond."
                    else "La présence n'est pas active : ouvre l'app Discord, vérifie que tu y es connecté, puis réessaie.",
                    color = theme.secondary, style = Ket27.Type.footnote,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
                DiscordAction("Vérifier la présence maintenant", enabled = !discord.busy) { Discord.connect() }
                DiscordAction("Utiliser la présence via l'app Discord", primary = true) { Discord.enablePresenceOnly() }
            }
            DiscordAction("Rouvrir la connexion Discord", enabled = !discord.busy) { connectInWebView() }
        } else if (Discord.available) {
            DiscordAction("Afficher via l'app Discord", enabled = !discord.busy, primary = true) { Discord.enablePresenceOnly() }
            Text("Sans lier de compte : l'app Discord doit être installée, ouverte et connectée. La présence est publiée par l'app Discord elle-même (RPC) et ne dépend pas de la connexion au compte.", color = theme.secondary,
                style = Ket27.Type.footnote, modifier = Modifier.padding(bottom = 6.dp))
            DiscordAction("Ou lier mon compte Discord (avancé)", enabled = !discord.busy) { connectInWebView() }
            Text("La connexion s'ouvre dans une fenêtre Ketvoice (WebView) : aucun mot de passe " +
                "Discord n'est demandé ni conservé par l'application.", color = theme.secondary,
                style = Ket27.Type.footnote, modifier = Modifier.padding(bottom = 4.dp))
            DiscordAction("Ouvrir l'app Discord") { openDiscord() }
            Text("OAuth2 · redirect à déclarer : https://ketvoice.netlify.app/discord.html",
                color = theme.secondary, style = Ket27.Type.caption,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
            localError?.let {
                Text(it, color = com.ketvoice.app.ui.common.DESTRUCTIVE, style = Ket27.Type.footnote,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            DiscordAction("Connexion par jeton (avancé)", tint = theme.secondary) { advanced = !advanced }
            if (advanced) {
                Text("Jeton OAuth2", color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 4.dp))
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
                    color = theme.secondary, style = Ket27.Type.caption,
                    modifier = Modifier.padding(top = 4.dp))
                DiscordAction("Connecter avec ce jeton", enabled = !discord.busy && token.length >= 20, primary = true) {
                    Discord.connectWithToken(token)
                    token = ""
                }

            }
        } else {
            Text("Cette version a été compilée sans le SDK Discord.", color = theme.secondary,
                style = Ket27.Type.footnote, modifier = Modifier.padding(top = 8.dp))
        }

        discord.error?.let {
            Text(it, color = com.ketvoice.app.ui.common.DESTRUCTIVE, style = Ket27.Type.footnote,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (Discord.available) {
            DiscordAction(if (diagnostics) "Masquer le diagnostic" else "Diagnostic Discord", tint = theme.secondary) {
                diagnostics = !diagnostics
            }
            if (diagnostics) {
                val report = buildString {
                    appendLine("Ketvoice · Discord")
                    appendLine("lié=" + discord.linked + " prêt=" + discord.ready + " occupé=" + discord.busy)
                    appendLine("mode=" + if (discord.presenceOnly) "app-discord-rpc" else "compte")
                    appendLine("présence=" + discord.presenceActive + " erreur-présence=" + (discord.presenceError ?: "-"))
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
                    com.ketvoice.app.ui.common.KetTextButton("Copier le journal", {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Ketvoice Discord", report))
                        localError = "Journal copié."
                    })
                    com.ketvoice.app.ui.common.KetTextButton("Relancer la connexion", { connectInWebView() }, enabled = !discord.busy)
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

/** Discord's tile in the list: the indigo of iOS 27's tiles (index 4). */
private const val DISCORD_TILE = 4

/**
 * An action of the Discord cell, as an iOS list writes one: a line of text in the app's colour
 * (semibold for the one to take, red for one that undoes, [tint] for quieter ones) with a 44 dp target.
 */
@Composable
private fun DiscordAction(label: String, enabled: Boolean = true, primary: Boolean = false, destructive: Boolean = false,
                          tint: Color? = null, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val color = when {
        destructive -> com.ketvoice.app.ui.common.DESTRUCTIVE
        else -> tint ?: theme.accent
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart) {
        Text(label, color = color.copy(alpha = if (enabled) 1f else .45f), style = Ket27.Type.body,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
    }
}
