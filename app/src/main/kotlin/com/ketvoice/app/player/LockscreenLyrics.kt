package com.ketvoice.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * « Paroles sur l'écran verrouillé » (6.1): the sung line in its own silent, ongoing notification, beside the
 * media notification and never inside it.
 *
 * Why a notification of its own and not the media one or the session's metadata:
 * - Since Android 13 the media controls on the lock screen and in the shade are drawn by the system from the
 *   media *session* (title, artist, cover), not from the notification's text: text put in the media notification
 *   is not shown there.
 * - Putting the line in the session's title or artist would show it on every Bluetooth car display, in Android
 *   Auto, on watches, and in scrobblers and Discord as if it were the title, changing every few seconds.
 * - A MediaStyle notification cannot be promoted to an Android 16 Live Update (only standard, BigText, Call and
 *   Progress styles can); a separate standard notification can.
 *
 * So: a low-importance channel (no sound, no vibration, no heads-up), visible on the lock screen in full, its
 * title the sung line and its text the next line (or the translation the lyrics screen shows). On Android 16
 * it asks to be promoted (a Live Update: at the top of the lock screen and the shade, expanded, with a chip in
 * the status bar); the listener can turn that off in Android's settings, and older versions show it as an
 * ordinary silent notification. Phones that hide silent notifications on the lock screen (an Android option,
 * and the default of some makers) need the channel raised in Android's settings: Settings → Lyrics opens it.
 */
internal object LyricsNotification {
    const val CHANNEL = "ketvoice.lyrics"
    const val ID = 7_402
    const val GROUP = "ketvoice.lyrics"
    /** Android 16's request for a promoted ongoing notification (Notification.EXTRA_REQUEST_PROMOTED_ONGOING, API 36.1). */
    const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
    const val PROMOTION_SDK = 36
    const val ACTION_DISMISSED = "com.ketvoice.app.lyrics.DISMISSED"
    const val EXTRA_TRACK = "track"

    /** What the notification says, worked out from the line (host-tested). */
    data class Content(val trackId: String, val title: String, val translation: String?, val next: String?, val sub: String) {
        /** Folded: the translation if the lyrics screen shows one, else the next line. */
        val text: String? get() = translation ?: next
        /** Unfolded (and as a Live Update): both. */
        val big: String? get() = listOfNotNull(translation, next).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun content(line: LiveLine): Content = Content(
        trackId = line.trackId,
        title = line.current,
        translation = line.translation,
        next = line.next,
        sub = listOf(line.title, line.artist).filter { it.isNotBlank() }.joinToString(" · "),
    )

    /** The translation in italics, as under the lines of the lyrics screen. */
    private fun styled(text: String?, translation: String?): CharSequence? {
        if (text == null || translation.isNullOrEmpty() || !text.startsWith(translation)) return text
        return android.text.SpannableString(text).apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), 0, translation.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, AppText.t("Paroles en direct"), NotificationManager.IMPORTANCE_LOW).apply {
            description = AppText.t("La ligne chantée sur l'écran verrouillé et dans les notifications")
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    /** Whether Android lets Ketvoice show it: notifications allowed, the permission granted, the channel not turned off. */
    fun allowed(context: Context): Boolean {
        val app = context.applicationContext
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val channel = app.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun build(context: Context, content: Content, sdk: Int = Build.VERSION.SDK_INT): Notification {
        val app = context.applicationContext
        val open = PendingIntent.getActivity(app, 3, Intent(app, com.ketvoice.app.MainActivity::class.java)
            .putExtra(com.ketvoice.app.widget.WidgetIntents.OPEN, "lyrics")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismissed = PendingIntent.getBroadcast(app, 4, Intent(app, LyricsNotificationReceiver::class.java)
            .setAction(ACTION_DISMISSED).putExtra(EXTRA_TRACK, content.trackId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(com.ketvoice.app.R.drawable.ic_notification_lyrics)
            .setContentTitle(content.title)
            .setContentText(styled(content.text, content.translation))
            .setSubText(content.sub.ifBlank { null })
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true) // a watch would buzz awake at every line
            .setGroup(GROUP)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        content.big?.let { builder.setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(content.title).bigText(styled(it, content.translation))) }
        // Android 16: a Live Update when the system allows it (ongoing, titled, standard or BigText style, not colourised).
        if (sdk >= PROMOTION_SDK) builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED, true) })
        return builder.build()
    }

