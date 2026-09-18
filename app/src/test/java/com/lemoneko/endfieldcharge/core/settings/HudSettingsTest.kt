package com.lemoneko.endfieldcharge.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings screen offers bounded controls, but values can also arrive from a provider call, so
 * sanitizing has to hold on its own.
 */
class HudSettingsTest {

    private val eps = 1e-6

    @Test
    fun `defaults survive sanitizing unchanged`() {
        assertEquals(HudSettings.Default, HudSettings.Default.sanitized())
    }

    @Test
    fun `out of range values are clamped`() {
        val wild = HudSettings(
            widthRatio = 5f,
            durationSeconds = 99.0,
            bounceStrength = -1.0,
            rippleIntensity = 9.0,
            rippleSpread = 0.0,
            topGapDp = -20,
        ).sanitized()

        assertEquals(HudSettings.MAX_WIDTH_RATIO, wild.widthRatio, 1e-6f)
        assertEquals(HudSettings.MAX_DURATION, wild.durationSeconds, eps)
        assertEquals(0.0, wild.bounceStrength, eps)
        assertEquals(2.0, wild.rippleIntensity, eps)
        assertEquals(0.5, wild.rippleSpread, eps)
        assertEquals(HudSettings.MIN_TOP_GAP_DP, wild.topGapDp)
    }

    @Test
    fun `an unknown language falls back to auto`() {
        assertEquals(HudSettings.LANGUAGE_AUTO, HudSettings(language = "kr").sanitized().language)
        assertEquals(HudSettings.LANGUAGE_ZH, HudSettings(language = "zh").sanitized().language)
    }

    @Test
    fun `animation options inherit the clamped ranges`() {
        val options = HudSettings(durationSeconds = 99.0, rippleSpread = 9.0).animationOptions()
        assertEquals(HudSettings.MAX_DURATION, options.clampedDuration, eps)
        assertEquals(1.5, options.clampedRippleSpread, eps)
    }

    @Test
    fun `json round trips every field`() {
        val original = HudSettings(
            enabled = false,
            widthRatio = 0.75f,
            durationSeconds = 8.5,
            bounceStrength = 0.4,
            rippleIntensity = 1.6,
            rippleSpread = 1.2,
            topGapDp = 24,
            language = HudSettings.LANGUAGE_EN,
            wakeOnPlug = false,
            playOnUnplug = false,
        )

        val restored = HudSettingsJson.fromJson(HudSettingsJson.toJson(original))

        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.widthRatio, restored.widthRatio, 1e-5f)
        assertEquals(original.durationSeconds, restored.durationSeconds, 1e-5)
        assertEquals(original.bounceStrength, restored.bounceStrength, 1e-5)
        assertEquals(original.rippleIntensity, restored.rippleIntensity, 1e-5)
        assertEquals(original.rippleSpread, restored.rippleSpread, 1e-5)
        assertEquals(original.topGapDp, restored.topGapDp)
        assertEquals(original.language, restored.language)
        assertEquals(original.wakeOnPlug, restored.wakeOnPlug)
        assertEquals(original.playOnUnplug, restored.playOnUnplug)
    }

    @Test
    fun `malformed json falls back to defaults instead of throwing`() {
        assertEquals(HudSettings.Default, HudSettingsJson.fromJson("not json at all"))
        assertEquals(HudSettings.Default, HudSettingsJson.fromJson(null))
        assertEquals(HudSettings.Default, HudSettingsJson.fromJson(""))
    }

    @Test
    fun `partial json keeps the declared defaults`() {
        val restored = HudSettingsJson.fromJson("""{"topGapDp": 30}""")
        assertEquals(30, restored.topGapDp)
        assertEquals(HudSettings.Default.durationSeconds, restored.durationSeconds, eps)
        assertTrue(restored.enabled)
    }
}
