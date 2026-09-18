package com.lemoneko.endfieldcharge.rom.oxygenos

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.lemoneko.endfieldcharge.core.HudLog
import com.lemoneko.endfieldcharge.core.settings.ContentSettings
import com.lemoneko.endfieldcharge.rom.RomAdapter
import com.lemoneko.endfieldcharge.rom.hasClass
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import java.util.concurrent.atomic.AtomicBoolean

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

    private const val METHOD_VFX_START = "onWaterWaveVFXStart"

    /**
     * The charging live alert is a Seedling card, not a charging animation.
     *
     * It is delivered by OPPO's Pantanal decision service: `com.oplus.pantanal.ums` decides, SystemUI
     * receives the list, and `com.oplus.systemui.plugins` renders it in a window titled
     * `SeedlingCard`. Measured on `CPH2747_16.0.9.400(EX01)`:
     *
     * ```
     * serviceId = 268451924   name = ChargeSeedling   hostName = com.oplus.battery
     * provider  = com.oplus.powermanager.chargeSeedling.ChargeSeedlingCardProvider
     * action    = pantanal.intent.business.app.system.CHARGE
     * ```
     *
     * `SeedlingCard` is the window title for every Pantanal card, so it cannot be filtered by
     * title without also killing unrelated live alerts. The card list is therefore filtered by
     * service id instead, in `DecisionHelper.filterStaticServiceListByEntrance`, which is a plain
     * static method taking and returning a `List<ServiceInfo>`.
     */
    private const val CLS_SEEDLING_PLUGIN_MANAGER =
        "com.oplus.systemui.statusbar.seeding.SeedlingPluginManager"
    private const val METHOD_ON_PLUGIN_CONNECTED = "onPluginConnected"

    private const val CLS_DECISION_HELPER = "pantanal.decision.DecisionHelper"
    private const val METHOD_FILTER_BY_ENTRANCE = "filterStaticServiceListByEntrance"
    private const val FILTER_PARAM_COUNT = 3

    /** See the class doc; re-derive with `references/_scratch/op15/capture_livealert.ps1`. */
    private const val CHARGE_SEEDLING_SERVICE_ID = "268451924"

    private val decisionFilterInstalled = AtomicBoolean(false)

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
        suppressWaterWaveVfx(xposed, classLoader)
        suppressChargeSeedlingCard(xposed, classLoader)
    }

    /**
     * Drops the charging live alert from the Pantanal card list.
     *
     * The decision classes live in the `com.oplus.systemui.plugins` APK, loaded into SystemUI
     * through a plugin classloader, so the hook cannot be installed up front: it waits for
     * `SeedlingPluginManager.onPluginConnected`, whose `Context` argument carries that classloader.
     */
    private fun suppressChargeSeedlingCard(xposed: XposedInterface, classLoader: ClassLoader) {
        val manager = runCatching { classLoader.loadClass(CLS_SEEDLING_PLUGIN_MANAGER) }
            .onFailure { HudLog.w(SCOPE, "$CLS_SEEDLING_PLUGIN_MANAGER not present") }
            .getOrNull() ?: return

        val method = manager.declaredMethods
            .firstOrNull { it.name == METHOD_ON_PLUGIN_CONNECTED && it.parameterTypes.size == 2 }
        if (method == null) {
            HudLog.w(SCOPE, "$METHOD_ON_PLUGIN_CONNECTED not found on $CLS_SEEDLING_PLUGIN_MANAGER")
            return
        }

        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val result = chain.proceed()
                (chain.args.getOrNull(1) as? Context)?.let { pluginContext ->
                    installDecisionFilter(xposed, pluginContext.classLoader)
                }
                result
            }
    }

    private fun installDecisionFilter(xposed: XposedInterface, pluginLoader: ClassLoader?) {
        if (pluginLoader == null) return
        if (!decisionFilterInstalled.compareAndSet(false, true)) return

        val helper = runCatching { pluginLoader.loadClass(CLS_DECISION_HELPER) }.getOrNull()
        if (helper == null) {
            decisionFilterInstalled.set(false)
            HudLog.w(SCOPE, "$CLS_DECISION_HELPER not reachable from the plugin classloader")
            return
        }

        // Match the real method, not the Kotlin `$default` synthetic of the same name.
        val method = helper.declaredMethods.firstOrNull {
            it.name == METHOD_FILTER_BY_ENTRANCE && it.parameterTypes.size == FILTER_PARAM_COUNT
        }
        if (method == null) {
            decisionFilterInstalled.set(false)
            HudLog.w(SCOPE, "$METHOD_FILTER_BY_ENTRANCE not found on $CLS_DECISION_HELPER")
            return
        }

        HudLog.i(SCOPE, "charge live alert filter installed on $CLS_DECISION_HELPER")

        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                val result = chain.proceed()
                val list = result as? List<*> ?: return@intercept result
                if (!suppressionActive()) return@intercept result

                val kept = list.filterNot { serviceIdOf(it) == CHARGE_SEEDLING_SERVICE_ID }
                if (kept.size != list.size) {
                    HudLog.i(SCOPE, "suppressed charge live alert (${list.size - kept.size} card)")
                }
                kept
            }
    }

    /**
     * `ServiceInfo.getServiceId` is read reflectively: the class lives in the plugin APK and is not
     * on this module's compile classpath.
     */
    private fun serviceIdOf(entity: Any?): String? = runCatching {
        entity?.javaClass?.getMethod("getServiceId")?.invoke(entity) as? String
    }.getOrNull()

    /**
     * The water wave ripple is not a window.
     *
     * `OplusChargeAnimController.onWaterWaveVFXStart` loads `ripple_update_density_001.coz` into
     * OPPO's COE effect engine and hands it a `SurfaceControlViewHost`, so it is composited on its
     * own surface and the window title filter never sees it. Confirmed on hardware: with the window
     * hook in place the charge percentage disappeared but the ripple kept playing, which is exactly
     * this path.
     *
     * Only the start is blocked. The stop paths (`onWaterWaveVFXStop`,
     * `onWaterWaveVFXStopImmediately`) are null-guarded and act on an effect that never started, so
     * leaving them alone keeps the ROM's cleanup intact.
     */
    private fun suppressWaterWaveVfx(xposed: XposedInterface, classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass(CLS_CHARGE_ANIM_CONTROLLER) }
            .onFailure { HudLog.e(SCOPE, "$CLS_CHARGE_ANIM_CONTROLLER not found", it) }
            .getOrNull() ?: return

        val method = runCatching { clazz.getDeclaredMethod(METHOD_VFX_START) }
            .onFailure { HudLog.e(SCOPE, "$METHOD_VFX_START not found", it) }
            .getOrNull() ?: return

        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain: Chain ->
                if (suppressionActive()) {
                    HudLog.i(SCOPE, "suppressed water wave VFX")
                    null
                } else {
                    chain.proceed()
                }
            }
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
