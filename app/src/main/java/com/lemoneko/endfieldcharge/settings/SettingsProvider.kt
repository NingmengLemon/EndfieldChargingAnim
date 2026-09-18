package com.lemoneko.endfieldcharge.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.lemoneko.endfieldcharge.core.settings.ContentSettings
import com.lemoneko.endfieldcharge.core.settings.HudSettingsJson

/**
 * Carries settings from the module app into the hooked SystemUI process.
 *
 * Exported without a permission on purpose: the payload is a handful of display preferences, and a
 * signature permission would not work because SystemUI is not signed with this key.
 *
 * The provider is the store of record; [SettingsRepository] writes through it so a single write
 * both persists and notifies observers on the hook side.
 */
class SettingsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        METHOD_GET -> Bundle().apply {
            putString(ContentSettings.jsonKey(), readSettings())
        }

        METHOD_SET -> {
            // The JSON normally arrives in the extras bundle. It is also accepted as the method
            // argument because `adb shell content call` cannot carry a value containing colons
            // through --extra, which makes scripted writes otherwise impossible.
            val json = extras?.getString(ContentSettings.jsonKey()) ?: arg
            if (json != null) writeSettings(json)
            Bundle.EMPTY
        }

        else -> super.call(method, arg, extras)
    }

    private fun prefs() = requireNotNull(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readSettings(): String =
        prefs().getString(KEY_JSON, null) ?: HudSettingsJson.toJson(
            com.lemoneko.endfieldcharge.core.settings.HudSettings.Default,
        )

    private fun writeSettings(json: String) {
        prefs().edit().putString(KEY_JSON, json).apply()
        requireNotNull(context).contentResolver.notifyChange(uri(), null)
    }

    private fun uri() = Uri.parse("content://${requireNotNull(context).packageName}.settings")

    // Unused CRUD surface: this provider only speaks through call().
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val PREFS = "endfield_hud"
        private const val KEY_JSON = "settings_json"
        private const val METHOD_GET = "get"
        private const val METHOD_SET = "set"
    }
}
