@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ketvoice.app.ui.player

import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal object CanvasPresentation {
    /** Banner-era artwork framing; eligibility and viewport define fullscreen, not FIT. */
    const val NORMAL_RESIZE_MODE = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    /** Portrait 9:16 only, with a tiny allowance for encoded dimension rounding. */
    fun canFillScreen(videoRatio: Float?, screenRatio: Float): Boolean {
        if (videoRatio == null || !videoRatio.isFinite() || videoRatio <= 0f ||
            !screenRatio.isFinite() || screenRatio <= 0f) return false
        return screenRatio < 1f && abs(videoRatio - 9f / 16f) <= .002f
    }

    fun aspectRatio(width: Int, height: Int, rotation: Int): Float? {
        if (width <= 0 || height <= 0) return null
        return if (rotation.mod(180) == 90) height.toFloat() / width else width.toFloat() / height
    }
}

/** Read only local clip metadata off the UI thread; unknown or unreadable means unavailable. */
@Composable
internal fun rememberCanvasAspectRatio(path: String?): State<Float?> = key(path) {
    produceState<Float?>(null) {
        if (path != null) value = withContext(Dispatchers.IO) {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(path)
                CanvasPresentation.aspectRatio(
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                )
            } catch (_: Exception) {
                null
            } finally {
                runCatching { reader.release() }
            }
        }
    }
}
