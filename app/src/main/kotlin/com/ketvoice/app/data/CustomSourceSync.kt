package com.ketvoice.app.data

import org.json.JSONObject

/**
 * Custom sources that follow the account (Windows 2.0, read by the phone too): each source is one `customSource.<id>`
 * item of the settings document (`SettingsKind`), holding only its manifest address, whether it is on and its rank in
 * the playback order — never the manifest's content, a quality choice, a status or anything a source's server answered.
 * A source removed on a device travels as `{"removed": true, "url": …}` for [REMOVED_KEEP_MS], so the others drop it
 * too; the settings document's own merge (newest per item) decides between a removal and a later re-add.
 */
internal object CustomSourceSync {
    const val PREFIX = "customSource."
    /** At most this many sources travel (and are taken from the account). */
    const val LIMIT = 30
    const val MAX_URL = 500
    const val REMOVED_KEEP_MS = 90L * 24 * 3600 * 1000

    data class Entry(val url: String, val enabled: Boolean = true, val rank: Int = 0, val removed: Boolean = false)

    /** What [adopt][CustomSources.adoptSynced] does with the account's items. */
    data class Plan(
        val add: List<Pair<String, Entry>> = emptyList(),
        val remove: Set<String> = emptySet(),
        val enabled: Map<String, Boolean> = emptyMap(),
        /** Every source's id in the account's rank order (local-only sources keep their place after). */
        val order: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty() && enabled.isEmpty() && order.isEmpty()
    }

    fun key(id: String): String = PREFIX + id

    fun encode(entry: Entry): JSONObject =
        if (entry.removed) JSONObject().put("url", entry.url).put("removed", true)
        else JSONObject().put("url", entry.url).put("enabled", entry.enabled).put("rank", entry.rank)

    /** An item's value, checked: an https manifest address ending in .json whose id is [id] (when given). */
    fun decode(text: String, id: String? = null): Entry? = runCatching {
        val json = JSONObject(text)
        val url = json.getString("url").trim()
        require(url.length <= MAX_URL && CustomSources.isValidManifestUrl(url))
        require(id == null || CustomSources.idOf(url) == id)
        if (json.optBoolean("removed")) Entry(url, removed = true)
        else Entry(url, json.optBoolean("enabled", true), json.optInt("rank", 0).coerceIn(0, 999))
    }.getOrNull()

    /** This device's items: [ordered] sources (playback priority first) and the removals still remembered. */
    fun entries(ordered: List<CustomSource>, removed: Map<String, Pair<String, Long>>, now: Long): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        ordered.filter { CustomSources.isValidManifestUrl(it.manifestUrl) && it.manifestUrl.length <= MAX_URL }.take(LIMIT)
            .forEachIndexed { rank, s -> out[key(s.id)] = encode(Entry(s.manifestUrl, s.enabled, rank)) }
        removed.filter { (id, gone) -> key(id) !in out && now - gone.second < REMOVED_KEEP_MS }
            .forEach { (id, gone) -> out[key(id)] = encode(Entry(gone.first, removed = true)) }
        return out
    }

    /** What the account's [values] (`customSource.*` items, live only) change here, for [local]'s sources. */
    fun plan(local: List<CustomSource>, values: Map<String, String>): Plan {
        val synced = values.filterKeys { it.startsWith(PREFIX) }.mapNotNull { (key, text) ->
            val id = key.removePrefix(PREFIX)
            decode(text, id)?.let { id to it }
        }.toMap()
        if (synced.isEmpty()) return Plan()
        val byId = local.associateBy { it.id }
        val live = synced.filterValues { !it.removed }.entries.sortedBy { it.value.rank }.take(LIMIT)
        val add = live.filter { it.key !in byId }.map { it.key to it.value }
        val remove = synced.filter { it.value.removed && it.key in byId }.keys
        val enabled = live.mapNotNull { (id, e) -> byId[id]?.takeIf { it.enabled != e.enabled }?.let { id to e.enabled } }.toMap()
        val order = live.map { it.key }
        val keptOrder = local.map { it.id }.filter { it !in remove }
        val sameOrder = ordered(keptOrder.map { byId.getValue(it) }, order).map { it.id } == keptOrder
        return Plan(add, remove, enabled, if (add.isEmpty() && sameOrder) emptyList() else order)
    }

    /** [sources] in [order] (ids it does not name keep their relative place, after the named ones). */
    fun ordered(sources: List<CustomSource>, order: List<String>): List<CustomSource> {
        if (order.isEmpty()) return sources
        val rank = order.withIndex().associate { it.value to it.index }
        return sources.withIndex().sortedBy { (i, s) -> rank[s.id] ?: (order.size + i) }.map { it.value }
    }

    fun readRemoved(raw: String?): Map<String, Pair<String, Long>> = runCatching {
        val json = JSONObject(raw ?: return emptyMap())
        json.keys().asSequence().mapNotNull { id ->
            val row = json.optJSONObject(id) ?: return@mapNotNull null
            id to (row.getString("url") to row.getLong("at"))
        }.toMap()
    }.getOrDefault(emptyMap())

    fun writeRemoved(removed: Map<String, Pair<String, Long>>): String =
        JSONObject().apply { removed.forEach { (id, gone) -> put(id, JSONObject().put("url", gone.first).put("at", gone.second)) } }.toString()
}
