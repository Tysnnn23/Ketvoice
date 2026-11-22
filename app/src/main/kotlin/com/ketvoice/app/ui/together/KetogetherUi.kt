package com.ketvoice.app.ui.together

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.together.Ketogether
import com.ketvoice.app.together.Member
import com.ketvoice.app.together.Names
import com.ketvoice.app.together.Policy
import com.ketvoice.app.together.QrCode
import com.ketvoice.app.together.RoomCode
import com.ketvoice.app.together.TogetherCodec
import com.ketvoice.app.together.TogetherLink
import com.ketvoice.app.together.TogetherPhase
import com.ketvoice.app.together.TogetherRole
import com.ketvoice.app.together.TogetherUi
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.LiquidGlassSwitch
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.settings.SettingsSheet
import com.ketvoice.app.ui.settings.SettingsSheetHeader

/** Ketogether's colours: the live dot, and the dot while the room reconnects. */
internal object KetogetherLook {
    val live = Color(0xFF34C759)
    val waiting = Color(0xFFFF9F0A)
    private val avatars = listOf(Color(0xFF0088FF), Color(0xFFFF2D55), Color(0xFF34C759), Color(0xFFFF8D28),
        Color(0xFF6155F5), Color(0xFF00C3D0), Color(0xFFCB30E0), Color(0xFFFFCC00))
    fun avatar(id: String): Color = avatars[Math.floorMod(id.hashCode(), avatars.size)]

    /** A line of the recent activity (8.4), as a French source text with the name and the title put in. */
    fun activity(a: com.ketvoice.app.together.Activity): String = AppText.t(when (a.kind) {
        "pause" -> "{0} a mis en pause"
        "play" -> "{0} a relancé la lecture"
        "next" -> "{0} est passé au titre suivant"
        "previous" -> "{0} est revenu au titre précédent"
        "seek" -> "{0} a déplacé la lecture"
        "add" -> "{0} a ajouté {1}"
        "join" -> "{0} a rejoint la session"
        "away" -> "{0} est absent · place gardée"
        "back" -> "{0} est de retour"
        "leave" -> "{0} a quitté la session"
        else -> "{0} a été retiré de la session"
    }).replace("{0}", a.name).replace("{1}", a.title.orEmpty())

    /**
     * The live « Synchronisé · N ms » (8.4): on a guest's phone how far it is from the host; on the host's, the
     * furthest guest at their last report. Null while nothing has been measured yet.
     */
    fun syncMs(ui: TogetherUi): Long? = if (ui.role == TogetherRole.Guest) ui.driftMs?.let { kotlin.math.abs(it) }
        else ui.members.filter { !it.me && !it.away }.mapNotNull { it.syncMs }.maxOrNull()

    /** What the end of a session says, as a French source text. */
    fun ending(reason: String?): String = when (reason) {
        "hostEnded" -> "L'hôte a terminé la session"
        "hostLost" -> "L'hôte s'est déconnecté · la session a pris fin"
        "notFound" -> "Aucune session avec ce code"
        "expired" -> "La session a pris fin : personne ne l'a rejointe"
        "connection" -> "Connexion perdue · la session a pris fin"
        "refused" -> "Le serveur n'accepte pas la session pour le moment"
        "left" -> "Vous avez quitté la session"
        "removed" -> "L'hôte vous a retiré de la session"
        "full" -> "La session est complète"
        else -> "Session terminée"
    }
}

/** The sheet and the notices of Ketogether. Placed once, at the root, beside the other playback dialogs. */
@Composable
fun KetogetherHost() {
    val ui by Ketogether.ui.collectAsState()
    if (Ketogether.sheet.value) KetogetherSheet { Ketogether.sheet.value = false }
    // Notices go to the snackbar while the sheet is closed (« Titre proposé », « Seul l'hôte contrôle la lecture »…).
    val notice = ui.notice
    LaunchedEffect(notice) {
        if (notice != null && !Ketogether.sheet.value) {
            val bar = com.ketvoice.app.ui.common.TrackActionFeedback.snackbar
            bar.currentSnackbarData?.dismiss()
            bar.showSnackbar(AppText.t(notice))
        }
    }
    val ended = ui.phase == TogetherPhase.Ended
    LaunchedEffect(ended) {
        if (ended && !Ketogether.sheet.value && ui.endReason != "left" && ui.endReason != "ended") {
            com.ketvoice.app.ui.common.TrackActionFeedback.snackbar.showSnackbar(AppText.t(KetogetherLook.ending(ui.endReason)))
            Ketogether.dismiss()
        }
    }
}

