package com.ketvoice.desktop.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.social.ShareUrls
import com.ketvoice.app.together.Member
import com.ketvoice.app.together.Policy
import com.ketvoice.app.together.RoomCode
import com.ketvoice.app.together.TogetherCodec
import com.ketvoice.app.together.TogetherPhase
import com.ketvoice.app.together.TogetherRole
import com.ketvoice.app.together.TogetherUi
import com.ketvoice.app.ui.together.KetogetherLook
import com.ketvoice.desktop.ui.Artwork
import com.ketvoice.desktop.ui.CapsuleButton
import com.ketvoice.desktop.ui.ChoiceRow
import com.ketvoice.desktop.ui.Hairline
import com.ketvoice.desktop.ui.KText
import com.ketvoice.desktop.ui.SettingsCard
import com.ketvoice.desktop.ui.ToggleRow
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/**
 * « Ensemble », the right panel's listening-room tab: create a room or join one by its code or link; once in, the
 * code and its link, what the host plays, a few quick reactions, who is there and, for the host, the 8.4 controls
 * (who drives, who adds, how many, proposals to accept, someone to take out).
 */
@Composable
internal fun TogetherPanel() {
    val ui by DesktopTogether.ui.collectAsState()
    val invitation by DesktopTogether.invitation.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("panel:together"), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            ui.active -> live(ui)
            ui.phase == TogetherPhase.Ended -> item("ended") { Ended(ui) }
            else -> item("start") { Start(invitation) }
        }
    }
}

@Composable
private fun Start(invitation: com.ketvoice.app.together.TogetherLink.Target?) {
    val ket = LocalKet.current
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (invitation != null) PanelCard(Modifier.testTag("together-invitation")) {
            KText("Invitation à écouter ensemble", Ket27Desk.Type.headline)
            KText(AppText.t("Salle") + " " + RoomCode.display(invitation.code), Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPill("Rejoindre", "together-accept-invitation", filled = true) { DesktopTogether.join(invitation.code, fingerprint = invitation.fingerprint) }
                SmallPill("Ignorer", "together-ignore-invitation") { DesktopTogether.invitation.value = null }
            }
        }
        PanelCard {
            KText("Écouter ensemble", Ket27Desk.Type.title3)
            Spacer(Modifier.height(4.dp))
            KText("La même musique au même moment, sur les téléphones et les PC de la salle. Chacun l'écoute par ses propres sources.",
                Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 4)
            Spacer(Modifier.height(12.dp))
            CapsuleButton("Créer une salle", SocialGlyphs.Together, { DesktopTogether.create() }, filled = true, accent = ket.accent,
                modifier = Modifier.testTag("together-create"))
        }
        PanelCard {
            KText("Rejoindre une salle", Ket27Desk.Type.headline)
            Spacer(Modifier.height(8.dp))
            FieldLine(typed, "Code ou lien de la salle", "together-code", 300, onDone = { wrong = !DesktopTogether.joinTyped(typed) }) { typed = it; wrong = false }
            Spacer(Modifier.height(8.dp))
            SmallPill("Rejoindre", "together-join", filled = true) { wrong = !DesktopTogether.joinTyped(typed) }
            if (wrong) NoticeLine("Ce n'est pas un code de salle")
        }
    }
}

private fun LazyListScope.live(ui: TogetherUi) {
    item("code") { RoomHeader(ui) }
    item("now") { NowInRoom(ui) }
    item("react") { Reactions(ui) }
    if (ui.role == TogetherRole.Host && ui.pending.isNotEmpty()) item("pending") { Pending(ui) }
    item("members") { Members(ui) }
    if (ui.role == TogetherRole.Host) item("rules") { HostRules(ui.policy) }
    if (ui.log.isNotEmpty()) item("log") { Activity(ui) }
    item("leave") {
        val ket = LocalKet.current
        CapsuleButton(if (ui.role == TogetherRole.Host) "Terminer la salle" else "Quitter la salle", null, { DesktopTogether.leave() },
            filled = false, accent = ket.accent, modifier = Modifier.testTag("together-leave"))
    }
}

