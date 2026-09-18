package com.lemoneko.endfieldcharge

import android.util.Log
import com.lemoneko.endfieldcharge.core.EndfieldHudController
import com.lemoneko.endfieldcharge.core.HudLog
import com.lemoneko.endfieldcharge.core.SystemUiContext
import com.lemoneko.endfieldcharge.core.settings.ContentSettings
import com.lemoneko.endfieldcharge.rom.RomAdapters
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed API 102 entry point.
 *
 * The module is scoped to [TARGET_PACKAGE] only (see `META-INF/xposed/scope.list`), but the
 * framework may still deliver callbacks for other packages loaded into the same process, so every
 * callback filters on the package name explicitly.
 */
class ModuleEntry : XposedModule() {

    @Volatile
    private var installed = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded: process=${param.processName} systemServer=${param.isSystemServer}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // Route the shared logger into the Xposed log too: logcat does not carry this process's
        // android.util.Log output on every ROM.
        HudLog.attach { priority, tag, message -> log(priority, tag, message) }
        log(
            Log.INFO,
            TAG,
            "packageReady: ${param.packageName} first=${param.isFirstPackage} " +
                "classLoader=${param.classLoader.javaClass.name}",
        )
        if (param.packageName != TARGET_PACKAGE) return
        if (installed) {
            log(Log.INFO, TAG, "already installed for $TARGET_PACKAGE; ignoring repeat callback")
            return
        }

        val adapter = RomAdapters.detect(param.classLoader)
        if (adapter == null) {
            log(Log.WARN, TAG, "no ROM adapter matched this build; nothing will be hooked")
            return
        }
        installed = true
        log(Log.INFO, TAG, "using ROM adapter: ${adapter.name}")

        runCatching {
            // Capture the SystemUI Context first: it only needs the framework Application class,
            // so it works even when the ROM adapter below matches nothing.
            SystemUiContext.install(this)
            adapter.install(this, param.classLoader)
        }.onSuccess {
            log(Log.INFO, TAG, "adapter installed: ${adapter.name}")
            SystemUiContext.onAvailable { context ->
                runCatching {
                    // Settings come from the module app's provider. Install before the controller
                    // so it observes the stored values rather than the defaults.
                    ContentSettings.install(context, MODULE_PACKAGE)
                    EndfieldHudController(context, MODULE_PACKAGE).start()
                }.onFailure { log(Log.ERROR, TAG, "failed to start HUD controller", it) }
            }
        }.onFailure {
            log(Log.ERROR, TAG, "adapter install failed", it)
        }
    }

    companion object {
        const val TAG = "EndfieldCharge"
        const val TARGET_PACKAGE = "com.android.systemui"
        const val MODULE_PACKAGE = "com.lemoneko.endfieldcharge"
    }
}
