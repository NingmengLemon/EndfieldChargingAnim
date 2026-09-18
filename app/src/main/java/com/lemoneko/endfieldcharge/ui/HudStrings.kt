package com.lemoneko.endfieldcharge.ui

import java.util.Locale

/**
 * HUD copy, mirroring `zmd-charge`'s `Localization` (`IsChinese ? ... : ...`).
 *
 * Only the charge strings are ported: the power saver variants belong to a feature this module
 * does not implement.
 */
internal object HudStrings {

    private val chinese: Boolean
        get() = Locale.getDefault().language == "zh"

    val tagline: String
        get() = if (chinese) "/// 超充模式" else "/// SUPER CHARGE MODE"

    val title: String
        get() = if (chinese) "超充模式" else "Super Charge Mode"
}
