package com.ketvoice.desktop.social

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.social.FriendAction
import com.ketvoice.app.social.FriendState
import com.ketvoice.app.social.FriendStates
import com.ketvoice.app.social.PinnedList
import com.ketvoice.app.social.ProfileModule
import com.ketvoice.app.social.ProfileRules
import com.ketvoice.app.social.ProfileView
import com.ketvoice.app.social.Reach
import com.ketvoice.app.social.Relation
import com.ketvoice.app.social.ShareUrls
import com.ketvoice.app.social.TopArtist
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.ArtistRef
import com.ketvoice.desktop.ui.Artwork
import com.ketvoice.desktop.ui.CapsuleButton
import com.ketvoice.desktop.ui.EmptyMessage
import com.ketvoice.desktop.ui.KText
import com.ketvoice.desktop.ui.LocalLibrary
import com.ketvoice.desktop.ui.LocalNavigator
import com.ketvoice.desktop.ui.LocalPlayer
import com.ketvoice.desktop.ui.Page
import com.ketvoice.desktop.ui.Route
import com.ketvoice.desktop.ui.Segments
import com.ketvoice.desktop.ui.SettingsCard
import com.ketvoice.desktop.ui.Spinner
import com.ketvoice.desktop.ui.ToggleRow
import com.ketvoice.desktop.ui.Hairline
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.launch

/** The ids of the social pages and panel tabs. */
object SocialPages {
    const val PROFILE = "profile"
    const val SHARED_PLAYLIST = "shared-playlist"
    const val TOGETHER_TAB = "together"
    const val FRIENDS_TAB = "friends"
}

/** What a profile page draws: the viewer's own profile and someone else's share it. */
internal data class ProfileShown(
    val handle: String, val name: String, val bio: String, val accent: String?, val hasAvatar: Boolean, val hasBanner: Boolean,
    val listening: Track?, val listeningNow: Boolean, val top: List<TopArtist>, val pinned: List<PinnedList>,
    val modules: Set<ProfileModule>, val isPublic: Boolean,
)

/** « Profil » (PageRegistry `profile`): the listener's own profile with its edit mode, or `arg`'s profile. */
@Composable
internal fun ProfilePage(arg: String?) {
    val account by AccountSession.state.collectAsState()
    val me by Social.me.collectAsState()
    if (!account.signedIn) {
        Page("Profil", "profile") { item { EmptyMessage("Votre profil", "Connectez-vous à votre compte Ketvoice (Réglages › Compte Ketvoice) pour créer votre profil.", icon = KetIcons.Account) } }
        return
    }
    LaunchedEffect(account.userId) { Social.refreshMe(); Social.refreshTop() }
    val wanted = arg?.removePrefix("@")?.lowercase()
    if (wanted == null || wanted == me?.handle) MyProfile(me) else OtherProfile(wanted)
}

@Composable
private fun MyProfile(me: MyProfile?) {
    if (me == null) { Page("Profil", "profile") { item { Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) { Spinner() } } }; return }
    var editing by remember { mutableStateOf(false) }
    val player = LocalPlayer.current
    val playing by player.state.collectAsState()
    val shown = ProfileShown(me.handle, me.displayName, me.bio, me.accent, me.avatarPath != null, me.bannerPath != null, playing.current,
        playing.isPlaying, remember(me) { Social.topArtists() }, me.pinned, ProfileModule.entries.toSet(), me.isPublic)
    Page(null, "profile") {
        item("header") {
            Header(shown) {
                CapsuleButton(if (editing) "Terminer" else "Modifier le profil", null, { editing = !editing }, filled = !editing,
                    accent = accentOf(me.accent), modifier = Modifier.testTag("profile-edit"))
                ShareUrls.profile(me.handle)?.takeIf { me.isPublic }?.let { url ->
                    CapsuleButton("Copier le lien", KetIcons.Share, { ShareLinks.copy(url); ShareLinks.notice.value = "Lien copié" }, filled = false,
                        accent = accentOf(me.accent), modifier = Modifier.testTag("profile-copy-link"))
                }
            }
        }
        if (editing) item("edit") { ProfileEditor(me) }
        item("modules") { Modules(shown, me) }
    }
}

