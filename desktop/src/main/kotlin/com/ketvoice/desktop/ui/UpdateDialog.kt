package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ketvoice.app.data.AppText
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import com.ketvoice.desktop.update.ReleaseNotes
import com.ketvoice.desktop.update.UpdateState
import com.ketvoice.desktop.update.WindowsRelease
import com.ketvoice.desktop.update.WindowsUpdater
import kotlinx.coroutines.launch

/**
 * The update alert: the release's English notes as written, then « Plus tard » (this version stays quiet until a newer one)
 * or « Installer » (download, SHA-256 check, msiexec, quit). Progress and failures show in place, in plain words.
 */
@Composable
fun UpdateDialog(release: WindowsRelease, updater: WindowsUpdater, onClose: () -> Unit) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsState()
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    val later = { if (!busy) { updater.dismiss(release); onClose() } }
    Popup(alignment = Alignment.Center, onDismissRequest = later, properties = PopupProperties(focusable = true),
        onKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { later(); true } else false }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (ket.dark) .45f else .25f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = later), contentAlignment = Alignment.Center) {
            Column(Modifier.width(440.dp).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(ket.raised)
                .clickable(remember { MutableInteractionSource() }, null) {}.padding(20.dp).testTag("update-dialog")) {
                KText("Mise à jour disponible", Ket27Desk.Type.headline, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                KText(release.name, Ket27Desk.Type.footnote, color = ket.secondary, translate = false, weight = FontWeight.Normal)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(10.dp)).background(ket.card)
                    .verticalScroll(rememberScrollState()).padding(14.dp).testTag("update-notes"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NotesText(release.notes.ifBlank { "Ketvoice for Windows ${release.version}" })
                }
                Spacer(Modifier.height(14.dp))
                when (val s = state) {
                    is UpdateState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.fraction != null) ProgressRing(s.fraction, size = 16.dp) else Spinner()
                        Spacer(Modifier.width(8.dp))
                        KText("Téléchargement…", Ket27Desk.Type.footnote, color = ket.secondary, modifier = Modifier.testTag("update-progress"))
                    }
                    is UpdateState.Installing -> KText("Ouverture de l'installateur…", Ket27Desk.Type.footnote, color = ket.secondary)
                    is UpdateState.Failed -> if (s.release != null) {
                        androidx.compose.material3.Text(s.message, Modifier.testTag("update-error"), color = destructiveRed, style = Ket27Desk.Type.footnote)
                    }
                    else -> {}
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UpdateButton("Plus tard", ket.label, !busy, Modifier.weight(1f).testTag("update-later"), later)
                    UpdateButton(if (state is UpdateState.Failed && (state as UpdateState.Failed).release != null) "Réessayer" else "Installer",
                        ket.accent, !busy, Modifier.weight(1f).testTag("update-install")) { scope.launch { updater.install(release) } }
                }
            }
        }
    }
}

@Composable
private fun NotesText(markdown: String) {
    val ket = LocalKet.current
    for (block in ReleaseNotes.blocks(markdown)) {
        val (plain, bold, code) = ReleaseNotes.spans(block.text)
        val text = buildAnnotatedString {
            if (block is ReleaseNotes.Bullet) append("•  ")
            val offset = length
            append(plain)
            bold.forEach { addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), it.first + offset, it.last + 1 + offset) }
            code.forEach { addStyle(SpanStyle(fontFamily = FontFamily.Monospace), it.first + offset, it.last + 1 + offset) }
        }
        val style = when (block) {
            is ReleaseNotes.Heading -> if (block.level <= 2) Ket27Desk.Type.headline else Ket27Desk.Type.subheadline
            else -> Ket27Desk.Type.footnote
        }
        val pad = if (block is ReleaseNotes.Bullet) (block.depth * 14).dp else 0.dp
        androidx.compose.material3.Text(text, Modifier.padding(start = pad, top = if (block is ReleaseNotes.Heading) 4.dp else 0.dp),
            color = if (block is ReleaseNotes.Heading) ket.label else ket.secondary,
            style = if (block is ReleaseNotes.Heading) style.copy(fontWeight = FontWeight.SemiBold) else style)
    }
}

@Composable
private fun UpdateButton(label: String, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ket = LocalKet.current
    Box(modifier.height(34.dp).clip(RoundedCornerShape(9.dp)).background(ket.fill).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        KText(label, Ket27Desk.Type.subheadline, color = if (enabled) color else ket.secondary, weight = FontWeight.SemiBold)
    }
}

/** Settings › À propos: what the last check found; a click checks now, or opens the update when there is one. */
@Composable
fun UpdateRow(updater: WindowsUpdater, onOpen: (WindowsRelease) -> Unit) {
    val ket = LocalKet.current
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsState()
    val status = when (val s = state) {
        UpdateState.Idle -> AppText.t("Vérifier maintenant")
        UpdateState.Checking -> AppText.t("Recherche…")
        UpdateState.UpToDate -> AppText.t("À jour")
        is UpdateState.Available -> "${s.release.version}"
        is UpdateState.Downloading, is UpdateState.Installing -> AppText.t("Téléchargement…")
        is UpdateState.Failed -> AppText.t("Échec")
    }
    Column {
        Row(Modifier.fillMaxWidth().height(44.dp).clickable {
            when (val s = state) {
                is UpdateState.Available -> onOpen(s.release)
                is UpdateState.Downloading -> onOpen(s.release)
                is UpdateState.Installing -> onOpen(s.release)
                is UpdateState.Failed -> s.release?.let(onOpen) ?: scope.launch { updater.check(force = true) }
                UpdateState.Checking -> {}
                else -> scope.launch { updater.check(force = true) }
            }
        }.padding(horizontal = 16.dp).testTag("update-row"), verticalAlignment = Alignment.CenterVertically) {
            KText("Mises à jour", Ket27Desk.Type.subheadline, Modifier.weight(1f))
            KText(status, Ket27Desk.Type.subheadline, color = if (state is UpdateState.Available) ket.accent else ket.secondary,
                translate = false, weight = FontWeight.Normal, modifier = Modifier.testTag("update-status"))
        }
        (state as? UpdateState.Failed)?.takeIf { it.release == null }?.let {
            androidx.compose.material3.Text(it.message, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).testTag("update-row-error"),
                color = ket.secondary, style = Ket27Desk.Type.footnote)
        }
    }
}
