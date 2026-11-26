package com.ketvoice.app.player

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.PlaybackJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether playback that stopped is brought back by itself (6.1, owner: « Reprise automatique au même endroit
 * si Android arrête quand même la lecture »). Only when Android or a battery manager ended the app while its
 * music played — never after the listener paused or stopped it, never after a force-stop, a crash or an
 * update — only within [WINDOW_MS] of that end, and at most [MAX_ATTEMPTS] times in a row.
 */
internal object ResumeDecision {
    const val WINDOW_MS = 5 * 60_000L
    const val MAX_ATTEMPTS = 3

    enum class Verdict(val note: String) {
        Resume("resumed"),
        Off("not resumed: automatic resume is off in Settings"),
        AlreadyPlaying("not resumed: playback is already running"),
        NotPlaying("not resumed: the music was not playing (paused or stopped)"),
        ByUser("not resumed: the app was stopped by the listener"),
        NotSystem("not resumed: the app ended by a crash, an update or itself"),
        TooLate("not resumed: it stopped more than 5 minutes ago"),
        Rebooted("not resumed: the phone restarted since"),
        TooManyAttempts("not resumed: already brought back 3 times in a row")
    }

    /** How the previous process ended, from [ApplicationExitInfo]: null when Android kept no reason. */
    enum class Ending { System, User, Other }

    fun ending(reason: Int?): Ending = when (reason) {
        null, ApplicationExitInfo.REASON_UNKNOWN, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> Ending.System
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> Ending.User
        else -> Ending.Other
    }

    /**
     * [wasPlaying]: the listener's music played at the last moment recorded; [playingNow]: this process plays;
     * [exitReason]: why the previous process ended (null when unknown); [sinceStopMs]: how long ago it stopped;
     * [attempts]: resumes already made in a row; [rebooted]: the phone started again since the music was last marked.
     */
    fun decide(enabled: Boolean, wasPlaying: Boolean, playingNow: Boolean, exitReason: Int?, sinceStopMs: Long, attempts: Int,
               rebooted: Boolean = false): Verdict = when {
        !enabled -> Verdict.Off
        playingNow -> Verdict.AlreadyPlaying
        !wasPlaying -> Verdict.NotPlaying
        ending(exitReason) == Ending.User -> Verdict.ByUser
        ending(exitReason) == Ending.Other -> Verdict.NotSystem
        rebooted -> Verdict.Rebooted
        sinceStopMs < 0 || sinceStopMs > WINDOW_MS -> Verdict.TooLate
        attempts >= MAX_ATTEMPTS -> Verdict.TooManyAttempts
        else -> Verdict.Resume
    }

    /** Where to resume: the playing process's last mark when it is about the same title, else the saved session's place. */
    fun resumePoint(markTrack: String?, markPositionMs: Long, sessionTrack: String, sessionPositionMs: Long): Long =
        (if (markTrack == sessionTrack) markPositionMs else sessionPositionMs).coerceAtLeast(0L)
}

/**
 * What the playing process last knew, kept every few seconds while music plays (6.1): the title, the place in
 * it, and whether the listener's music was playing. A process ended by Android cannot say so itself; these
 * marks are what a new process finds.
 */
// Written with commit(), off the main thread: a mark must be on the disk before Android can end the process.
@android.annotation.SuppressLint("ApplySharedPref")
internal class RevivalStore(context: Context) {
    data class Mark(val playing: Boolean, val trackId: String?, val positionMs: Long, val at: Long,
                    val attempts: Int, val lastAttemptAt: Long, val handled: Long)

    private val prefs = context.applicationContext.getSharedPreferences("playback-revival", Context.MODE_PRIVATE)

    fun read() = Mark(prefs.getBoolean("playing", false), prefs.getString("track", null), prefs.getLong("position", 0L),
        prefs.getLong("at", 0L), prefs.getInt("attempts", 0), prefs.getLong("lastAttemptAt", 0L), prefs.getLong("handled", 0L))

    /** Music playing: [trackId] at [positionMs], now. A run of resumes ends once playback has held for a while. */
    fun playing(trackId: String, positionMs: Long, now: Long) {
        val before = read()
        prefs.edit().putBoolean("playing", true).putString("track", trackId).putLong("position", positionMs.coerceAtLeast(0L))
            .putLong("at", now).apply {
                if (before.attempts > 0 && now - before.lastAttemptAt > STEADY_MS) putInt("attempts", 0)
            }.commit()
    }

    /** The music was paused or stopped (by the listener, or it ended): nothing is to be resumed. */
    fun stopped(now: Long) {
        val before = read()
        if (!before.playing && before.attempts == 0) return
        prefs.edit().putBoolean("playing", false).putLong("at", now).putInt("attempts", 0).commit()
    }

