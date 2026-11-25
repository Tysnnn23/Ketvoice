package com.ketvoice.desktop.social

import androidx.compose.ui.graphics.ImageBitmap
import com.ketvoice.app.data.LiveStats
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.social.FriendAction
import com.ketvoice.app.social.FriendLists
import com.ketvoice.app.social.FriendNow
import com.ketvoice.app.social.FriendStates
import com.ketvoice.app.social.FriendsNow
import com.ketvoice.app.social.PinnedList
import com.ketvoice.app.social.ProfileCodec
import com.ketvoice.app.social.ProfileModule
import com.ketvoice.app.social.ProfileRules
import com.ketvoice.app.social.ProfileView
import com.ketvoice.app.social.Reach
import com.ketvoice.app.social.SocialText
import com.ketvoice.app.social.TopArtist
import com.ketvoice.desktop.ui.UserPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The listener's own profile as they edit it. Everything new starts private. */
data class MyProfile(
    val displayName: String = "", val handle: String = "", val bio: String = "", val accent: String? = null,
    val avatarPath: String? = null, val bannerPath: String? = null, val isPublic: Boolean = false, val shareListening: Boolean = false,
    val reaches: Map<ProfileModule, Reach> = emptyMap(), val pinned: List<PinnedList> = emptyList(),
) {
    fun reach(module: ProfileModule): Reach = reaches[module] ?: Reach.Me
}

/**
 * The social state of this window: the listener's profile, friends, « Activité des amis » and the pictures shown,
 * over a [SocialBackend]. Every call is the listener's own choice, or a refresh while a social page is open.
 */
object Social {
    @Volatile var backend: SocialBackend = RestSocial

    private val _me = MutableStateFlow<MyProfile?>(null)
    val me: StateFlow<MyProfile?> = _me
    private val _friends = MutableStateFlow(FriendLists())
    val friends: StateFlow<FriendLists> = _friends
    private val _now = MutableStateFlow<List<FriendNow>>(emptyList())
    val now: StateFlow<List<FriendNow>> = _now

    val signedIn: Boolean get() = backend.userId() != null

    // ---------------------------------------------------------------- my profile
    suspend fun refreshMe(): MyProfile? {
        val row = backend.myProfile() ?: return _me.value
        val modules = backend.myModules() ?: JSONArray()
        val reaches = HashMap<ProfileModule, Reach>()
        var pinned = emptyList<PinnedList>()
        for (i in 0 until modules.length()) {
            val m = modules.optJSONObject(i) ?: continue
            val module = ProfileModule.of(m.optString("module")) ?: continue
            reaches[module] = Reach.of(m.optString("reach"))
            if (module == ProfileModule.Pinned) pinned = ProfileCodec.pinned(m.optJSONObject("data"))
        }
        fun text(key: String) = row.optString(key).takeIf { !row.isNull(key) }.orEmpty()
        val me = MyProfile(text("display_name"), text("handle"), text("bio"), ProfileRules.accent(text("accent")),
            text("avatar_url").ifBlank { null }, text("banner_url").ifBlank { null }, row.optBoolean("is_public"),
            row.optBoolean("share_listening"), reaches, pinned)
        _me.value = me
        return me
    }

    /** Saves the identity; null when it worked, else what to tell the listener (French). */
    suspend fun saveIdentity(name: String, handle: String, bio: String, accent: String?, isPublic: Boolean): String? {
        val cleanName = name.trim().replace(Regex("\\s+"), " ")
        if (cleanName.length !in 1..40 || cleanName.any(Char::isISOControl)) return "Nom invalide (1 à 40 caractères)."
        val tag = handle.trim().removePrefix("@").lowercase().ifBlank { null }
        if (tag != null && !Regex("[a-z0-9_.]{3,24}").matches(tag)) return "Nom d'utilisateur : 3 à 24 lettres, chiffres, points ou tirets bas."
        if (isPublic && tag == null) return "Un profil public a besoin d'un nom d'utilisateur."
        val fields = JSONObject().put("display_name", cleanName).put("handle", tag ?: JSONObject.NULL)
            .put("bio", ProfileRules.cleanBio(bio)).put("accent", ProfileRules.accent(accent) ?: JSONObject.NULL).put("is_public", isPublic)
        val status = backend.patchProfile(fields)
        if (status == 409) return "Ce nom d'utilisateur est déjà pris."
        if (status !in 200..299) return "Enregistrement impossible pour le moment."
        refreshMe()
        return null
    }

