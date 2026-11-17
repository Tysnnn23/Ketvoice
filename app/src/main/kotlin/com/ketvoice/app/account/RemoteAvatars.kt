package com.ketvoice.app.account

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Other people's photos: a co-member's (the private bucket, readable by someone one shares a playlist with)
 * and a public profile's copy (a random name in `ketvoice-public-avatars`). Read with the listener's own
 * session, kept as small files in the cache, forgotten at sign-out.
 */
internal object RemoteAvatars {
    const val PRIVATE = Profiles.BUCKET
    const val PUBLIC = "ketvoice-public-avatars"
    private const val MAX = 1024 * 1024
    private var context: Context? = null

    fun init(context: Context) { this.context = context.applicationContext }

    private fun dir(): File? = context?.let { File(it.cacheDir, "avatars").apply { mkdirs() } }

    internal fun cacheName(bucket: String, path: String): String = bucket + "-" + path.map { if (it == '/') '-' else it }.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.joinToString("")

    /** The photo at [path] of [bucket] as a file, or null (no photo, no access, offline). */
    suspend fun get(bucket: String, path: String): File? = withContext(Dispatchers.IO) {
        val directory = dir() ?: return@withContext null
        if (path.isBlank() || path.length > 200 || ".." in path) return@withContext null
        val file = File(directory, cacheName(bucket, path))
        if (file.exists() && file.length() > 0) return@withContext file
        runCatching {
            val response = AccountSession.authorized { token ->
                SupabaseHttp.request("GET", "/storage/v1/object/authenticated/$bucket/$path", token = token)
            }
            if (!response.ok || response.bytes.isEmpty() || response.bytes.size > MAX) return@runCatching null
            File(directory, file.name + ".tmp").also { it.writeBytes(response.bytes); it.renameTo(file) }
            file
        }.getOrNull()
    }

    fun forget() { dir()?.deleteRecursively() }
}

/**
 * The public copy of the listener's photo (see the migration): while the profile is public the photo is
 * uploaded once under a random name; the profile page of others reads that copy, never the private photo
 * (whose path holds the account's id). It is removed when the profile goes private or the photo goes.
 */
internal object PublicAvatar {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs get() = Profiles.localPrefs()

    /** The profile or the photo changed: puts the copy right, in the background. */
    fun changed() { scope.launch { runCatching { sync() } } }

    internal suspend fun sync() {
        val uid = AccountSession.userId() ?: return
        val profile = Profiles.profile.value
        val file = Profiles.avatar.value
        val wanted = profile.isPublic && file != null && file.exists()
        val copiedFor = prefs?.getString("publicAvatarFor", "").orEmpty()
        if (wanted && profile.avatarRef.isNotBlank() && copiedFor == profile.avatarPath) return
        if (profile.avatarRef.isNotBlank()) deleteObject(profile.avatarRef)
        if (!wanted) { setRef(uid, null); return }
        val ref = UUID.randomUUID().toString()
        setRef(uid, ref)
        val upload = AccountSession.authorized { token ->
            SupabaseHttp.request("POST", "/storage/v1/object/${RemoteAvatars.PUBLIC}/$ref.jpg", file!!.readBytes(), token, "image/jpeg",
                mapOf("x-upsert" to "false", "cache-control" to "3600"))
        }
        if (upload.ok) prefs?.edit()?.putString("publicAvatarFor", profile.avatarPath)?.apply()
    }

    /** Before the account goes (and when signing out is not enough): the copy is deleted while the profile still names it. */
    suspend fun removeCopy() {
        val ref = Profiles.profile.value.avatarRef
        if (ref.isNotBlank()) runCatching { deleteObject(ref) }
    }

    private suspend fun deleteObject(ref: String) {
        AccountSession.authorized { token ->
            SupabaseHttp.request("DELETE", "/storage/v1/object/${RemoteAvatars.PUBLIC}", JSONObject()
                .put("prefixes", org.json.JSONArray(listOf("$ref.jpg"))).toString().toByteArray(), token)
        }
        prefs?.edit()?.remove("publicAvatarFor")?.apply()
    }

    private suspend fun setRef(uid: String, ref: String?) {
        val body = JSONObject().put("avatar_ref", ref ?: JSONObject.NULL).toString()
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("PATCH", "/rest/v1/ketvoice_profiles?id=eq.$uid", body.toByteArray(), token,
                headers = mapOf("Prefer" to "return=minimal"))
        }
        check(response.ok) { "Enregistrement impossible pour le moment." }
        Profiles.keep(Profiles.profile.value.copy(avatarRef = ref.orEmpty()))
    }
}
