package com.lemoneko.endfieldcharge.core

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface

/**
 * Resolves the SystemUI [Context] without any ROM specific knowledge.
 *
 * `onPackageReady` fires before the Application object exists, so the context is captured by
 * hooking `Application#onCreate`, which the SystemUI process calls exactly once. Hooking the
 * framework base class rather than a SystemUI subclass keeps this ROM agnostic: the object passed
 * to `onCreate` *is* the Application, and every Application is a Context.
 */
object SystemUiContext {

    private const val SCOPE = "ctx"

    @Volatile
    private var context: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val waiting = ArrayDeque<(Context) -> Unit>()

    /** Installs the context capture hook. Safe to call more than once. */
    fun install(xposed: XposedInterface) {
        val method = Application::class.java.getDeclaredMethod("onCreate")
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val self = chain.thisObject
                if (self is Context) {
                    publish(self)
                } else {
                    HudLog.w(SCOPE, "Application#onCreate thisObject was ${self?.javaClass?.name}")
                }
                result
            }
        HudLog.i(SCOPE, "hooked Application#onCreate for context capture")
    }

    /**
     * Returns the context if it is already known, otherwise a cached lookup through
     * `ActivityThread.currentApplication()`.
     */
    fun get(): Context? = context ?: lookupCurrentApplication()?.also { publish(it) }

    /** Runs [block] on the main thread as soon as a context is available. */
    fun onAvailable(block: (Context) -> Unit) {
        val known = get()
        if (known != null) {
            block(known)
            return
        }
        synchronized(waiting) { waiting.addLast(block) }
    }

    private fun publish(ctx: Context) {
        if (context === ctx) return
        context = ctx
        HudLog.i(SCOPE, "SystemUI context captured: ${ctx.packageName}")

        val pending = synchronized(waiting) {
            val copy = waiting.toList()
            waiting.clear()
            copy
        }
        for (block in pending) {
            mainHandler.post { runCatching { block(ctx) }.onFailure { HudLog.e(SCOPE, "context consumer failed", it) } }
        }
    }

    private fun lookupCurrentApplication(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()
}