    /** « Partager mon écoute »: on writes this PC's title for friends; off removes it at once (server side too). */
    suspend fun setShareListening(on: Boolean): Boolean {
        if (on && _me.value?.handle.isNullOrBlank()) return false
        val ok = backend.patchProfile(JSONObject().put("share_listening", on)) in 200..299
        if (ok) _me.value = _me.value?.copy(shareListening = on)
        if (ok && on) Presence.force()
        return ok
    }

    /** A module's reach, with its data as it is now (top artists computed here, pinned lists as kept). */
    suspend fun setReach(module: ProfileModule, reach: Reach): Boolean {
        val me = _me.value ?: return false
        val data = when (module) {
            ProfileModule.Top -> ProfileCodec.topData(topArtists())
            ProfileModule.Pinned -> ProfileCodec.pinnedData(me.pinned)
            ProfileModule.Now -> JSONObject()
        }
        val ok = backend.putModule(module.wire, reach.wire, data)
        if (ok) _me.value = me.copy(reaches = me.reaches + (module to reach))
        return ok
    }

    /** The artists of the month from this computer's statistics (no server table: the profile holds a snapshot). */
    internal fun topArtists(input: LiveStats.Input = com.ketvoice.desktop.ui.historyInput()): List<TopArtist> =
        LiveStats.compute(LiveStats.Period.Month, input).artists.take(ProfileRules.TOP_MAX)
            .map { TopArtist(it.name, it.cover, it.catalogId) }

    /** Refreshes the top artists' snapshot when the module exists (on opening the profile, at most once a day). */
    suspend fun refreshTop(today: java.time.LocalDate = java.time.LocalDate.now()) {
        val reach = _me.value?.reaches?.get(ProfileModule.Top) ?: return
        if (topWritten == today) return
        if (backend.putModule(ProfileModule.Top.wire, reach.wire, ProfileCodec.topData(topArtists()))) topWritten = today
    }

    @Volatile private var topWritten: java.time.LocalDate? = null

    /** Pins [lists] (at most six): each gets a link snapshot, so visitors can open it. */
    suspend fun pin(lists: List<UserPlaylist>): Boolean {
        val me = _me.value ?: return false
        val pinned = lists.take(ProfileRules.PINNED_MAX).mapNotNull { p ->
            val link = ShareLinks.saveSnapshot(p) ?: return@mapNotNull null
            PinnedList(p.name, p.tracks.firstNotNullOfOrNull { it.coverUrl }, p.tracks.size, link)
        }
        val reach = me.reach(ProfileModule.Pinned)
        val ok = backend.putModule(ProfileModule.Pinned.wire, reach.wire, ProfileCodec.pinnedData(pinned))
        if (ok) _me.value = me.copy(pinned = pinned, reaches = me.reaches + (ProfileModule.Pinned to reach))
        return ok
    }

    /** Uploads the framed photo or banner, points the profile at it, then removes the old one. Null when it worked. */
    suspend fun setPicture(kind: ProfileImages.Kind, jpeg: ByteArray): String? {
        val uid = backend.userId() ?: return "Connexion requise"
        val path = ProfileImages.path(uid, kind)
        if (!backend.upload(path, jpeg)) return "Envoi de l'image impossible pour le moment."
        val column = if (kind == ProfileImages.Kind.Avatar) "avatar_url" else "banner_url"
        if (backend.patchProfile(JSONObject().put(column, path)) !in 200..299) { backend.removeObjects(listOf(path)); return "Envoi de l'image impossible pour le moment." }
        val old = if (kind == ProfileImages.Kind.Avatar) _me.value?.avatarPath else _me.value?.bannerPath
        old?.takeIf { it != path }?.let { backend.removeObjects(listOf(it)) }
        _me.value = _me.value?.let { if (kind == ProfileImages.Kind.Avatar) it.copy(avatarPath = path) else it.copy(bannerPath = path) }
        _me.value?.handle?.takeIf { it.isNotBlank() }?.let { Pictures.forget(it) }
        return null
    }