@Composable
private fun RoomHeader(ui: TogetherUi) {
    val ket = LocalKet.current
    var copied by remember { mutableStateOf(false) }
    PanelCard(Modifier.testTag("together-room")) {
        KText(phaseLabel(ui), Ket27Desk.Type.eyebrow, color = ket.secondary, translate = false)
        KText(RoomCode.display(ui.code), Ket27Desk.Type.largeTitle.copy(letterSpacing = 4.sp), translate = false)
        NoticeLine(ui.notice)
        if (ui.fingerprint.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallPill(if (copied) "Lien copié" else "Copier le lien", "together-copy") {
                    ShareLinks.copy(ShareUrls.room(ui.code, ui.fingerprint)); copied = true
                }
                KText("Le lien ou le code suffisent pour rejoindre", Ket27Desk.Type.caption, color = ket.tertiary, maxLines = 2)
            }
        }
    }
}

private fun phaseLabel(ui: TogetherUi): String = when (ui.phase) {
    TogetherPhase.Connecting -> AppText.t("Connexion…")
    TogetherPhase.Reconnecting -> AppText.t("Reconnexion…")
    else -> if (ui.role == TogetherRole.Host) AppText.t("Vous êtes l'hôte") else if (ui.hostName.isBlank()) AppText.t("Avec l'hôte")
        else AppText.t("Avec {0}").replace("{0}", ui.hostName)
}

@Composable
private fun NowInRoom(ui: TogetherUi) {
    val ket = LocalKet.current
    val ref = ui.nowPlaying
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(ref?.cover, 48.dp, radius = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when {
                    !ui.available -> KText("L'hôte écoute un titre qui ne peut pas être partagé", Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 2)
                    ref == null -> KText("Rien en cours", Ket27Desk.Type.subheadline, color = ket.secondary)
                    else -> {
                        KText(ref.title, Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
                        KText(ref.artist, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
                    }
                }
            }
            if (ui.role == TogetherRole.Guest) ui.driftMs?.let { drift ->
                KText(if (kotlin.math.abs(drift) < 400) "Synchronisé" else "Recalage…", Ket27Desk.Type.caption2,
                    color = if (kotlin.math.abs(drift) < 400) ket.accent else ket.secondary)
            }
        }
        if (ui.upcoming.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            KText(AppText.t("À suivre") + " · " + ui.upcoming.mapNotNull { it.ref?.title }.take(3).joinToString(", "), Ket27Desk.Type.caption,
                color = ket.tertiary, maxLines = 2, translate = false)
        }
    }
}

@Composable
private fun Reactions(ui: TogetherUi) {
    val ket = LocalKet.current
    PanelCard {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TogetherCodec.reactions.forEachIndexed { i, emoji ->
                Box(Modifier.size(34.dp).clip(CircleShape).background(ket.fill).clickable { DesktopTogether.react(emoji) }.testTag("together-react:$i"),
                    contentAlignment = Alignment.Center) { KText(emoji, Ket27Desk.Type.callout, translate = false) }
            }
        }
        ui.reaction?.let { r ->
            Spacer(Modifier.height(8.dp))
            KText(r.emoji + "  " + r.name, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
    }
}

@Composable
private fun Pending(ui: TogetherUi) {
    val ket = LocalKet.current
    PanelCard(Modifier.testTag("together-pending")) {
        KText("Propositions", Ket27Desk.Type.headline)
        ui.pending.forEach { p ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    KText(p.ref.title, Ket27Desk.Type.subheadline, translate = false)
                    KText(p.fromName, Ket27Desk.Type.caption, color = ket.secondary, translate = false)
                }
                SmallPill("Accepter", "together-accept:${p.id}", filled = true) { DesktopTogether.accept(p.id) }
                Spacer(Modifier.width(6.dp))
                SmallPill("Refuser", "together-refuse:${p.id}") { DesktopTogether.refuse(p.id) }
            }
        }
    }
}

