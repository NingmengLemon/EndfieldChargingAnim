package com.lemoneko.endfieldcharge.rom.hyperos

import android.os.Bundle
import com.lemoneko.endfieldcharge.core.HudLog
import com.lemoneko.endfieldcharge.core.settings.ContentSettings
import com.lemoneko.endfieldcharge.rom.RomAdapter
import com.lemoneko.endfieldcharge.rom.hasClass
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain

/**
 * Xiaomi HyperOS 2 (Android 15) adapter.
 *
 * Verified against `OS2.0.208.0.VMNEUXM` on `mondrian_eea` (Redmi K60). See
 * `docs/investigations/01-feasibility.md` for the full decompiled call chain and the on-device
 * measurements behind each hook.
 */
object HyperOsAdapter : RomAdapter {

    private const val SCOPE = "hyperos"

    const val CLS_CHARGE_ANIM_VIEW = "com.miui.charge.container.MiuiChargeAnimationView"
    const val CLS_CHARGE_CONTROLLER = "com.miui.charge.MiuiChargeController"
    const val CLS_CHARGE_MANAGER = "com.miui.charge.MiuiChargeManager"
    const val CLS_CHARGE_UTILS = "com.miui.charge.ChargeUtils"
    const val CLS_BATTERY_STATUS = "com.miui.systemui.charge.MiuiBatteryStatus"
    const val CLS_STRONG_TOAST_CONTROL = "com.miui.toast.MIUIStrongToastControl"

    private const val METHOD_ADD_CHARGE_VIEW = "addChargeView"
    private const val METHOD_SHOW_CUSTOM_STRONG_TOAST = "showCustomStrongToast"

    private const val KEY_STRONG_TOAST_CATEGORY = "strong_toast_category"
    private const val CATEGORY_CHARGE = "charge"

    override val name: String = "HyperOS (com.miui.charge)"

    override fun matches(classLoader: ClassLoader): Boolean =
        classLoader.hasClass(CLS_CHARGE_ANIM_VIEW)

    override fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        suppressLockscreenChargeAnimation(xposed, classLoader)
        suppressChargeStrongToast(xposed, classLoader)
        HudLog.i(SCOPE, "suppression hooks installed")
    }

    /**
     * Lockscreen path.
     *
     * `MiuiChargeAnimationView.addChargeView()` is the last step before the ROM's full screen
     * charging view is attached to the notification shade root view. Making it a no-op keeps that
     * view out of the window tree while leaving the rest of `MiuiChargeController` intact.
     *
     * Suppression is decided per call rather than at install time, so turning the module off in the
     * settings screen hands the animation straight back to the ROM without restarting SystemUI.
     *
     * Do not flip `ChargeUtils.sChargeAnimationDisabled` instead: it is read in five other places
     * and changes layout semantics rather than just visibility.
     */
    private fun suppressLockscreenChargeAnimation(xposed: XposedInterface, classLoader: ClassLoader) {
        val method = classLoader.loadClass(CLS_CHARGE_ANIM_VIEW)
            .getDeclaredMethod(METHOD_ADD_CHARGE_VIEW)
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                if (suppressionActive()) {
                    HudLog.i(SCOPE, "suppressed $CLS_CHARGE_ANIM_VIEW.$METHOD_ADD_CHARGE_VIEW()")
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    /**
     * Unlocked path.
     *
     * `MIUIStrongToastControl` renders a full width "strong toast" strip at the top of the screen
     * on plug while the keyguard is not showing. The same class serves every strong toast category
     * (game mode, power saving, ...), so the `strong_toast_category` filter is mandatory: only
     * `"charge"` is dropped.
     */
    private fun suppressChargeStrongToast(xposed: XposedInterface, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass(CLS_STRONG_TOAST_CONTROL) }.getOrNull()
        if (clazz == null) {
            HudLog.w(SCOPE, "$CLS_STRONG_TOAST_CONTROL not present; unlocked charge toast not suppressed")
            return
        }
        val method = clazz.getDeclaredMethod(METHOD_SHOW_CUSTOM_STRONG_TOAST, Bundle::class.java)
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val bundle = chain.args.firstOrNull() as? Bundle
                val category = bundle?.getString(KEY_STRONG_TOAST_CATEGORY)
                if (category == CATEGORY_CHARGE && suppressionActive()) {
                    HudLog.i(SCOPE, "suppressed charge strong toast")
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
