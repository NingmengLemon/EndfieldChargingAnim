package com.ningmenglemon.endfieldcharge.rom

import com.ningmenglemon.endfieldcharge.rom.hyperos.HyperOsAdapter

/** Ordered registry of known ROM adapters. The first match wins. */
object RomAdapters {

    private val adapters: List<RomAdapter> = listOf(
        HyperOsAdapter,
    )

    fun detect(classLoader: ClassLoader): RomAdapter? =
        adapters.firstOrNull { runCatching { it.matches(classLoader) }.getOrDefault(false) }
}
