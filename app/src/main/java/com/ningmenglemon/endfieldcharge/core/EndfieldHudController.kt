package com.ningmenglemon.endfieldcharge.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.ningmenglemon.endfieldcharge.ui.EndfieldHudView

/** Which of the two `zmd-charge` timelines to play. */
enum class HudPlayMode {
    /** Full three state animation: bolt pops in, pill expands, ripples, title, then numbers. */
    CHARGE,

    /** Simplified animation: just the pill with the numbers, no bolt-first, no ripples. */
    UNPLUG,
}

/**
 * ROM agnostic HUD driver: watches the battery, decides when to play, owns the window.
 *
 * Everything ROM specific lives behind [com.ningmenglemon.endfieldcharge.rom.RomAdapter]; this
 * class only knows about batteries and pixels.
 */
class EndfieldHudController(private val context: Context) {

    private val window = HudWindow(context)
    private val view = EndfieldHudView(context)
    private val main = Handler(Looper.getMainLooper())

    private var lastSnapshot: BatterySnapshot? = null
    private var watcher: BatteryWatcher? = null

    private val hideRunnable = Runnable { hide() }

    fun start() {
        if (watcher != null) return
        watcher = BatteryWatcher(context, ::onBatteryChanged).also { it.start() }
        HudLog.i(SCOPE, "controller started")
    }

    fun stop() {
        watcher?.stop()
        watcher = null
        main.removeCallbacks(hideRunnable)
        window.detach()
    }

    private fun onBatteryChanged(snapshot: BatterySnapshot) {
        val previous = lastSnapshot
        lastSnapshot = snapshot

        if (previous == null) {
            HudLog.i(
                SCOPE,
                "initial state: level=${snapshot.level}% plugged=${snapshot.plugged} " +
                    "status=${snapshot.status} chargeFull=${snapshot.chargeFullUah}uAh " +
                    "counter=${snapshot.chargeCounterRaw} voltage=${snapshot.voltageUv}uV",
            )
            return
        }

        val wasPlugged = previous.isPluggedIn
        val isPlugged = snapshot.isPluggedIn
        when {
            !wasPlugged && isPlugged -> {
                HudLog.i(SCOPE, "plugged in (level=${snapshot.level}%)")
                play(HudPlayMode.CHARGE, snapshot)
            }

            wasPlugged && !isPlugged -> {
                HudLog.i(SCOPE, "unplugged (level=${snapshot.level}%)")
                play(HudPlayMode.UNPLUG, snapshot)
            }
        }
    }

    private fun play(mode: HudPlayMode, snapshot: BatterySnapshot) {
        if (mode == HudPlayMode.CHARGE) wakeScreenIfNeeded()

        main.removeCallbacks(hideRunnable)

        val density = context.resources.displayMetrics.density
        val topMarginPx = (TOP_MARGIN_DP * density).toInt()

        view.setSnapshot(snapshot)
        if (!window.attach(view, topMarginPx)) {
            HudLog.w(SCOPE, "window attach failed; skipping playback")
            return
        }
        view.play(mode)

        // Stage 1: a fixed hold time. Stage 2 replaces this with the real timeline completion.
        main.postDelayed(hideRunnable, PLACEHOLDER_DURATION_MS)
    }

    private fun hide() {
        view.stop()
        window.detach()
    }

    /**
     * The ROM wakes the screen when it shows its charge animation
     * (`MiuiChargeController.showChargeAnimation`). Since we suppress that path we have to do it
     * ourselves, otherwise plugging in while the screen is off produces nothing visible.
     *
     * `PowerManager.wakeUp` is `@hide`, so it is not in the public SDK and has to go through
     * reflection.
     */
    private fun wakeScreenIfNeeded() {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isInteractive) return
        runCatching {
            val method = PowerManager::class.java
                .getMethod("wakeUp", Long::class.javaPrimitiveType, String::class.java)
            method.invoke(power, SystemClock.uptimeMillis(), WAKE_REASON)
            HudLog.i(SCOPE, "woke screen for charge animation")
        }.onFailure { HudLog.e(SCOPE, "failed to wake screen", it) }
    }

    private companion object {
        const val SCOPE = "hud"
        const val WAKE_REASON = "com.ningmenglemon.endfieldcharge:PLUGGED"
        const val TOP_MARGIN_DP = 8f
        const val PLACEHOLDER_DURATION_MS = 4000L
    }
}
