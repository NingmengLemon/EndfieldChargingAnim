package com.lemoneko.endfieldcharge.core.settings

import android.content.SharedPreferences

/**
 * Reads and writes [HudSettings] through a [SharedPreferences].
 *
 * Both sides use this: the module app writes through the libxposed service, SystemUI reads the
 * same group through `XposedInterface.getRemotePreferences`. Keeping one codec means the key names
 * and defaults cannot drift apart.
 */
object HudSettingsCodec {

    /** Remote preference group name. */
    const val GROUP = "endfield_hud"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_WIDTH_RATIO = "width_ratio"
    private const val KEY_DURATION = "duration_seconds"
    private const val KEY_BOUNCE = "bounce_strength"
    private const val KEY_RIPPLE_INTENSITY = "ripple_intensity"
    private const val KEY_RIPPLE_SPREAD = "ripple_spread"
    private const val KEY_TOP_GAP = "top_gap_dp"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_WAKE_ON_PLUG = "wake_on_plug"
    private const val KEY_PLAY_ON_UNPLUG = "play_on_unplug"

    fun read(prefs: SharedPreferences?): HudSettings {
        if (prefs == null) return HudSettings.Default
        val defaults = HudSettings.Default
        return HudSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            widthRatio = prefs.getFloat(KEY_WIDTH_RATIO, defaults.widthRatio),
            durationSeconds = prefs.getFloat(KEY_DURATION, defaults.durationSeconds.toFloat()).toDouble(),
            bounceStrength = prefs.getFloat(KEY_BOUNCE, defaults.bounceStrength.toFloat()).toDouble(),
            rippleIntensity = prefs.getFloat(KEY_RIPPLE_INTENSITY, defaults.rippleIntensity.toFloat()).toDouble(),
            rippleSpread = prefs.getFloat(KEY_RIPPLE_SPREAD, defaults.rippleSpread.toFloat()).toDouble(),
            topGapDp = prefs.getInt(KEY_TOP_GAP, defaults.topGapDp),
            language = prefs.getString(KEY_LANGUAGE, defaults.language) ?: defaults.language,
            wakeOnPlug = prefs.getBoolean(KEY_WAKE_ON_PLUG, defaults.wakeOnPlug),
            playOnUnplug = prefs.getBoolean(KEY_PLAY_ON_UNPLUG, defaults.playOnUnplug),
        ).sanitized()
    }

    /** Writes every field and returns the editor so the caller can `commit()` or `apply()`. */
    fun write(editor: SharedPreferences.Editor, settings: HudSettings): SharedPreferences.Editor {
        val s = settings.sanitized()
        editor.putBoolean(KEY_ENABLED, s.enabled)
        editor.putFloat(KEY_WIDTH_RATIO, s.widthRatio)
        editor.putFloat(KEY_DURATION, s.durationSeconds.toFloat())
        editor.putFloat(KEY_BOUNCE, s.bounceStrength.toFloat())
        editor.putFloat(KEY_RIPPLE_INTENSITY, s.rippleIntensity.toFloat())
        editor.putFloat(KEY_RIPPLE_SPREAD, s.rippleSpread.toFloat())
        editor.putInt(KEY_TOP_GAP, s.topGapDp)
        editor.putString(KEY_LANGUAGE, s.language)
        editor.putBoolean(KEY_WAKE_ON_PLUG, s.wakeOnPlug)
        editor.putBoolean(KEY_PLAY_ON_UNPLUG, s.playOnUnplug)
        return editor
    }
}
