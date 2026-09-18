package com.lemoneko.endfieldcharge.rom

import com.lemoneko.endfieldcharge.rom.hyperos.HyperOsAdapter
import com.lemoneko.endfieldcharge.rom.oxygenos.OxygenOsAdapter

/** Ordered registry of known ROM adapters. The first match wins. */
object RomAdapters {

    private val adapters: List<RomAdapter> = listOf(
        HyperOsAdapter,
        OxygenOsAdapter,
    )

    fun detect(classLoader: ClassLoader): RomAdapter? =
        adapters.firstOrNull { runCatching { it.matches(classLoader) }.getOrDefault(false) }
}
