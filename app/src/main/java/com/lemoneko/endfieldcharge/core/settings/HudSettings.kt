package com.lemoneko.endfieldcharge.core.settings

import com.lemoneko.endfieldcharge.core.timeline.AnimationOptions

/**
 * Everything the user can tune.
 *
 * Deliberately free of Android imports so the defaults and the clamping can be unit tested.
 * Persistence and delivery to the hooked process live in [HudSettingsJson] and the module app's
 * SettingsProvider.
 */
data class HudSettings(
    /** Master switch. When off, the ROM keeps its own animation and nothing is drawn. */
    val enabled: Boolean = true,
    /** Fraction of the screen width the pill may occupy. 560 design units are scaled to this. */
    val widthRatio: Float = DEFAULT_WIDTH_RATIO,
    val durationSeconds: Double = 6.0,
    val bounceStrength: Double = 0.275,
    val rippleIntensity: Double = 1.0,
    val rippleSpread: Double = 1.0,
    /** Gap between the display cutout and the top of the HUD window. */
    val topGapDp: Int = DEFAULT_TOP_GAP_DP,
    /** [LANGUAGE_AUTO], [LANGUAGE_ZH] or [LANGUAGE_EN]. */
    val language: String = LANGUAGE_AUTO,
    /** Wake the screen when the charger is connected while the screen is off. */
    val wakeOnPlug: Boolean = true,
    /** Play the simplified animation when the charger is removed. */
    val playOnUnplug: Boolean = true,
) {

    fun animationOptions(): AnimationOptions = AnimationOptions(
        durationSeconds = durationSeconds.coerceIn(MIN_DURATION, MAX_DURATION),
        bounceStrength = bounceStrength.coerceIn(0.0, 0.5),
        rippleIntensity = rippleIntensity.coerceIn(0.0, 2.0),
        rippleSpread = rippleSpread.coerceIn(0.5, 1.5),
    )

    /** Clamps every field into the range the UI offers. */
    fun sanitized(): HudSettings = copy(
        widthRatio = widthRatio.coerceIn(MIN_WIDTH_RATIO, MAX_WIDTH_RATIO),
        durationSeconds = durationSeconds.coerceIn(MIN_DURATION, MAX_DURATION),
        bounceStrength = bounceStrength.coerceIn(0.0, 0.5),
        rippleIntensity = rippleIntensity.coerceIn(0.0, 2.0),
        rippleSpread = rippleSpread.coerceIn(0.5, 1.5),
        topGapDp = topGapDp.coerceIn(MIN_TOP_GAP_DP, MAX_TOP_GAP_DP),
        language = language.takeIf { it in LANGUAGES } ?: LANGUAGE_AUTO,
    )

    companion object {
        const val LANGUAGE_AUTO = "auto"
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"
        val LANGUAGES = setOf(LANGUAGE_AUTO, LANGUAGE_ZH, LANGUAGE_EN)

        const val DEFAULT_WIDTH_RATIO = 0.92f
        const val MIN_WIDTH_RATIO = 0.5f
        const val MAX_WIDTH_RATIO = 1.0f

        const val MIN_DURATION = 3.0
        const val MAX_DURATION = 10.0

        const val DEFAULT_TOP_GAP_DP = 8
        const val MIN_TOP_GAP_DP = 0
        const val MAX_TOP_GAP_DP = 160

        val Default = HudSettings()
    }
}
