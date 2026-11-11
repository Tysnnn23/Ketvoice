package com.ketvoice.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.ketvoice.app.ui.common.GlassSlider
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.handoff.WifiHandoff
import com.ketvoice.app.player.AudioEqualizer
import com.ketvoice.app.player.EqualizerProfile
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.LiquidGlassSwitch
import com.ketvoice.app.ui.settings.SettingsAction
import com.ketvoice.app.ui.settings.SettingsSheet
import com.ketvoice.app.theme.LocalTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlaybackFeatureDialogs() {
    com.ketvoice.app.ui.library.PlaylistDialogs()
    if (AppUi.showCapsule.value) com.ketvoice.app.ui.sub.CapsuleDialog { AppUi.showCapsule.value = false }
    val app=LocalContext.current.applicationContext
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val enabled=AppSettings.wifiHandoffEnabled.value
    DisposableEffect(app,lifecycle,enabled){
        fun update(){if(enabled&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))WifiHandoff.start(app) else WifiHandoff.stop()}
        val observer=LifecycleEventObserver{_,_->update()}
        lifecycle.addObserver(observer);update()
        onDispose{lifecycle.removeObserver(observer);WifiHandoff.stop()}
    }
    if(AppUi.showEqualizer.value) EqualizerDialog { AppUi.showEqualizer.value=false }
    if(AppUi.showHandoff.value) HandoffDialog { AppUi.showHandoff.value=false }
    val pending by WifiHandoff.pending.collectAsState()
    pending?.let{offer->
        val theme = LocalTheme.current
        SettingsSheet(onDismiss={WifiHandoff.approve(offer.id,false)}) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Reprendre la lecture ici ?", color=theme.onBackground, fontWeight=FontWeight.Bold, fontSize=20.sp)
                Text("${offer.message.sender} · ${offer.address}",localize=false,color=theme.secondary,fontSize=13.sp)
                Text(offer.message.current.title+" — "+offer.message.current.artist,localize=false,color=theme.onBackground)
                Text("La lecture et la file seront reprises uniquement après votre accord.",color=theme.secondary)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    SettingsAction("Refuser", { WifiHandoff.approve(offer.id,false) })
                    SettingsAction("Accepter", { WifiHandoff.approve(offer.id,true) }, primary=true)
                }
            }
        }
    }
}

@Composable
private fun EqualizerDialog(onClose:()->Unit){
    val status by AudioEqualizer.status.collectAsState()
    val theme = LocalTheme.current
    val scope=rememberCoroutineScope()
    var job by remember{mutableStateOf<Job?>(null)}
    fun apply(){job?.cancel();job=scope.launch{delay(45);Player.applyEqualizer()}}
    fun finish(){job?.cancel();Player.applyEqualizer();SettingsStore.save();onClose()}
    SettingsSheet(onDismiss=::finish) {
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(rememberScrollState()).padding(20.dp)){
            Text("Égaliseur",color=theme.onBackground,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(bottom=12.dp))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Activer l'égaliseur",color=theme.onBackground,modifier=Modifier.weight(1f))
                val label = com.ketvoice.app.data.AppText.t("Activer l'égaliseur")
                LiquidGlassSwitch(AppSettings.equalizerEnabled.value,{AppSettings.equalizerEnabled.value=it;Player.applyEqualizer();SettingsStore.save()},
                    modifier=Modifier.semantics { contentDescription = label })
            }
            Text(when{
                status.sessionId==0 -> "Lancez un titre pour initialiser l'effet audio."
                !status.available -> "L'égaliseur natif est indisponible sur cet appareil."
                status.applied -> "Traitement natif actif sur la session audio."
                else -> "Égaliseur désactivé."
            },color=theme.secondary,fontSize=12.sp,modifier=Modifier.padding(vertical=8.dp))
            EqualizerCurve(AppSettings.equalizerGains.value)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                EqualizerProfile.presets.forEach{(name,gains)->
                    val selected=AppSettings.equalizerGains.value==gains
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if(selected) theme.accent.copy(alpha=.16f) else theme.onBackground.copy(alpha=.06f))
                        .selectable(selected,role=Role.RadioButton){AppSettings.equalizerGains.value=gains;Player.applyEqualizer();SettingsStore.save()}
                        .heightIn(min=48.dp).padding(horizontal=14.dp),contentAlignment=Alignment.Center){
                        Text(name,color=if(selected) theme.accent else theme.onBackground,fontSize=12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=4.dp),verticalAlignment=Alignment.CenterVertically){
                Text(if(status.centersHz.isEmpty()) "Bandes natives : détection en cours"
                     else "${status.centersHz.size} bandes natives · ${status.centersHz.joinToString(" · ") { if (it>=1000) "${it/1000} kHz" else "$it Hz" }}",
                     localize=false,color=theme.secondary,fontSize=11.sp,modifier=Modifier.weight(1f))
                Text("Réinitialiser", color=theme.accent, fontSize=12.sp,
                    modifier=Modifier.clickable{
                        AppSettings.equalizerGains.value=listOf(0,0,0,0,0)
                        Player.applyEqualizer();SettingsStore.save()
                    })
            }
            EqualizerProfile.frequencies.forEachIndexed{i,hz->
                val gain=AppSettings.equalizerGains.value[i]
                Row(Modifier.fillMaxWidth().padding(top=8.dp)){
                    Text(if(hz%1000==0) "${hz/1000} kHz" else if(hz>=1000) "${hz/1000f} kHz" else "$hz Hz",localize=false,color=theme.onBackground,fontSize=12.sp)
                    Spacer(Modifier.weight(1f));Text("${if(gain>0)"+" else ""}$gain dB",localize=false,color=theme.secondary,fontSize=12.sp)
                }
                GlassSlider(value=gain.toFloat(),onValueChange={value->
                    AppSettings.equalizerGains.value=AppSettings.equalizerGains.value.toMutableList().apply{set(i,value.toInt())};apply()
                },onValueChangeFinished={Player.applyEqualizer();SettingsStore.save()},valueRange=-12f..12f,
                    enabled=status.available&&AppSettings.equalizerEnabled.value)
            }
            Text("Les gains sont adaptés aux bandes et à la plage réellement prises en charge par l'appareil.",color=theme.secondary,fontSize=11.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { SettingsAction("Fermer", ::finish, primary=true) }
        }
    }
}

