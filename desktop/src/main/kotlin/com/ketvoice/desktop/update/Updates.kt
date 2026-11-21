package com.ketvoice.desktop.update

import androidx.compose.runtime.mutableStateOf
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.data.AppText
import com.ketvoice.desktop.platform.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** The app's one updater (Data\updates), its check every 6 h, and whether its window is open. */
object Updates {
    /** Main sets it: saves everything and leaves, so the MSI can replace the files. */
    @Volatile var quit: () -> Unit = {}

    val updater: WindowsUpdater by lazy {
        WindowsUpdater(
            OkHttpUpdateTransport("Ketvoice-Windows/${BuildConfig.VERSION_NAME}"),
            File(android.content.Context.app.dataDir, "updates"),
            SemVer.parse(BuildConfig.VERSION_NAME) ?: SemVer(1, 0, 0),
            launcher = { msi ->
                if (!Os.isWindows) throw IOException("msiexec is Windows only")
                WindowsUpdater.msiexec(msi)
                quit()
            },
            text = AppText::t,
        )
    }

    /** The update window: a release the listener has not put off, or one asked for from Settings. */
    val dialog = mutableStateOf<WindowsRelease?>(null)

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            val state = updater.check()
            if (state is UpdateState.Available && state.release.tag != updater.dismissedTag && dialog.value == null) dialog.value = state.release
            delay(WindowsUpdater.SIX_HOURS)
        }
    }
}

/** Release notes as GitHub Markdown, reduced to what release notes use: headings, bullets, paragraphs, **bold**, `code`, links. */
object ReleaseNotes {
    sealed interface Block { val text: String }
    data class Heading(override val text: String, val level: Int) : Block
    data class Bullet(override val text: String, val depth: Int) : Block
    data class Paragraph(override val text: String) : Block

    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)\d+[.)]\s+(.*)$""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    fun blocks(markdown: String): List<Block> {
        val out = ArrayList<Block>()
        val paragraph = StringBuilder()
        fun flush() { if (paragraph.isNotBlank()) out += Paragraph(paragraph.toString().trim()); paragraph.clear() }
        for (raw in COMMENT.replace(markdown, "").lines()) {
            val line = IMAGE.replace(raw.trimEnd(), "")
            val heading = HEADING.matchEntire(line.trim())
            val bullet = BULLET.matchEntire(line) ?: NUMBERED.matchEntire(line)
            when {
                line.isBlank() || line.trim().matches(Regex("""^([-*_])\1{2,}$""")) -> flush()
                heading != null -> { flush(); out += Heading(inline(heading.groupValues[2]), heading.groupValues[1].length) }
                bullet != null -> { flush(); out += Bullet(inline(bullet.groupValues[2]), bullet.groupValues[1].length / 2) }
                out.lastOrNull() is Bullet && paragraph.isEmpty() && raw.startsWith("  ") ->
                    (out.removeAt(out.lastIndex) as Bullet).let { out += it.copy(text = it.text + " " + inline(line.trim())) }
                else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(inline(line.trim()))
            }
        }
        flush()
        return out
    }

    /** Links keep their words; the `**`/`__` and backtick markers stay for [spans] to style. */
    private fun inline(text: String): String = LINK.replace(text) { it.groupValues[1] }

    /** [text] without its markers, and the bold / code ranges in it. */
    fun spans(text: String): Triple<String, List<IntRange>, List<IntRange>> {
        val plain = StringBuilder()
        val bold = ArrayList<IntRange>(); val code = ArrayList<IntRange>()
        var i = 0
        var boldStart = -1
        while (i < text.length) {
            when {
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    if (boldStart < 0) boldStart = plain.length else { if (plain.length > boldStart) bold += boldStart until plain.length; boldStart = -1 }
                    i += 2
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) { plain.append(text[i]); i++ } else {
                        val start = plain.length
                        plain.append(text, i + 1, end); code += start until plain.length; i = end + 1
                    }
                }
                else -> { plain.append(text[i]); i++ }
            }
        }
        return Triple(plain.toString(), bold, code)
    }
}
