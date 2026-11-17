package com.ketvoice.app.airket

import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import com.ketvoice.app.handoff.LanBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Air Ket on the phone (7.0): the sender and the receiver as the screens drive them. The engines (AirKetEngine.kt)
 * do the network work; this object owns the screens' state, the announcement and search on the local network, the
 * staging folder and the foreground service that keeps a transfer alive (AirKetService: notification, wake lock,
 * Wi-Fi lock).
 */
internal object AirKet {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _send = MutableStateFlow(AirKetSendUi())
    val send: StateFlow<AirKetSendUi> = _send
    private val _receive = MutableStateFlow(AirKetReceiveUi())
    val receive: StateFlow<AirKetReceiveUi> = _receive
    private val _ongoing = MutableStateFlow<AirKetOngoing?>(null)
    val ongoing: StateFlow<AirKetOngoing?> = _ongoing

    /** The sheet is open: from Settings, from onboarding or from a QR code. */
    val sheet = mutableStateOf<Mode?>(null)
    enum class Mode { Menu, Send, Receive }

    private var app: Context? = null
    private var sender: AirKetSender? = null
    private var announcer: AirKetAnnouncer? = null
    private var finder: AirKetFinder? = null
    private var sendJob: Job? = null
    private var receiveJob: Job? = null
    private var receiving: AirKetReceiving? = null
    private var wifi: AirKetWifi? = null
    private var lastFound: AirKetFound? = null
    private var lastCode = ""
    private val sentBytes = AtomicLong()

    private fun deviceName() = (Build.MODEL ?: "Android").filterNot { it.isISOControl() }.take(40)

    private fun staging(context: Context) = AirKetStaging(File(context.filesDir, "airket-incoming")).also { dropStale(it.dir) }