@Composable
private fun HandoffDialog(onClose:()->Unit){
    val status by WifiHandoff.status.collectAsState()
    val playback by Player.state.collectAsState()
    val theme=LocalTheme.current
    SettingsSheet(onDismiss=onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Transfert sur le même Wi-Fi",color=theme.onBackground,fontSize=20.sp,fontWeight=FontWeight.Bold)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Découverte et réception locales",color=theme.onBackground,modifier=Modifier.weight(1f))
                val label=com.ketvoice.app.data.AppText.t("Découverte et réception locales")
                LiquidGlassSwitch(AppSettings.wifiHandoffEnabled.value,{
                    AppSettings.wifiHandoffEnabled.value=it;SettingsStore.save()
                },modifier=Modifier.semantics { contentDescription=label })
            }
            Text("Activez cette option sur les deux appareils, puis choisissez le destinataire. Aucun QR code ni compte supplémentaire.",fontSize=13.sp)
            Text(status.message,fontSize=12.sp)
            if(playback.current?.let(com.ketvoice.app.handoff.HandoffMessage::compatible)!=true)
                Text("Seuls les titres du catalogue en ligne peuvent être transférés",fontSize=12.sp)
            if(status.listening&&status.peers.isEmpty()) Text("Aucun appareil trouvé. Gardez Ketvoice ouvert sur l'autre appareil et vérifiez l'isolation Wi-Fi.",fontSize=12.sp)
            status.peers.forEach{peer->
                val canSend=!status.sending&&playback.current?.let(com.ketvoice.app.handoff.HandoffMessage::compatible)==true
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.onBackground.copy(alpha=.06f))
                    .clickable(enabled=canSend){WifiHandoff.send(peer)}){
                    Column(Modifier.padding(14.dp)){
                        Text(peer.name,localize=false)
                        Text(peer.address.hostAddress.orEmpty(),localize=false,fontSize=11.sp)
                    }
                }
            }
            Text("Transfère le catalogue en ligne, la position et jusqu'à 50 titres. Aucun cookie, jeton de compte, fichier local ou URL privée n'est partagé. Confirmation obligatoire sur le destinataire.",fontSize=11.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { SettingsAction("Fermer", onClose, primary=true) }
        }
    }
}


/** Smooth glowing response curve of the five band gains. */
@Composable
private fun EqualizerCurve(gains: List<Int>) {
    val theme = LocalTheme.current
    val accent = theme.accent
    Canvas(Modifier.fillMaxWidth().height(74.dp).padding(vertical = 6.dp)) {
        if (gains.isEmpty()) return@Canvas
        val step = size.width / (gains.size - 1).coerceAtLeast(1)
        fun point(i: Int): Offset {
            val normalized = (gains.getOrElse(i) { 0 }.coerceIn(-12, 12) + 12f) / 24f
            return Offset(i * step, size.height * (.95f - normalized * .9f))
        }
        val path = Path().apply {
            val first = point(0)
            moveTo(first.x, first.y)
            for (i in 1 until gains.size) {
                val previous = point(i - 1)
                val current = point(i)
                val middle = (previous.x + current.x) / 2f
                cubicTo(middle, previous.y, middle, current.y, current.x, current.y)
            }
        }
        drawPath(path, accent.copy(alpha = .16f), style = Stroke(11.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, accent.copy(alpha = .42f), style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, theme.onBackground.copy(alpha = .92f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        for (i in gains.indices) drawCircle(theme.onBackground, 3.dp.toPx(), point(i))
    }
}
