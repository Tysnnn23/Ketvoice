package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsRank
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.stems.ModelPack
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.util.Romanizer
import com.ketvoice.desktop.lyrics.AiLyrics
import com.ketvoice.desktop.lyrics.AiSync
import com.ketvoice.desktop.lyrics.AlignModels
import com.ketvoice.desktop.lyrics.LyricsEngine
import com.ketvoice.desktop.lyrics.LyricsPcSettings
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/*
 * Windows 2.0 lyrics, in the panel and the full player: the switches under the source (translation, translation under
 * every line, romanisation, floating lyrics), the word sync by AI's state, and the source menu's « Pour ce titre ».
 */

/** The switches of the lyrics view, as small capsules in a row that scrolls when narrow. */
@Composable
internal fun LyricsToggles(track: Track, lyrics: LyricsRepo.State, translating: Boolean, onTranslate: () -> Unit, full: Boolean) {
    if (lyrics.lines.isEmpty()) return
    val romanization = AppSettings.lyricsRomanization
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = if (full) 0.dp else 14.dp, end = 10.dp, bottom = 6.dp)
        .testTag("lyrics-toggles"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        LyricChip("Traduction", translating, full, "lyrics-chip-translate", onTranslate)
        if (translating) LyricChip("Sous chaque ligne", LyricsPcSettings.translateEvery.value, full, "lyrics-chip-every") {
            LyricsPcSettings.translateEvery.value = !LyricsPcSettings.translateEvery.value; LyricsPcSettings.save()
        }
        if (RomanCache.useful(lyrics.lines.map { it.text })) LyricChip("Romanisation", romanization.value, full, "lyrics-chip-romanization") {
            romanization.value = !romanization.value
        }
        LyricChip("Paroles flottantes", LyricsPcSettings.floating.value, full, "lyrics-chip-floating") { FloatingLyrics.toggle() }
        AiChip(track, lyrics, full)
    }
}

/** The word sync by AI: « Synchronisé par IA » on lyrics it timed, its work in progress, or its model to download. */
@Composable
private fun AiChip(track: Track, lyrics: LyricsRepo.State, full: Boolean) {
    val status by AiSync.status.collectAsState()
    val model by AlignModels.pack.state.collectAsState()
    AiLyrics.changes.collectAsState().value
    val shownByAi = lyrics.source.endsWith(AiLyrics.LABEL)
    val reverted = AiLyrics.entry(track.id)?.reverted == true
    when {
        shownByAi -> LyricChip(AiLyrics.LABEL, true, full, "lyrics-chip-ai") { AiLyrics.revert(track.id); LyricsRepo.load(track, force = true) }
        reverted -> LyricChip("Synchro IA", false, full, "lyrics-chip-ai") { AiLyrics.revert(track.id, back = false); LyricsRepo.load(track, force = true) }
        (status as? AiSync.Status.Working)?.trackId == track.id -> LyricChip("Synchronisation des mots…", true, full, "lyrics-chip-ai-working") {}
        status == AiSync.Status.NeedsModel && LyricsRank.level(lyrics.lines) <= LyricsRank.LINE -> when (val m = model) {
            is ModelPack.State.Downloading -> LyricChip("${com.ketvoice.app.data.AppText.t("Téléchargement")} ${(m.fraction * 100).toInt()} %", true, full, "lyrics-chip-ai-download") {}
            else -> LyricChip("Synchroniser les mots (IA, 970 Mo)", false, full, "lyrics-chip-ai-download") { AiSync.download() }
        }
        else -> {}
    }
}

@Composable
private fun LyricChip(label: String, on: Boolean, full: Boolean, tag: String, onClick: () -> Unit) {
    val ket = LocalKet.current
    val ground = when {
        full -> Color.White.copy(alpha = if (on) .26f else .12f)
        on -> ket.accent.copy(alpha = .16f)
        else -> ket.fill
    }
    val ink = when { full -> Color.White.copy(alpha = if (on) 1f else .8f); on -> ket.accent; else -> ket.label }
    Row(Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(ground).clickable(onClick = onClick).padding(horizontal = 11.dp)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.caption, color = ink, weight = if (on) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/**
 * « Pour ce titre » in the lyrics' source menu: every source that answered for this title with its timing, the one
 * shown checked; picking one keeps it for this title ([LyricsEngine.choose]), « Meilleure synchronisation » forgets it.
 */
internal fun perTitleItems(track: Track, reload: () -> Unit): List<MenuItem> {
    val answers = LyricsEngine.answers.value?.takeIf { it.trackId == track.id }?.candidates.orEmpty()
    if (answers.isEmpty()) return emptyList()
    val chosen = LyricsEngine.chosen(track.id)
    val auto = MenuItem("Meilleure synchronisation", if (chosen == null) KetIcons.Check else null, "lyrics-title-best") {
        LyricsEngine.choose(track.id, null); reload()
    }
    return listOf(MenuItem("Pour ce titre", tag = "lyrics-title-header", children = listOf(auto) + answers.map { c ->
        MenuItem("${c.label} · ${com.ketvoice.app.data.AppText.t(LyricsRank.label(c.level))}", if (chosen == c.source) KetIcons.Check else null,
            "lyrics-title:${c.source}", translate = false) { LyricsEngine.choose(track.id, c.source); reload() }
    }))
}

/** Romanisation per set of lines, worked out once (the ICU transforms are slow on long songs) and kept for a few songs. */
internal object RomanCache {
    private val cache = object : LinkedHashMap<List<String>, List<String?>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, List<String?>>?) = size > 12
    }

    fun lines(texts: List<String>): List<String?> = synchronized(cache) { cache[texts] } ?: Romanizer.lines(texts).also { r -> synchronized(cache) { cache[texts] = r } }

    /** Whether the lyrics have lines romanisation would read (not Latin script only). */
    fun useful(texts: List<String>): Boolean = texts.any { line -> line.any { c -> Romanizer.scriptOf(c).let { it != Romanizer.Script.Latin && it != Romanizer.Script.Other } } }
}
