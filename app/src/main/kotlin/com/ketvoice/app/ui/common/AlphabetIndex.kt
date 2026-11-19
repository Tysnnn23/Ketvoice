package com.ketvoice.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.rememberHaptics
import java.text.Normalizer

/**
 * The letters of an A–Z index (7.1, « Défilement rapide »): a name is filed under its first letter
 * without its accents (« École » under E, « Ølafur » under O); digits, signs and names in other scripts
 * (Cyrillic, Greek, CJK, Hangul, Devanagari…) all go under « # », at the end, as on iOS.
 */
internal object IndexLetters {
    const val OTHER = "#"
    /** A list shorter than this scrolls well enough without an index. */
    const val MIN_ITEMS = 30
    /** Fewer different letters than this and the index would only be a few dots. */
    const val MIN_LETTERS = 3

    fun of(name: String): String {
        val first = name.trim().firstOrNull { it.isLetterOrDigit() } ?: return OTHER
        val base = when (first) {
            'Æ', 'æ' -> 'A'
            'Œ', 'œ', 'Ø', 'ø' -> 'O'
            'ß' -> 'S'
            'Ð', 'ð', 'Đ', 'đ' -> 'D'
            'Þ', 'þ' -> 'T'
            'Ł', 'ł' -> 'L'
            else -> Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first()
        }.uppercaseChar()
        return if (base in 'A'..'Z') base.toString() else OTHER
    }

    /** [items] (already in name order) with the « # » ones moved to the end, each group keeping its order. */
    fun <T> grouped(items: List<T>, name: (T) -> String): List<T> = items.sortedBy { of(name(it)) == OTHER }

    /** Each letter present, in order, with the position of its first item in [items] (as [grouped] leaves them). */
    fun <T> firsts(items: List<T>, name: (T) -> String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var last: String? = null
        items.forEachIndexed { index, item ->
            val letter = of(name(item))
            if (letter != last && out.none { it.first == letter }) out += letter to index
            last = letter
        }
        return out
    }

    fun shown(items: Int, letters: Int): Boolean = items >= MIN_ITEMS && letters >= MIN_LETTERS

    /** The letter under [y] on a strip [height] high that holds [count] letters, clamped to the strip. */
    fun at(y: Float, height: Float, count: Int): Int {
        if (count <= 0) return -1
        if (!y.isFinite() || height <= 0f) return 0
        return (y / (height / count)).toInt().coerceIn(0, count - 1)
    }
}

/**
 * The A–Z index at the right edge of a long sorted list: a touch or a drag across the letters jumps to
 * the first entry of each, with a tick felt at every new letter. Plain letters in the accent colour, no
 * glass (it belongs to the list). A screen reader finds each letter as a button.
 */
@Composable
internal fun AlphabetIndex(letters: List<String>, onLetter: (Int) -> Unit, modifier: Modifier = Modifier, maxRow: Dp = 16.dp,
                           available: Dp = 480.dp) {
    if (letters.isEmpty()) return
    val theme = LocalTheme.current
    val haptic = rememberHaptics()
    val jump by rememberUpdatedState(onLetter)
    var height by remember { mutableIntStateOf(1) }
    var current by remember { mutableIntStateOf(-1) }
    val row = minOf(maxRow, available / letters.size).coerceAtLeast(10.dp)
    val goLabel = AppText.t("Aller à cette lettre")
    val otherLabel = AppText.t("Autres noms")
    fun pick(y: Float) {
        val index = IndexLetters.at(y, height.toFloat(), letters.size)
        if (index < 0 || index == current) return
        current = index
        haptic(Haptics.Kind.Tick)
        jump(index)
    }
    Column(modifier.width(28.dp).testTag("alphabet-index").onSizeChanged { height = it.height }
        .semantics { isTraversalGroup = true }
        .pointerInput(letters) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                pick(down.position.y)
                down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    pick(change.position.y)
                    change.consume()
                }
                current = -1
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally) {
        letters.forEachIndexed { index, letter ->
            Box(Modifier.fillMaxWidth().height(row).semantics {
                contentDescription = if (letter == IndexLetters.OTHER) otherLabel else letter
                role = Role.Button
                onClick(goLabel) { jump(index); true }
            }, contentAlignment = Alignment.Center) {
                Text(letter, localize = false, color = theme.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
