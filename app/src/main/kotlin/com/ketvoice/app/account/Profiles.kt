package com.ketvoice.app.account

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The listener's profile: a name, an optional @handle and a photo (a stored object path, never a public link). */
data class AccountProfile(val displayName: String = "", val handle: String = "", val avatarPath: String = "",
                          /** Others may open the profile page by its @handle (off by default, and it needs a handle). */
                          val isPublic: Boolean = false,
                          /** The profile page shows the title playing now (off by default; only with a public profile). */
                          val showListening: Boolean = false,
                          /** Random name of the photo's public copy, see the migration of 2026-11-18. */
                          val avatarRef: String = "")

/**
 * The profile of the signed-in listener, kept on the device for offline display: the name in the app's
 * preferences, the photo as a small file. The photo lives in the private `ketvoice-avatars` bucket and is
 * only ever read with the listener's own session.
 */
object Profiles {
    internal const val BUCKET = "ketvoice-avatars"
    private const val PREFS = "ketvoice-account"
    private val _profile = MutableStateFlow(AccountProfile())
    val profile: StateFlow<AccountProfile> = _profile
    /** The photo on this device, or null; its file changes (and its name with it) when the photo does. */
    private val _avatar = MutableStateFlow<File?>(null)
    val avatar: StateFlow<File?> = _avatar
    private var context: Context? = null

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        RemoteAvatars.init(context)
        val p = prefs() ?: return
        _profile.value = AccountProfile(p.getString("name", "").orEmpty(), p.getString("handle", "").orEmpty(),
            p.getString("avatar", "").orEmpty(), p.getBoolean("public", false), p.getBoolean("showListening", false),
            p.getString("avatarRef", "").orEmpty())
        _avatar.value = avatarDir()?.listFiles()?.firstOrNull { it.name.startsWith("avatar-") }
    }

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    internal fun localPrefs() = prefs()
    private fun avatarDir(): File? = context?.let { File(it.filesDir, "account").apply { mkdirs() } }

    internal fun keep(profile: AccountProfile) {
        _profile.value = profile
        prefs()?.edit()?.putString("name", profile.displayName)?.putString("handle", profile.handle)
            ?.putString("avatar", profile.avatarPath)?.putBoolean("public", profile.isPublic)
            ?.putBoolean("showListening", profile.showListening)?.putString("avatarRef", profile.avatarRef)?.apply()
    }

    internal fun signedIn(userId: String, displayName: String?) {
        if (displayName != null) keep(AccountProfile(displayName))
    }

    internal fun signedOut(context: Context) {
        keep(AccountProfile())
        RemoteAvatars.forget()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        File(context.filesDir, "account").listFiles()?.filter { it.name.startsWith("avatar-") }?.forEach { it.delete() }
        _avatar.value = null
    }

    internal fun parse(row: JSONObject): AccountProfile = AccountProfile(
        if (row.isNull("display_name")) "" else row.optString("display_name"),
        if (row.isNull("handle")) "" else row.optString("handle"),
        if (row.isNull("avatar_url")) "" else row.optString("avatar_url"),
        row.optBoolean("is_public"), row.optBoolean("show_listening"),
        if (row.isNull("avatar_ref")) "" else row.optString("avatar_ref"))

    /** Reads the profile from the server (creates it when missing) and fetches the photo when it changed. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val uid = AccountSession.userId() ?: return@withContext
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("GET", "/rest/v1/ketvoice_profiles?select=display_name,handle,avatar_url,is_public,show_listening,avatar_ref&id=eq.$uid", token = token)
        }
        if (!response.ok) return@withContext
        val rows = JSONArray(response.text)
        if (rows.length() == 0) { create(uid); return@withContext }
        val remote = parse(rows.getJSONObject(0))
        keep(remote)
        if (remote.avatarPath.isNotBlank() && _avatar.value?.name != localName(remote.avatarPath)) download(remote.avatarPath)
        if (remote.avatarPath.isBlank()) clearLocalAvatar()
    }

    private suspend fun create(uid: String) {
        val name = _profile.value.displayName
        AccountSession.authorized { token ->
            SupabaseHttp.request("POST", "/rest/v1/ketvoice_profiles", JSONObject().put("id", uid).put("display_name", name)
                .toString().toByteArray(), token, headers = mapOf("Prefer" to "return=minimal,resolution=ignore-duplicates"))
        }
        keep(_profile.value.copy(displayName = name))
    }

    /** Saves the name and the handle; a handle taken by someone else is refused by the server (unique). */
    suspend fun update(displayName: String, handle: String) = withContext(Dispatchers.IO) {
        val uid = AccountSession.userId() ?: error("Connexion requise")
        val name = AccountRules.displayName(displayName)
        val tag = AccountRules.handle(handle)
        require(tag != null || !_profile.value.isPublic) { "Désactivez d'abord le profil public pour retirer le nom d'utilisateur." }
        val body = JSONObject().put("display_name", name).put("handle", tag ?: JSONObject.NULL)
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("PATCH", "/rest/v1/ketvoice_profiles?id=eq.$uid", body.toString().toByteArray(), token,
                headers = mapOf("Prefer" to "return=minimal"))
        }
        if (response.status == 409) error("Ce nom d'utilisateur est déjà pris.")
        check(response.ok) { "Enregistrement impossible pour le moment." }
        keep(_profile.value.copy(displayName = name, handle = tag.orEmpty()))
    }

    /** Uploads [jpeg] (already square and compressed), points the profile at it, then removes the old photo. */
    suspend fun uploadAvatar(jpeg: ByteArray) = withContext(Dispatchers.IO) {
        val uid = AccountSession.userId() ?: error("Connexion requise")
        require(jpeg.size in 1..AvatarImage.MAX_BYTES)
        val path = "$uid/avatar-${System.currentTimeMillis()}.jpg"
        val upload = AccountSession.authorized { token ->
            SupabaseHttp.request("POST", "/storage/v1/object/$BUCKET/$path", jpeg, token, "image/jpeg",
                mapOf("x-upsert" to "false", "cache-control" to "3600"))
        }
        check(upload.ok) { "Envoi de la photo impossible pour le moment." }
        val old = _profile.value.avatarPath
        val patch = AccountSession.authorized { token ->
            SupabaseHttp.request("PATCH", "/rest/v1/ketvoice_profiles?id=eq.$uid", JSONObject().put("avatar_url", path).toString().toByteArray(),
                token, headers = mapOf("Prefer" to "return=minimal"))
        }
        check(patch.ok) { "Envoi de la photo impossible pour le moment." }
        keep(_profile.value.copy(avatarPath = path))
        storeLocal(path, jpeg)
        if (old.isNotBlank() && old != path) removeObjects(listOf(old))
        PublicAvatar.changed()
    }

    suspend fun removeAvatar() = withContext(Dispatchers.IO) {
        val uid = AccountSession.userId() ?: return@withContext
        val old = _profile.value.avatarPath
        val patch = AccountSession.authorized { token ->
            SupabaseHttp.request("PATCH", "/rest/v1/ketvoice_profiles?id=eq.$uid", "{\"avatar_url\":null}".toByteArray(),
                token, headers = mapOf("Prefer" to "return=minimal"))
        }
        check(patch.ok) { "Enregistrement impossible pour le moment." }
        keep(_profile.value.copy(avatarPath = ""))
        clearLocalAvatar()
        if (old.isNotBlank()) removeObjects(listOf(old))
        PublicAvatar.changed()
    }

    /**
     * « Profil public » and « Montrer ce que j'écoute »: saved on the profile. A public profile needs a
     * @handle (the server refuses it otherwise). Both are off until the listener turns them on.
     */
    suspend fun setVisibility(isPublic: Boolean, showListening: Boolean) = withContext(Dispatchers.IO) {
        val uid = AccountSession.userId() ?: error("Connexion requise")
        require(!isPublic || _profile.value.handle.isNotBlank()) { "Choisissez d'abord un nom d'utilisateur (Modifier le profil)." }
        val shows = showListening && isPublic
        val body = JSONObject().put("is_public", isPublic).put("show_listening", shows).toString()
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("PATCH", "/rest/v1/ketvoice_profiles?id=eq.$uid", body.toByteArray(), token,
                headers = mapOf("Prefer" to "return=minimal"))
        }
        check(response.ok) { "Enregistrement impossible pour le moment." }
        keep(_profile.value.copy(isPublic = isPublic, showListening = shows))
        PublicAvatar.changed()
    }

    private suspend fun download(path: String) {
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("GET", "/storage/v1/object/authenticated/$BUCKET/$path", token = token)
        }
        if (response.ok && response.bytes.size <= AvatarImage.MAX_BYTES) storeLocal(path, response.bytes)
    }

    internal fun localName(path: String): String = "avatar-" + path.substringAfterLast("avatar-", "").filter { it.isLetterOrDigit() || it == '.' }

    private fun storeLocal(path: String, bytes: ByteArray) {
        val dir = avatarDir() ?: return
        val file = File(dir, localName(path))
        File(dir, file.name + ".tmp").also { it.writeBytes(bytes); it.renameTo(file) }
        dir.listFiles()?.filter { it.name.startsWith("avatar-") && it.name != file.name }?.forEach { it.delete() }
        _avatar.value = file
    }

    private fun clearLocalAvatar() {
        avatarDir()?.listFiles()?.filter { it.name.startsWith("avatar-") }?.forEach { it.delete() }
        _avatar.value = null
    }

    private suspend fun removeObjects(paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            AccountSession.authorized { token ->
                SupabaseHttp.request("DELETE", "/storage/v1/object/$BUCKET", JSONObject().put("prefixes", JSONArray(paths))
                    .toString().toByteArray(), token)
            }
        }
    }

    /** Before the account goes: every photo the listener stored (the server refuses the deletion while one remains). */
    internal suspend fun deleteAllAvatars() {
        val uid = AccountSession.userId() ?: return
        PublicAvatar.removeCopy()
        val listed = AccountSession.authorized { token ->
            SupabaseHttp.request("POST", "/storage/v1/object/list/$BUCKET", JSONObject().put("prefix", "$uid/").put("limit", 100)
                .toString().toByteArray(), token)
        }
        check(listed.ok) { "Suppression impossible pour le moment." }
        val rows = JSONArray(listed.text)
        val names = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
        if (names.isNotEmpty()) {
            val response = AccountSession.authorized { token ->
                SupabaseHttp.request("DELETE", "/storage/v1/object/$BUCKET", JSONObject().put("prefixes", JSONArray(names.map { "$uid/$it" }))
                    .toString().toByteArray(), token)
            }
            check(response.ok) { "Suppression impossible pour le moment." }
        }
    }

    /** For host renders: a profile shown without any network. */
    internal fun showForTest(profile: AccountProfile, avatar: File? = null) { _profile.value = profile; _avatar.value = avatar }
}
