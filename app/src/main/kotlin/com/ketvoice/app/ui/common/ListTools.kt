package com.ketvoice.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.ListOrder
import com.ketvoice.app.data.ListOrders
import com.ketvoice.app.data.ListSort

/** The order of the list [key], remembered on this device across visits (7.5). */
@Composable
internal fun rememberListOrder(key: String, fallback: ListOrder): MutableState<ListOrder> {
    val context = LocalContext.current.applicationContext
    val state = remember(key) { mutableStateOf(ListOrders.load(context, key, fallback)) }
    return remember(key, state) {
        object : MutableState<ListOrder> by state {
            override var value: ListOrder
                get() = state.value
                set(next) { state.value = next; ListOrders.save(context, key, next) }
        }
    }
}

/**
 * The sorts of a list's menu (Apple Music's « Trier par »): each sort with a tick on the one in use, then the
 * direction. [tag] prefixes the items' test tags (`<tag>:<sort>`, `<tag>:asc`, `<tag>:desc`).
 */
internal fun listSortItems(order: ListOrder, sorts: List<ListSort>, tag: String, onOrder: (ListOrder) -> Unit): List<List<KetMenuItem>> = listOf(
    sorts.map { sort -> KetMenuItem(sort.label, checked = sort == order.sort, tag = tag + ":" + sort.name) { onOrder(order.choose(sort)) } },
    listOf(KetMenuItem("Croissant", checked = !order.descending, tag = tag + ":" + "asc") { onOrder(order.copy(descending = false)) },
        KetMenuItem("Décroissant", checked = order.descending, tag = tag + ":" + "desc") { onOrder(order.copy(descending = true)) }))

/**
 * A list's search and sort (7.5), as in Apple Music's playlists: a field that filters the titles by title,
 * artist or album (accents and case aside), and a round button beside it with the sorts.
 */
@Composable
internal fun ListTools(query: String, onQuery: (String) -> Unit, order: ListOrder, sorts: List<ListSort>, tag: String,
                       onOrder: (ListOrder) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(modifier.fillMaxWidth().padding(horizontal = pageMargin), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CompactSearchField(query, onQuery, { keyboard?.hide() }, Modifier.weight(1f).testTag(tag + "-search"))
        KetMenuButton(listSortItems(order, sorts, tag + "-sort", onOrder), "Trier", tag = tag + "-sort",
            icon = Icons.AutoMirrored.Filled.Sort)
    }
}