@Composable
private fun Members(ui: TogetherUi) {
    val ket = LocalKet.current
    PanelCard(Modifier.testTag("together-members")) {
        KText(AppText.t("Membres") + " · " + ui.members.size, Ket27Desk.Type.headline, translate = false)
        ui.members.forEach { m -> Spacer(Modifier.height(8.dp)); MemberRow(m, ui.role == TogetherRole.Host) }
    }
}

@Composable
private fun MemberRow(m: Member, hostView: Boolean) {
    val ket = LocalKet.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(KetogetherLook.avatar(m.id)), contentAlignment = Alignment.Center) {
            KText(com.ketvoice.app.together.Names.initials(m.name), Ket27Desk.Type.caption2, color = androidx.compose.ui.graphics.Color.White, translate = false)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            KText(m.name + if (m.me) " · " + AppText.t("Vous") else "", Ket27Desk.Type.subheadline, translate = false)
            val status = when {
                m.host -> "Hôte"
                m.away -> "Absent, place gardée"
                hostView && m.syncMs != null -> if (kotlin.math.abs(m.syncMs!!) < 400) "Synchronisé" else "Recalage…"
                else -> null
            }
            if (status != null) KText(status, Ket27Desk.Type.caption, color = if (m.host) ket.accent else ket.secondary)
        }
        if (hostView && !m.me && !m.host) SmallPill("Retirer", "together-remove:${m.id}") { DesktopTogether.remove(m.id) }
    }
}

@Composable
private fun HostRules(policy: Policy) {
    Column(Modifier.testTag("together-rules")) {
        SettingsCard {
            ToggleRow("Tout le monde contrôle la lecture", policy.everyoneControls, "together-controls") { DesktopTogether.setPolicy(policy.copy(everyoneControls = it)) }
            Hairline()
            ToggleRow("Tout le monde peut ajouter", policy.everyoneAdds, "together-adds") { DesktopTogether.setPolicy(policy.copy(everyoneAdds = it)) }
            Hairline()
            val sizes = (Policy.MIN_LIMIT..Policy.MAX_LIMIT).toList()
            ChoiceRow("Personnes au maximum", sizes.indexOf(policy.limit).coerceAtLeast(0), sizes.map { it.toString() }, "together-limit", translate = false) {
                DesktopTogether.setPolicy(policy.copy(limit = sizes[it]))
            }
        }
    }
}

@Composable
private fun Activity(ui: TogetherUi) {
    val ket = LocalKet.current
    PanelCard {
        KText("Activité", Ket27Desk.Type.headline)
        ui.log.take(5).forEach { a ->
            Spacer(Modifier.height(4.dp))
            KText(KetogetherLook.activity(a), Ket27Desk.Type.caption, color = ket.secondary, translate = false, maxLines = 2)
        }
    }
}

@Composable
private fun Ended(ui: TogetherUi) {
    val ket = LocalKet.current
    PanelCard(Modifier.testTag("together-ended")) {
        KText(KetogetherLook.ending(ui.endReason), Ket27Desk.Type.headline, maxLines = 3)
        Spacer(Modifier.height(10.dp))
        SmallPill("Fermer", "together-dismiss", filled = true) { DesktopTogether.dismiss() }
    }
}

/** The island's mark while in a room: « Ensemble » and how many are there. */
@Composable
internal fun TogetherMark() {
    val ui by DesktopTogether.ui.collectAsState()
    if (!ui.active) return
    val ket = LocalKet.current
    Row(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(5.dp)).background(ket.accent.copy(alpha = .16f))
        .padding(horizontal = 5.dp, vertical = 1.dp).testTag("together-badge"), verticalAlignment = Alignment.CenterVertically) {
        com.ketvoice.app.ui.common.Icon(SocialGlyphs.Together, null, Modifier.size(10.dp), tint = ket.accent)
        KText(AppText.t("Ensemble") + " · " + ui.members.size, Ket27Desk.Type.caption2.copy(fontSize = Ket27Desk.Type.caption2.fontSize * .82f),
            Modifier.padding(start = 3.dp), color = ket.accent, weight = FontWeight.SemiBold, translate = false)
    }
}