    fun attempted(markAt: Long, now: Long) {
        val before = read()
        prefs.edit().putInt("attempts", before.attempts + 1).putLong("lastAttemptAt", now).putLong("handled", markAt).commit()
    }

    /** This end was looked at and not resumed: the other ways back do not look at it again. */
    fun handled(markAt: Long) { prefs.edit().putLong("handled", markAt).commit() }

    companion object {
        /** Playback that held this long after a resume starts a new run of attempts. */
        const val STEADY_MS = 10 * 60_000L
    }
}

/**
 * Brings back playback that Android ended (6.1). Three ways back, whichever comes first:
 * - a "dead man's" alarm, pushed back every few seconds while music plays, which fires only once the
 *   playing process is gone (an exact alarm when the listener allows them: Android then lets the app
 *   start its playback from the background);
 * - Android starting the playback service again by itself (Media3's service asks to be restarted);
 * - the app being opened.
 * Each looks at the marks of [RevivalStore] and at why the previous process ended, decides with
 * [ResumeDecision], and writes what it did to the playback journal. When Android refuses to let playback
 * start from the background, a notification offers to resume it with one touch.
 */
internal object PlaybackRevival {
    const val ACTION_ALARM = "com.ketvoice.app.action.REVIVE"
    const val ACTION_TAP = "com.ketvoice.app.action.REVIVE_TAP"
    /** The app was opened: one of the ways back, as the journal names it. */
    const val WAY_APP = "app opened"
    /** The alarm fires this long after the last mark of a process that stopped marking. */
    private const val ALARM_AFTER_MS = 40_000L
    /** How often the playing process marks where it is. */
    const val MARK_MS = 5_000L
    private const val CHANNEL = "playback-revival"
    private const val NOTIFICATION_ID = 7_301

    /** Set by the playback service at each mark: this process plays now. */
    @Volatile var playingHere = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var marks = 0
    @Volatile private var armed = false

