package com.lemoneko.endfieldcharge.core

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Owns the overlay window the HUD is drawn into.
 *
 * The window type is `2026`, the same type the ROM's dead code path would have used for its own
 * charge animation (`MiuiChargeAnimationView.getWindowParam()`). It is a system window type that
 * sits above the keyguard, and the SystemUI process (uid 1000) holds the permission to add it.
 *
 * A normal app could not do this: `TYPE_APPLICATION_OVERLAY` windows are hidden by the keyguard,
 * and the charging animation happens precisely on the keyguard.
 */
class HudWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var attached: View? = null

    val isAttached: Boolean
        get() = attached != null

    /** Adds [view] to the window, or returns false when the window manager refuses it. */
    fun attach(view: View, topMarginPx: Int): Boolean {
        if (attached === view) return true
        detach()
        return runCatching {
            windowManager.addView(view, buildParams(topMarginPx))
            attached = view
            HudLog.i(SCOPE, "HUD window attached (type=$WINDOW_TYPE)")
            true
        }.onFailure {
            HudLog.e(SCOPE, "failed to attach HUD window", it)
        }.getOrDefault(false)
    }

    fun detach() {
        val view = attached ?: return
        attached = null
        runCatching { windowManager.removeViewImmediate(view) }
            .onFailure { HudLog.e(SCOPE, "failed to detach HUD window", it) }
    }

    /**
     * Re-places an attached window. Rotation and resolution changes move the display cutout, so the
     * offset computed at attach time goes stale.
     */
    fun updateTopMargin(topMarginPx: Int) {
        val view = attached ?: return
        runCatching { windowManager.updateViewLayout(view, buildParams(topMarginPx)) }
            .onFailure { HudLog.e(SCOPE, "failed to move HUD window", it) }
    }

    private fun buildParams(topMarginPx: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WINDOW_TYPE,
            FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topMarginPx
            windowAnimations = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setTitle(WINDOW_TITLE)
        }

    private companion object {
        const val SCOPE = "window"

        /** See `MiuiChargeAnimationView.getWindowParam()`. */
        const val WINDOW_TYPE = 2026

        const val WINDOW_TITLE = "endfield_charge_hud"

        /**
         * Deliberately *not* the ROM's flags. The ROM wants a focusable window so a key press
         * dismisses its animation; the HUD must be inert and let every touch through.
         */
        const val FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