    fun post(context: Context, content: Content): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            ensureChannel(context)
            NotificationManagerCompat.from(context).notify(ID, build(context, content))
            true
        } catch (_: SecurityException) {
            // Notifications refused at that moment: nothing else depends on it.
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun cancel(context: Context) { runCatching { NotificationManagerCompat.from(context).cancel(ID) } }

    /** The title whose line the listener swiped away: not shown again until another title plays. */
    @Volatile var dismissedTrack: String? = null
}

/** The listener swiped the lyrics notification away. */
class LyricsNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LyricsNotification.ACTION_DISMISSED) return
        LyricsNotification.dismissedTrack = intent.getStringExtra(LyricsNotification.EXTRA_TRACK)
        LiveLyrics.demandChanged()
    }
}

/** Whether the lock screen line is wanted (host-tested): the setting, the device and what Android allows. */
internal object LockscreenLyricsGate {
    fun wanted(setting: Boolean, quest: Boolean, allowed: Boolean): Boolean = setting && !quest && allowed
}

/**
 * The lock screen line, run by the playback service: [LiveLyricsEngine] works the line out, and this posts it,
 * at most once every [MIN_POST_MS] (Android drops updates sent faster than a few a second and each one is drawn
 * by the system), cancels it when there is nothing to show, and stops with the service.
 */
internal class LockscreenLyrics(
    private val context: Context,
    private val scope: CoroutineScope,
    playhead: () -> Playhead?,
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val screenOn: () -> Boolean = { defaultScreenOn(context) },
) {
    val engine = LiveLyricsEngine(scope, playhead, screenOn, ::demand)
    private val throttle = PostThrottle(MIN_POST_MS)
    private var shown: LyricsNotification.Content? = null
    private var sink: Job? = null
    private var receiver: BroadcastReceiver? = null
    /** Posts sent to Android (for tests). */
    var posts = 0; private set

    fun demand(): LiveLyrics.Demand = LiveLyrics.Demand(
        notification = LockscreenLyricsGate.wanted(AppSettings.lockscreenLyrics.value, com.ketvoice.app.BuildConfig.IS_QUEST,
            LyricsNotification.allowed(context)),
        widget = LiveLyrics.widgetWants)

    fun start() {
        if (sink != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent?) { engine.poke() }
        }.also { r ->
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(context, r,
                    IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
        sink = scope.launch {
            LiveLyrics.state.map { state ->
                state.line?.takeIf { state.notify && it.trackId != LyricsNotification.dismissedTrack }?.let(LyricsNotification::content)
            }.distinctUntilChanged().collectLatest { show(it) }
        }
        engine.start()
    }

    fun poke() = engine.poke()

    fun stop() {
        engine.stop()
        sink?.cancel(); sink = null
        receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }; receiver = null
        if (shown != null) LyricsNotification.cancel(context)
        shown = null
    }

    private suspend fun show(content: LyricsNotification.Content?) {
        if (content == null) {
            if (shown != null) LyricsNotification.cancel(context)
            shown = null
            return
        }
        // A new title: a line swiped away earlier may show again.
        if (LyricsNotification.dismissedTrack != null && LyricsNotification.dismissedTrack != content.trackId) LyricsNotification.dismissedTrack = null
        val wait = throttle.waitAt(now())
        if (wait > 0) delay(wait)
        if (LyricsNotification.post(context, content)) { posts++; shown = content }
        throttle.posted(now())
    }

    companion object {
        const val MIN_POST_MS = 1_000L
        fun defaultScreenOn(context: Context): Boolean =
            runCatching { context.getSystemService(android.os.PowerManager::class.java)?.isInteractive }.getOrNull() ?: true
    }
}
