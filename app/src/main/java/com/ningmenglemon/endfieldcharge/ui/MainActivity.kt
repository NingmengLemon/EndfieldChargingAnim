package com.ningmenglemon.endfieldcharge.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.ningmenglemon.endfieldcharge.rom.RomAdapters

/**
 * Placeholder settings screen. It only reports whether a ROM adapter matches the *host* process
 * (which it never will from the module app itself, so this is a build-info screen for now).
 * The real settings UI lands with the animation work.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * density).toInt(), (96 * density).toInt(), (24 * density).toInt(), 0)
        }

        root.addView(TextView(this).apply {
            text = "EndfieldCharge"
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text = buildString {
                append("module skeleton\n\n")
                append("adapters: ")
                append(RomAdapters.javaClass.name)
                append("\n")
                append("build: ${com.ningmenglemon.endfieldcharge.BuildConfig.VERSION_NAME}")
            }
            textSize = 14f
        })

        setContentView(root)
    }
}
