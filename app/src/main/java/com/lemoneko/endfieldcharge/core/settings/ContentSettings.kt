package com.lemoneko.endfieldcharge.core.settings

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.lemoneko.endfieldcharge.core.HudLog

/**
 * Hook side of the settings channel.
 *
 * Settings live in a [android.content.ContentProvider] owned by the module app, and SystemUI reads
 * them over the resolver. libxposed remote preferences would be the idiomatic channel, but they
 * need the framework to push a binder into the module app, and that push is performed by the
 * framework's manager app, which is not installed here. A provider works regardless of which
 * framework is in use and needs no permission.
 *
 * A [ContentObserver] gives change notifications, so edits in the settings screen reach a running
 * HUD without polling.
 */
object ContentSettings {

    private const val SCOPE = "settings"
    private const val METHOD_GET = "get"
    private const val KEY_JSON = "json"

    /** Must match the authority declared by the module app's provider. */
    fun authority(modulePackage: String): String = "$modulePackage.settings"

    @Volatile
    private var current: HudSettings = HudSettings.Default

    private val observers = mutableListOf<(HudSettings) -> Unit>()

    fun install(context: Context, modulePackage: String) {
        val uri = Uri.parse("content://${authority(modulePackage)}")
        current = read(context, uri)
        HudLog.i(SCOPE, "loaded $current")

        runCatching {
            context.contentResolver.registerContentObserver(
                uri,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        val updated = read(context, uri)
                        current = updated
                        HudLog.i(SCOPE, "changed to $updated")
                        val snapshot = synchronized(observers) { observers.toList() }
                        snapshot.forEach { it(updated) }
                    }
                },
            )
        }.onFailure { HudLog.e(SCOPE, "could not observe settings", it) }
    }

    fun current(): HudSettings = current

    /** Registers [observer] and calls it immediately with the current value. */
    fun observe(observer: (HudSettings) -> Unit) {
        synchronized(observers) { observers.add(observer) }
        observer(current)
    }

    private fun read(context: Context, uri: Uri): HudSettings = runCatching {
        val result = context.contentResolver.call(uri, METHOD_GET, null, null)
        HudSettingsJson.fromJson(result?.getString(KEY_JSON))
    }.onFailure {
        HudLog.w(SCOPE, "settings provider unreachable; using defaults", it)
    }.getOrDefault(HudSettings.Default)

    /** Bundle key used by both sides. */
    fun jsonKey(): String = KEY_JSON
}
