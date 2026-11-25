package com.ketvoice.app.data

import org.json.JSONObject

/**
 * The source catalogue (Windows 2.0): a JSON file on the website (`sources/catalogue.json` of ketvoice-web, schema in
 * that repository's `sources/README.md`) listing manifests a listener can install in one click. Only the catalogue's
 * `version` 1 fields are read; a newer major version is refused rather than misread, and a malformed entry is skipped.
 */
object SourceCatalogue {
    const val URL = "https://ketvoice-web.vercel.app/sources/catalogue.json"
    const val SCHEMA = "ketvoice-source-catalogue"
    const val VERSION = 1
    private const val MAX_ENTRIES = 200

    data class Entry(
        val id: String,
        val name: String,
        val manifestUrl: String,
        /** Descriptions by language code ("fr", "en"…). */
        val description: Map<String, String> = emptyMap(),
        /** What the manifest serves: search, stream, download, lyrics, canvas, cover. */
        val provides: Set<String> = emptySet(),
        /** "lossless", "high" or "low": the best quality it offers, for the badge. */
        val quality: String? = null,
        /** Run by Ketvoice itself. */
        val official: Boolean = false,
        val homepage: String? = null,
    ) {
        /** The description in [language], else French, else English, else any. */
        fun describe(language: String): String = description[language] ?: description["fr"] ?: description["en"]
            ?: description.values.firstOrNull().orEmpty()
    }

    data class Catalogue(val version: Int, val updated: String, val entries: List<Entry>)

    /** The catalogue in [raw], or null when it is not one this version can read. */
    fun parse(raw: String): Catalogue? = runCatching {
        val root = JSONObject(raw)
        require(root.optString("schema", SCHEMA) == SCHEMA)
        val version = root.getInt("version")
        require(version in 1..VERSION)
        val array = root.getJSONArray("sources")
        val entries = (0 until minOf(array.length(), MAX_ENTRIES)).mapNotNull { i -> array.optJSONObject(i)?.let(::entry) }
            .distinctBy { it.manifestUrl }
        Catalogue(version, root.optString("updated"), entries)
    }.getOrNull()

    private val PROVIDES = setOf("search", "stream", "download", "lyrics", "canvas", "cover")

    private fun entry(o: JSONObject): Entry? = runCatching {
        val url = o.getString("manifest").trim()
        require(url.length <= CustomSourceSync.MAX_URL && CustomSources.isValidManifestUrl(url))
        val name = o.getString("name").trim().take(80).also { require(it.isNotBlank()) }
        val description = when (val d = o.opt("description")) {
            is String -> mapOf("fr" to d)
            is JSONObject -> d.keys().asSequence().associateWith { d.optString(it).take(400) }.filterValues { it.isNotBlank() }
            else -> emptyMap()
        }
        val provides = o.optJSONArray("provides")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase() }.filter { it in PROVIDES }.toSet() }.orEmpty()
        Entry(id = o.optString("id").ifBlank { CustomSources.idOf(url) }.take(64), name = name, manifestUrl = url,
            description = description, provides = provides,
            quality = o.optString("quality").lowercase().takeIf { it in setOf("lossless", "high", "low") },
            official = o.optBoolean("official"),
            homepage = o.optString("homepage").takeIf { it.startsWith("https://") })
    }.getOrNull()

    /** Whether [entry] is installed among [sources] (same manifest address). */
    fun installed(entry: Entry, sources: List<CustomSource>): Boolean = sources.any { it.manifestUrl == entry.manifestUrl }
}