@Composable
private fun KetogetherSheet(onClose: () -> Unit) {
    val ui by Ketogether.ui.collectAsState()
    val context = LocalContext.current
    val invitation = Ketogether.invitation.value
    val discord = remember { Ketogether.discordName() }
    var nickname by remember { mutableStateOf(Ketogether.displayName(context).orEmpty()) }
    var code by remember(invitation) { mutableStateOf(invitation?.code?.let(RoomCode::display).orEmpty()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    fun name(): String? {
        if (discord != null) return discord
        val clean = Names.clean(nickname)
        nameError = when {
            nickname.isBlank() -> "Choisissez un surnom"
            Names.looksLikeEmail(nickname) -> "Choisissez un surnom, pas une adresse e-mail"
            clean == null -> "Choisissez un surnom"
            else -> null
        }
        if (clean != null) Ketogether.saveNickname(context, clean)
        return clean
    }
    SettingsSheet(onDismiss = onClose) {
        KetogetherSheetContent(ui, discord, nickname, { nickname = it; nameError = null }, nameError, code, { code = it },
            invitation != null && !ui.active,
            actions = KetogetherActions(
                create = { name()?.let { Ketogether.create(context, it) } },
                join = {
                    val target = RoomCode.normalize(code)
                    if (target == null) nameError = "Ce code ne ressemble pas à un code Ketogether"
                    else name()?.let { Ketogether.join(context, target, it, invitation?.takeIf { i -> i.code == target }?.fingerprint) }
                },
                share = { share(context, ui) },
                leave = { if (ui.role == TogetherRole.Host) confirmEnd = true else Ketogether.leave() },
                close = onClose,
                again = { Ketogether.dismiss() },
                policy = Ketogether::setPolicy, accept = Ketogether::accept, refuse = Ketogether::refuse,
                skip = Ketogether::skip, react = Ketogether::react, remove = Ketogether::remove))
    }
    if (confirmEnd) AppAlertDialog(onDismissRequest = { confirmEnd = false },
        confirmButton = { KetTextButton("Terminer", { confirmEnd = false; Ketogether.leave() }, destructive = true) },
        dismissButton = { KetTextButton("Annuler", { confirmEnd = false }) },
        title = { Text("Terminer la session ?") },
        text = { Text("La session prend fin pour tout le monde. Chacun garde la musique en cours.") })
}

private fun share(context: android.content.Context, ui: TogetherUi) {
    val link = TogetherLink.of(ui.code, ui.fingerprint)
    val text = AppText.t("Rejoins ma session Ketogether sur Ketvoice") + "\n" + AppText.t("Code") + " : " + RoomCode.display(ui.code) + "\n" + link
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_TITLE, "Ketogether") }
    runCatching { context.startActivity(Intent.createChooser(send, AppText.t("Inviter à Ketogether"))) }
}

internal class KetogetherActions(
    val create: () -> Unit = {}, val join: () -> Unit = {}, val share: () -> Unit = {}, val leave: () -> Unit = {},
    val close: () -> Unit = {}, val again: () -> Unit = {}, val policy: (Policy) -> Unit = {}, val accept: (String) -> Unit = {},
    val refuse: (String) -> Unit = {}, val skip: () -> Unit = {}, val react: (String) -> Unit = {},
    val remove: (String) -> Unit = {}
)

