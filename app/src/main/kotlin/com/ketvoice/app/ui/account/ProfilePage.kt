package com.ketvoice.app.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.PublicPlaylist
import com.ketvoice.app.account.PublicProfile
import com.ketvoice.app.account.PublicProfiles
import com.ketvoice.app.account.RemoteAvatars
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.DESTRUCTIVE
import com.ketvoice.app.ui.common.Ket27PageBar
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.library.PersonAvatar
import java.io.File

/** The profile page that is open (the handle it was opened from), if any: from a link, a search or a member's photo. */
object ProfileUi {
    val handle = mutableStateOf<String?>(null)

    fun open(handle: String) {
        val clean = handle.trim().removePrefix("@").lowercase()
        if (clean.isNotEmpty()) this.handle.value = clean
    }

    fun close() { handle.value = null }
}

/** What the page has of the profile: still loading, nothing to show (and why) or the profile. */
sealed interface ProfileLoad {
    data object Loading : ProfileLoad
    data object SignedOut : ProfileLoad
    /** Unknown handle or a private profile: the server does not tell which. */
    data object NotFound : ProfileLoad
    data object Failed : ProfileLoad
    data class Loaded(val profile: PublicProfile) : ProfileLoad
}

/** Over everything but the account sheet's alerts: opened by [ProfileUi.open]. */
@Composable
fun ProfileHost(modifier: Modifier = Modifier) {
    val handle = ProfileUi.handle.value ?: return
    ProfilePage(handle, modifier.zIndex(8.5f))
}

@Composable
fun ProfilePage(handle: String, modifier: Modifier = Modifier) {
    val account by AccountSession.state.collectAsState()
    var state by remember(handle) { mutableStateOf<ProfileLoad>(ProfileLoad.Loading) }
    var attempt by remember(handle) { mutableIntStateOf(0) }
    var avatar by remember(handle) { mutableStateOf<File?>(null) }
    LaunchedEffect(handle, account.signedIn, attempt) {
        if (!account.signedIn) { state = ProfileLoad.SignedOut; return@LaunchedEffect }
        state = ProfileLoad.Loading
        state = try { PublicProfiles.lookup(handle)?.let { ProfileLoad.Loaded(it) } ?: ProfileLoad.NotFound
        } catch (stopped: kotlinx.coroutines.CancellationException) { throw stopped
        } catch (error: Exception) { ProfileLoad.Failed }
    }
    val loaded = (state as? ProfileLoad.Loaded)?.profile
    LaunchedEffect(loaded?.avatarRef) {
        avatar = loaded?.avatarRef?.let { RemoteAvatars.get(RemoteAvatars.PUBLIC, it + ".jpg") }
    }
    BackHandler { ProfileUi.close() }
    val follow = com.ketvoice.app.ui.friends.rememberProfileFollow(loaded)
    ProfileContent(handle, state, avatar, onPlay = ::playAll, onPlayTrack = ::playFrom, onRetry = { attempt++ },
        onSignIn = { AppUi_showAccount() }, onBack = ProfileUi::close, modifier = modifier, follow = follow)
}

private fun AppUi_showAccount() { com.ketvoice.app.ui.AppUi.showAccount.value = true }

private fun playAll(playlist: PublicPlaylist) {
    if (playlist.tracks.isEmpty()) return
    Player.setShuffle(false)
    Player.playQueue(playlist.tracks)
}

private fun playFrom(playlist: PublicPlaylist, track: Track) {
    val index = playlist.tracks.indexOf(track).coerceAtLeast(0)
    Player.setShuffle(false)
    Player.playTrack(track, playlist.tracks, index, 0L)
}

/** The page itself, without any network: the avatar, the name, what is playing, the public playlists (tap to play). */
@Composable
fun ProfileContent(handle: String, state: ProfileLoad, avatar: File?, onPlay: (PublicPlaylist) -> Unit,
                   onPlayTrack: (PublicPlaylist, Track) -> Unit, onRetry: () -> Unit, onSignIn: () -> Unit, onBack: () -> Unit,
                   modifier: Modifier = Modifier, follow: com.ketvoice.app.ui.friends.ProfileFollow? = null) {
    val theme = LocalTheme.current
    Column(modifier.fillMaxSize().background(theme.background).statusBarsPadding().testTag("profile-page")) {
        Ket27PageBar("Profil", onBack)
        when (state) {
            ProfileLoad.Loading -> CenterNote { ActivitySpinner(size = 24.dp) }
            ProfileLoad.SignedOut -> CenterNote {
                Text("Connectez-vous à votre compte Ketvoice pour voir les profils.", color = theme.secondary, textAlign = TextAlign.Center)
                KetTextButton("Se connecter", onSignIn, primary = true)
            }
            ProfileLoad.NotFound -> CenterNote {
                Text("@$handle", localize = false, color = theme.onBackground, style = Ket27.Type.title3)
                Text("Profil introuvable ou privé.", color = theme.secondary, textAlign = TextAlign.Center)
            }
            ProfileLoad.Failed -> CenterNote {
                Text("Impossible de charger ce profil pour le moment.", color = DESTRUCTIVE, textAlign = TextAlign.Center)
                KetTextButton("Réessayer", onRetry, primary = true)
            }
            is ProfileLoad.Loaded -> ProfileBody(state.profile, avatar, onPlay, onPlayTrack, follow)
        }
    }
}

