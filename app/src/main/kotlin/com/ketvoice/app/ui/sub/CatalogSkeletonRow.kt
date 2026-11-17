package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.pageMargin

/**
 * The placeholder of a row of titles while a request waits, laid out as the row that will replace it
 * (TrackRow: a 52 dp cover with 10 dp corners at the page margin, title and artist lines), so the list
 * does not jump when it arrives. Quiet on purpose: no perpetual shimmer, no allocation while it waits.
 */
@Composable
internal fun CatalogSkeletonRow(index: Int = 0) {
    val color = LocalTheme.current.secondary.copy(alpha = .12f)
    // Lines of varied length, as real titles are, instead of a column of identical bars.
    val title = SkeletonLook.titleShare(index)
    Row(Modifier.fillMaxWidth().padding(start = pageMargin, end = 6.dp, top = 7.dp, bottom = 7.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(color))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp)) {
            Box(Modifier.fillMaxWidth(title).height(13.dp).clip(RoundedCornerShape(6.dp)).background(color))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(title * .55f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        }
        Spacer(Modifier.width(48.dp))
    }
}

/** A column of placeholder rows that reads to accessibility services as one "loading" element. */
@Composable
internal fun CatalogSkeletonList(rows: Int = 6, modifier: Modifier = Modifier) {
    val loading = AppText.t("Chargement…")
    Column(modifier.fillMaxWidth().testTag("skeleton-list").clearAndSetSemantics {
        contentDescription = loading
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        repeat(rows) { CatalogSkeletonRow(it) }
    }
}

internal object SkeletonLook {
    private val shares = floatArrayOf(.72f, .58f, .80f, .64f, .50f, .76f)
    /** How much of the line a placeholder title fills (between half and four fifths). */
    fun titleShare(index: Int): Float = shares[Math.floorMod(index, shares.size)]
}
