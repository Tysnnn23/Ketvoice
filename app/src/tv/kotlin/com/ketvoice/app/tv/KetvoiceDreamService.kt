package com.ketvoice.app.tv

import android.service.dreams.DreamService
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ketvoice.app.theme.KetvoiceTheme

/**
 * « Écran de veille Ketvoice » (Ketvoice TV 1.1.0): the screensaver the TV offers under the name « Ketvoice ». It
 * shows the title playing (its still cover, never a Canvas video), the time, and the art frame's gallery when nothing
 * plays; it reads the shared player and library of this process and starts nothing. Any key ends it (not interactive).
 */
class KetvoiceDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        // The library the gallery reads (already loaded when the app ran in this process; a file read otherwise).
        val app = applicationContext
        runCatching { com.ketvoice.app.data.SettingsStore.load(app) }
        runCatching { com.ketvoice.app.data.ListeningHistory.init(app) }
        runCatching { com.ketvoice.app.data.PlaylistStore.init(app) }
        runCatching { TvPrefs.init(app) }
        runCatching { TvCapability.init(app) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val tier = TvCapability.current
        val view = ComposeView(this).apply {
            setContent {
                KetvoiceTheme(useDark = true) {
                    CompositionLocalProvider(LocalTvTier provides tier) { TvDreamScreen() }
                }
            }
        }
        // Compose finds its lifecycle up the view tree: set before the view is attached (it composes on attach).
        listOfNotNull(window?.decorView, view).forEach { v ->
            v.setViewTreeLifecycleOwner(this)
            v.setViewTreeSavedStateRegistryOwner(this)
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
