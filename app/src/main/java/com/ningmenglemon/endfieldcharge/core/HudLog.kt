package com.ningmenglemon.endfieldcharge.core

import android.util.Log

/**
 * Single log tag so `adb logcat -s EndfieldCharge:*` shows everything the module does.
 * All module logging goes through here; do not scatter bare `Log` calls with other tags.
 */
internal object HudLog {

    const val TAG = "EndfieldCharge"

    fun i(scope: String, message: String) = Log.i(TAG, "[$scope] $message")

    fun w(scope: String, message: String) = Log.w(TAG, "[$scope] $message")

    fun e(scope: String, message: String, tr: Throwable? = null) = Log.e(TAG, "[$scope] $message", tr)
}
