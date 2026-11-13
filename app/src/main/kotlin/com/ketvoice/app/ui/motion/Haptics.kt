package com.ketvoice.app.ui.motion

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.data.AppSettings

/**
 * Light touch feedback tuned to the glass springs. One place decides the platform
 * constant, so call sites state intent (press, tick, confirm) and nothing else.
 * Honours the in-app toggle and the system "touch feedback" setting; never on Quest.
 */
object Haptics {
    enum class Kind {
        /** A finger pushing a glass control. */
        Press,
        /** A discrete step: tab change, slider released, list reorder. */
        Tick,
        /** A state the user asked for is now set: favourite added, download queued. */
        Confirm
    }

    fun constantFor(kind: Kind, sdk: Int = Build.VERSION.SDK_INT): Int = when (kind) {
        Kind.Press -> HapticFeedbackConstants.VIRTUAL_KEY
        Kind.Tick -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        Kind.Confirm -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
    }

    fun enabled(setting: Boolean = AppSettings.haptics.value, quest: Boolean = BuildConfig.IS_QUEST): Boolean =
        setting && !quest

    fun perform(view: View, kind: Kind) {
        if (!enabled()) return
        runCatching { view.performHapticFeedback(constantFor(kind)) }
    }
}

/** Stable callback for composables; the view never changes within a window. */
@Composable
fun rememberHaptics(): (Haptics.Kind) -> Unit {
    val view = LocalView.current
    return remember(view) { { kind: Haptics.Kind -> Haptics.perform(view, kind) } }
}