@Composable
private fun OtherProfile(handle: String) {
    var view by remember(handle) { mutableStateOf<ProfileView?>(null) }
    var loaded by remember(handle) { mutableStateOf(false) }
    var note by remember(handle) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(handle) { view = Social.view(handle); loaded = true }
    val v = view
    if (!loaded) { Page(null, "profile") { item { Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) { Spinner() } } }; return }
    if (v == null) { Page("Profil", "profile") { item { EmptyMessage("Profil introuvable", "Ce profil n'existe pas, ou il n'est pas visible pour vous.", icon = KetIcons.Account) } }; return }
    val shown = ProfileShown(v.handle, v.displayName, v.bio, v.accent, v.hasAvatar, v.hasBanner, v.listening, v.listeningNow, v.top, v.pinned,
        v.reaches.keys, v.isPublic)
    fun act(action: FriendAction) = scope.launch {
        val status = Social.act(action, v.handle, v.ref)
        note = FriendStates.message(status)
        view = Social.view(handle) ?: v.copy(friend = FriendStates.after(v.friend, action, status))
    }
    Page(null, "profile") {
        item("header") {
            Header(shown) {
                FriendStates.actions(v.friend).forEachIndexed { i, action ->
                    CapsuleButton(action.label, null, { act(action) }, filled = i == 0 && action != FriendAction.Remove && action != FriendAction.Cancel,
                        accent = accentOf(v.accent), modifier = Modifier.testTag("profile-friend:${action.name}"))
                }
            }
            NoticeLine(note)
        }
        if (v.hidden) item("hidden") {
            EmptyMessage("Profil privé", if (v.friend == FriendState.Friends) "" else "Devenez amis pour voir ce profil.", icon = KetIcons.Shield)
        } else item("modules") { Modules(shown, null) }
    }
}

private fun accentOf(hex: String?): Color = ProfileRules.accentArgb(hex)?.let { Color(it) } ?: Color(0xFFFA2D48)

/** The banner (the picture, or the accent's wash), the photo over its edge, the name, @handle and bio, the buttons. */
@Composable
private fun Header(p: ProfileShown, buttons: @Composable () -> Unit) {
    val ket = LocalKet.current
    val accent = accentOf(p.accent)
    var banner by remember(p.handle, p.hasBanner) { mutableStateOf<ImageBitmap?>(Pictures.cached(p.handle, "banner")) }
    if (p.hasBanner && p.handle.isNotBlank()) LaunchedEffect(p.handle) { banner = Pictures.load(p.handle, "banner") }
    Column(Modifier.fillMaxWidth().testTag("profile-header")) {
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(Ket27Desk.Radius.card))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = .85f), accent.copy(alpha = .35f), ket.card)))) {
            banner?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Row(Modifier.padding(start = 24.dp).offset(y = (-44).dp), verticalAlignment = Alignment.Bottom) {
            ProfileAvatar(p.handle.ifBlank { null }, p.name, p.accent, p.hasAvatar, 112.dp,
                Modifier.border(4.dp, ket.ground, CircleShape))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.padding(bottom = 6.dp)) {
                KText(p.name.ifBlank { "@" + p.handle }, Ket27Desk.Type.largeTitle, translate = false)
                if (p.handle.isNotBlank()) KText("@" + p.handle + if (p.isPublic) "" else " · " + AppText.t("Profil privé"), Ket27Desk.Type.subheadline,
                    color = ket.secondary, translate = false)
            }
        }
        Column(Modifier.offset(y = (-28).dp).padding(horizontal = 6.dp)) {
            if (p.bio.isNotBlank()) androidx.compose.material3.Text(p.bio, Modifier.widthIn(max = 620.dp), color = ket.label,
                style = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.callout))
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
        }
    }
}

/** The modules shown, each in its card; on one's own profile, each with who sees it. */
@Composable
private fun Modules(p: ProfileShown, me: MyProfile?) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ProfileModule.entries.filter { it in p.modules }.forEach { module ->
            if (me == null && module == ProfileModule.Now && p.listening == null) return@forEach
            if (me == null && module == ProfileModule.Top && p.top.isEmpty()) return@forEach
            if (me == null && module == ProfileModule.Pinned && p.pinned.isEmpty()) return@forEach
            Column(Modifier.testTag("profile-module:${module.wire}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KText(module.label, Ket27Desk.Type.title2, Modifier.weight(1f))
                    if (me != null) ReachPicker(module, me)
                }
                Spacer(Modifier.height(10.dp))
                when (module) {
                    ProfileModule.Now -> NowModule(p.listening, p.listeningNow)
                    ProfileModule.Top -> TopModule(p.top)
                    ProfileModule.Pinned -> PinnedModule(p.pinned, me)
                }
            }
        }
    }
}

@Composable
private fun ReachPicker(module: ProfileModule, me: MyProfile) {
    val scope = rememberCoroutineScope()
    val reach = me.reach(module)
    Column(horizontalAlignment = Alignment.End) {
        Segments(Reach.entries.map { it.label }, reach.ordinal, "profile-reach:${module.wire}") { i ->
            scope.launch { Social.setReach(module, Reach.entries[i]) }
        }
        if (reach == Reach.Public && !me.isPublic) KText("Profil privé : visible par vos amis seulement", Ket27Desk.Type.caption,
            Modifier.padding(top = 4.dp), color = LocalKet.current.secondary)
    }
}

