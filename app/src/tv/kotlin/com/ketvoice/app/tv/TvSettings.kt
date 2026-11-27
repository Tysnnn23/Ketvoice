package com.ketvoice.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.launch

/** A choice of a setting: the value stored and its label (French source text). */
data class TvOption(val id: Int, val label: String)

/**
 * Settings, the ones that matter on a TV: the account (sign in with the phone, sign out), Ketmix (switch, intensity,
 * style, analysis, « Ketmix apprend », albums), playback quality, lyrics, display, about. They are the phone's own
 * settings: the synced ones follow the account, the others (analysis, performance) stay on this TV.
 */
@Composable
fun TvSettingsPage() {
    TvPivot(pivot = .3f) {
        LazyColumn(Modifier.fillMaxSize().testTag("tv-settings"), contentPadding = PaddingValues(top = TvDesign.Safe.vertical, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "title") { TvPageTitle("Réglages", Modifier.padding(bottom = 10.dp)) }
            item(key = "account") { AccountSection() }
            item(key = "profiles") { TvProfilesSection() }
            section("Ketmix") { KetmixSection() }
            section("Lecture") { PlaybackSection() }
            section("Paroles") { LyricsSection() }
            section("Affichage") { DisplaySection() }
            section("Écran de veille et accueil") { TvSystemSection() }
            section("À propos") { AboutSection() }
        }
    }
}

private fun LazyListScope.section(title: String, content: @Composable () -> Unit) {
    item(key = "h:$title") {
        Text(title, style = TvDesign.Type.row, color = TvDesign.Ink.primary,
            modifier = Modifier.padding(start = TvDesign.Safe.horizontal, top = 22.dp, bottom = 4.dp))
    }
    item(key = "s:$title") { Column(Modifier.padding(horizontal = TvDesign.Safe.horizontal).widthIn(max = 720.dp)) { content() } }
}

