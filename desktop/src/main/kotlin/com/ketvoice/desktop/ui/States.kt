package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.delay

/** What a page loads from the catalogue: still loading, there, or missing (offline, or the catalogue failed). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val offline: Boolean) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val retry: () -> Unit) {
    val value: T? get() = (state as? Load.Ready<T>)?.value
}

/**
 * Loads [block] for [key]. A null or [empty] answer asks the catalogue whether the network reaches it:
 * offline, the page shows the offline state; online, an empty answer is the page's own empty state and a
 * null one (a request that failed) the error state. [Loader.retry] loads again.
 */
@Composable
fun <T : Any> rememberLoad(key: Any?, empty: (T) -> Boolean = { false }, block: suspend CatalogSource.() -> T?): Loader<T> {
    val catalog = LocalCatalog.current
    var attempt by remember(key) { mutableStateOf(0) }
    val state by produceState<Load<T>>(Load.Loading, key, attempt) {
        value = Load.Loading
        val v = try { catalog.block() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { null }
        value = when {
            v != null && !empty(v) -> Load.Ready(v)
            !catalog.online() -> Load.Failed(offline = true)
            v != null -> Load.Ready(v)
            else -> Load.Failed(offline = false)
        }
    }
    return Loader(state) { attempt++ }
}

/** The loading spinner, or the offline / error state, as list items; nothing once the value is there. */
fun <T> LazyListScope.loadStates(loader: Loader<T>) {
    if (loader.state !is Load.Ready) item("load-state") { LoadState(loader) }
}

@Composable
fun <T> LoadState(loader: Loader<T>) {
    when (val s = loader.state) {
        Load.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) { Spinner() }
        is Load.Failed -> if (s.offline) OfflineMessage(loader.retry) else ErrorMessage(loader.retry)
        is Load.Ready -> {}
    }
}

/** No network: what still works (the downloads) and a way to try again. */
@Composable
fun OfflineMessage(onRetry: () -> Unit) {
    val nav = LocalNavigator.current
    StateMessage(KetIcons.Wifi, "Vous êtes hors ligne",
        "Connectez-vous à Internet pour parcourir le catalogue. Vos téléchargements restent disponibles.",
        listOf("Réessayer" to onRetry, "Téléchargements" to { nav.root(Route.Downloads) }), Modifier.testTag("state:offline"))
}

/** The catalogue answered with an error. */
@Composable
fun ErrorMessage(onRetry: () -> Unit) {
    StateMessage(KetIcons.Info, "Impossible de charger cette page", "Le service ne répond pas pour le moment. Réessayez dans un instant.",
        listOf("Réessayer" to onRetry), Modifier.testTag("state:error"))
}

/** A page's centred state: a symbol, a title, a line of explanation and up to two buttons (the first one filled). */
@Composable
fun StateMessage(icon: ImageVector, title: String, message: String, actions: List<Pair<String, () -> Unit>> = emptyList(), modifier: Modifier = Modifier) {
    val ket = LocalKet.current
    Column(modifier.fillMaxWidth().padding(vertical = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(38.dp), tint = ket.tertiary)
        Spacer(Modifier.height(14.dp))
        KText(title, Ket27Desk.Type.title3)
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.Text(AppText.t(message), style = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline),
            color = ket.secondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 400.dp))
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { i, (label, action) ->
                    CapsuleButton(label, null, action, filled = i == 0, accent = ket.accent, modifier = Modifier.testTag("state-action:$i"))
                }
            }
        }
    }
}

/** Playback problems (« Flux indisponible »…), shown a few seconds at the bottom of the window. */
@Composable
fun BoxScope.PlaybackToast() {
    val player = LocalPlayer.current
    val ket = LocalKet.current
    var message by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        player.errors.collect { message = it; shown = true }
    }
    LaunchedEffect(shown, message) { if (shown) { delay(4_000); shown = false } }
    AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        enter = if (Motion.reduced) fadeIn() else fadeIn() + slideInVertically { it / 2 },
        exit = if (Motion.reduced) fadeOut() else fadeOut() + slideOutVertically { it / 2 }) {
        Row(Modifier.shadow(12.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(ket.raised)
            .border(.5.dp, ket.hairline, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 11.dp).testTag("toast"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Info, null, Modifier.size(16.dp), tint = ket.secondary)
            Spacer(Modifier.width(10.dp))
            KText(message.orEmpty(), Ket27Desk.Type.subheadline)
        }
    }
}

/** Which text fields have the keyboard: the window's shortcuts (Space, arrows) leave them their keys. */
object TextEntry {
    private val focused = androidx.compose.runtime.mutableStateMapOf<Any, Boolean>()
    val active: Boolean get() = focused.values.any { it }
    internal fun set(key: Any, on: Boolean) { if (on) focused[key] = true else focused.remove(key) }
}

/** Marks a text field for [TextEntry]. */
fun Modifier.textEntry(): Modifier = composed {
    val key = remember { Any() }
    androidx.compose.runtime.DisposableEffect(key) { onDispose { TextEntry.set(key, false) } }
    onFocusChanged { TextEntry.set(key, it.isFocused) }
}
