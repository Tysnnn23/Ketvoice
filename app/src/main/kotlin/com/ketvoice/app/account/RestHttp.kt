package com.ketvoice.app.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * One HTTP call to the project's Supabase with the listener's session. The shared playlists and the
 * public profiles talk through this, so tests can put a fake behind them (no network, no Keystore).
 */
internal fun interface RestHttp {
    suspend fun call(method: String, path: String, body: String?, headers: Map<String, String>): SupabaseHttp.Response
}

internal object RealRestHttp : RestHttp {
    override suspend fun call(method: String, path: String, body: String?, headers: Map<String, String>): SupabaseHttp.Response =
        withContext(Dispatchers.IO) {
            AccountSession.authorized { token ->
                SupabaseHttp.request(method, path, body?.toByteArray(Charsets.UTF_8), token, headers = headers)
            }
        }
}

/** The server understood and said no (4xx other than a timeout or a rate limit): retrying the same call cannot help. */
internal class RestRejected(val status: Int, val code: String, message: String) : Exception(message)

/** A 2xx answer; otherwise [RestRejected] for a refusal and [IOException] for what may work later (5xx, 408, 429). */
internal fun SupabaseHttp.Response.requireOk(what: String): SupabaseHttp.Response {
    if (ok) return this
    val code = runCatching { org.json.JSONObject(text).optString("code") }.getOrDefault("")
    if (status in 400..499 && status != 408 && status != 429) throw RestRejected(status, code, "$what: $status")
    throw IOException("$what: $status")
}
