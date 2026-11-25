package com.ketvoice.desktop.ui.glass

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.ketvoice.desktop.platform.WindowGlass

/**
 * How the window's glass surfaces (sidebar, player island, side panel, menus) are drawn.
 *
 * - [LIQUID]: the refractive shader over what is behind ([GlassShader]), above Windows 11's Mica.
 * - [FROSTED]: what is behind, blurred, under a tint: the shader could not compile, « Verre Windows 11 » is off,
 *   Windows 10, or transparency effects are off.
 * - [TINTED]: a flat tint (1.2's Acrylic material): high contrast, or a software renderer, where blurring the
 *   window every frame would cost too much.
 */
enum class GlassMode { LIQUID, FROSTED, TINTED }

object GlassPolicy {
    /** What the decision reads; every field is given by the window (or by tests). */
    data class Inputs(
        /** « Verre Windows 11 » (Settings › Windows). */
        val enabled: Boolean,
        val system: WindowGlass.SystemLook,
        /** skiko's render API once the window draws (`DIRECT3D`, `OPENGL`, `SOFTWARE_FAST`…); null before. */
        val renderApi: String?,
        /** Skia compiled [GlassShader]. */
        val shaderOk: Boolean,
    )

    fun choose(i: Inputs): GlassMode = when {
        i.system.highContrast -> GlassMode.TINTED
        isSoftware(i.renderApi) -> GlassMode.TINTED
        !i.shaderOk -> GlassMode.FROSTED
        !i.enabled -> GlassMode.FROSTED
        WindowGlass.choose(i.system, true) == WindowGlass.Backdrop.NONE -> GlassMode.FROSTED
        else -> GlassMode.LIQUID
    }

    fun isSoftware(api: String?): Boolean = api != null && api.uppercase().startsWith("SOFTWARE")

    /** The render API the main window drew its first frame with (Main sets it). */
    val renderApi = mutableStateOf<String?>(null)

    /** The mode for this window now; logged by Main when it changes. */
    fun current(system: WindowGlass.SystemLook = WindowGlass.readSystem()): GlassMode =
        choose(Inputs(WindowGlass.enabled.value, system, renderApi.value, GlassShader.ok))
}

/** The glass mode the shell's surfaces draw with. */
val LocalGlassMode = staticCompositionLocalOf { GlassMode.TINTED }
