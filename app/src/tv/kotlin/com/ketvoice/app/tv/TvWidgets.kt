package com.ketvoice.app.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text

/** A row of cards under its title: lazy, keyed, the focus kept in place as it scrolls, back to its last card on re-entry. */
@Composable
fun TvShelfRow(shelf: TvShelf, onItem: (TvItem) -> Unit, modifier: Modifier = Modifier, onMenu: ((TvItem) -> Unit)? = null,
               first: Boolean = false) {
    Column(modifier.fillMaxWidth()) {
        Text(shelf.title, style = TvDesign.Type.row, color = TvDesign.Ink.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = TvDesign.Safe.horizontal, bottom = 12.dp))
        val state = rememberLazyListState()
        // Entering the row: its last focused card, else its first one (never the nearest by geometry).
        val head = remember { androidx.compose.ui.focus.FocusRequester() }
        TvPivot {
            LazyRow(state = state, modifier = Modifier.fillMaxWidth().focusRestorer(head).focusGroup().testTag("row-${shelf.key}"),
                contentPadding = PaddingValues(horizontal = TvDesign.Safe.horizontal, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(TvDesign.Size.rowGap)) {
                itemsIndexed(shelf.items, key = { _, item -> item.key }, contentType = { _, item -> item.kind }) { index, item ->
                    TvItemCard(item, { onItem(item) }, focus = Modifier.pageItem(shelf.key, item.key, first = first && index == 0)
                        .then(if (index == 0) Modifier.focusRequester(head) else Modifier),
                        onMenu = onMenu?.let { menu -> { menu(item) } })
                }
            }
        }
    }
}

/** A card of a row: a square cover (a circle for an artist), its title and subtitle under it. */
@Composable
fun TvItemCard(item: TvItem, onClick: () -> Unit, modifier: Modifier = Modifier, focus: Modifier = Modifier, onMenu: (() -> Unit)? = null) {
    val artist = item.kind == TvItemKind.Artist
    val size = if (artist) TvDesign.Size.artist else TvDesign.Size.card
    val shape = if (artist) CircleShape else RoundedCornerShape(TvDesign.Radius.card)
    Column(modifier.width(size), horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start) {
        TvCover(item.image, size, shape, focus.tvFocusable(onClick, shape, onLongClick = onMenu, tag = "card-${item.key}")
            .semantics { contentDescription = if (item.appText) AppText.t(item.title) else item.title },
            placeholder = placeholderFor(item.kind))
        Spacer(Modifier.height(10.dp))
        Text(item.title, localize = item.appText, style = TvDesign.Type.card, color = TvDesign.Ink.primary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        if (item.subtitle.isNotBlank()) Text(item.subtitle, localize = item.appText || item.kind == TvItemKind.Playlist,
            style = TvDesign.Type.cardSub, color = TvDesign.Ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun placeholderFor(kind: TvItemKind): ImageVector = when (kind) {
    TvItemKind.Artist -> KetIcons.Account
    TvItemKind.Album -> KetIcons.Album
    TvItemKind.Playlist, TvItemKind.Collection, TvItemKind.Mix -> KetIcons.Playlist
    TvItemKind.Track -> KetIcons.Music
}

/**
 * A capsule button: glass at rest, white with dark ink when focused (the TV's usual pill). [primary] buttons are
 * white at rest too.
 */
@Composable
fun TvButton(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
             localize: Boolean = true, tag: String? = null, selected: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(when {
        focused -> TvDesign.Ink.focusFill
        primary -> Color.White.copy(alpha = .9f)
        selected -> Color.White.copy(alpha = .22f)
        else -> Color.White.copy(alpha = .1f)
    }, label = "tv-button")
    val ink = if (focused || primary) TvDesign.Ink.onFocusFill else TvDesign.Ink.primary
    val shape = RoundedCornerShape(TvDesign.Radius.pill)
    Row(modifier
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
        .clip(shape).background(fill)
        .border(1.dp, if (focused) Color.Transparent else TvDesign.Ink.glassEdge, shape)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 22.dp).height(TvDesign.Size.button),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = ink)
        if (icon != null && label.isNotEmpty()) Spacer(Modifier.width(10.dp))
        if (label.isNotEmpty()) Text(label, localize = localize, style = TvDesign.Type.label, color = ink, maxLines = 1)
    }
}

/** A round icon button (the transport): white disc when focused, [active] marks a mode that is on. */
@Composable
fun TvIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp,
                 active: Boolean = false, accent: Color = Color.White, tag: String? = null) {
    TvRoundButton(label, onClick, modifier, size, active, tag) { ink ->
        Icon(icon, null, Modifier.size(size * .44f), tint = if (ink == TvDesign.Ink.primary && active) accent else ink)
    }
}

/** A round button with any [content], drawn in the ink it is given (dark on the white focus disc, white otherwise). */
@Composable
fun TvRoundButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp, active: Boolean = false,
                  tag: String? = null, content: @Composable (ink: Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(if (focused) TvDesign.Ink.focusFill else Color.White.copy(alpha = if (active) .2f else .08f), label = "tv-icon")
    val ink = if (focused) TvDesign.Ink.onFocusFill else TvDesign.Ink.primary
    val description = AppText.t(label)
    Box(modifier
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .graphicsLayer { val s = if (focused) 1.1f else 1f; scaleX = s; scaleY = s }
        .size(size).clip(CircleShape).background(fill)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick)
        .semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        content(ink)
    }
}

/** A page's large title, inside the safe frame. */
@Composable
fun TvPageTitle(text: String, modifier: Modifier = Modifier, localize: Boolean = true, inset: Boolean = true) {
    val margin = if (inset) TvDesign.Safe.horizontal else 0.dp
    Text(text, localize = localize, style = TvDesign.Type.title, color = TvDesign.Ink.primary, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier.padding(start = margin, end = margin))
}

/** A message in the middle of an empty page. */
@Composable
fun TvEmpty(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = TvDesign.Safe.horizontal, vertical = 40.dp)) {
        Text(title, style = TvDesign.Type.row, color = TvDesign.Ink.primary)
        Spacer(Modifier.height(8.dp))
        Text(body, style = TvDesign.Type.body, color = TvDesign.Ink.secondary)
    }
}
