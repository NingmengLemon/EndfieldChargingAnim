package com.lemoneko.endfieldcharge.rom

import io.github.libxposed.api.XposedInterface

/**
 * Per-ROM hook set.
 *
 * Everything outside this package must stay ROM agnostic: the timeline, the drawing, the HUD
 * window and the plug/unplug detection are shared. An adapter's only job is to stop the ROM from
 * rendering its own charging animation, and (optionally) to report what the ROM already knows
 * about the charger.
 */
interface RomAdapter {

    /** Human readable name, used in logs and in the settings screen. */
    val name: String

    /** Cheap, side effect free check that this adapter applies to the currently loaded build. */
    fun matches(classLoader: ClassLoader): Boolean

    /** Install the suppression hooks. Called at most once per process. */
    fun install(xposed: XposedInterface, classLoader: ClassLoader)
}

/** Returns true when [name] resolves through this class loader without initialising the class. */
internal fun ClassLoader.hasClass(name: String): Boolean =
    runCatching { Class.forName(name, false, this) }.isSuccess
