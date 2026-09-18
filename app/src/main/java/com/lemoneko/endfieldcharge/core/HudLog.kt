package com.lemoneko.endfieldcharge.core

import android.util.Log

/**
 * Single log tag so `adb logcat -s EndfieldCharge:*` shows everything the module does.
 * All module logging goes through here; do not scatter bare `Log` calls with other tags.
 *
 * Logs also go to the Xposed log once [attach] has been called. That is not a nicety: on the
 * OnePlus 15, `android.util.Log` output from the hooked SystemUI process never reaches logcat,
 * while the framework's own log does, so without this the module is silent on that device.
 */
internal object HudLog {

    const val TAG = "EndfieldCharge"

    @Volatile
    private var sink: ((priority: Int, tag: String, message: String) -> Unit)? = null

    /** Routes module logs into the Xposed log as well. Called once, from the module entry point. */
    fun attach(sink: (priority: Int, tag: String, message: String) -> Unit) {
        this.sink = sink
    }

    fun i(scope: String, message: String) = emit(Log.INFO, scope, message, null)

    fun w(scope: String, message: String) = emit(Log.WARN, scope, message, null)

    fun w(scope: String, message: String, tr: Throwable) = emit(Log.WARN, scope, message, tr)

    fun e(scope: String, message: String, tr: Throwable? = null) = emit(Log.ERROR, scope, message, tr)

    private fun emit(priority: Int, scope: String, message: String, tr: Throwable?) {
        val text = "[$scope] $message"
        if (tr == null) {
            Log.println(priority, TAG, text)
        } else {
            Log.println(priority, TAG, "$text\n${Log.getStackTraceString(tr)}")
        }
        sink?.invoke(priority, TAG, if (tr == null) text else "$text: $tr")
    }
}
