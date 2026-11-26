package com.ketvoice.app.ui

import com.ketvoice.app.data.ListeningHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Host tests set the listening history directly. Since 9.1.1 the public flows show this device's own listening plus
 * the account's other devices', derived again after every change, so a test sets the device's own flows and then
 * calls [publish].
 */
internal object ListeningSeam {
    private val own = mapOf("tracks" to "_tracks", "knownTracks" to "_known", "playCounts" to "_playCounts",
        "listenedMs" to "_listenedMs", "days" to "_days", "years" to "_years", "months" to "_months")

    @Suppress("UNCHECKED_CAST")
    fun <T> own(name: String): MutableStateFlow<T> = ListeningHistory::class.java.getDeclaredField(own.getValue(name))
        .apply { isAccessible = true }.get(ListeningHistory) as MutableStateFlow<T>

    /** This device's own flow behind one of [ListeningHistory]'s public flows. */
    fun <T> ownOf(public: StateFlow<T>): MutableStateFlow<T> = own(when {
        public === ListeningHistory.tracks -> "tracks"
        public === ListeningHistory.knownTracks -> "knownTracks"
        public === ListeningHistory.playCounts -> "playCounts"
        public === ListeningHistory.listenedMs -> "listenedMs"
        public === ListeningHistory.days -> "days"
        public === ListeningHistory.years -> "years"
        public === ListeningHistory.months -> "months"
        else -> error("not a listening flow")
    })

    fun publish() = ListeningHistory.republishForTests()

    /** The device's own flow behind [public], showing every value a test sets right away. */
    fun <T> live(public: StateFlow<T>): MutableStateFlow<T> = Live(ownOf(public))

    /** Same for a field named the way [ListeningHistory] names its own flows (`_days`, `_tracks`…). */
    @Suppress("UNCHECKED_CAST")
    fun <T> liveField(name: String): MutableStateFlow<T> = Live(ListeningHistory::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(ListeningHistory) as MutableStateFlow<T>)

    private class Live<T>(private val f: MutableStateFlow<T>) : MutableStateFlow<T> by f {
        override var value: T
            get() = f.value
            set(v) { f.value = v; publish() }
    }
}
