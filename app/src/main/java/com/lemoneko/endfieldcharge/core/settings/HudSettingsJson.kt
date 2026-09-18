package com.lemoneko.endfieldcharge.core.settings

import org.json.JSONObject

/**
 * JSON form of [HudSettings].
 *
 * Used by the content provider that carries settings from the module app into the hooked SystemUI
 * process. `org.json` is part of the platform, so this needs no dependency.
 */
object HudSettingsJson {

    fun toJson(settings: HudSettings): String {
        val s = settings.sanitized()
        return JSONObject().apply {
            put("enabled", s.enabled)
            put("widthRatio", s.widthRatio.toDouble())
            put("durationSeconds", s.durationSeconds)
            put("bounceStrength", s.bounceStrength)
            put("rippleIntensity", s.rippleIntensity)
            put("rippleSpread", s.rippleSpread)
            put("topGapDp", s.topGapDp)
            put("language", s.language)
            put("wakeOnPlug", s.wakeOnPlug)
            put("playOnUnplug", s.playOnUnplug)
        }.toString()
    }

    fun fromJson(text: String?): HudSettings {
        if (text.isNullOrBlank()) return HudSettings.Default
        val defaults = HudSettings.Default
        return runCatching {
            val o = JSONObject(text)
            HudSettings(
                enabled = o.optBoolean("enabled", defaults.enabled),
                widthRatio = o.optDouble("widthRatio", defaults.widthRatio.toDouble()).toFloat(),
                durationSeconds = o.optDouble("durationSeconds", defaults.durationSeconds),
                bounceStrength = o.optDouble("bounceStrength", defaults.bounceStrength),
                rippleIntensity = o.optDouble("rippleIntensity", defaults.rippleIntensity),
                rippleSpread = o.optDouble("rippleSpread", defaults.rippleSpread),
                topGapDp = o.optInt("topGapDp", defaults.topGapDp),
                language = o.optString("language", defaults.language),
                wakeOnPlug = o.optBoolean("wakeOnPlug", defaults.wakeOnPlug),
                playOnUnplug = o.optBoolean("playOnUnplug", defaults.playOnUnplug),
            ).sanitized()
        }.getOrDefault(HudSettings.Default)
    }
}