/** What the sheet shows for [ui]; stateless, so it renders the same on the host and on a phone. */
@Composable
internal fun KetogetherSheetContent(
    ui: TogetherUi, discordName: String?, nickname: String, onNickname: (String) -> Unit, error: String?,
    code: String, onCode: (String) -> Unit, invited: Boolean, actions: KetogetherActions, showQr: Boolean = false
) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight())
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp).testTag("ketogether-sheet"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSheetHeader("Ketogether", when (ui.phase) {
            TogetherPhase.Idle -> AppText.t("Écoutez la même musique en même temps, à plusieurs, avec une file d'attente partagée.")
            TogetherPhase.Connecting -> AppText.t("Connexion à la session…")
            TogetherPhase.Reconnecting -> AppText.t("Reconnexion…")
            TogetherPhase.Live -> if (ui.role == TogetherRole.Host) AppText.t("Vous êtes l'hôte") else AppText.t("Session de") + " " + ui.hostName
            TogetherPhase.Ended -> AppText.t(KetogetherLook.ending(ui.endReason))
        },
            onClose = actions.close)
        when (ui.phase) {
            TogetherPhase.Idle -> IdleContent(discordName, nickname, onNickname, error, code, onCode, invited, actions)
            TogetherPhase.Ended -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                KetTextButton("Nouvelle session", actions.again, primary = true)
            }
            TogetherPhase.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                ActivitySpinner(size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (ui.role == TogetherRole.Host) "Ouverture de la session…" else "Recherche de l'hôte…", color = theme.secondary,
                    style = Ket27.Type.subheadline)
            }
            else -> LiveContent(ui, actions, showQr)
        }
        ui.notice?.let { Text(it, color = theme.secondary, style = Ket27.Type.footnote) }
    }
}

