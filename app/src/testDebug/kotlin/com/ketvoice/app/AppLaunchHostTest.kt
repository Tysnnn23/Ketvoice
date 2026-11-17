package com.ketvoice.app

import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** The whole app starts: KetvoiceApp, MainActivity, its first frames (6.1.0 crashed at launch on a phone). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLaunchHostTest {
    @Test fun theAppStartsAndDrawsItsFirstScreens() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        // The host cannot run the playback service's binder: the activity starts without its connection.
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, com.ketvoice.app.player.PlaybackService::class.java))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
        controller.pause().stop().destroy()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafeModeHostTest {
    @Test fun twoCrashedLaunchesOpenSafeModeWithTheReport() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val logs = java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, "logs").apply { mkdirs() }
        java.io.File(logs, "crash-${System.currentTimeMillis()}.txt").writeText("java.lang.IllegalStateException: boom\n\tat com.ketvoice.app.Example")
        app.getSharedPreferences("ketvoice-crash-loop", android.content.Context.MODE_PRIVATE).edit()
            .putInt("unsteadyLaunches", 2).putLong("lastLaunchAt", System.currentTimeMillis() - 5_000).commit()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        val next = shadowOf(main.get()).nextStartedActivity
        org.junit.Assert.assertEquals(SafeModeActivity::class.java.name, next.component?.className)
        org.junit.Assert.assertTrue(main.get().isFinishing)
        val safe = Robolectric.buildActivity(SafeModeActivity::class.java, next).setup().get()
        val texts = mutableListOf<String>()
        fun walk(v: android.view.View) { if (v is android.widget.TextView) texts += v.text.toString(); if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(safe.window.decorView)
        org.junit.Assert.assertTrue(texts.any { "boom" in it })
    }
}
