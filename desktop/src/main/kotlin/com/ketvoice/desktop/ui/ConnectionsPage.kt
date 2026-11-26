package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.connect.ConnectPrefs
import com.ketvoice.desktop.connect.DiscordPresence
import com.ketvoice.desktop.connect.DiscordStatus
import com.ketvoice.desktop.connect.LastFm
import com.ketvoice.desktop.connect.ListenBrainz
import com.ketvoice.desktop.connect.Scrobbler
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.glass.glassSource
import com.ketvoice.desktop.ui.glass.rememberGlassBackdrop
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Windows 2.0 « Connexions »: the services linked to this PC and the account (Discord's status, Last.fm, ListenBrainz,
 * imports), the account's devices, the live sync, sign-in by QR code, the profiles of this PC and « Mes données ».
 * Registered as the page `connections` (ConnectUi.register) and as a section of Settings.
 */

@Composable
internal fun ConnectionsPage() {
    val account by AccountSession.state.collectAsState()
    Page(null, "connections") {
        item("hero") { ConnectHero(account.signedIn) }
        if (!account.signedIn) item("qr") { QrSignInCard() }
        item("h:services") { SectionHeader("Services") }
        item("discord") { SettingsCard { DiscordCard() } }
        item("gap1") { Gap(12.dp) }
        item("lastfm") { SettingsCard { LastFmCard(account.signedIn) } }
        item("gap2") { Gap(12.dp) }
        item("listenbrainz") { SettingsCard { ListenBrainzCard() } }
        item("gap3") { Gap(12.dp) }
        item("imports") { SettingsCard { ImportRow() } }
        item("h:devices") { SectionHeader("Appareils connectés") }
        item("devices") { DevicesCard(account.signedIn) }
        item("h:profiles") { SectionHeader("Profils sur ce PC") }
        item("profiles") { LocalProfilesCard() }
        item("h:data") { SectionHeader("Vie privée") }
        item("data") { SettingsCard { MyDataRow() } }
    }
}

/** The page's head: the title on clear glass over the accent, the account and the profile in use. */
@Composable
private fun ConnectHero(signedIn: Boolean) {
    val ket = LocalKet.current
    val picture = rememberGlassBackdrop()
    val profile = ProfilesUi.current()
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(196.dp).clip(RoundedCornerShape(Ket27Desk.Radius.card))) {
        Box(Modifier.matchParentSize().glassSource(picture).background(Brush.linearGradient(listOf(ket.accent, ket.accent.copy(alpha = .55f).compositeOver(Color.Black)))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp).widthIn(max = 560.dp)
            .glass(picture, 26.dp, Color.Black.copy(alpha = .14f), GlassStyle.Clear, rim = Color.White.copy(alpha = .3f))
            .padding(horizontal = 24.dp, vertical = 18.dp)) {
            KText(AppText.t("Connexions").uppercase(), Ket27Desk.Type.eyebrow, color = Color.White.copy(alpha = .8f), translate = false)
            Spacer(Modifier.height(4.dp))
            KText("Vos services et vos appareils", Ket27Desk.Type.title1, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileDot(profile, 22.dp)
                Spacer(Modifier.width(8.dp))
                val email = AccountSession.state.collectAsState().value.email
                KText(ProfilesUi.label(profile) + if (signedIn) " · $email" else " · " + AppText.t("Non connecté"), Ket27Desk.Type.subheadline,
                    color = Color.White.copy(alpha = .9f), translate = false)
            }
        }
    }
}

/** A service's head row: its mark, its name, its status (and when it last synced), then its action. */
@Composable
internal fun ServiceRow(mark: String, markColour: Color, name: String, status: String, ok: Boolean, tag: String,
                        action: (@Composable () -> Unit)? = null) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("service:$tag"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(markColour), contentAlignment = Alignment.Center) {
            KText(mark, Ket27Desk.Type.headline, color = Color.White, translate = false, weight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            KText(name, Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Color(0xFF30D158) else ket.tertiary))
                Spacer(Modifier.width(6.dp))
                KText(status, Ket27Desk.Type.footnote, Modifier.testTag("status:$tag"), color = ket.secondary, translate = false, maxLines = 2)
            }
        }
        action?.invoke()
    }
}

/** « Dernier envoi : 14:32 » (or the day before when not today), « Jamais » otherwise. */
internal fun lastSync(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return AppText.t("Jamais synchronisé")
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(at).atZone(zone)
    val sameDay = then.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return AppText.t("Dernier envoi") + " · " + DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "dd/MM HH:mm").format(then)
}

