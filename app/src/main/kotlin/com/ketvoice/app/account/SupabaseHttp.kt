package com.ketvoice.app.account

import com.ketvoice.app.data.KetvoiceApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The Ketvoice account talks to the project's Supabase Auth, PostgREST and Storage over plain HTTPS,
 * with the public (anon) key the app already ships and, once signed in, the listener's own session.
 * Nothing here writes to the log: e-mail addresses and tokens never leave these calls.
 */
internal object SupabaseHttp {
    const val ROOT = "https://xodklrunqlzyeiqtovgx.supabase.co"
    private const val MAX_RESPONSE = 8L * 1024 * 1024

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }

    class Response(val status: Int, val bytes: ByteArray, val serverTimeMs: Long) {
        val text: String get() = String(bytes, Charsets.UTF_8)
        val ok: Boolean get() = status in 200..299
    }

    /** The server answered with an error (not a network failure). */
    class HttpError(val status: Int, val code: String, message: String) : Exception(message)

    fun request(method: String, path: String, body: ByteArray? = null, token: String? = null,
                contentType: String = "application/json", headers: Map<String, String> = emptyMap()): Response {
        require(path.startsWith("/"))
        val builder = Request.Builder().url(ROOT + path)
            .header("apikey", KetvoiceApi.ANON_KEY)
            .header("Authorization", "Bearer ${token ?: KetvoiceApi.ANON_KEY}")
            .header("Accept", "application/json")
        headers.forEach { (key, value) -> builder.header(key, value) }
        builder.method(method, body?.toRequestBody(contentType.toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val length = response.body?.contentLength() ?: 0L
            if (length > MAX_RESPONSE) throw IOException("Response too large")
            val bytes = response.body?.source()?.let { source ->
                source.request(MAX_RESPONSE + 1)
                if (source.buffer.size > MAX_RESPONSE) throw IOException("Response too large")
                source.buffer.readByteArray()
            } ?: ByteArray(0)
            return Response(response.code, bytes, response.headers.getDate("Date")?.time ?: 0L)
        }
    }

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
