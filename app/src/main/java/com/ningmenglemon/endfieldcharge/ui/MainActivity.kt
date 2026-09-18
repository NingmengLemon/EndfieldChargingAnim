package com.ningmenglemon.endfieldcharge.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ningmenglemon.endfieldcharge.BuildConfig
import com.ningmenglemon.endfieldcharge.core.HudPlayMode
import com.ningmenglemon.endfieldcharge.core.timeline.AnimationOptions
import com.ningmenglemon.endfieldcharge.core.timeline.HudTimeline
import com.ningmenglemon.endfieldcharge.debug.FrameSheetRenderer
import java.io.File

/**
 * Developer screen.
 *
 * The HUD is driven from inside SystemUI by an Xposed hook, which makes iteration slow and hard to
 * observe. This screen runs the exact same view and timeline in an ordinary app process, so the
 * animation can be played and inspected without touching the device's system UI.
 */
class MainActivity : Activity() {

    private val options = AnimationOptions.Default
    private val timeline = HudTimeline(options)

    private lateinit var preview: EndfieldHudView
    private lateinit var status: TextView
    private var animator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#202124"))
            setPadding(0, (48 * density).toInt(), 0, 0)
        }

        root.addView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#9A9A9A"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (120 * density).toInt(),
                )
                preview = EndfieldHudView(context).apply {
                    setSnapshot(FrameSheetRenderer.SAMPLE_SNAPSHOT)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    )
                }
                addView(preview)
            },
        )

        root.addView(button("Play charge") { play(HudPlayMode.CHARGE) })
        root.addView(button("Play unplug") { play(HudPlayMode.UNPLUG) })
        root.addView(button("Render frame sheets") { renderSheets() })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), 0)
            text = "build ${BuildConfig.VERSION_NAME} · ${timeline.durationMillis} ms"
        }
        root.addView(status)

        setContentView(root)

        if (intent?.getBooleanExtra(EXTRA_RENDER_SHEETS, false) == true) {
            renderSheets()
        }
    }

    companion object {
        /** `adb shell am start -n <pkg>/.ui.MainActivity --ez renderSheets true` */
        const val EXTRA_RENDER_SHEETS = "renderSheets"
    }

    override fun onDestroy() {
        animator?.cancel()
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun play(mode: HudPlayMode) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = timeline.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val cue = (animation.animatedValue as Float).toDouble()
                preview.render(
                    when (mode) {
                        HudPlayMode.CHARGE -> timeline.evaluateCharge(cue)
                        HudPlayMode.UNPLUG -> timeline.evaluateUnplug(cue)
                    },
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    status.text = "played ${mode.name.lowercase()}"
                }
            })
        }.also { it.start() }
    }

    private fun renderSheets() {
        val dir = File(getExternalFilesDir(null), "frames")
        val charge = FrameSheetRenderer.render(this, File(dir, "charge"), HudPlayMode.CHARGE, options = options)
        val unplug = FrameSheetRenderer.render(this, File(dir, "unplug"), HudPlayMode.UNPLUG, options = options)
        status.text = "wrote ${charge.size} charge + ${unplug.size} unplug frames to\n${dir.absolutePath}"
    }
}
