package com.lemoneko.endfieldcharge.rom.oxygenos

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.lemoneko.endfieldcharge.core.HudLog
import com.lemoneko.endfieldcharge.core.settings.ContentSettings
import com.lemoneko.endfieldcharge.rom.RomAdapter
import com.lemoneko.endfieldcharge.rom.hasClass
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain

/**
 * OxygenOS 16 / ColorOS adapter.
 *
 * Verified against `CPH2747_16.0.9.400(EX01)` on the OnePlus 15. See
 * `docs/investigations/02-oxygenos-charging-animation.md` for the decompiled call chain.
 *
 * Unlike HyperOS there is no single view to intercept. The charge animation is built inside
 * `OplusChargeAnimImpl.updateChargeAnimState`, which inflates a layout and hands it to the window
 * manager, and OxygenOS additionally ships the stock AOSP wired and wireless animations. What they
 * have in common is that every one of them titles its window, so suppression keys off that title.
 */
object OxygenOsAdapter : RomAdapter {

    private const val SCOPE = "oplus"

    const val CLS_CHARGE_ANIM_IMPL = "com.oplus.charge.viewmodel.OplusChargeAnimImpl"
    const val CLS_CHARGE_ANIM_CONTROLLER = "com.oplus.charge.viewmodel.OplusChargeAnimController"
    const val CLS_CHARGING_ANIMATION_IMPL =
        "com.oplus.systemui.keyguard.charginganim.ChargingAnimationImpl"
    const val CLS_CHARGE_LEVEL_VIEW = "com.oplus.charge.view.ChargeLevelAndLogoView"
    const val CLS_FRAME_CHARGE_LEVEL_VIEW = "com.oplus.charge.view.FrameChargeLevelAndLogoView"
    const val CLS_CHARGE_UTIL = "com.oplus.charge.util.ChargeUtil"

    private const val CLS_WINDOW_MANAGER_IMPL = "android.view.WindowManagerImpl"
    private const val METHOD_ADD_VIEW = "addView"

    /**
     * Window titles of every charging animation this ROM can put on screen.
     *
     * The first two are OPPO's own, set in `OplusChargeAnimImpl` (main and sub display). The last
     * two are the stock AOSP ones from `WiredChargingRippleController` and
     * `WirelessChargingAnimation`.
     */
    private val CHARGE_WINDOW_TITLES = setOf(
        "OplusChargeAnimationView",
        "OplusSubChargeAnimationView",
        "Wired Charging Animation",
        "Charging Animation",
    )

    override val name: String = "OxygenOS (com.oplus.charge)"

    override fun matches(classLoader: ClassLoader): Boolean =
        classLoader.hasClass(CLS_CHARGE_ANIM_IMPL)

    override fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        suppressChargeWindows(xposed)
    }
    /**
     * Blocks the charging animation at the last moment before it becomes visible.
     *
     * Nothing else is touched, so the ROM's own state machine still runs exactly as it would: it
     * creates the view, drives it, and later calls `removeMainChargeView`, which checks
     * `WindowInspector.getGlobalWindowViews().contains(view)` before removing and is therefore a
     * no-op for a view that was never added. That check is why this point is safe; hooking
     * `updateChargeAnimState` instead would have left the ROM's state machine inconsistent.
     */
    private fun suppressChargeWindows(xposed: XposedInterface) {
        val method = runCatching {
            Class.forName(CLS_WINDOW_MANAGER_IMPL)
                .getDeclaredMethod(METHOD_ADD_VIEW, View::class.java, ViewGroup.LayoutParams::class.java)
        }.onFailure {
            HudLog.e(SCOPE, "$CLS_WINDOW_MANAGER_IMPL.$METHOD_ADD_VIEW not found", it)
        }.getOrNull() ?: return

        HudLog.i(SCOPE, "suppression hook installed on $CLS_WINDOW_MANAGER_IMPL.$METHOD_ADD_VIEW " +
            "for ${CHARGE_WINDOW_TITLES.joinToString()}")

        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val params = chain.args.getOrNull(1) as? WindowManager.LayoutParams
                val title = params?.title?.toString()
                if (title != null && title in CHARGE_WINDOW_TITLES && suppressionActive()) {
                    HudLog.i(SCOPE, "suppressed charge window: $title")
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    /**
     * True while the module is meant to replace the ROM's animation.
     *
     * Defaults to true: before the settings provider answers, and if it is ever unreachable, the
     * module should behave as if it is on rather than silently doing nothing.
     */
    private fun suppressionActive(): Boolean = ContentSettings.current().enabled
}