@Composable
private fun NowModule(track: Track?, playing: Boolean) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(track?.coverUrl, 56.dp, radius = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            KText(if (track == null) "Rien en cours" else if (playing) "En train d'écouter" else "Écouté il y a peu", Ket27Desk.Type.caption, color = ket.secondary)
            if (track != null) {
                KText(track.title, Ket27Desk.Type.headline, translate = false)
                KText(track.artist, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
            }
        }
        if (track != null) SmallPill("Écouter", "profile-play-now") { player.playQueue(listOf(track), 0) }
    }
}

@Composable
private fun TopModule(top: List<TopArtist>) {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    if (top.isEmpty()) { KText("Écoutez un peu de musique ce mois-ci : vos artistes apparaîtront ici.", Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 2); return }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        top.forEachIndexed { i, a ->
            Column(Modifier.width(110.dp).clickable { nav.push(Route.Artist(ArtistRef(a.id?.takeIf { it.startsWith("UC") }, a.name))) }.testTag("profile-top:$i"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(a.cover, 96.dp, circle = true)
                Spacer(Modifier.height(8.dp))
                KText(a.name, Ket27Desk.Type.footnote, weight = FontWeight.SemiBold, translate = false)
                KText("#${i + 1}", Ket27Desk.Type.caption, color = ket.secondary, translate = false)
            }
        }
    }
}

@Composable
private fun PinnedModule(pinned: List<PinnedList>, me: MyProfile?) {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    val library = LocalLibrary.current
    val playlists by library.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    Column {
        if (pinned.isEmpty()) KText(if (me != null) "Épinglez jusqu'à six playlists sur votre profil." else "", Ket27Desk.Type.footnote, color = ket.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            pinned.forEachIndexed { i, p ->
                Column(Modifier.width(150.dp).clickableIf(p.link != null) { nav.push(Route.Extra(SocialPages.SHARED_PLAYLIST, p.link)) }.testTag("profile-pinned:$i")) {
                    Artwork(p.cover, 150.dp, radius = Ket27Desk.Radius.artwork)
                    Spacer(Modifier.height(6.dp))
                    KText(p.name, Ket27Desk.Type.footnote, weight = FontWeight.SemiBold, translate = false)
                    KText(AppText.t("${p.count} titres"), Ket27Desk.Type.caption, color = ket.secondary, translate = false)
                }
            }
        }
        if (me != null) {
            Spacer(Modifier.height(10.dp))
            SmallPill(if (choosing) "Fermer" else "Choisir les playlists", "profile-pin-choose") { choosing = !choosing }
            if (choosing) {
                val picked = remember { mutableStateOf(pinned.mapNotNull { pin -> playlists.firstOrNull { it.name == pin.name }?.id }.toSet()) }
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    playlists.forEachIndexed { i, pl ->
                        if (i > 0) Hairline()
                        ToggleRow(pl.name, pl.id in picked.value, "profile-pin:$i") { on ->
                            picked.value = if (on) (picked.value + pl.id).take(ProfileRules.PINNED_MAX).toSet() else picked.value - pl.id
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SmallPill("Épingler", "profile-pin-save", filled = true) {
                    scope.launch { Social.pin(playlists.filter { it.id in picked.value }); choosing = false }
                }
            }
        }
    }
}

/** A playlist shared by link (PageRegistry `shared-playlist`): its snapshot, to play or keep. */
@Composable
internal fun SharedPlaylistPage(id: String?) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val library = LocalLibrary.current
    var loaded by remember(id) { mutableStateOf<Pair<String, List<Track>>?>(null) }
    var missing by remember(id) { mutableStateOf(false) }
    var kept by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        val text = id?.let { Social.backend.openRpc("ketvoice_link_playlist", org.json.JSONObject().put("p_id", it)) }
        val o = text?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        if (o == null) { missing = true; return@LaunchedEffect }
        val rows = o.optJSONArray("tracks") ?: org.json.JSONArray()
        loaded = com.ketvoice.app.social.SocialText.name(o.optString("name"), 80) to
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(com.ketvoice.app.social.ProfileCodec::track) }
    }
    val data = loaded
    Page(data?.first ?: "Playlist", "shared-playlist") {
        if (missing) { item { EmptyMessage("Playlist introuvable", "Ce lien ne mène plus à une playlist.") }; return@Page }
        if (data == null) { item { Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) { Spinner() } }; return@Page }
        item("actions") {
            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CapsuleButton("Lecture", KetIcons.Play, { if (data.second.isNotEmpty()) player.playQueue(data.second, 0) }, filled = true, accent = ket.accent,
                    modifier = Modifier.testTag("shared-play"))
                CapsuleButton(if (kept) "Ajoutée" else "Ajouter à mes playlists", KetIcons.Plus, {
                    if (!kept && data.second.isNotEmpty()) { library.createPlaylist(data.first, data.second); kept = true }
                }, filled = false, accent = ket.accent, modifier = Modifier.testTag("shared-keep"))
            }
        }
        data.second.forEachIndexed { i, t ->
            item("t$i") { com.ketvoice.desktop.ui.CompactTrackRow(t, data.second, i, Modifier.fillMaxWidth()) }
        }
    }
}
