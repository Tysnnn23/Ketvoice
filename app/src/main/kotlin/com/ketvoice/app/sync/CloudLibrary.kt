package com.ketvoice.app.sync

import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.SupabaseHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A kind's document as the cloud holds it, with the revision a write must name to replace it. */
internal data class CloudDoc(val doc: JSONObject, val revision: Long, val deviceId: String, val updatedAt: String)

/**
 * The `ketvoice_library` table through PostgREST, with the listener's own session: row level security
 * lets an account read and write its own rows only. A write names the revision it read; when another
 * device wrote first, nothing is changed and the caller merges again.
 */
internal object CloudLibrary {
    private const val TABLE = "/rest/v1/ketvoice_library"

    private fun uid(): String = AccountSession.userId() ?: throw IOException("Signed out")

    suspend fun fetch(kind: String): CloudDoc? {
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("GET", "$TABLE?select=doc,revision,device_id,updated_at&user_id=eq.${uid()}&kind=eq.$kind", token = token)
        }
        if (!response.ok) throw IOException("Library read failed: ${response.status}")
        val rows = JSONArray(response.text)
        if (rows.length() == 0) return null
        val row = rows.getJSONObject(0)
        return CloudDoc(row.optJSONObject("doc") ?: JSONObject(), row.getLong("revision"), row.optString("device_id"), row.optString("updated_at"))
    }

    /** The first copy of a kind; false when another device created it meanwhile. */
    suspend fun insert(kind: String, doc: JSONObject, deviceId: String): Boolean {
        val body = JSONObject().put("user_id", uid()).put("kind", kind).put("doc", doc).put("device_id", deviceId)
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("POST", TABLE, body.toString().toByteArray(), token, headers = mapOf("Prefer" to "return=minimal"))
        }
        if (response.status == 409) return false
        if (!response.ok) throw IOException("Library write failed: ${response.status}")
        return true
    }

    /** Replaces the copy at [revision] (the server counts the next one); false when it is no longer the current one. */
    suspend fun update(kind: String, doc: JSONObject, revision: Long, deviceId: String): Boolean {
        val body = JSONObject().put("doc", doc).put("device_id", deviceId)
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("PATCH", "$TABLE?user_id=eq.${uid()}&kind=eq.$kind&revision=eq.$revision&select=revision",
                body.toString().toByteArray(), token, headers = mapOf("Prefer" to "return=representation"))
        }
        if (!response.ok) throw IOException("Library write failed: ${response.status}")
        return JSONArray(response.text).length() > 0
    }

    /** What plays now, written over whatever another device wrote (the latest listening wins). */
    suspend fun putNowPlaying(doc: JSONObject, deviceId: String) {
        val body = JSONObject().put("user_id", uid()).put("kind", NowPlayingSync.KIND).put("doc", doc).put("device_id", deviceId)
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("POST", "$TABLE?on_conflict=user_id,kind", body.toString().toByteArray(), token,
                headers = mapOf("Prefer" to "return=minimal,resolution=merge-duplicates"))
        }
        if (!response.ok) throw IOException("Now playing write failed: ${response.status}")
    }
}
