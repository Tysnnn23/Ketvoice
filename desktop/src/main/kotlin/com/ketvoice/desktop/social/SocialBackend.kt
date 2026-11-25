package com.ketvoice.desktop.social

import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.SupabaseHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the social features ask of the server (Windows 2.0): the listener's own rows under owner-only RLS, the
 * functions of supabase/migrations/20261125120*_social_*.sql, and pictures through the `ketvoice-profile` edge
 * function. Always the listener's own session, never a key of ours; nothing is logged. Tests give a fake.
 */
interface SocialBackend {
    /** The answer's JSON text, or null (signed out, offline, refused). */
    suspend fun rpc(name: String, body: JSONObject = JSONObject()): String?
    /** A function open to anyone (a playlist link), called with the listener's session or without one. */
    suspend fun openRpc(name: String, body: JSONObject): String?
    suspend fun myProfile(): JSONObject?
    /** The HTTP status (409: the handle is taken), 0 when nothing could be sent. */
    suspend fun patchProfile(fields: JSONObject): Int
    suspend fun myModules(): JSONArray?
    suspend fun putModule(module: String, reach: String, data: JSONObject): Boolean
    suspend fun upload(path: String, jpeg: ByteArray): Boolean
    suspend fun removeObjects(paths: List<String>)
    /** A profile's photo or banner as the server lets this listener see it. */
    suspend fun picture(handle: String, kind: String): ByteArray?
    /** Saves a playlist's snapshot behind a link; its random id, or null. */
    suspend fun saveLink(localId: String, name: String, cover: String?, tracks: JSONArray): String?
    fun userId(): String?
}

object RestSocial : SocialBackend {
    private const val BUCKET = "ketvoice-avatars"

    private suspend fun call(method: String, path: String, body: ByteArray? = null, type: String = "application/json",
                             headers: Map<String, String> = emptyMap()): SupabaseHttp.Response? = withContext(Dispatchers.IO) {
        if (AccountSession.userId() == null) return@withContext null
        runCatching { AccountSession.authorized { token -> SupabaseHttp.request(method, path, body, token, type, headers) } }.getOrNull()
    }

    override fun userId(): String? = AccountSession.userId()

    override suspend fun rpc(name: String, body: JSONObject): String? =
        call("POST", "/rest/v1/rpc/$name", body.toString().toByteArray())?.takeIf { it.ok }?.text

    override suspend fun openRpc(name: String, body: JSONObject): String? = withContext(Dispatchers.IO) {
        if (userId() != null) return@withContext rpc(name, body)
        runCatching { SupabaseHttp.request("POST", "/rest/v1/rpc/$name", body.toString().toByteArray()) }.getOrNull()?.takeIf { it.ok }?.text
    }

    override suspend fun myProfile(): JSONObject? {
        val uid = userId() ?: return null
        val r = call("GET", "/rest/v1/ketvoice_profiles?select=display_name,handle,bio,accent,avatar_url,banner_url,is_public,share_listening&id=eq.$uid")
        return r?.takeIf { it.ok }?.let { runCatching { JSONArray(it.text).optJSONObject(0) }.getOrNull() }
    }

    override suspend fun patchProfile(fields: JSONObject): Int {
        val uid = userId() ?: return 0
        return call("PATCH", "/rest/v1/ketvoice_profiles?id=eq.$uid", fields.toString().toByteArray(), headers = mapOf("Prefer" to "return=minimal"))?.status ?: 0
    }

    override suspend fun myModules(): JSONArray? =
        call("GET", "/rest/v1/ketvoice_profile_modules?select=module,reach,data")?.takeIf { it.ok }?.let { runCatching { JSONArray(it.text) }.getOrNull() }

    override suspend fun putModule(module: String, reach: String, data: JSONObject): Boolean {
        val uid = userId() ?: return false
        val row = JSONObject().put("user_id", uid).put("module", module).put("reach", reach).put("data", data)
        return call("POST", "/rest/v1/ketvoice_profile_modules?on_conflict=user_id,module", row.toString().toByteArray(),
            headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"))?.ok == true
    }

    override suspend fun upload(path: String, jpeg: ByteArray): Boolean =
        call("POST", "/storage/v1/object/$BUCKET/$path", jpeg, "image/jpeg", mapOf("x-upsert" to "false", "cache-control" to "3600"))?.ok == true

    override suspend fun removeObjects(paths: List<String>) {
        if (paths.isEmpty()) return
        call("DELETE", "/storage/v1/object/$BUCKET", JSONObject().put("prefixes", JSONArray(paths)).toString().toByteArray())
    }

    override suspend fun picture(handle: String, kind: String): ByteArray? =
        call("GET", "/functions/v1/ketvoice-profile/image?handle=${SupabaseHttp.encode(handle)}&kind=$kind")
            ?.takeIf { it.ok && it.bytes.size in 4..1_048_576 }?.bytes

    override suspend fun saveLink(localId: String, name: String, cover: String?, tracks: JSONArray): String? {
        val uid = userId() ?: return null
        val row = JSONObject().put("owner_id", uid).put("local_id", localId.take(80)).put("name", name.take(80))
            .put("cover_url", cover ?: JSONObject.NULL).put("tracks", tracks)
        val r = call("POST", "/rest/v1/ketvoice_link_playlists?on_conflict=owner_id,local_id&select=id", row.toString().toByteArray(),
            headers = mapOf("Prefer" to "resolution=merge-duplicates,return=representation")) ?: return null
        return r.takeIf { it.ok }?.let { runCatching { JSONArray(it.text).getJSONObject(0).getString("id") }.getOrNull() }
    }
}
