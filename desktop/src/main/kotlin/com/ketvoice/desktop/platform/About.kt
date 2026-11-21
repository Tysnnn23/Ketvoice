package com.ketvoice.desktop.platform

import org.json.JSONObject

/** French is the source language; the other languages come from the app's translations.json. */
object PlatformText {
    private val table: Map<String, String> by lazy {
        val lang = java.util.Locale.getDefault().language
        if (lang == "fr") return@lazy emptyMap()
        runCatching {
            val all = JSONObject(PlatformText::class.java.getResourceAsStream("/assets/translations.json")!!.reader().readText())
            val row = all.optJSONObject(lang) ?: return@lazy emptyMap()
            row.keySet().associateWith { row.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun t(french: String): String = table[french] ?: french
}

/**
 * « À propos »: the app's version and the open-source licences it ships with (docs/licenses,
 * packaged at `/licenses/`). The UI shows [title] and [text] as they are (monospace suits the
 * licence texts).
 */
object About {
    data class Component(val name: String, val version: String, val licence: String, val file: String)

    val components = listOf(
        Component("FFmpeg", "8.0.1", "LGPL 2.1+", "FFmpeg-LGPL-2.1.txt"),
        Component("ONNX Runtime", "1.32.0", "MIT", "ONNXRuntime-MIT.txt"),
        Component("JNA", "5.19.1", "Apache 2.0", "JNA-Apache-2.0.txt"),
        Component("ICU4J", "77.1", "Unicode 3.0", "ICU4J-Unicode-3.0.txt"),
    )

    val title: String get() = PlatformText.t("À propos")

    fun licence(component: Component): String? =
        About::class.java.getResourceAsStream("/licenses/${component.file}")?.use { it.reader(Charsets.UTF_8).readText() }

    fun text(): String = buildString {
        appendLine("${PlatformText.t("Ketvoice pour Windows")} ${com.ketvoice.app.BuildConfig.VERSION_NAME}")
        appendLine()
        appendLine(PlatformText.t("Ketvoice utilise ces logiciels libres. Leurs licences suivent."))
        appendLine()
        components.forEach { appendLine("• ${it.name} ${it.version} — ${it.licence}") }
        components.forEach { c ->
            appendLine()
            appendLine("―".repeat(40))
            appendLine()
            append(licence(c)?.trimEnd() ?: "${c.name}: ${c.licence}")
            appendLine()
        }
    }
}