@Composable
private fun CenterNote(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
private fun ProfileBody(profile: PublicProfile, avatar: File?, onPlay: (PublicPlaylist) -> Unit, onPlayTrack: (PublicPlaylist, Track) -> Unit,
                        follow: com.ketvoice.app.ui.friends.ProfileFollow? = null) {
    val theme = LocalTheme.current
    var open by remember(profile.handle) { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("profile-list"), contentPadding = PaddingValues(bottom = 120.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PersonAvatar(profile.displayName.ifBlank { profile.handle }, avatar, 112.dp, Modifier.testTag("profile-avatar"))
                Text(profile.displayName.ifBlank { profile.handle }, localize = false, color = theme.onBackground, style = Ket27.Type.largeTitle,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp).testTag("profile-name"))
                Text("@" + profile.handle, localize = false, color = theme.secondary, style = Ket27.Type.subheadline, modifier = Modifier.testTag("profile-handle"))
                if (follow != null) com.ketvoice.app.ui.friends.FollowButton(follow, Modifier.padding(top = 14.dp))
            }
        }
        profile.listening?.let { listening ->
            item(key = "listening") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(if (listening.playing) "Écoute en ce moment" else "Dernière écoute", color = theme.secondary, style = Ket27.Type.footnote)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(16.dp)).background(theme.card).padding(10.dp)
                        .testTag("profile-listening"), verticalAlignment = Alignment.CenterVertically) {
                        Cover(listening.coverUrl, 52)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(listening.title, localize = false, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (listening.artist.isNotBlank()) Text(listening.artist, localize = false, color = theme.secondary,
                                style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (listening.playing) Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF34C759)))
                    }
                }
            }
        }
        item(key = "playlists-title") {
            Text("Playlists publiques", color = theme.onBackground, style = Ket27.Type.title2, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
        }
        if (profile.playlists.isEmpty()) item(key = "none") {
            Text("Aucune playlist publique.", color = theme.secondary, style = Ket27.Type.subheadline, modifier = Modifier.padding(horizontal = 16.dp))
        }
        items(profile.playlists, key = { it.id }) { playlist ->
            PlaylistLine(playlist, open == playlist.id, { open = if (open == playlist.id) null else playlist.id }, onPlay, onPlayTrack)
        }
        if (follow != null) item(key = "block") { com.ketvoice.app.ui.friends.BlockProfileButton(follow) }
    }
}

@Composable
private fun Cover(url: String?, size: Int) {
    val theme = LocalTheme.current
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(theme.onBackground.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else androidx.compose.material3.Icon(KetIcons.Music, null, tint = theme.secondary, modifier = Modifier.size((size / 2).dp))
    }
}

@Composable
private fun PlaylistLine(playlist: PublicPlaylist, expanded: Boolean, onToggle: () -> Unit, onPlay: (PublicPlaylist) -> Unit,
                         onPlayTrack: (PublicPlaylist, Track) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().testTag("public-playlist-" + playlist.id)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPlay(playlist) }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Cover(playlist.coverUrl ?: playlist.tracks.firstNotNullOfOrNull { it.coverUrl }, 56)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(playlist.name, localize = false, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (playlist.tracks.size == 1) "1 titre" else "${playlist.tracks.size} titres", color = theme.secondary, style = Ket27.Type.footnote)
            }
            androidx.compose.material3.Icon(KetIcons.Play, AppText.t("Écouter"), tint = theme.accent, modifier = Modifier.size(24.dp))
            Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onToggle).testTag("public-playlist-toggle"), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
            }
        }
        if (expanded) playlist.tracks.forEachIndexed { index, track ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPlayTrack(playlist, track) }.padding(start = 40.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", localize = false, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, localize = false, color = theme.onBackground, style = Ket27.Type.subheadline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, localize = false, color = theme.secondary, style = Ket27.Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