@Composable
private fun ColumnScope.IdleContent(discordName: String?, nickname: String, onNickname: (String) -> Unit, error: String?,
                        code: String, onCode: (String) -> Unit, invited: Boolean, actions: KetogetherActions) {
    val theme = LocalTheme.current
    if (discordName != null) {
        Text(AppText.t("Votre nom dans la session") + " : " + discordName + " (Discord)", localize = false,
            color = theme.secondary, style = Ket27.Type.footnote)
    } else KetTextField(nickname, { onNickname(it.take(Names.MAX)) }, "Votre surnom dans la session",
        modifier = Modifier.fillMaxWidth().testTag("ketogether-name"),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
    if (invited) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.accent.copy(alpha = .14f)).padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Invitation à une session", color = theme.onBackground, style = Ket27.Type.headline)
                Text(code, localize = false, color = theme.onBackground, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("Vous écouterez la musique de l'hôte sur cet appareil, avec vos propres sources.", color = theme.secondary,
                    style = Ket27.Type.footnote)
                PrimaryButton("Rejoindre", actions.join, Modifier.fillMaxWidth())
            }
        }
    } else {
        PrimaryButton("Créer une session", actions.create, Modifier.fillMaxWidth().testTag("ketogether-create"))
        Text("ou", color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                KetTextField(code, { onCode(it.uppercase().take(7)) }, "Code de la session", modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            }
            Spacer(Modifier.width(10.dp))
            KetTextButton("Rejoindre", actions.join, primary = true, enabled = code.isNotBlank())
        }
    }
    error?.let { Text(it, color = Color(0xFFFF453A), style = Ket27.Type.footnote) }
    Text("Seuls votre nom et les titres du catalogue en ligne sont partagés. Les fichiers locaux et les sources personnelles restent sur cet appareil.",
        color = theme.secondary, style = Ket27.Type.caption)
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Box(modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(theme.accent)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = Ket27.Type.headline)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalTheme.current.secondary, style = Ket27.Type.footnote, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun LiveContent(ui: TogetherUi, actions: KetogetherActions, initialQr: Boolean) {
    val theme = LocalTheme.current
    var qr by remember { mutableStateOf(initialQr) }
    // The code, to say aloud or to share; the QR code for the friends beside you.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.onBackground.copy(alpha = .06f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Code de la session", color = theme.secondary, style = Ket27.Type.footnote)
                Text(RoomCode.display(ui.code), localize = false, color = theme.onBackground, fontSize = 32.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = Modifier.testTag("ketogether-code"))
            }
            KetogetherPill(ui, onDark = theme.isDark)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KetTextButton("Partager le lien", actions.share, primary = true)
            if (ui.fingerprint.isNotBlank()) KetTextButton(if (qr) "Masquer le QR" else "QR code", { qr = !qr })
        }
        if (qr && ui.fingerprint.isNotBlank()) QrImage(TogetherLink.of(ui.code, ui.fingerprint),
            Modifier.align(Alignment.CenterHorizontally).size(208.dp))
    }

    SyncStatus(ui)
    SectionTitle(AppText.t("Participants") + " · " + ui.members.size + "/" + ui.policy.limit)
    // 8.4: the host touches a listener to take them out of the session.
    var removing by remember { mutableStateOf<Member?>(null) }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.members.forEach { member ->
            MemberAvatar(member, if (ui.role == TogetherRole.Host && !member.me) ({ removing = member }) else null)
        }
    }
    removing?.let { member ->
        AppAlertDialog(onDismissRequest = { removing = null },
            confirmButton = { KetTextButton("Retirer", { removing = null; actions.remove(member.id) }, destructive = true) },
            dismissButton = { KetTextButton("Annuler", { removing = null }) },
            title = { Text(AppText.t("Retirer {0} de la session ?").replace("{0}", member.name), localize = false) },
            text = { Text("Sa musique s'arrête de suivre la vôtre et il ne pourra pas revenir dans cette session.") })
    }

    SectionTitle("En cours")
    val now = ui.nowPlaying
    Row(verticalAlignment = Alignment.CenterVertically) {
        Cover(now?.title ?: "K", now?.cover, size = 48.dp, radius = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            when {
                now != null -> {
                    Text(now.title, localize = false, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(now.artist, localize = false, color = theme.secondary, style = Ket27.Type.subheadline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                !ui.available -> {
                    Text("Titre indisponible pour les invités", color = theme.onBackground, style = Ket27.Type.headline)
                    Text(if (ui.role == TogetherRole.Host) "Un fichier local ou une source personnelle ne quitte pas cet appareil."
                        else "L'hôte écoute un titre qui n'est disponible que chez lui.", color = theme.secondary, style = Ket27.Type.footnote)
                }
                else -> Text("Rien en lecture", color = theme.secondary, style = Ket27.Type.subheadline)
            }
        }
        if (!ui.available && ui.role == TogetherRole.Host) KetTextButton("Passer", actions.skip)
    }

    // Reactions: a few, for everyone.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TogetherCodec.reactions.forEach { emoji ->
            Box(Modifier.size(44.dp).clip(CircleShape).background(theme.onBackground.copy(alpha = .06f))
                .clickable(role = Role.Button, onClickLabel = AppText.t("Réagir")) { actions.react(emoji) }
                .testTag("ketogether-react"), contentAlignment = Alignment.Center) {
                Text(emoji, localize = false, fontSize = 20.sp)
            }
        }
    }
    ui.reaction?.let { Text(it.name + " " + it.emoji, localize = false, color = theme.secondary, style = Ket27.Type.footnote) }

    if (ui.pending.isNotEmpty()) {
        SectionTitle(AppText.t("En attente de l'hôte") + " · " + ui.pending.size)
        ui.pending.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.ref.title + " — " + p.ref.artist, localize = false, color = theme.onBackground, style = Ket27.Type.subheadline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(AppText.t("Proposé par") + " " + p.fromName, localize = false, color = theme.secondary, style = Ket27.Type.caption)
                }
                if (ui.role == TogetherRole.Host) {
                    KetTextButton("Refuser", { actions.refuse(p.id) })
                    KetTextButton("Ajouter", { actions.accept(p.id) }, primary = true)
                }
            }
        }
    }

    SectionTitle(AppText.t("À suivre") + if (ui.upcoming.isNotEmpty()) " · " + ui.upcoming.size else "")
    if (ui.upcoming.isEmpty()) Text("La file partagée est vide. Ajoutez des titres avec « Ajouter à Ketogether » dans le menu d'un titre.",
        color = theme.secondary, style = Ket27.Type.footnote)
    ui.upcoming.take(8).forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ref = item.ref
            Column(Modifier.weight(1f)) {
                if (ref != null) Text(ref.title + " — " + ref.artist, localize = false, color = theme.onBackground, style = Ket27.Type.subheadline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Text("Titre réservé à l'hôte", color = theme.secondary, style = Ket27.Type.subheadline)
                item.by?.let { Text(AppText.t("Proposé par") + " " + it, localize = false, color = theme.secondary, style = Ket27.Type.caption) }
            }
        }
    }
    if (ui.upcoming.size > 8) Text("+" + (ui.upcoming.size - 8), localize = false,
        color = theme.secondary, style = Ket27.Type.caption)

    ActivityLog(ui)

    SectionTitle("Règles de la session")
    if (ui.role == TogetherRole.Host) {
        LimitRow(ui.policy.limit) { actions.policy(ui.policy.copy(limit = Policy.limit(it))) }
        PolicyRow("Tout le monde contrôle", "Lecture, pause, titre suivant et position", ui.policy.everyoneControls) {
            actions.policy(ui.policy.copy(everyoneControls = it))
        }
        PolicyRow("Tout le monde peut ajouter", "Sinon, vous validez chaque proposition", ui.policy.everyoneAdds) {
            actions.policy(ui.policy.copy(everyoneAdds = it))
        }
    } else {
        Text(if (ui.policy.everyoneControls) "Tout le monde contrôle la lecture" else "L'hôte contrôle la lecture ; vous pouvez mettre en pause sur cet appareil",
            color = theme.onBackground, style = Ket27.Type.subheadline)
        Text(if (ui.policy.everyoneAdds) "Vos titres rejoignent la file directement" else "L'hôte valide chaque titre proposé",
            color = theme.secondary, style = Ket27.Type.footnote)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        KetTextButton(if (ui.role == TogetherRole.Host) "Terminer la session" else "Quitter la session", actions.leave, destructive = true)
    }
}