@Composable
private fun AccountSection() {
    val account by AccountSession.state.collectAsState()
    val profile by com.ketvoice.app.account.Profiles.profile.collectAsState()
    val actions = LocalTvActions.current
    val scope = rememberCoroutineScope()
    Row(Modifier.padding(horizontal = TvDesign.Safe.horizontal).widthIn(max = 720.dp).fillMaxWidth()
        .clip(RoundedCornerShape(TvDesign.Radius.panel)).background(TvDesign.Ink.glass).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Text((profile.displayName.ifBlank { account.email }.firstOrNull() ?: 'K').uppercase(), localize = false,
                style = TvDesign.Type.row, color = TvDesign.Ink.primary)
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            if (account.signedIn) {
                Text(profile.displayName.ifBlank { "Compte Ketvoice" }, localize = profile.displayName.isBlank(), style = TvDesign.Type.row,
                    color = TvDesign.Ink.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(account.email, localize = false, style = TvDesign.Type.caption, color = TvDesign.Ink.secondary, maxLines = 1,
                    modifier = Modifier.testTag("account-email"))
            } else {
                Text("Compte Ketvoice", style = TvDesign.Type.row, color = TvDesign.Ink.primary)
                Text(account.message ?: "Retrouvez vos favoris, playlists et écoutes du téléphone.", style = TvDesign.Type.caption,
                    color = TvDesign.Ink.secondary, maxLines = 2)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (account.signedIn) TvButton("Se déconnecter", null, { scope.launch { runCatching { AccountSession.signOut() } } },
            Modifier.pageItem("account", "sign-out", first = true), tag = "account-sign-out")
        else TvButton("Se connecter avec le téléphone", null, { actions.nav.open(TvRoute.SignIn) },
            Modifier.pageItem("account", "sign-in", first = true), primary = true, tag = "account-sign-in")
    }
}

@Composable
private fun KetmixSection() {
    TvToggle("Ketmix", "Les titres s'enchaînent comme dans un mix de DJ : tempos accordés, basses échangées, le chant jamais coupé.",
        AppSettings.automix.value, "ketmix") { AppSettings.automix.value = it; SettingsStore.save() }
    TvChoice("Intensité du mix", "Longueur des transitions et écart de tempo accepté", AppSettings.automixIntensity.value,
        listOf(TvOption(0, "Discret"), TvOption(1, "Équilibré"), TvOption(2, "DJ")), "ketmix-intensity") {
        AppSettings.automixIntensity.value = it; SettingsStore.save()
    }
    TvChoice("Style de transition", "Comment un titre passe au suivant", AppSettings.ketmixStyle.value, KETMIX_STYLES, "ketmix-style") {
        AppSettings.ketmixStyle.value = it; SettingsStore.save()
    }
    TvChoice("Analyse des titres", "Part du processeur du téléviseur consacrée à l'écoute des titres avant un mix",
        AppSettings.ketmixAnalysis.value, listOf(TvOption(0, "Légère"), TvOption(1, "Équilibrée"), TvOption(2, "Maximale")), "ketmix-analysis") {
        AppSettings.ketmixAnalysis.value = it; SettingsStore.save()
    }
    TvRhythmModelLine()
    TvToggle("Ketmix apprend de vos avis", "Après chaque transition, 👍 ou 👎 sur l'écran En cours. Tout reste sur ce téléviseur.",
        AppSettings.ketmixLearns.value, "ketmix-learns") { AppSettings.ketmixLearns.value = it; SettingsStore.save() }
    TvToggle("Mixer aussi les albums", "Les titres d'un album écouté dans l'ordre sont mixés aussi, sauf si l'album n'a aucun blanc entre ses titres.",
        AppSettings.automixAlbums.value, "ketmix-albums") { AppSettings.automixAlbums.value = it; SettingsStore.save() }
}

/** The ten styles of the phone's setting, in their stored order. */
val KETMIX_STYLES = listOf(TvOption(0, "Automatique"), TvOption(1, "Calé sur le tempo"), TvOption(2, "Écho"), TvOption(3, "Filtres"),
    TvOption(4, "Fondu"), TvOption(5, "Montée"), TvOption(6, "Coupe"), TvOption(7, "Sur le drop"), TvOption(8, "Relais des voix"),
    TvOption(9, "Pont en boucle"))

/** The stream qualities of the phone, stored by their French names. */
private val QUALITIES = listOf("Sans perte", "Élevé", "Normal")

@Composable
private fun PlaybackSection() {
    val quality = QUALITIES.indexOf(AppSettings.wifiQuality.value).coerceAtLeast(0)
    TvChoice("Qualité audio", "Sans perte = FLAC, sinon MP3 rapide", quality, QUALITIES.mapIndexed(::TvOption), "quality") {
        AppSettings.wifiQuality.value = QUALITIES[it]; SettingsStore.save()
    }
    TvToggle("Normaliser le volume", "Tous les titres à un volume perçu proche, sans saut d'une chanson à l'autre",
        AppSettings.volumeLeveling.value, "leveling") { AppSettings.volumeLeveling.value = it; SettingsStore.save() }
    TvToggle("Ignorer les silences", "Saute les passages silencieux (moteur audio)", AppSettings.skipSilence.value, "skip-silence") {
        Player.setSkipSilence(it); SettingsStore.save()
    }
}

@Composable
private fun LyricsSection() {
    TvChoice("Taille des paroles", "Lisibilité depuis le canapé", AppSettings.lyricsTextSize.value.coerceIn(0, 2),
        listOf(TvOption(0, "Standard"), TvOption(1, "Grand"), TvOption(2, "Très grand")), "lyrics-size") {
        AppSettings.lyricsTextSize.value = it; SettingsStore.save()
    }
    if (com.ketvoice.app.ui.player.LyricsBlur.deviceSupported()) TvToggle("Flou des paroles",
        "Les lignes éloignées de celle chantée s'estompent dans un léger flou.", AppSettings.lyricsBlur.value, "lyrics-blur") {
        AppSettings.lyricsBlur.value = it; SettingsStore.save()
    }
}

@Composable
private fun DisplaySection() {
    TvToggle("Activer les Canvas vidéo", "La vidéo du titre en plein écran derrière l'écran En cours.", AppSettings.animatedArtwork.value, "canvas") {
        AppSettings.animatedArtwork.value = it; SettingsStore.save()
    }
    TvToggle("Mode performance", "Garde les effets visibles et allège le reste : conseillé sur les téléviseurs modestes.",
        AppSettings.highPerformanceMode.value, "performance") { AppSettings.highPerformanceMode.value = it; SettingsStore.save() }
    TvToggle("Animations réduites", "Atténue les mouvements décoratifs, sans désactiver le karaoké", AppSettings.reduceAnimations.value, "reduce-motion") {
        AppSettings.reduceAnimations.value = it; SettingsStore.save()
    }
}

@Composable
private fun AboutSection() {
    TvInfoRow("Version", "${com.ketvoice.app.BuildConfig.VERSION_NAME} · TV", localizeValue = false)
    TvInfoRow("Ce téléviseur", TvCapability.about(tvTier()), localizeValue = true)
}

/** A switch: the whole line is the button; « Activé » / « Désactivé » on the right. */
@Composable
fun TvToggle(title: String, subtitle: String, value: Boolean, key: String, onChange: (Boolean) -> Unit) {
    TvSettingLine(title, subtitle, if (value) "Activé" else "Désactivé", key, onClick = { onChange(!value) }, on = value)
}

/** A choice: the line shows the current option; select opens the options under it, select again picks one. */
@Composable
fun TvChoice(title: String, subtitle: String, value: Int, options: List<TvOption>, key: String, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TvSettingLine(title, subtitle, options.firstOrNull { it.id == value }?.label ?: "", key, onClick = { open = !open })
        if (open) Column(Modifier.padding(start = 24.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { option ->
                        TvButton(option.label, null, { onPick(option.id); open = false }, selected = option.id == value,
                            tag = "$key-${option.id}")
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvSettingLine(title: String, subtitle: String, value: String, key: String, onClick: () -> Unit, on: Boolean? = null,
                           localizeSubtitle: Boolean = true, localizeValue: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.pageItem("settings", key).tvFocusable(onClick, shape, scale = 1.02f, edge = 2.dp, tag = "setting-$key")
        .fillMaxWidth().clip(shape).background(Color.White.copy(alpha = .06f)).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TvDesign.Type.label, color = TvDesign.Ink.primary)
            Text(subtitle, localize = localizeSubtitle, style = TvDesign.Type.caption, color = TvDesign.Ink.secondary, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        Text(value, localize = localizeValue, style = TvDesign.Type.label,
            color = if (on == true) com.ketvoice.app.theme.LocalTheme.current.accent else TvDesign.Ink.secondary)
    }
}

@Composable
private fun TvInfoRow(title: String, value: String, localizeValue: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = TvDesign.Type.label, color = TvDesign.Ink.primary, modifier = Modifier.width(200.dp))
        Text(value, localize = localizeValue, style = TvDesign.Type.body, color = TvDesign.Ink.secondary)
    }
}
