package com.ketvoice.desktop.audio

import androidx.compose.runtime.snapshotFlow
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.HeadphoneLink
import com.ketvoice.app.player.HeadphoneLinks
import com.ketvoice.app.player.HeadphoneProfile
import com.ketvoice.app.player.HeadphoneStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * « Égaliseur par casque » on Windows (the phone's 9.0): the listener's corrections (`headphoneProfiles`), each linked
 * to an output device (`headphoneLinks`, synced with the phone's), played by the engine's [HeadphoneStage] whenever
 * that device is the one Ketvoice plays to. A Windows device is known by its name, kept only as a digest.
 */
object DesktopHeadphoneEq {
    /** What the equalizer page shows: the device playing (null: the system's default), its link and whether it is heard. */
    data class Status(val device: String? = null, val key: String? = null, val profile: HeadphoneProfile? = null, val applied: Boolean = false)

    /** « Écouter sans correction »: held off while the listener compares (never saved). */
    val compare = MutableStateFlow(false)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    @Volatile private var engine: FfmpegAudioEngine? = null
    @Volatile private var device: String? = null
    private var jobs: List<Job> = emptyList()

    /**
     * The key a device is linked by: a digest of its name, in the form the phone gives an output it knows by name only
     * (`btn:`), so the phone keeps the PC's links when it edits its own (it drops any other form). Null for the
     * system's default, whose device Java Sound does not name.
     */
    fun keyOf(device: String?): String? = device?.trim()?.takeIf { it.isNotEmpty() }?.let { "btn:" + HeadphoneLinks.digest("windows|$it") }

    /** The correction heard on [device]: its linked one, while the switch is on and the listener is not comparing. */
    fun resolve(device: String?, enabled: Boolean, comparing: Boolean, profiles: List<HeadphoneProfile>, links: Map<String, HeadphoneLink>): Status {
        val key = keyOf(device)
        val profile = HeadphoneLinks.profileFor(key, profiles, links)
        return Status(device, key, profile, applied = profile != null && enabled && !comparing)
    }

    /** Takes up the device Ketvoice plays to now. */
    fun follow(device: String?) { this.device = device; update() }

    /** The settings, a link or the comparison changed: the engine follows. */
    fun update() {
        val s = resolve(device, AppSettings.headphoneEq.value, compare.value, HeadphoneStore.profiles(), HeadphoneStore.links())
        _status.value = s
        engine?.setHeadphoneCorrection(s.profile?.takeIf { s.applied })
    }

    fun attach(engine: FfmpegAudioEngine, scope: CoroutineScope) {
        jobs.forEach { it.cancel() }
        this.engine = engine
        jobs = listOf(
            scope.launch { engine.output.collect(::follow) },
            scope.launch { compare.collect { update() } },
            // A change from this page, a restore or the account's sync (the phone's profiles and links).
            scope.launch {
                snapshotFlow { Triple(AppSettings.headphoneEq.value, AppSettings.headphoneProfiles.value, AppSettings.headphoneLinks.value) }
                    .collect { update() }
            },
        )
    }

    // ------------------------------------------------------------------ the listener's changes

    /** Adds [profile] (one of the same name is replaced); the first one goes to the device playing when nothing is linked to it yet. */
    fun import(profile: HeadphoneProfile): HeadphoneProfile {
        val kept = HeadphoneStore.add(profile)
        val s = _status.value
        if (s.key != null && s.profile == null) link(s.device, kept) else update()
        return kept
    }

    fun remove(profileId: String) { HeadphoneStore.remove(profileId); update() }

    /** Links [profile] to [device] (null profile: unlinked). */
    fun link(device: String?, profile: HeadphoneProfile?) {
        val name = device?.trim() ?: return
        val key = keyOf(name) ?: return
        HeadphoneStore.link(key, name, profile)
        update()
    }

    fun unlink(key: String) { HeadphoneStore.unlink(key); update() }

    /** Tests. */
    internal fun resetForTest() { jobs.forEach { it.cancel() }; jobs = emptyList(); engine = null; device = null; compare.value = false; _status.value = Status() }

    /** Tests: as [attach] without the collectors. */
    internal fun attachForTest(engine: FfmpegAudioEngine?) { this.engine = engine }
}
