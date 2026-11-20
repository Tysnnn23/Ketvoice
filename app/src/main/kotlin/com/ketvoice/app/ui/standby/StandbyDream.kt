package com.ketvoice.app.ui.standby

import android.service.dreams.DreamService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme

/**
 * « Ketvoice » as Android's screen saver (Settings → Display → Screen saver), the variant of the standby mode
 * that the system starts itself while charging. It shows the same screen as [StandbyHost] over the title
 * playing or last played; with no title at all it ends at once.
 */
class StandbyDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        if (!com.ketvoice.app.data.SettingsStore.loaded) runCatching { com.ketvoice.app.data.SettingsStore.load(this) }
        runCatching { Player.connect(applicationContext) }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@StandbyDream)
            setViewTreeSavedStateRegistryOwner(this@StandbyDream)
            setContent {
                KetvoiceTheme(useDark = true) {
                    val state by Player.state.collectAsState()
                    val track = state.current
                    if (track == null) finish() else StandbyScreen(track, state.isPlaying, onClose = { finish() })
                }
            }
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
