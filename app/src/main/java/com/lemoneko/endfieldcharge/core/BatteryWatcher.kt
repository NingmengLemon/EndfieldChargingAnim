package com.lemoneko.endfieldcharge.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * ROM agnostic plug/unplug detection.
 *
 * `ACTION_BATTERY_CHANGED` is sticky, so registering delivers the current state immediately and
 * there is no need for a separate initial poll.
 */
class BatteryWatcher(
    private val context: Context,
    private val onChanged: (BatterySnapshot) -> Unit,
) {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null || intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val snapshot = runCatching { BatterySnapshot.from(intent) }
                .onFailure { HudLog.e(SCOPE, "failed to read battery snapshot", it) }
                .getOrNull() ?: return
            onChanged(snapshot)
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        runCatching {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }.onSuccess {
            registered = true
            HudLog.i(SCOPE, "battery watcher registered")
        }.onFailure {
            HudLog.e(SCOPE, "failed to register battery watcher", it)
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private companion object {
        const val SCOPE = "battery"
    }
}