/** « Discord connecté sur Pixel 9 · name », when another device of the account has Discord linked. */
internal fun discordElsewhere(links: List<com.ketvoice.app.sync.DiscordLink>): String? =
    com.ketvoice.app.sync.ConnectionsKind.elsewhere(links)?.let { other ->
        AppText.t("Discord connecté sur {0}").replace("{0}", other.device + (other.user?.let { " · $it" } ?: ""))
    }

@Composable
private fun DiscordCard() {
    val status by DiscordPresence.status.collectAsState()
    val user by DiscordPresence.user.collectAsState()
    val text = when {
        !ConnectPrefs.discord.value -> AppText.t("Désactivé")
        status == DiscordStatus.CONNECTED -> AppText.t("Connecté à Discord") + (user?.let { " · $it" } ?: "")
        else -> AppText.t("En attente de Discord · le statut s'affiche pendant la lecture")
    }
    ServiceRow("D", Color(0xFF5865F2), "Discord", text, status == DiscordStatus.CONNECTED && ConnectPrefs.discord.value, "discord")
    // 9.2: Discord linked on another device of the account (the phone), as that device told it; its tokens stay there.
    val others by com.ketvoice.app.sync.ConnectionsKind.others.collectAsState()
    discordElsewhere(others)?.let { Note(it) }
    Hairline()
    ToggleRow("Statut Discord pendant la lecture", ConnectPrefs.discord.value, "discord-presence") { ConnectPrefs.set(ConnectPrefs.discord, "discord", it) }
    Hairline()
    ToggleRow("Masquer le titre", ConnectPrefs.discordHideTitle.value, "discord-hide") { ConnectPrefs.set(ConnectPrefs.discordHideTitle, "discordHideTitle", it) }
    KText("Discord affiche le titre, l'artiste, la pochette et le temps écoulé, seulement pendant la lecture.", Ket27Desk.Type.footnote,
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = LocalKet.current.secondary, maxLines = 3)
}

@Composable
private fun LastFmCard(signedIn: Boolean) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    val user = ConnectPrefs.lastfmUser.value
    var step by remember { mutableStateOf(0) } // 0 idle, 1 waiting for the listener to allow on last.fm, 2 busy
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(signedIn) { if (signedIn) runCatching { LastFm.refresh() } }
    val status = when {
        !signedIn -> AppText.t("Connectez-vous à votre compte Ketvoice pour lier Last.fm.")
        user.isBlank() -> AppText.t("Non connecté")
        else -> AppText.t("Connecté") + " · $user · " + lastSync(ConnectPrefs.lastfmSyncAt.longValue) + waiting("lastfm")
    }
    ServiceRow("as", Color(0xFFD51007), "Last.fm", status, user.isNotBlank(), "lastfm") {
        if (!signedIn) return@ServiceRow
        when {
            step == 2 -> Spinner()
            user.isNotBlank() -> CapsuleButton("Déconnecter", null, { step = 2; scope.launch { LastFm.disconnect(); step = 0 } }, filled = false,
                accent = ket.accent, modifier = Modifier.testTag("lastfm-disconnect"))
            step == 1 -> CapsuleButton("J'ai autorisé", null, {
                step = 2; error = null
                scope.launch { if (LastFm.complete() == null) { error = "Last.fm n'a pas encore reçu votre autorisation."; step = 1 } else step = 0 }
            }, filled = true, accent = ket.accent, modifier = Modifier.testTag("lastfm-complete"))
            else -> CapsuleButton("Connecter", null, {
                step = 2; error = null
                scope.launch {
                    val url = runCatching { LastFm.begin() }.getOrNull()
                    if (url == null) { error = "Connexion impossible pour le moment."; step = 0 } else { openBrowser(url); step = 1 }
                }
            }, filled = true, accent = ket.accent, modifier = Modifier.testTag("lastfm-connect"))
        }
    }
    if (step == 1) Note("Autorisez Ketvoice sur la page Last.fm qui vient de s'ouvrir, puis revenez ici.")
    error?.let { Note(it, destructiveRed) }
    if (user.isNotBlank()) { Hairline(); ToggleRow("Scrobbler sur Last.fm", ConnectPrefs.lastfmOn.value, "lastfm-on") { ConnectPrefs.set(ConnectPrefs.lastfmOn, "lastfmOn", it) } }
}

