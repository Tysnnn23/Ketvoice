package com.ketvoice.app.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountProfile
import com.ketvoice.app.account.Profiles
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.DESTRUCTIVE
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.LiquidGlassSwitch
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.launch

/**
 * The public profile's switches and the search by handle, in the account sheet: « Profil public » and
 * « Montrer ce que j'écoute » (both off by default; the second only means something once the first is on).
 */
@Composable
internal fun PublicProfileSettings(modifier: Modifier = Modifier) {
    val profile by Profiles.profile.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(isPublic: Boolean, listening: Boolean) {
        error = null
        scope.launch { runCatching { Profiles.setVisibility(isPublic, listening) }.onFailure { error = it.message ?: "Enregistrement impossible pour le moment." } }
    }
    PublicProfileSwitches(profile, error, modifier, onPublic = { save(it, profile.showListening && it) }, onListening = { save(true, it) })
    ProfileSearch(Modifier.testTag("profile-search"))
}

/** The two switches and their explanations (stateless: drawn by host tests as well). */
@Composable
internal fun PublicProfileSwitches(profile: AccountProfile, error: String?, modifier: Modifier = Modifier,
                                   onPublic: (Boolean) -> Unit, onListening: (Boolean) -> Unit) {
    val theme = LocalTheme.current
    Column(modifier.fillMaxWidth().padding(top = 14.dp).testTag("public-profile-settings")) {
        SwitchRow("Profil public", "Les autres peuvent ouvrir votre profil avec votre nom d'utilisateur : photo, nom et playlists publiques.",
            profile.isPublic, true, onPublic, Modifier.testTag("switch-public-profile"))
        SwitchRow("Montrer ce que j'écoute", "Votre profil affiche le titre en cours, jamais votre adresse e-mail.",
            profile.showListening && profile.isPublic, profile.isPublic, onListening, Modifier.testTag("switch-show-listening"))
        if (profile.isPublic && profile.handle.isNotBlank()) {
            KetTextButton("Voir mon profil", { ProfileUi.open(profile.handle) }, Modifier.testTag("see-my-profile"))
        }
        error?.let { Text(it, color = DESTRUCTIVE, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 4.dp)) }
        if (!profile.isPublic) Text("Choisissez un nom d'utilisateur dans « Modifier le profil » avant de rendre votre profil public.",
            color = theme.secondary, style = Ket27.Type.caption, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val theme = LocalTheme.current
    val label = AppText.t(title)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) theme.onBackground else theme.secondary, style = Ket27.Type.body)
            Text(sub, color = theme.secondary, style = Ket27.Type.footnote)
        }
        LiquidGlassSwitch(checked, if (enabled) onChange else null, modifier.semantics { contentDescription = label })
    }
}

/** « Rechercher un profil »: opens the page of a handle (it says so when there is none, or when it is private). */
@Composable
private fun ProfileSearch(modifier: Modifier = Modifier) {
    var handle by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        KetTextField(handle, { handle = it.take(25) }, "Rechercher un profil", Modifier.fillMaxWidth().testTag("profile-search-field"))
        KetTextButton("Ouvrir le profil", { ProfileUi.open(handle) }, Modifier.testTag("profile-search-open"), primary = true,
            enabled = handle.trim().removePrefix("@").length >= 3)
    }
}
