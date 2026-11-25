package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.desktop.connect.LocalProfiles
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/** The profiles of this PC as the window shows them (re-read after each change). */
internal object ProfilesUi {
    val version = mutableStateOf(0)

    private var cached: Pair<Int, LocalProfiles.Book>? = null

    fun book(): LocalProfiles.Book {
        val v = version.value
        cached?.takeIf { it.first == v }?.let { return it.second }
        return LocalProfiles.load().also { cached = v to it }
    }

    fun current(): LocalProfiles.Profile = book().profiles.firstOrNull { it.id == LocalProfiles.current } ?: book().profiles.first()

    /** The main profile's default name is translated; names the listener gave are shown as they are. */
    fun label(p: LocalProfiles.Profile): String = if (p.id == LocalProfiles.MAIN && p.name == "Principal") AppText.t("Principal") else p.name

    fun changed() { version.value++ }
}

/**
 * « Profils sur ce PC »: each profile has its own settings, account, library and caches (the audio cache is shared).
 * Changing profile restarts Ketvoice on it in a second or two.
 */
@Composable
internal fun LocalProfilesCard() {
    val book = ProfilesUi.book()
    var adding by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<LocalProfiles.Profile?>(null) }
    SettingsCard {
        book.profiles.forEachIndexed { i, p ->
            if (i > 0) Hairline()
            ProfileRow(p, active = p.id == LocalProfiles.current, onSwitch = {
                LocalProfiles.switchTo(p.id, com.ketvoice.desktop.update.Updates.quit)
            }, onDelete = { confirm = p })
        }
        Hairline()
        if (adding) NewProfile(onDone = { adding = false; ProfilesUi.changed() })
        else Row(Modifier.fillMaxWidth().height(44.dp).clickable { adding = true }.padding(horizontal = 16.dp).testTag("profile-add"),
            verticalAlignment = Alignment.CenterVertically) {
            KText("Ajouter un profil", Ket27Desk.Type.subheadline, color = LocalKet.current.accent, weight = FontWeight.Medium)
        }
        Note("Chaque profil a ses réglages, son compte, sa bibliothèque et ses caches. Le cache audio est partagé.")
    }
    confirm?.let { p ->
        ConfirmDialog(AppText.t("Supprimer ce profil ?"), AppText.t("Ses réglages, sa bibliothèque et ses téléchargements sur ce PC seront effacés."),
            "Supprimer", onConfirm = { LocalProfiles.delete(p.id); ProfilesUi.changed() }, onDismiss = { confirm = null })
    }
}

@Composable
private fun ProfileRow(p: LocalProfiles.Profile, active: Boolean, onSwitch: () -> Unit, onDelete: () -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("profile:${p.id}"), verticalAlignment = Alignment.CenterVertically) {
        ProfileDot(p, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            KText(ProfilesUi.label(p), Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
            if (active) KText("Profil utilisé", Ket27Desk.Type.footnote, color = ket.secondary)
        }
        if (!active) Row(horizontalArrangement = ButtonGap, verticalAlignment = Alignment.CenterVertically) {
            CapsuleButton("Utiliser", null, onSwitch, filled = false, accent = ket.accent, modifier = Modifier.testTag("profile-use:${p.id}"))
            if (p.id != LocalProfiles.MAIN) KetIconButton(com.ketvoice.app.ui.common.KetIcons.Trash, "Supprimer", onDelete,
                modifier = Modifier.testTag("profile-delete:${p.id}"))
        }
    }
}

@Composable
private fun NewProfile(onDone: () -> Unit) {
    val ket = LocalKet.current
    var name by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf(LocalProfiles.COLOURS[1]) }
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("profile-new")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KText("Nom", Ket27Desk.Type.subheadline, Modifier.width(80.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) KText("Nom du profil", Ket27Desk.Type.subheadline, color = ket.tertiary)
                BasicTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth().textEntry().testTag("profile-name"), singleLine = true,
                    textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
                    cursorBrush = SolidColor(ket.accent))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LocalProfiles.COLOURS.forEach { c ->
                Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                    .border(2.dp, if (c == colour) ket.label else Color.Transparent, CircleShape).clickable { colour = c }.testTag("profile-colour:$c"))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = ButtonGap) {
            CapsuleButton("Créer", null, { if (name.isNotBlank()) { LocalProfiles.create(name, colour); onDone() } }, filled = true,
                accent = ket.accent, modifier = Modifier.testTag("profile-create"))
            KetTextLink("Annuler", "profile-cancel", onDone)
        }
    }
}