    suspend fun removePicture(kind: ProfileImages.Kind): Boolean {
        val column = if (kind == ProfileImages.Kind.Avatar) "avatar_url" else "banner_url"
        if (backend.patchProfile(JSONObject().put(column, JSONObject.NULL)) !in 200..299) return false
        val old = if (kind == ProfileImages.Kind.Avatar) _me.value?.avatarPath else _me.value?.bannerPath
        old?.let { backend.removeObjects(listOf(it)) }
        _me.value = _me.value?.let { if (kind == ProfileImages.Kind.Avatar) it.copy(avatarPath = null) else it.copy(bannerPath = null) }
        _me.value?.handle?.takeIf { it.isNotBlank() }?.let { Pictures.forget(it) }
        return true
    }

    // ---------------------------------------------------------------- other profiles
    suspend fun view(handle: String): ProfileView? =
        backend.rpc("ketvoice_profile_view", JSONObject().put("handle", handle))?.let { runCatching { ProfileCodec.view(JSONObject(it)) }.getOrNull() }

    // ---------------------------------------------------------------- friends
    suspend fun refreshFriends() {
        val text = backend.rpc("ketvoice_friend_lists") ?: return
        _friends.value = runCatching { FriendStates.lists(JSONObject(text)) }.getOrDefault(_friends.value)
    }

    suspend fun refreshNow() {
        val text = backend.rpc("ketvoice_friends_now") ?: return
        _now.value = runCatching { FriendsNow.parse(JSONArray(text)) }.getOrDefault(_now.value)
    }

    /** Runs [action] and answers the server's status (`error` when it did not answer). */
    suspend fun act(action: FriendAction, handle: String?, ref: String?): String {
        val text = when (action) {
            FriendAction.Ask -> handle?.let { backend.rpc("ketvoice_friend_request", JSONObject().put("handle", it)) }
            FriendAction.Accept, FriendAction.Decline -> ref?.let {
                backend.rpc("ketvoice_friend_answer", JSONObject().put("p_ref", it).put("p_accept", action == FriendAction.Accept))
            }
            FriendAction.Cancel, FriendAction.Remove -> ref?.let { backend.rpc("ketvoice_friend_remove", JSONObject().put("p_ref", it)) }
        }
        val status = text?.let { runCatching { JSONObject(it).optString("status") }.getOrNull() } ?: "error"
        refreshFriends()
        return status
    }

    /** Signed out: nothing of the account stays on screen. */
    fun clear() { _me.value = null; _friends.value = FriendLists(); _now.value = emptyList(); topWritten = null; Pictures.clear() }
}

/**
 * « Partager mon écoute », written from this PC: the title, play/pause and the Ketogether room, through
 * `ketvoice_presence_put`, on a change or once a minute while sharing. Nothing at all while the switch is off.
 */
object Presence {
    private var last: FriendsNow.Snapshot? = null

    fun force() { last = null }

    /** One step of the loop (every few seconds); [now] in ms. */
    suspend fun step(now: Long) {
        val me = Social.me.value ?: return
        if (!me.shareListening || !Social.signedIn) { last = null; return }
        val s = Player.state.value
        val track: Track? = s.current
        val room = DesktopTogether.roomWire()
        val snap = FriendsNow.Snapshot(track?.id, s.isPlaying, room, now)
        if (!FriendsNow.due(last, snap, now)) return
        val body = JSONObject().put("p_track", FriendsNow.trackJson(track) ?: JSONObject.NULL).put("p_playing", s.isPlaying)
            .put("p_room", room ?: JSONObject.NULL)
        if (Social.backend.rpc("ketvoice_presence_put", body) != null) last = snap
    }
}

/** Profile photos and banners shown in the window, fetched once per handle while the window lives. */
object Pictures {
    private val cache = HashMap<String, ImageBitmap?>()

    fun cached(handle: String, kind: String): ImageBitmap? = synchronized(cache) { cache["$kind:$handle"] }

    suspend fun load(handle: String, kind: String): ImageBitmap? {
        val key = "$kind:$handle"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val image = Social.backend.picture(handle, kind)?.let { runCatching { com.ketvoice.desktop.ui.Covers.decode(it, 1500) }.getOrNull() }
        synchronized(cache) { if (cache.size > 200) cache.clear(); cache[key] = image }
        return image
    }

    fun forget(handle: String) = synchronized(cache) { cache.keys.removeAll { it.endsWith(":$handle") } }
    fun clear() = synchronized(cache) { cache.clear() }
}

/** A display name for a line of a list, never blank. */
internal fun nameOf(name: String, handle: String): String = SocialText.name(name).ifBlank { "@$handle" }
