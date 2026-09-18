package com.lemoneko.endfieldcharge.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import com.lemoneko.endfieldcharge.core.settings.HudSettings
import com.lemoneko.endfieldcharge.core.settings.ContentSettings
import com.lemoneko.endfieldcharge.core.timeline.HudTimeline
import com.lemoneko.endfieldcharge.ui.EndfieldHudView

/** Which of the two `zmd-charge` timelines to play. */
enum class HudPlayMode {
    /** Full three state animation: bolt pops in, pill expands, ripples, title, then numbers. */
    CHARGE,

    /** Simplified animation: just the pill with the numbers, no bolt-first, no ripples. */
    UNPLUG,
}

/**
 * ROM agnostic HUD driver: watches the battery, decides when to play, owns the window and runs
 * the frame loop.
 *
 * Everything ROM specific lives behind [com.lemoneko.endfieldcharge.rom.RomAdapter]; this class
 * only knows about batteries and pixels.
 */
class EndfieldHudController(
    private val context: Context,
    modulePackageName: String,
) {

    /**
     * Inside SystemUI the ambient context belongs to SystemUI, so its assets do not contain the
     * module's bundled font. The module's own package context does.
     */
    private val moduleAssets = runCatching {
        context.createPackageContext(modulePackageName, 0).assets
    }.onFailure {
        HudLog.w(SCOPE, "cannot open $modulePackageName assets; falling back to system fonts", it)
    }.getOrDefault(context.assets)

    private val window = HudWindow(context)
    private val view = EndfieldHudView(context, moduleAssets)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var settings: HudSettings = HudSettings.Default

    @Volatile
    private var timeline: HudTimeline = HudTimeline(HudSettings.Default.animationOptions())

    private var lastSnapshot: BatterySnapshot? = null
    private var watcher: BatteryWatcher? = null
    private var animator: ValueAnimator? = null

    /** Rotation and resolution changes move the cutout, so an attached window is re-placed. */
    private val configurationCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            main.post {
                if (window.isAttached) {
                    window.updateTopMargin(HudMetrics.topMarginPx(context, settings.topGapDp))
                    HudLog.i(SCOPE, "re-placed HUD window after a configuration change")
                }
            }
        }

        override fun onLowMemory() = Unit
    }

    fun start() {
        if (watcher != null) return
        ContentSettings.observe(::applySettings)
        context.registerComponentCallbacks(configurationCallback)
        watcher = BatteryWatcher(context, ::onBatteryChanged).also { it.start() }
        HudLog.i(SCOPE, "controller started (duration=${timeline.durationMillis}ms)")
    }

    fun stop() {
        watcher?.stop()
        watcher = null
        runCatching { context.unregisterComponentCallbacks(configurationCallback) }
        cancelAnimation()
        window.detach()
    }

    /** Settings callbacks may arrive on a binder thread, so hop to the main thread. */
    private fun applySettings(value: HudSettings) {
        main.post {
            settings = value
            timeline = HudTimeline(value.animationOptions())
            view.widthRatio = value.widthRatio
            view.language = value.language
            if (!value.enabled && window.isAttached) hide()
            if (window.isAttached) {
                window.updateTopMargin(HudMetrics.topMarginPx(context, value.topGapDp))
            }
            HudLog.i(SCOPE, "settings applied: $value")
        }
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
        val active = settings
        if (!active.enabled) return
        if (mode == HudPlayMode.UNPLUG && !active.playOnUnplug) return
        if (mode == HudPlayMode.CHARGE && active.wakeOnPlug) wakeScreenIfNeeded()

        view.setSnapshot(snapshot)
        if (!window.attach(view, HudMetrics.topMarginPx(context, active.topGapDp))) {
            HudLog.w(SCOPE, "window attach failed; skipping playback")
            return
        }
        startAnimation(mode)
    }

    private fun startAnimation(mode: HudPlayMode) {
        cancelAnimation()
        val run = timeline

        val started = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = run.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val cue = (animation.animatedValue as Float).toDouble()
                view.render(
                    when (mode) {
                        HudPlayMode.CHARGE -> run.evaluateCharge(cue)
                        HudPlayMode.UNPLUG -> run.evaluateUnplug(cue)
                    },
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Only the animator that is still current may dismiss the HUD; a cancelled
                    // predecessor must not close the window of its replacement.
                    if (animator === animation) hide()
                }
            })
        }
        animator = started
        started.start()
    }

    private fun cancelAnimation() {
        val running = animator ?: return
        animator = null
        running.cancel()
    }

    private fun hide() {
        animator = null
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
        const val WAKE_REASON = "com.lemoneko.endfieldcharge:PLUGGED"
    }
}