@Composable
private fun ListenBrainzCard() {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    val user = ConnectPrefs.listenBrainzUser.value
    val linked = user.isNotBlank() && ConnectPrefs.listenBrainzToken() != null
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val status = if (linked) AppText.t("Connecté") + " · $user · " + lastSync(ConnectPrefs.listenBrainzSyncAt.longValue) + waiting("listenbrainz")
        else AppText.t("Non connecté")
    ServiceRow("LB", Color(0xFF353070), "ListenBrainz", status, linked, "listenbrainz") {
        if (busy) Spinner()
        else if (linked) CapsuleButton("Déconnecter", null, {
            ConnectPrefs.setListenBrainzToken(null); ConnectPrefs.setText(ConnectPrefs.listenBrainzUser, "listenBrainzUser", "")
        }, filled = false, accent = ket.accent, modifier = Modifier.testTag("listenbrainz-disconnect"))
    }
    if (!linked) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            KText("Jeton", Ket27Desk.Type.subheadline, Modifier.width(80.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (token.isEmpty()) KText("Collez votre jeton utilisateur ListenBrainz", Ket27Desk.Type.subheadline, color = ket.tertiary)
                BasicTextField(token, { token = it.filterNot(Char::isWhitespace).take(80) }, Modifier.fillMaxWidth().textEntry().testTag("listenbrainz-token"),
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
                    cursorBrush = SolidColor(ket.accent))
            }
            Spacer(Modifier.width(10.dp))
            CapsuleButton("Connecter", null, {
                if (busy || token.isBlank()) return@CapsuleButton
                busy = true; error = null
                scope.launch {
                    val name = runCatching { ListenBrainz.validate(token) }.getOrNull()
                    if (name == null) error = "Ce jeton n'est pas valide."
                    else { ConnectPrefs.setListenBrainzToken(token); ConnectPrefs.setText(ConnectPrefs.listenBrainzUser, "listenBrainzUser", name); token = "" }
                    busy = false
                }
            }, filled = true, accent = ket.accent, modifier = Modifier.testTag("listenbrainz-connect"))
        }
        Note("Votre jeton se trouve dans les réglages de votre profil ListenBrainz. Il reste chiffré sur ce PC et n'est jamais synchronisé.")
        error?.let { Note(it, destructiveRed) }
    } else {
        Hairline()
        ToggleRow("Scrobbler sur ListenBrainz", ConnectPrefs.listenBrainzOn.value, "listenbrainz-on") { ConnectPrefs.set(ConnectPrefs.listenBrainzOn, "listenBrainzOn", it) }
    }
}

/** « 3 en attente » when listens wait to be sent. */
private fun waiting(service: String): String = Scrobbler.pending(service).takeIf { it > 0 }?.let { " · $it " + AppText.t("en attente") }.orEmpty()

/** Imports have their own page (`import`) when that feature is in this build. */
@Composable
private fun ImportRow() {
    val nav = LocalNavigator.current
    val page = PageRegistry.find(ConnectUi.IMPORT_PAGE)
    ServiceRow("↧", Color(0xFF8E8E93), AppText.t("Importer"), AppText.t(if (page != null) "Vos playlists d'autres services, avec un rapport des titres trouvés"
        else "Bientôt disponible"), page != null, "imports") {
        if (page != null) CapsuleButton("Ouvrir", null, { nav.push(Route.Extra(ConnectUi.IMPORT_PAGE)) }, filled = false,
            accent = LocalKet.current.accent, modifier = Modifier.testTag("imports-open"))
    }
}

@Composable
private fun MyDataRow() {
    val nav = LocalNavigator.current
    ServiceRow("✓", Color(0xFF30D158), AppText.t("Mes données"), AppText.t("Zéro télémétrie · exporter ou supprimer votre compte"), true, "my-data") {
        CapsuleButton("Ouvrir", null, { nav.push(Route.Extra(ConnectUi.MY_DATA_PAGE)) }, filled = false, accent = LocalKet.current.accent,
            modifier = Modifier.testTag("my-data-open"))
    }
}

/** A small explanation under a row. */
@Composable
internal fun Note(text: String, colour: Color = Color.Unspecified) {
    KText(text, Ket27Desk.Type.footnote, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (colour == Color.Unspecified) LocalKet.current.secondary else colour, maxLines = 4)
}

@Composable
internal fun ProfileDot(profile: com.ketvoice.desktop.connect.LocalProfiles.Profile, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(profile.colour)), contentAlignment = Alignment.Center) {
        KText(ProfilesUi.label(profile).take(1).uppercase(), if (size > 30.dp) Ket27Desk.Type.headline else Ket27Desk.Type.caption2,
            color = Color.White, translate = false, weight = FontWeight.Bold)
    }
}

/** Opens [url] in the listener's browser. */
internal fun openBrowser(url: String) {
    runCatching {
        val uri = java.net.URI(url)
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) java.awt.Desktop.getDesktop().browse(uri)
        else ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start()
    }
}

/** A small arrangement helper so rows of buttons wrap their spacing the same way everywhere here. */
internal val ButtonGap = Arrangement.spacedBy(8.dp)
