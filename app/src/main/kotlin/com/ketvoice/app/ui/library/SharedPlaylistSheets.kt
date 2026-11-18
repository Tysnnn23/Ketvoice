package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ketvoice.app.data.AppText
import com.ketvoice.app.sync.SharedInvite
import com.ketvoice.app.sync.SharedMember
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.account.AccountButton
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.DESTRUCTIVE
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import java.io.File

/** The « Partagée » sign of a playlist several accounts edit together, in the Library and on the playlist page. */
@Composable
fun SharedBadge(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Row(modifier.testTag("shared-badge").clip(RoundedCornerShape(999.dp)).background(theme.accent.copy(alpha = .16f))
        .padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(KetIcons.Account, null, tint = theme.accent, modifier = Modifier.size(11.dp))
        Text("Partagée", color = theme.accent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(start = 3.dp))
    }
}

/** What the share sheet shows of the invitation. */
sealed interface ShareState {
    data object Loading : ShareState
    data class Ready(val invite: SharedInvite) : ShareState
    data class Failed(val message: String) : ShareState
}

/** The box a sheet's content sits in: the card colour, rounded, scrolling when it is tall. */
@Composable
private fun SheetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalTheme.current
    Column(modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(28.dp)).background(theme.card)
        .verticalScroll(rememberScrollState()).padding(20.dp), content = content)
}

@Composable
private fun SheetHeader(title: String, subtitle: String? = null, onClose: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.onBackground, style = Ket27.Type.title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        KetTextButton("Fermer", onClose)
    }
}

/** « Partager la playlist »: the invitation as text (a short code and a link), to copy or to send with the system's share sheet. */
@Composable
fun ShareSheet(playlistName: String, state: ShareState, onCopyCode: () -> Unit, onCopyLink: () -> Unit, onShare: () -> Unit,
               onRetry: () -> Unit, onClose: () -> Unit) {
    val theme = LocalTheme.current
    SheetCard(Modifier.testTag("share-sheet")) {
        SheetHeader("Partager la playlist", playlistName, onClose)
        Text("Les personnes qui entrent ce code peuvent ajouter, retirer et réordonner des titres avec vous.",
            color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 10.dp))
        when (state) {
            ShareState.Loading -> Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                ActivitySpinner(size = 20.dp)
                Text("Création de l'invitation…", color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(start = 10.dp))
            }
            is ShareState.Failed -> {
                Text(state.message, color = DESTRUCTIVE, style = Ket27.Type.subheadline, modifier = Modifier.padding(top = 18.dp))
                AccountButton("Réessayer", modifier = Modifier.padding(top = 12.dp), onClick = onRetry)
            }
            is ShareState.Ready -> {
                Text("Code d'invitation", color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 18.dp))
                Text(state.invite.code.chunked(5).joinToString("  "), localize = false, color = theme.onBackground,
                    fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp).testTag("share-code"))
                Text(state.invite.link, localize = false, color = theme.accent, style = Ket27.Type.subheadline,
                    modifier = Modifier.padding(top = 6.dp).testTag("share-link"))
                Text("Valable 7 jours.", color = theme.secondary, style = Ket27.Type.caption, modifier = Modifier.padding(top = 4.dp))
                AccountButton("Partager…", primary = true, modifier = Modifier.padding(top = 16.dp).testTag("share-system"), onClick = onShare)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountButton("Copier le code", modifier = Modifier.weight(1f).testTag("share-copy-code"), onClick = onCopyCode)
                    AccountButton("Copier le lien", modifier = Modifier.weight(1f).testTag("share-copy-link"), onClick = onCopyLink)
                }
            }
        }
    }
}

/** Where avatar photos are decoded: off the main thread; host tests decode in place (Robolectric's native graphics abort on a worker thread). */
internal object AvatarDecode { @Volatile var dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO }

/** A person's round photo, or their first letter on a colour taken from their name. */
@Composable
fun PersonAvatar(name: String, photo: File?, size: Dp, modifier: Modifier = Modifier) {
    val tints = listOf(0xFF5E5CE6, 0xFF0A84FF, 0xFF30B0C7, 0xFF34C759, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF8E8E93)
    // A photo is at most 512 px (see AvatarImage): decoded off the main thread, drawn as a plain bitmap.
    val decoded by produceState<ImageBitmap?>(null, photo) {
        value = photo?.let { file ->
            withContext(AvatarDecode.dispatcher) { runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        val bitmap = decoded
        if (bitmap != null) Image(bitmap, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Box(Modifier.fillMaxSize().background(Color(tints[(name.hashCode() and 0x7fffffff) % tints.size])), contentAlignment = Alignment.Center) {
                Text(name.trim().take(1).uppercase().ifBlank { "K" }, localize = false, color = Color.White,
                    fontSize = (size.value * .42f).sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The members of a shared playlist: photos (their own, from their profile), names, who owns it. Touching a
 * person who made their profile public opens it; the owner removes a member, a member leaves.
 */
@Composable
fun MembersSheet(playlistName: String, members: List<SharedMember>?, failed: Boolean, me: String, iAmOwner: Boolean,
                 avatars: Map<String, File>, waiting: Boolean, onProfile: (SharedMember) -> Unit, onRemove: (SharedMember) -> Unit,
                 onLeave: () -> Unit, onClose: () -> Unit) {
    val theme = LocalTheme.current
    SheetCard(Modifier.testTag("members-sheet")) {
        SheetHeader("Membres", playlistName, onClose)
        when {
            failed -> Text("Impossible de charger les membres pour le moment.", color = DESTRUCTIVE, style = Ket27.Type.subheadline,
                modifier = Modifier.padding(vertical = 18.dp))
            members == null -> Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center) {
                ActivitySpinner(size = 20.dp)
            }
            else -> members.forEach { member ->
                val opens = member.isPublic && member.handle.isNotBlank()
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("member-" + member.userId)
                    .then(if (opens) Modifier.clickable(role = Role.Button) { onProfile(member) } else Modifier).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(member.displayName.ifBlank { member.handle }, avatars[member.userId], 44.dp, Modifier.testTag("member-avatar"))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(member.displayName.ifBlank { "Compte Ketvoice" }, color = theme.onBackground, style = Ket27.Type.headline,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, localize = member.displayName.isBlank())
                        val line = listOfNotNull(if (member.handle.isNotBlank()) "@" + member.handle else null,
                            if (member.userId == me) AppText.t("Vous") else null).joinToString(" · ")
                        if (line.isNotBlank()) Text(line, localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1)
                    }
                    if (member.owner) Text("Propriétaire", color = theme.secondary, style = Ket27.Type.footnote)
                    else if (iAmOwner) KetTextButton("Retirer", { onRemove(member) }, destructive = true)
                }
            }
        }
        if (waiting) Text("Des modifications attendent d'être envoyées (hors ligne).", color = theme.secondary, style = Ket27.Type.caption,
            textAlign = TextAlign.Start, modifier = Modifier.padding(top = 8.dp))
        if (!iAmOwner && members != null) KetTextButton("Quitter la playlist", onLeave, Modifier.fillMaxWidth().padding(top = 6.dp), destructive = true)
    }
}
