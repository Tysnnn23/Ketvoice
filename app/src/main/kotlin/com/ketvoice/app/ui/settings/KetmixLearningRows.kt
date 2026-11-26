package com.ketvoice.app.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.KetmixLearning
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.TrackActionFeedback
import com.ketvoice.app.ui.player.KetmixLastTransition
import kotlinx.coroutines.launch

internal const val KETMIX_LEARNS_SUB =
    "Après chaque transition, 👍 ou 👎 sur le lecteur. En style automatique, Ketmix laisse de côté les transitions que vous n'aimez pas ; un style choisi à la main n'est jamais remplacé. Tout reste sur ce téléphone."

/**
 * « Ketmix apprend de vos avis » in Ketmix's settings (off by default): the switch and, while it is on, the last
 * transition to rate and a way to forget what was learnt.
 */
@Composable
internal fun ColumnScope.KetmixLearningRows() {
    val scope = rememberCoroutineScope()
    SToggle(KetIcons.Sliders, "Ketmix apprend de vos avis", KETMIX_LEARNS_SUB,
        AppSettings.ketmixLearns.value) { AppSettings.ketmixLearns.value = it; SettingsStore.save() }
    if (!AppSettings.ketmixLearns.value) return
    KetmixLastTransition(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
    SRow(KetIcons.Trash, "Oublier ce que Ketmix a appris") {
        KetmixLearning.reset()
        scope.launch { TrackActionFeedback.snackbar.showSnackbar(AppText.t("Ketmix a tout oublié")) }
    }
}
