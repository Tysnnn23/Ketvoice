package com.ketvoice.app.tv

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** What the TV's account screens hold over everything: the picker, the handoff toast, a mix being prepared, the offer. */
@Composable
fun TvAccountsLayer() {
    TvHandoffToast()
    TvMixProgress()
    TvRhythmModelSuggestion()
    TvRemoveProfileConfirm()
    TvProfilePicker()
}

/** « Qui écoute ? », at launch when two listeners or more share the TV, and from Settings. */
@Composable
fun TvProfilePicker() {
    val picking by TvProfiles.picking.collectAsState()
    if (!picking) return
    val book by TvProfiles.book.collectAsState()
    val stamp by TvProfiles.avatars.collectAsState()
    val actions = LocalTvActions.current
    val activity = LocalContext.current as? Activity
    BackHandler { TvProfiles.picking.value = false }
    TvProfilePickerView(book, avatar = { id -> stamp.let { TvProfiles.avatarOf(id) } },
        onPick = { id -> if (activity != null) TvProfiles.switchTo(activity, id) else TvProfiles.picking.value = false },
        onAdd = { TvProfiles.picking.value = false; actions.nav.open(TvRoute.SignIn) })
}

/**
 * An overlay that keeps the remote's focus: it takes it (again while the page under it settles, which asks for the focus
 * too) and never lets the arrows leave it for the page beneath.
 */
@Composable
internal fun Modifier.holdFocus(first: FocusRequester): Modifier {
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(first) {
        repeat(HOLD_TRIES) { if (!inside) runCatching { first.requestFocus() }; delay(100) }
    }
    return this.onFocusChanged { inside = it.hasFocus }.focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
}

private const val HOLD_TRIES = 15

/** Stateless, so the host tests can draw it. The profile in use has the focus first. */
@Composable
fun TvProfilePickerView(book: TvProfileBook, avatar: (String) -> File?, onPick: (String) -> Unit, onAdd: () -> Unit) {
    val requesters = remember(book.profiles.map { it.id }) { book.profiles.associate { it.id to FocusRequester() } }
    val add = remember { FocusRequester() }
    val first = requesters[book.mounted] ?: requesters.values.firstOrNull() ?: add
    Box(Modifier.fillMaxSize().background(TvDesign.Ink.page).testTag("profile-picker"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Qui écoute ?", style = TvDesign.Type.display, color = TvDesign.Ink.primary)
            Spacer(Modifier.height(44.dp))
            // Many listeners: the row scrolls with the focus (the TV's safe margins kept on both sides).
            Row(Modifier.holdFocus(first).horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = TvDesign.Safe.horizontal, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
                book.profiles.forEach { p ->
                    ProfileTile(p.name.ifBlank { p.email.substringBefore('@') }, p.id, avatar(p.id), current = p.id == book.mounted,
                        requester = requesters.getValue(p.id)) { onPick(p.id) }
                }
                AddTile(add, onAdd)
            }
            Spacer(Modifier.height(40.dp))
            Text("Chaque profil a sa bibliothèque, ses statistiques, ses réglages Ketmix et son historique d'écoute.",
                style = TvDesign.Type.body, color = TvDesign.Ink.tertiary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp))
        }
    }
}

