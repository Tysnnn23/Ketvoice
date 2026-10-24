package com.ketvoice.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlaybackFeatureDialogs() {
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
        AlertDialog(onDismissRequest={WifiHandoff.approve(offer.id,false)},
            title={Text("Reprendre la lecture ici ?")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("${offer.message.sender} · ${offer.address}",localize=false,fontSize=13.sp)
                Text(offer.message.current.title+" — "+offer.message.current.artist,localize=false)
                Text("La lecture et la file seront reprises uniquement après votre accord.")
            }},
            confirmButton={TextButton(onClick={WifiHandoff.approve(offer.id,true)}){Text("Accepter")}},
            dismissButton={TextButton(onClick={WifiHandoff.approve(offer.id,false)}){Text("Refuser")}})
    }
}

@Composable
private fun EqualizerDialog(onClose:()->Unit){
    val status by AudioEqualizer.status.collectAsState()
    val scope=rememberCoroutineScope()
    var job by remember{mutableStateOf<Job?>(null)}
    fun apply(){job?.cancel();job=scope.launch{delay(45);Player.applyEqualizer()}}
    fun finish(){job?.cancel();Player.applyEqualizer();SettingsStore.save();onClose()}
    AlertDialog(onDismissRequest=::finish,title={Text("Égaliseur")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=540.dp).verticalScroll(rememberScrollState())){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Activer l'égaliseur",modifier=Modifier.weight(1f))
                Switch(AppSettings.equalizerEnabled.value,{AppSettings.equalizerEnabled.value=it;Player.applyEqualizer();SettingsStore.save()})
            }
            Text(when{
                status.sessionId==0 -> "Lancez un titre pour initialiser l'effet audio."
                !status.available -> "L'égaliseur natif est indisponible sur cet appareil."
                status.applied -> "Traitement natif actif sur la session audio."
                else -> "Égaliseur désactivé."
            },fontSize=12.sp,modifier=Modifier.padding(vertical=8.dp))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                EqualizerProfile.presets.forEach{(name,gains)->
                    FilterChip(selected=AppSettings.equalizerGains.value==gains,onClick={AppSettings.equalizerGains.value=gains;Player.applyEqualizer();SettingsStore.save()},
                        label={Text(name,fontSize=12.sp)})
                }
            }
            EqualizerProfile.frequencies.forEachIndexed{i,hz->
                val gain=AppSettings.equalizerGains.value[i]
                Row(Modifier.fillMaxWidth().padding(top=8.dp)){
                    Text(if(hz%1000==0) "${hz/1000} kHz" else if(hz>=1000) "${hz/1000f} kHz" else "$hz Hz",localize=false,fontSize=12.sp)
                    Spacer(Modifier.weight(1f));Text("${if(gain>0)"+" else ""}$gain dB",localize=false,fontSize=12.sp)
                }
                Slider(value=gain.toFloat(),onValueChange={value->
                    AppSettings.equalizerGains.value=AppSettings.equalizerGains.value.toMutableList().apply{set(i,value.toInt())};apply()
                },onValueChangeFinished={Player.applyEqualizer();SettingsStore.save()},valueRange=-12f..12f,
                    enabled=status.available&&AppSettings.equalizerEnabled.value)
            }
            Text("Les gains sont adaptés aux bandes et à la plage réellement prises en charge par l'appareil.",fontSize=11.sp)
        }},confirmButton={TextButton(onClick=::finish){Text("Fermer")}})
}

@Composable
private fun HandoffDialog(onClose:()->Unit){
    val status by WifiHandoff.status.collectAsState()
    val playback by Player.state.collectAsState()
    AlertDialog(onDismissRequest=onClose,title={Text("Transfert sur le même Wi-Fi")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Découverte et réception locales",modifier=Modifier.weight(1f))
                Switch(AppSettings.wifiHandoffEnabled.value,{
                    AppSettings.wifiHandoffEnabled.value=it;SettingsStore.save()
                })
            }
            Text("Activez cette option sur les deux appareils, puis choisissez le destinataire. Aucun QR code ni compte supplémentaire.",fontSize=13.sp)
            Text(status.message,fontSize=12.sp)
            if(playback.current?.let(com.ketvoice.app.handoff.HandoffMessage::compatible)!=true)
                Text("Seuls les titres du catalogue en ligne peuvent être transférés",fontSize=12.sp)
            if(status.listening&&status.peers.isEmpty()) Text("Aucun appareil trouvé. Gardez Ketvoice ouvert sur l'autre appareil et vérifiez l'isolation Wi-Fi.",fontSize=12.sp)
            status.peers.forEach{peer->
                val canSend=!status.sending&&playback.current?.let(com.ketvoice.app.handoff.HandoffMessage::compatible)==true
                Surface(Modifier.fillMaxWidth().clickable(enabled=canSend){WifiHandoff.send(peer)},shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),tonalElevation=2.dp){
                    Column(Modifier.padding(14.dp)){
                        Text(peer.name,localize=false)
                        Text(peer.address.hostAddress.orEmpty(),localize=false,fontSize=11.sp)
                    }
                }
            }
            Text("Transfère le catalogue en ligne, la position et jusqu'à 50 titres. Aucun cookie, jeton de compte, fichier local ou URL privée n'est partagé. Confirmation obligatoire sur le destinataire.",fontSize=11.sp)
        }},confirmButton={TextButton(onClick=onClose){Text("Fermer")}})
}
