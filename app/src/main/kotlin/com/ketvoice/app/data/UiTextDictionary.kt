package com.ketvoice.app.data

/** Exact UI messages and whole-message templates. Captured data stays intact. */
internal class UiTextDictionary(private val entries: Map<String, String>) {
    private data class Template(val pattern: Regex, val target: String, val arguments: List<String>, val composite: Boolean)
    private val placeholder = Regex("\\{(\\d+)\\}")
    private val templates = entries.entries
        .filter { placeholder.containsMatchIn(it.key) }
        .sortedByDescending { it.key.length }
        .mapNotNull { (source, target) ->
            val args = placeholder.findAll(source).map { it.groupValues[1] }.toList()
            if (placeholder.findAll(target).map { it.groupValues[1] }.toSet() != args.toSet()) {
                return@mapNotNull null
            }
            val pattern = placeholder.split(source).joinToString("(.+?)") { Regex.escape(it) }
            Template(Regex(pattern, RegexOption.DOT_MATCHES_ALL), target, args, " · " in source)
        }

    fun translate(text: String): String {
        entries[text]?.let { return it }
        templates.forEach { template ->
            if (" · " in text && !template.composite) return@forEach
            val match = template.pattern.matchEntire(text) ?: return@forEach
            val values = template.arguments.mapIndexed { index, name -> name to match.groupValues[index + 1] }.toMap()
            return placeholder.replace(template.target) { values[it.groupValues[1]].orEmpty() }
        }
        // Status labels combine independently translated phrases with bullets.
        if (" · " in text) return text.split(" · ").joinToString(" · ") { translate(it) }
        if ('\n' in text) return text.split('\n').joinToString("\n") { translate(it) }
        return text
    }
}