@Composable
private fun PolicyRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val theme = LocalTheme.current
    val label = AppText.t(title)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.onBackground, style = Ket27.Type.body)
            Text(sub, color = theme.secondary, style = Ket27.Type.footnote)
        }
        LiquidGlassSwitch(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

/** « Synchronisé · N ms » (8.4), with the live dot; « Synchronisation… » until a first measure. */
@Composable
private fun SyncStatus(ui: TogetherUi) {
    val theme = LocalTheme.current
    val ms = KetogetherLook.syncMs(ui)
    if (ms == null && (ui.role == TogetherRole.Host && ui.members.none { !it.me })) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("ketogether-sync")) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (ms != null && ui.phase == TogetherPhase.Live) KetogetherLook.live else KetogetherLook.waiting))
        Spacer(Modifier.width(8.dp))
        Text(if (ms == null || ui.phase != TogetherPhase.Live) AppText.t("Synchronisation…") else AppText.t("Synchronisé · {0} ms").replace("{0}", ms.toString()),
            localize = false, color = theme.secondary, style = Ket27.Type.footnote)
    }
}

/** « Activité récente » (8.4): who paused, skipped, added, joined or left, newest first. */
@Composable
private fun ActivityLog(ui: TogetherUi) {
    if (ui.log.isEmpty()) return
    val theme = LocalTheme.current
    SectionTitle("Activité récente")
    Column(Modifier.testTag("ketogether-activity"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ui.log.forEach { Text(KetogetherLook.activity(it), localize = false, color = theme.onBackground, style = Ket27.Type.footnote,
            maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** « Participants maximum » (8.4): 2 to 10, the host included; who is already in stays. */
@Composable
private fun LimitRow(limit: Int, onLimit: (Int) -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().testTag("ketogether-limit"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Participants maximum", color = theme.onBackground, style = Ket27.Type.body)
            Text("Vous compris ; ceux déjà présents restent", color = theme.secondary, style = Ket27.Type.footnote)
        }
        KetTextButton("−", { onLimit(limit - 1) }, enabled = limit > Policy.MIN_LIMIT, localize = false,
            modifier = Modifier.semantics { contentDescription = AppText.t("Moins de participants") })
        Text(limit.toString(), localize = false, color = theme.onBackground, style = Ket27.Type.headline,
            modifier = Modifier.widthIn(min = 28.dp).testTag("ketogether-limit-value"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        KetTextButton("+", { onLimit(limit + 1) }, enabled = limit < Policy.MAX_LIMIT, localize = false,
            modifier = Modifier.semantics { contentDescription = AppText.t("Plus de participants") })
    }
}

@Composable
private fun MemberAvatar(member: Member, onRemove: (() -> Unit)? = null) {
    val theme = LocalTheme.current
    val label = AppText.t("Retirer") + " " + member.name
    Column(Modifier.width(72.dp).graphicsLayer { alpha = if (member.away) .5f else 1f }
        .then(if (onRemove != null) Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onRemove) else Modifier)
        .testTag("ketogether-member"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(KetogetherLook.avatar(member.id))
            .then(if (member.host) Modifier.border(2.dp, theme.onBackground.copy(alpha = .85f), CircleShape) else Modifier),
            contentAlignment = Alignment.Center) {
            Text(Names.initials(member.name), localize = false, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(if (member.me) AppText.t("Vous") else member.name, localize = false, color = theme.onBackground, style = Ket27.Type.caption,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        when {
            member.host -> Text("Hôte", color = theme.secondary, fontSize = 10.sp)
            member.away -> Text("Absent · place gardée", color = theme.secondary, fontSize = 10.sp, maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 12.sp)
            member.syncMs != null && !member.me -> Text(member.syncMs.toString() + " ms", localize = false, color = theme.secondary, fontSize = 10.sp)
        }
    }
}

/** The QR code of the link, black on white with its quiet zone, as cameras expect whatever the theme. */
@Composable
internal fun QrImage(text: String, modifier: Modifier = Modifier) {
    val code = remember(text) { QrCode.encode(text) } ?: return
    val label = AppText.t("QR code de la session")
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).semantics { contentDescription = label }.testTag("ketogether-qr")) {
        val quiet = 4
        val cell = size.minDimension / (code.size + quiet * 2)
        for (y in 0 until code.size) for (x in 0 until code.size) {
            if (code[x, y]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + .5f, cell + .5f))
        }
    }
}

/**
 * The live sign of a session: a breathing dot, « Ketogether » and how many listen. A reaction pops in it for a
 * moment. [compact] is the mini-player's: the dot and the count only. Touched, it opens the sheet.
 */
@Composable
fun KetogetherPill(ui: TogetherUi, modifier: Modifier = Modifier, compact: Boolean = false, onDark: Boolean = true,
                   onClick: (() -> Unit)? = { Ketogether.sheet.value = true }) {
    if (!ui.active) return
    val ink = if (onDark) Color.White else LocalTheme.current.onBackground
    val waiting = ui.phase != TogetherPhase.Live
    val dot = if (waiting) KetogetherLook.waiting else KetogetherLook.live
    val reduced = Motion.reduced
    // Read in the layer (6.6): read here it recomposed the pill, inside the mini-player, every frame of a session.
    val breathState = if (reduced || waiting) null else rememberInfiniteTransition(label = "ketogether-live").animateFloat(.45f, 1f,
        infiniteRepeatable(tween(1_100), RepeatMode.Reverse), label = "dot")
    // A reaction shows for two and a half seconds.
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.reaction?.serial) {
        val r = ui.reaction ?: return@LaunchedEffect
        shown = r.emoji
        kotlinx.coroutines.delay(2_500)
        shown = null
    }
    val pop by animateFloatAsState(if (shown != null) 1f else 0f,
        if (reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = .5f, stiffness = 420f), label = "reaction")
    val description = AppText.t("Ketogether") + " · " + ui.members.size + " " + AppText.t("à l'écoute")
    Row(modifier.clip(CircleShape).background(ink.copy(alpha = if (onDark) .16f else .08f))
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics { contentDescription = description }.testTag("ketogether-pill")
        .padding(horizontal = if (compact) 7.dp else 10.dp, vertical = if (compact) 2.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 6.dp else 7.dp).graphicsLayer { alpha = breathState?.value ?: 1f }.clip(CircleShape).background(dot))
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        if (!compact) {
            Text("Ketogether", localize = false, color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(" · ", localize = false, color = ink.copy(alpha = .6f), fontSize = 12.sp)
        }
        Box(contentAlignment = Alignment.Center) {
            Text(ui.members.size.toString(), localize = false, color = ink.copy(alpha = if (compact) .8f else .85f),
                fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.graphicsLayer { alpha = 1f - pop.coerceIn(0f, 1f) })
            shown?.let { emoji ->
                Text(emoji, localize = false, fontSize = if (compact) 11.sp else 13.sp,
                    modifier = Modifier.graphicsLayer { val s = .4f + .6f * pop; scaleX = s; scaleY = s; alpha = pop.coerceIn(0f, 1f) })
            }
        }
    }
}

/** The player's device label (« Ce téléphone »), which becomes the live pill while a session runs. */
@Composable
fun KetogetherDeviceLabel(modifier: Modifier = Modifier, default: @Composable () -> Unit) {
    val ui by Ketogether.ui.collectAsState()
    if (ui.active) KetogetherPill(ui, modifier) else default()
}

/** The live pill alone, where a player has no device label (tablet bar, Quest). */
@Composable
fun KetogetherLivePill(modifier: Modifier = Modifier, compact: Boolean = false, onDark: Boolean = true) {
    val ui by Ketogether.ui.collectAsState()
    KetogetherPill(ui, modifier, compact = compact, onDark = onDark)
}

/** The icon of Ketogether in menus. */
val KetogetherIcon get() = Icons.Filled.Groups