    private fun dropStale(dir: File) {
        val newest = dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: return
        if (System.currentTimeMillis() - newest > AirKetStaging.KEEP_MS) dir.listFiles()?.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- opening

    /** A QR code or a link brought a code (and perhaps the sender's address): the receive screen opens with it. */
    fun openReceive(code: String, host: String? = null, port: Int = 0) {
        val digits = AirKetCode.normalize(code) ?: return
        _receive.update { it.copy(prefilledCode = digits) }
        pendingAddress = host?.takeIf { port in 1024..65535 }?.let { it to port }
        sheet.value = Mode.Receive
    }

    private var pendingAddress: Pair<String, Int>? = null

    // ---------------------------------------------------------------- sending

    fun startSending(context: Context) {
        if (sendJob?.isActive == true) return
        val application = context.applicationContext
        app = application
        val network = AirKetNet.wifi(application)
        if (network == null) { _send.value = AirKetSendUi(AirKetSendUi.Phase.NoWifi); return }
        wifi = network
        _send.value = AirKetSendUi(AirKetSendUi.Phase.Preparing)
        sendJob = scope.launch(Dispatchers.IO) { runSender(application, network) }
    }

    private suspend fun runSender(application: Context, network: AirKetWifi) {
        try {
            val source = AirKetDeviceSource(application, deviceName()).also { it.prepare() }
            val code = AirKetCode.generate()
            sentBytes.set(0)
            val engine = AirKetSender(network.boundary, code, source, onState = ::onSenderState, onSent = { sentBytes.addAndGet(it) })
            sender = engine
            announcer = AirKetAnnouncer(application).also {
                it.start(network, "AirKet-" + java.util.UUID.randomUUID().toString().take(8), deviceName(), engine.port) {
                    _send.update { ui -> ui.copy(phase = AirKetSendUi.Phase.Failed) }
                }
            }
            val link = AirKetCode.link(code, network.boundary.address.hostAddress, engine.port)
            _send.value = AirKetSendUi(AirKetSendUi.Phase.Waiting, code, link, (engine.millisLeft / 1000).toInt(),
                titles = source.manifest.titles, downloadBytes = source.manifest.downloadBytes)
            watch(engine)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _send.update { it.copy(phase = AirKetSendUi.Phase.Failed) } }
    }

    /** Once a second: the code's countdown, the speed and the notification, until the sender closes. */
    private suspend fun watch(engine: AirKetSender) {
        var last = 0L
        while (currentCoroutineContext().isActive && sender === engine) {
            engine.tick()
            val sent = sentBytes.get()
            val speed = sent - last; last = sent
            _send.update { it.copy(secondsLeft = (engine.millisLeft / 1000).toInt(), wrongTries = engine.wrongTries, sentBytes = sent, bytesPerSecond = speed) }
            if (engine.currentState == AirKetState.Sending) _ongoing.value = AirKetOngoing(true, "Envoi", sent, 0, speed)
            if (engine.currentState in setOf(AirKetState.Done, AirKetState.Expired, AirKetState.Locked, AirKetState.Cancelled)) break
            delay(1000)
        }
        finishSending()
    }

    private fun onSenderState(state: AirKetState) {
        val phase = when (state) {
            AirKetState.Waiting -> AirKetSendUi.Phase.Waiting
            AirKetState.Paired -> AirKetSendUi.Phase.Paired
            AirKetState.Sending -> AirKetSendUi.Phase.Sending
            AirKetState.Done -> AirKetSendUi.Phase.Done
            AirKetState.Expired -> AirKetSendUi.Phase.Expired
            AirKetState.Locked -> AirKetSendUi.Phase.Locked
            AirKetState.Cancelled -> return
            AirKetState.Failed -> AirKetSendUi.Phase.Failed
        }
        _send.update { it.copy(phase = phase) }
        // A receiver is connected: the transfer now runs under a foreground service.
        if (state == AirKetState.Paired) { _ongoing.value = AirKetOngoing(true, "", 0, 0, 0); app?.let { AirKetService.start(it) } }
        else if (state == AirKetState.Done || state == AirKetState.Failed) _ongoing.update { if (it?.sending == true) null else it }
    }

    private fun finishSending() {
        announcer?.stop(); announcer = null
        sender?.close(); sender = null
        _ongoing.update { if (it?.sending == true) null else it }
    }

    /** Stops sending: the phone stops listening and the code stops working. */
    fun stopSending() {
        sendJob?.cancel(); sendJob = null
        finishSending()
        _send.value = AirKetSendUi()
    }

    // ---------------------------------------------------------------- receiving

    fun startSearching(context: Context) {
        val application = context.applicationContext
        app = application
        finder?.stop()
        val network = AirKetNet.wifi(application)
        if (network == null) { _receive.update { AirKetReceiveUi(AirKetReceiveUi.Phase.Failed, error = AirKetReceiveUi.Error.NoWifi, prefilledCode = it.prefilledCode) }; return }
        wifi = network
        _receive.update { AirKetReceiveUi(AirKetReceiveUi.Phase.Searching, prefilledCode = it.prefilledCode) }
        finder = AirKetFinder(application, network.boundary) { list ->
            _receive.update { if (it.phase == AirKetReceiveUi.Phase.Searching) it.copy(found = list) else it }
        }.also { it.start() }
        // A QR code gave the address: no need to wait for the search.
        pendingAddress?.let { (host, port) ->
            runCatching { InetAddress.getByName(host) }.getOrNull()?.takeIf { network.boundary.allows(it) }
                ?.let { connectTo(AirKetFound("link", "Air Ket", it, port), _receive.value.prefilledCode) }
        }
        pendingAddress = null
    }

    fun stopSearching() { finder?.stop(); finder = null }

    fun connectTo(found: AirKetFound, rawCode: String) {
        val code = AirKetCode.normalize(rawCode)
        val application = app ?: return
        val network = wifi ?: return
        if (code == null || receiveJob?.isActive == true) return
        stopSearching()
        lastFound = found; lastCode = code
        _receive.update { it.copy(phase = AirKetReceiveUi.Phase.Connecting, error = null) }
        val session = AirKetReceiving(application, staging(application), ::progress).also { receiving = it }
        receiveJob = scope.launch(Dispatchers.IO) {
            try {
                val offer = session.connect(network.boundary, found, code)
                _receive.update { it.copy(phase = AirKetReceiveUi.Phase.Offer, offer = offer) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (refused: AirKetRefused) { fail(if (refused.reason == AirKetRefused.Reason.WrongCode) AirKetReceiveUi.Error.WrongCode else AirKetReceiveUi.Error.Closed) }
            catch (_: Exception) { fail(AirKetReceiveUi.Error.Closed) }
        }
    }

    /** « Recevoir » on the offer: [withDownloads] adds the titles, [replace] makes the playlists and favourites the sender's. */
    fun accept(withDownloads: Boolean, replace: Boolean) {
        val application = app ?: return
        val session = receiving ?: return
        if (receiveJob?.isActive == true) return
        _receive.update { it.copy(phase = AirKetReceiveUi.Phase.Receiving, label = "Sauvegarde", doneBytes = 0, totalBytes = 0) }
        _ongoing.value = AirKetOngoing(false, "", 0, 0, 0)
        AirKetService.start(application)
        receiveJob = scope.launch(Dispatchers.IO) {
            try {
                val summary = session.run(withDownloads, replace)
                _receive.update { it.copy(phase = AirKetReceiveUi.Phase.Summary, summary = summary) }
            } catch (cancelled: CancellationException) { fail(AirKetReceiveUi.Error.Cancelled) }
            catch (e: IllegalArgumentException) { fail(if (e.message == "Space") AirKetReceiveUi.Error.NoRoom else AirKetReceiveUi.Error.Other) }
            catch (e: Exception) { fail(if (e.isStop()) AirKetReceiveUi.Error.Interrupted else AirKetReceiveUi.Error.Other) }
            finally { _ongoing.update { if (it?.sending == false) null else it } }
        }
    }

    private var lastTick = 0L
    private var lastBytes = 0L
    private fun progress(label: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTick < 500 && done < total) return
        val speed = if (lastTick == 0L || now <= lastTick) 0L else ((done - lastBytes).coerceAtLeast(0) * 1000 / (now - lastTick))
        lastTick = now; lastBytes = done
        _receive.update { it.copy(label = label, doneBytes = done, totalBytes = total, bytesPerSecond = speed) }
        _ongoing.value = AirKetOngoing(false, label, done, total, speed)
    }

    private fun fail(error: AirKetReceiveUi.Error) {
        receiving?.close()
        _receive.update { it.copy(phase = AirKetReceiveUi.Phase.Failed, error = error) }
    }

    /** Resumes after an interruption with the same code: what was verified is not fetched again. */
    fun resume() { lastFound?.let { connectTo(it, lastCode) } }

    /** Cancel button: stops the transfer, keeps what is verified for a later resume. */
    fun cancel() {
        receiving?.cancel(); receiveJob?.cancel()
        if (sender != null) stopSending()
    }

    fun closeReceive() {
        receiving?.cancel(); receiveJob?.cancel(); receiveJob = null
        receiving?.close(); receiving = null
        stopSearching()
        _receive.value = AirKetReceiveUi()
        _ongoing.update { if (it?.sending == false) null else it }
    }

    /** « Effacer » : forgets what a stopped transfer kept. */
    fun discardStaging(context: Context) { staging(context.applicationContext).clear() }
}