    fun enabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).getBoolean("autoResume", true)
    }.getOrDefault(true)

    /** Called by the playback service every [MARK_MS]: keeps the marks and pushes the alarm back. */
    fun mark(context: Context, trackId: String?, positionMs: Long, playing: Boolean) {
        playingHere = playing && trackId != null
        val app = context.applicationContext
        val wanted = playingHere && enabled(app)
        // Pushed back every third mark; cancelled once, when the music stops or the setting is turned off.
        val arm = wanted && (marks++ % 3 == 0 || !armed)
        val disarm = !wanted && armed
        armed = wanted
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val store = RevivalStore(app)
            if (playing && trackId != null) store.playing(trackId, positionMs, now) else store.stopped(now)
            if (arm) arm(app) else if (disarm) disarm(app)
        }
    }

    /** The service ends normally (the listener stopped it, nothing plays): nothing is to be resumed. */
    fun stopped(context: Context) {
        playingHere = false
        armed = false
        val app = context.applicationContext
        RevivalStore(app).stopped(System.currentTimeMillis())
        disarm(app)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, RevivalReceiver::class.java).setAction(ACTION_ALARM), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * Whether the alarm may be exact (Settings → Apps → Alarms and reminders, Android 12+). Android 11 (Ketvoice
     * Lite) has no such permission: exact alarms are always allowed there.
     */
    fun exactAllowed(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)

    private fun arm(context: Context) {
        runCatching {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val at = SystemClock.elapsedRealtime() + ALARM_AFTER_MS
            if (exactAllowed(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(context))
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(context))
        }
    }

    private fun disarm(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context)) }
    }

    /** When and why the process ended ([ApplicationExitInfo]'s time and reason). */
    private class Ended(val timestamp: Long, val reason: Int)

    /**
     * Why and when the app's playing process last ended, after [since] (wall clock); null when Android kept nothing.
     * Android keeps these reasons since Android 11; earlier (Ketvoice TV runs from Android 9) there is none.
     */
    private fun lastEnd(context: Context, since: Long): Ended? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .filter { it.processName == context.packageName && it.timestamp >= since }
                .maxByOrNull { it.timestamp }?.let { Ended(it.timestamp, it.reason) }
        }.getOrNull()
    }

    /**
     * Looks whether playback was ended by Android and brings it back when [ResumeDecision] says so. [way] says
     * which way back this is, for the journal. True when playback was asked to resume.
     */
    suspend fun check(context: Context, way: String): Boolean {
        val app = context.applicationContext
        val store = RevivalStore(app)
        val (mark, end) = withContext(Dispatchers.IO) { store.read().let { it to lastEnd(app, it.at - 15_000L) } }
        // Nothing was playing, or this end was already looked at by another way back.
        if (!mark.playing || mark.handled == mark.at) return false
        val now = System.currentTimeMillis()
        val since = now - (end?.timestamp ?: mark.at)
        // The phone's clock since it started is shorter than the time since the last mark: it restarted meanwhile.
        val rebooted = SystemClock.elapsedRealtime() < now - mark.at
        val verdict = ResumeDecision.decide(enabled(app), mark.playing, playingHere, end?.reason, since, mark.attempts, rebooted)
        if (verdict == ResumeDecision.Verdict.AlreadyPlaying) return false
        val why = end?.let { PlaybackJournal.reason(it.reason) } ?: "no reason kept by Android"
        val attempt = if (verdict == ResumeDecision.Verdict.Resume) " · attempt ${mark.attempts + 1}/${ResumeDecision.MAX_ATTEMPTS}" else ""
        PlaybackJournal.add("playback ended by Android ($why, ${since / 1000} s ago) · $way · ${verdict.note}$attempt")
        withContext(Dispatchers.IO) { if (verdict == ResumeDecision.Verdict.Resume) store.attempted(mark.at, now) else store.handled(mark.at) }
        if (verdict != ResumeDecision.Verdict.Resume) return false
        return resume(app, mark)
    }

    /** Plays the saved session again, at the place the playing process last marked. */
    private suspend fun resume(context: Context, mark: RevivalStore.Mark?): Boolean = withContext(Dispatchers.Main) {
        Player.connect(context)
        // The saved session is read from the disk when the queue first connects.
        val state = withTimeoutOrNull(6_000) { Player.state.first { it.current != null } }
        val track = state?.current
        if (track == null) { PlaybackJournal.add("automatic resume: no saved session to resume"); return@withContext false }
        val position = ResumeDecision.resumePoint(mark?.trackId, mark?.positionMs ?: 0L, track.id, Player.positionMs.value)
        val ok = Player.resumeRestored(position)
        if (ok) PlaybackJournal.add("automatic resume: « ${track.title} » at ${position / 60_000}:${"%02d".format((position / 1000) % 60)}")
        ok
    }

    /**
     * Android did not let playback start from the background (no exemption at that moment): the music is
     * paused rather than left to be frozen, and a notification offers to resume it.
     */
    fun refused(context: Context) {
        PlaybackJournal.add("Android did not let playback start in the background · a notification offers to resume")
        val app = context.applicationContext
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, AppText.t("Reprise de la lecture"), NotificationManager.IMPORTANCE_LOW))
            // A touch on the notification opens the app, which resumes; its button resumes without opening it.
            val open = PendingIntent.getActivity(app, 2, Intent(app, com.ketvoice.app.MainActivity::class.java).setAction(ACTION_TAP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val tap = PendingIntent.getBroadcast(app, 1, Intent(app, RevivalReceiver::class.java).setAction(ACTION_TAP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(com.ketvoice.app.R.drawable.ic_widget_play)
                .setContentTitle(AppText.t("La musique a été arrêtée par Android"))
                .setContentText(AppText.t("Touchez pour reprendre là où elle s'était arrêtée."))
                .setContentIntent(open)
                .addAction(0, AppText.t("Reprendre"), tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: RuntimeException) {
            // Notifications refused at that moment (a SecurityException among them): the journal says what happened.
        }
    }

    /**
     * Playback started by any other way (the media controls, a widget, a tile, the app): the offer to resume goes, so
     * the shade and the lock screen never show two play buttons for the same music (6.4).
     */
    fun playing(context: Context) {
        runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID) }
    }

    /** The listener touched the notification or its button: playback resumes, whatever the time since it stopped. */
    suspend fun resumeAsked(context: Context) {
        val app = context.applicationContext
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
        PlaybackJournal.add("resume asked from the notification")
        val mark = withContext(Dispatchers.IO) { RevivalStore(app).read() }
        if (!resume(app, mark)) withContext(Dispatchers.Main) { if (!Player.state.value.playWhenReady) Player.toggle() }
    }
}

/** The alarm of [PlaybackRevival] and the touch on its notification. */
class RevivalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                PlaybackJournal.init(app)
                when (intent?.action) {
                    PlaybackRevival.ACTION_ALARM -> {
                        if (!com.ketvoice.app.data.SettingsStore.loaded) com.ketvoice.app.data.SettingsStore.load(app)
                        PlaybackRevival.check(app, if (PlaybackRevival.exactAllowed(app)) "alarm" else "alarm (inexact)")
                    }
                    PlaybackRevival.ACTION_TAP -> {
                        if (!com.ketvoice.app.data.SettingsStore.loaded) com.ketvoice.app.data.SettingsStore.load(app)
                        PlaybackRevival.resumeAsked(app)
                    }
                }
            } catch (_: Exception) {
            } finally {
                result.finish()
            }
        }
    }
}
