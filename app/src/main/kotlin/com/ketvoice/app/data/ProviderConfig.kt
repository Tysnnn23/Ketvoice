package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Non-secret public adapters only. Authentication and playback trust anchors stay pinned. */
object ProviderConfig {
    internal data class Rule(val id: String, val base: String, val enabled: Boolean, val editable: Boolean)
    @Volatile private var defaults: List<Rule> = emptyList()
    @Volatile private var rules: Map<String, Rule> = emptyMap()
    private var started = false

    @Synchronized fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        defaults = parseDefaults(app.assets.open("provider_registry.json").bufferedReader().use { it.readText() })
        val prefs = app.getSharedPreferences("ketvoice_provider_config", Context.MODE_PRIVATE)
        runCatching { rules = parseRules(prefs.getString("rules", "[]")!!, defaults) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                runCatching {
                    val c = URL(KetvoiceApi.BASE.replace("ketvoice-api", "ketvoice-content") + "/providers").openConnection() as HttpURLConnection
                    val raw = try {
                        c.instanceFollowRedirects = false; c.connectTimeout = 8000; c.readTimeout = 12000
                        c.setRequestProperty("apikey", KetvoiceApi.ANON_KEY)
                        c.setRequestProperty("Authorization", "Bearer ${KetvoiceApi.ANON_KEY}")
                        check(c.responseCode == 200)
                        c.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) { val count = input.read(buffer); if (count < 0) break; check(out.size() + count <= 65536); out.write(buffer, 0, count) }
                            val bytes = out.toByteArray()
                            JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("rules").toString()
                        }
                    } finally { c.disconnect() }
                    val next = parseRules(raw, defaults)
                    prefs.edit().putString("rules", raw).apply()
                    rules = next
                }
                delay(5 * 60_000L)
            }
        }
    }

    internal fun parseDefaults(raw: String): List<Rule> {
        val rows = JSONArray(raw)
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            Rule(it.getString("id"), it.getString("base"), true, it.getBoolean("editable"))
        } }
    }

    internal fun parseRules(raw: String, definitions: List<Rule>): Map<String, Rule> {
        val rows = JSONArray(raw); require(rows.length() <= definitions.size)
        val out = mutableMapOf<String, Rule>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val id = row.getString("id")
            val def = definitions.firstOrNull { it.id == id } ?: error("Unknown provider")
            require(def.editable || id in setOf("stream-um", "stream-last"))
            require(!out.containsKey(id) && row.get("enabled") is Boolean)
            val base = row.getString("base")
            if (def.editable) {
                val uri = URI(base); val host = uri.host.orEmpty().lowercase()
                require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    uri.port in setOf(-1, 443) && uri.path.endsWith("/") && base.length <= 512 &&
                    host.contains('.') && !host.first().isDigit() && !host.contains(':') &&
                    listOf(".local", ".localhost", ".internal", ".test", ".invalid").none { host.endsWith(it) })
            } else require(base.isEmpty())
            out[id] = def.copy(base = base, enabled = row.getBoolean("enabled"))
        }
        return out
    }

    fun enabled(id: String): Boolean = rules[id]?.enabled ?: true
    /** Only call on unauthenticated public adapter requests; null means disabled. */
    fun publicUrl(url: String): String? = resolve(url, defaults, rules)

    internal fun resolve(url: String, definitions: List<Rule>, overrides: Map<String, Rule>): String? {
        val def = definitions.firstOrNull { it.editable && url.startsWith(it.base) } ?: return url
        val rule = overrides[def.id] ?: return url
        if (!rule.enabled) return null
        return rule.base + url.removePrefix(def.base)
    }
}
