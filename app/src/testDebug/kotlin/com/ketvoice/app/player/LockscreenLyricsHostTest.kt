package com.ketvoice.app.player

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.LyricsTranslation
import com.ketvoice.app.data.MusicApi.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The lock screen line (6.1) posted, updated and cancelled through Android's notification manager, on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LockscreenLyricsHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val repo get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var lock: LockscreenLyrics

    // A deck that moves with the host's clock.
    private var base = 0L; private var since = 0L; private var playing = true; private var trackId = "t1"
    private fun position() = if (playing) base + (SystemClock.uptimeMillis() - since) else base
    private fun head() = Playhead(trackId, position(), playing, playing, 1f, "Midnight City", "M83")

    private val lines = listOf(
        LyricLine(2_000, "Waiting in a car"),
        LyricLine(6_000, "Waiting for a ride in the dark"),
        LyricLine(6_300, "The night city grows"),
        LyricLine(6_600, "Look and see her eyes"),
        LyricLine(12_000, "They glow"),
    )

    @Before fun setup() {
        // Quest has no lock screen: the feature is off there by design (LockscreenLyricsGate).
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AppSettings.lockscreenLyrics.value = true
        LyricsNotification.dismissedTrack = null
        repo.value = LyricsRepo.State(lines = lines, isTimed = true, trackId = "t1")
        since = SystemClock.uptimeMillis()
        lock = LockscreenLyrics(context, scope, ::head, now = SystemClock::uptimeMillis, screenOn = { true })
    }

    @After fun cleanup() {
        if (::lock.isInitialized) lock.stop(); scope.cancel()
        repo.value = LyricsRepo.State()
        LyricsTranslation.showing("t1", false)
        AppSettings.lockscreenLyrics.value = true
    }

    private fun idle(ms: Long = 0) { if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) else shadowOf(Looper.getMainLooper()).idle() }
    private fun posted(): Notification? = shadowOf(manager).getNotification(LyricsNotification.ID)
    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test fun theLineIsPostedUpdatedAndCancelled() {
        lock.start(); idle()
        val intro = posted()!!
        assertEquals(LyricTimeline.REST, title(intro))
        assertEquals("Waiting in a car", text(intro))
        assertEquals(LyricsNotification.CHANNEL, intro.channelId)
        assertEquals("Midnight City · M83", intro.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertTrue("ongoing", intro.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, intro.visibility)
        assertTrue("asks to be a Live Update on Android 16", intro.extras.getBoolean(LyricsNotification.EXTRA_REQUEST_PROMOTED))
        assertFalse("never colourised (it could not be promoted)", intro.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertNull("a standard notification, not a custom view", intro.contentView)
        // Beside the media notification (6.4): no controls of its own, so the lock screen never shows two transports,
        // and its own group, so Android never folds it into the media controls.
        assertTrue("no actions", intro.actions.isNullOrEmpty())
        assertNull("not a media style", intro.extras.getString(Notification.EXTRA_TEMPLATE)?.takeIf { it.contains("MediaStyle") })
        assertEquals(LyricsNotification.GROUP, intro.group)
        val channel = manager.getNotificationChannel(LyricsNotification.CHANNEL)!!
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull("silent", channel.sound)
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)

        idle(2_100)
        val first = posted()!!
        assertEquals("Waiting in a car", title(first))
        assertEquals("Waiting for a ride in the dark", text(first))
        assertEquals("Waiting for a ride in the dark", first.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())

        // Three lines in 600 ms: Android is not sent each of them, the last one arrives.
        idle(3_900) // 6 000 ms: the quick run starts
        val before = lock.posts
        idle(1_200)
        assertTrue("at most two posts for three quick lines (${lock.posts - before})", lock.posts - before <= 2)
        assertEquals("Look and see her eyes", title(posted()!!))

        // Paused: the notification goes; playing again brings it back.
        base = position(); playing = false; lock.poke(); idle()
        assertNull(posted())
        since = SystemClock.uptimeMillis(); playing = true; lock.poke(); idle(1_100)
        assertEquals("Look and see her eyes", title(posted()!!))

        // A seek.
        base = 12_500; since = SystemClock.uptimeMillis(); lock.poke(); idle(1_100)
        assertEquals("They glow", title(posted()!!))
        assertNull("nothing after the last line", text(posted()!!))

        // The service stops: nothing left behind.
        lock.stop(); idle()
        assertNull(posted())
    }

    @Test fun theTranslationShowsUnderTheLine() {
        LyricsTranslation.showing("t1", true)
        LyricsTranslation.translated("t1", 0, "En attendant dans une voiture")
        base = 2_500
        lock.start(); idle()
        val n = posted()!!
        assertEquals("Waiting in a car", title(n))
        assertEquals("En attendant dans une voiture", text(n))
        assertEquals("En attendant dans une voiture\nWaiting for a ride in the dark", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
    }

    @Test fun noLyricsNoNotification() {
        repo.value = LyricsRepo.State(lines = listOf(LyricLine(-1, "Plain text only")), isTimed = false, trackId = "t1")
        lock.start(); idle(3_000)
        assertNull(posted())
        // Lyrics of another title (the new one still loading).
        repo.value = LyricsRepo.State(lines = lines, isTimed = true, trackId = "other"); idle(500)
        assertNull(posted())
    }

    @Test fun theSettingAndThePermissionAreHonoured() {
        AppSettings.lockscreenLyrics.value = false
        lock.start(); idle(3_000)
        assertNull(posted())
        AppSettings.lockscreenLyrics.value = true; LiveLyrics.demandChanged(); idle()
        assertNotNull(posted())
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        LiveLyrics.demandChanged(); idle()
        assertNull("permission withdrawn: cancelled", posted())
    }

    @Test fun aLineSwipedAwayStaysAwayForThatTitle() {
        lock.start(); idle()
        assertNotNull(posted())
        // Android removes the notification and sends its delete intent.
        manager.cancel(LyricsNotification.ID)
        LyricsNotificationReceiver().onReceive(context, Intent(LyricsNotification.ACTION_DISMISSED).putExtra(LyricsNotification.EXTRA_TRACK, "t1"))
        idle(6_500)
        assertNull("not posted again for the same title", posted())
        // The next title shows again.
        trackId = "t2"; base = 2_100; since = SystemClock.uptimeMillis()
        repo.value = LyricsRepo.State(lines = lines, isTimed = true, trackId = "t2"); idle(1_100)
        assertEquals("Waiting in a car", title(posted()!!))
    }
}

/** Android 15 and older: the same notification, without the Live Update request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class LockscreenLyricsOlderAndroidHostTest {
    /** Android's own notification templates, inflated and drawn on the host (not a phone's lock screen). */
    @Test fun theTemplatesDrawTheLineBigAndTheNextSmall() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val line = LiveLine("t1", "Midnight City", "M83", 0, "Waiting in a car", "Waiting for a ride in the dark", "En attendant dans une voiture")
        val n = LyricsNotification.build(context, LyricsNotification.content(line))
        val builder = Notification.Builder.recoverBuilder(context, n)
        for ((name, remote) in listOf("folded" to builder.createContentView(), "unfolded" to builder.createBigContentView())) {
            val host = android.widget.FrameLayout(context)
            val view = remote.apply(context, host)
            val density = context.resources.displayMetrics.density
            val w = (380 * density).toInt()
            host.addView(view)
            host.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(((if (name == "folded") 76 else 200) * density).toInt(), android.view.View.MeasureSpec.EXACTLY))
            host.layout(0, 0, w, host.measuredHeight)
            val bitmap = android.graphics.Bitmap.createBitmap(w, host.measuredHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            org.robolectric.util.ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            canvas.drawColor(0xFFF2F2F7.toInt())
            host.draw(canvas)
            val file = java.io.File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/lockscreen-lyrics-$name.png")
            file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun noPromotionBeforeAndroid16() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val content = LyricsNotification.content(LiveLine("t1", "Song", "Artist", 0, "Line", "Next"))
        val n = LyricsNotification.build(context, content)
        assertFalse(n.extras.containsKey(LyricsNotification.EXTRA_REQUEST_PROMOTED))
        assertEquals("Line", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Next", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }
}
