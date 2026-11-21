package com.ketvoice.desktop.platform

import com.ketvoice.app.data.AppText

/**
 * « À propos »: the app's version and the open-source licences it ships with (docs/licenses,
 * packaged at `/licenses/`). The UI shows [title] and [text] as they are (monospace suits the
 * licence texts).
 */
object About {
    data class Component(val name: String, val version: String, val licence: String, val file: String)

    val components = listOf(
        Component("FFmpeg", "8.0.1", "LGPL 3.0+", "FFmpeg-LGPL-3.0.txt"),
        Component("JavaCPP", "1.5.13", "Apache 2.0", "JavaCPP-Apache-2.0.txt"),
        Component("ONNX Runtime", "1.32.0", "MIT", "ONNXRuntime-MIT.txt"),
        Component("Compose Multiplatform", "1.12.2", "Apache 2.0", "Compose-Apache-2.0.txt"),
        Component("Coil", "3.4.0", "Apache 2.0", "Coil-Apache-2.0.txt"),
        Component("OkHttp", "4.12.0", "Apache 2.0", "OkHttp-Apache-2.0.txt"),
        Component("JNA", "5.19.1", "Apache 2.0", "JNA-Apache-2.0.txt"),
        Component("ICU4J", "77.1", "Unicode 3.0", "ICU4J-Unicode-3.0.txt"),
        Component("Gson", "2.11.0", "Apache 2.0", "Gson-Apache-2.0.txt"),
        Component("JSON-Java", "20240303", "Public domain", "JSON-Java-Public-Domain.txt"),
        Component("Inter", "4", "SIL OFL 1.1", "Inter-OFL.txt"),
        Component("OpenJDK", "21", "GPL 2.0 + Classpath Exception", "OpenJDK-GPL-2.0-CE.txt"),
    )

    /** Licence texts several notices point to, shown once after them. */
    val texts = listOf("Apache-2.0.txt")

    val title: String get() = AppText.t("À propos")

    fun licence(component: Component): String? = resource(component.file)

    fun resource(file: String): String? =
        About::class.java.getResourceAsStream("/licenses/$file")?.use { it.reader(Charsets.UTF_8).readText() }

    fun text(): String = buildString {
        appendLine("${AppText.t("Ketvoice pour Windows")} ${com.ketvoice.app.BuildConfig.VERSION_NAME}")
        appendLine()
        appendLine(AppText.t("Ketvoice utilise ces logiciels libres. Leurs licences suivent."))
        appendLine()
        components.forEach { appendLine("• ${it.name} ${it.version} — ${it.licence}") }
        components.forEach { c ->
            appendLine()
            appendLine("―".repeat(40))
            appendLine()
            append(licence(c)?.trimEnd() ?: "${c.name}: ${c.licence}")
            appendLine()
        }
        texts.forEach { file ->
            resource(file)?.let { appendLine(); appendLine("―".repeat(40)); appendLine(); append(it.trimEnd()); appendLine() }
        }
    }
}