@Composable
private fun ProfileTile(name: String, id: String, avatar: File?, current: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    Column(Modifier.width(TILE_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(TILE).tvFocusable(onClick, CircleShape, focusRequester = requester, scale = 1.08f, tag = "profile-$id")) {
            ProfileAvatar(name, id, avatar, TILE)
        }
        Spacer(Modifier.height(14.dp))
        Text(name, localize = false, style = TvDesign.Type.row, color = TvDesign.Ink.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (current) Text("Profil en cours", style = TvDesign.Type.caption, color = TvDesign.Ink.secondary)
    }
}

@Composable
private fun AddTile(requester: FocusRequester, onClick: () -> Unit) {
    Column(Modifier.width(TILE_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(TILE).tvFocusable(onClick, CircleShape, focusRequester = requester, scale = 1.08f, tag = "profile-add")
            .clip(CircleShape).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
            com.ketvoice.app.ui.common.Icon(KetIcons.Plus, null, Modifier.size(44.dp), tint = TvDesign.Ink.secondary)
        }
        Spacer(Modifier.height(14.dp))
        Text("Ajouter un profil", style = TvDesign.Type.row, color = TvDesign.Ink.secondary, maxLines = 2, textAlign = TextAlign.Center)
    }
}

private val TILE = 132.dp
private val TILE_WIDTH = 184.dp

/** The account's photo, or its initial on a colour of its own (the same on every TV for the same account). */
@Composable
fun ProfileAvatar(name: String, id: String, avatar: File?, size: Dp) {
    if (avatar != null) {
        TvCover("file://" + avatar.absolutePath + "?" + avatar.lastModified(), size, CircleShape)
        return
    }
    val hue = ((id.hashCode() % 360) + 360) % 360
    Box(Modifier.size(size).clip(CircleShape).background(Color.hsv(hue.toFloat(), .45f, .55f)), contentAlignment = Alignment.Center) {
        Text((name.firstOrNull() ?: 'K').uppercase(), localize = false, style = if (size > 80.dp) TvDesign.Type.display else TvDesign.Type.row,
            color = Color.White)
    }
}

/** Settings › Profils: who shares this TV, switching, adding one by QR code, removing one. */
@Composable
fun TvProfilesSection() {
    val book by TvProfiles.book.collectAsState()
    val stamp by TvProfiles.avatars.collectAsState()
    val actions = LocalTvActions.current
    val activity = LocalContext.current as? Activity
    val account by com.ketvoice.app.account.AccountSession.state.collectAsState()
    if (book.profiles.isEmpty() && !account.signedIn) return
    Column(Modifier.padding(horizontal = TvDesign.Safe.horizontal).padding(top = 12.dp).widthIn(max = 720.dp).testTag("tv-profiles"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Profils", style = TvDesign.Type.row, color = TvDesign.Ink.primary, modifier = Modifier.padding(bottom = 2.dp))
        book.profiles.forEach { p ->
            ProfileLine(p, stamp.let { TvProfiles.avatarOf(p.id) }, current = p.id == book.mounted,
                onSwitch = { activity?.let { TvProfiles.switchTo(it, p.id) } }, onRemove = { TvProfiles.confirming.value = p })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Ajouter un profil", KetIcons.Plus, { actions.nav.open(TvRoute.SignIn) }, Modifier.pageItem("profiles", "add"),
                tag = "profiles-add")
            if (book.asksAtLaunch) TvButton("Qui écoute ?", null, { TvProfiles.picking.value = true }, Modifier.pageItem("profiles", "picker"),
                tag = "profiles-picker")
        }
    }
}

@Composable
private fun ProfileLine(p: TvProfile, avatar: File?, current: Boolean, onSwitch: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .06f)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(p.name.ifBlank { p.email }, p.id, avatar, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name.ifBlank { p.email.substringBefore('@') }, localize = false, style = TvDesign.Type.label, color = TvDesign.Ink.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (current) AppText.t("Profil en cours") else p.email, localize = false, style = TvDesign.Type.caption,
                color = TvDesign.Ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!current) TvButton("Changer", null, onSwitch, Modifier.pageItem("profiles", "switch:${p.id}"), tag = "profile-switch-${p.id}")
        Spacer(Modifier.width(10.dp))
        TvButton("Retirer", null, onRemove, Modifier.pageItem("profiles", "remove:${p.id}"), tag = "profile-remove-${p.id}")
    }
}

/** « Retirer ce profil ? »: it is signed out of this TV and its data here deleted; its account and online library stay. */
@Composable
fun TvRemoveProfileConfirm() {
    val target by TvProfiles.confirming.collectAsState()
    val p = target ?: return
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    fun close() { TvProfiles.confirming.value = null }
    BackHandler { close() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).testTag("profile-remove-confirm"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).clip(RoundedCornerShape(TvDesign.Radius.panel)).background(Color(0xFF1C1C22)).padding(28.dp).holdFocus(first)) {
            Text("Retirer ce profil ?", style = TvDesign.Type.title, color = TvDesign.Ink.primary)
            Spacer(Modifier.height(6.dp))
            Text(p.name.ifBlank { p.email }, localize = false, style = TvDesign.Type.row, color = TvDesign.Ink.secondary, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Text("Il est déconnecté de ce téléviseur et ses données ici (réglages, téléchargements, historique) sont effacées. Son compte et sa bibliothèque en ligne restent.",
                style = TvDesign.Type.body, color = TvDesign.Ink.secondary)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Annuler", null, { close() }, Modifier.focusRequester(first), tag = "profile-remove-cancel")
                TvButton("Retirer", null, {
                    if (!busy && activity != null) { busy = true; scope.launch { runCatching { TvProfiles.remove(activity, p.id) }; busy = false; close() } }
                }, primary = true, tag = "profile-remove-ok")
            }
        }
    }
}

/** « Lecture reçue de … »: a small toast at the top when a phone or a PC of the account sent what it played here. */
@Composable
fun TvHandoffToast() {
    val received by com.ketvoice.app.remote.PhoneRemote.received.collectAsState()
    var shown by remember { mutableStateOf<com.ketvoice.app.remote.PhoneRemote.Received?>(null) }
    LaunchedEffect(received) {
        val r = received ?: return@LaunchedEffect
        if (System.currentTimeMillis() - r.at > TOAST_MS) return@LaunchedEffect
        shown = r
        delay(TOAST_MS)
        if (shown == r) shown = null
    }
    shown?.let { TvHandoffToastView(it.from) }
}

private const val TOAST_MS = 4_500L

@Composable
fun TvHandoffToastView(from: String) {
    Box(Modifier.fillMaxSize().padding(top = TvDesign.Safe.vertical, end = TvDesign.Safe.horizontal), contentAlignment = Alignment.TopEnd) {
        Row(Modifier.clip(RoundedCornerShape(TvDesign.Radius.pill)).background(Color(0xF21C1C22)).padding(horizontal = 22.dp, vertical = 12.dp)
            .testTag("handoff-toast"), verticalAlignment = Alignment.CenterVertically) {
            com.ketvoice.app.ui.common.Icon(KetIcons.Play, null, Modifier.size(18.dp), tint = TvDesign.Ink.primary)
            Spacer(Modifier.width(12.dp))
            Text(AppText.t("Lecture reçue de") + " " + from, localize = false, style = TvDesign.Type.label, color = TvDesign.Ink.primary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}
