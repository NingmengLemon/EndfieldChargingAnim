package com.lemoneko.endfieldcharge.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.lemoneko.endfieldcharge.core.settings.ContentSettings
import com.lemoneko.endfieldcharge.core.settings.HudSettings
import com.lemoneko.endfieldcharge.core.settings.HudSettingsJson

/**
 * Settings store for the module app.
 *
 * Everything goes through [SettingsProvider], which both persists the value and notifies the
 * hooked SystemUI process, so there is exactly one write path and no chance of the app and the
 * hook disagreeing about where the settings live.
 */
object SettingsRepository {

    private const val TAG = "EndfieldCharge"
    private const val METHOD_GET = "get"
    private const val METHOD_SET = "set"

    @Volatile
    private var appContext: Context? = null

    private val observers = mutableListOf<(HudSettings) -> Unit>()

    fun start(context: Context) {
        appContext = context.applicationContext
    }

    fun current(): HudSettings {
        val context = appContext ?: return HudSettings.Default
        return runCatching {
            val result = context.contentResolver.call(uri(context), METHOD_GET, null, null)
            HudSettingsJson.fromJson(result?.getString(ContentSettings.jsonKey()))
        }.onFailure { Log.e(TAG, "could not read settings", it) }
            .getOrDefault(HudSettings.Default)
    }

    fun update(settings: HudSettings) {
        val context = appContext ?: return
        val payload = Bundle().apply {
            putString(ContentSettings.jsonKey(), HudSettingsJson.toJson(settings))
        }
        runCatching { context.contentResolver.call(uri(context), METHOD_SET, null, payload) }
            .onFailure { Log.e(TAG, "could not write settings", it) }
        notifyObservers()
    }

    fun observe(observer: (HudSettings) -> Unit) {
        synchronized(observers) { observers.add(observer) }
        observer(current())
    }

    private fun uri(context: Context) =
        Uri.parse("content://${ContentSettings.authority(context.packageName)}")

    private fun notifyObservers() {
        val value = current()
        synchronized(observers) { observers.toList() }.forEach { it(value) }
    }
}
