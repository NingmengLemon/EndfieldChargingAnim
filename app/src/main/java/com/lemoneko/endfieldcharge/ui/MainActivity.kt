package com.lemoneko.endfieldcharge.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.lemoneko.endfieldcharge.BuildConfig
import com.lemoneko.endfieldcharge.core.HudMetrics
import com.lemoneko.endfieldcharge.core.HudPlayMode
import com.lemoneko.endfieldcharge.core.timeline.AnimationOptions
import com.lemoneko.endfieldcharge.core.timeline.HudTimeline
import com.lemoneko.endfieldcharge.debug.FrameSheetRenderer
import java.io.File

/**
 * Developer screen.
 *
 * The HUD is driven from inside SystemUI by an Xposed hook, which makes iteration slow and hard to
 * observe. This screen runs the exact same view and timeline in an ordinary app process, so the
 * animation can be played, scrubbed, positioned and dumped without touching the system UI.
 *
 * The preview strip's top edge represents the top of the screen: the HUD is offset by
 * `offset - statusBarInset` inside it, so the slider value is directly the distance from the top of
 * the screen, which is what the overlay uses.
 */
class MainActivity : Activity() {

    private val options = AnimationOptions.Default
    private val timeline = HudTimeline(options)

    private lateinit var preview: EndfieldHudView
    private lateinit var status: TextView
    private lateinit var scrub: SeekBar
    private lateinit var offset: SeekBar

    private var animator: ValueAnimator? = null
    private var mode = HudPlayMode.CHARGE
    private var insetPx = 0
    private var density = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        density = resources.displayMetrics.density
        insetPx = HudMetrics.topInsetPx(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#202124"))
        }

        // Apps targeting SDK 35 are forced edge to edge, so the controls would otherwise sit under
        // the status bar.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        root.addView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#9A9A9A"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (200 * density).toInt(),
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

        root.addView(label("cue"))
        scrub = SeekBar(this).apply {
            max = SCRUB_STEPS
            setOnSeekBarChangeListener(progressListener { value ->
                animator?.cancel()
                showCue(value.toDouble() / SCRUB_STEPS)
            })
        }
        root.addView(scrub)

        root.addView(label("top offset (dp from screen top)"))
        offset = SeekBar(this).apply {
            max = MAX_OFFSET_DP
            progress = defaultOffsetDp()
            setOnSeekBarChangeListener(progressListener { applyOffset(it) })
        }
        root.addView(offset)

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Play charge") { play(HudPlayMode.CHARGE) })
                addView(button("Play unplug") { play(HudPlayMode.UNPLUG) })
            },
        )
        root.addView(button("Render frame sheets") { renderSheets() })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), 0)
        }
        root.addView(status)

        setContentView(root)

        mode = if (intent?.getBooleanExtra(EXTRA_UNPLUG, false) == true) {
            HudPlayMode.UNPLUG
        } else {
            HudPlayMode.CHARGE
        }
        applyOffset(offset.progress)

        // Cue 0 is a fully transparent HUD and looks like a broken preview.
        val startCue = (intent?.getIntExtra(EXTRA_CUE, 500) ?: 500).coerceIn(0, SCRUB_STEPS)
        scrub.progress = startCue
        showCue(startCue.toDouble() / SCRUB_STEPS)

        if (intent?.getBooleanExtra(EXTRA_RENDER_SHEETS, false) == true) {
            renderSheets()
        }
    }

    override fun onDestroy() {
        animator?.cancel()
        super.onDestroy()
    }

    /** The overlay places the pill below the cutout, so the preview starts there too. */
    private fun defaultOffsetDp(): Int = HudMetrics.topMarginDp(this).coerceIn(0, MAX_OFFSET_DP)

    private fun applyOffset(offsetDp: Int) {
        // The strip already starts below the status bar, so shift by the difference to make the
        // slider read as "distance from the top of the screen".
        val margin = (offsetDp * density).toInt() - insetPx
        (preview.layoutParams as FrameLayout.LayoutParams).topMargin = margin
        preview.requestLayout()
        refreshStatus()
    }

    private fun progressListener(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) onChange(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        }

    private fun label(text: String) = TextView(this).apply {
        setTextColor(Color.LTGRAY)
        textSize = 11f
        setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
        this.text = text
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private var lastCue = 0.0

    private fun showCue(cue: Double) {
        lastCue = cue
        preview.render(
            when (mode) {
                HudPlayMode.CHARGE -> timeline.evaluateCharge(cue)
                HudPlayMode.UNPLUG -> timeline.evaluateUnplug(cue)
            },
        )
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = "%s · cue %.3f · top %d dp · cutout %d px · %d ms".format(
            mode.name.lowercase(),
            lastCue,
            offset.progress,
            insetPx,
            timeline.durationMillis,
        )
    }

    private fun play(playMode: HudPlayMode) {
        animator?.cancel()
        mode = playMode
        scrub.progress = 0

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = timeline.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val cue = (animation.animatedValue as Float).toDouble()
                scrub.progress = (cue * SCRUB_STEPS).toInt()
                showCue(cue)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    refreshStatus()
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

    companion object {
        /** `adb shell am start -n <pkg>/.ui.MainActivity --ez renderSheets true` */
        const val EXTRA_RENDER_SHEETS = "renderSheets"

        /** `--ei cue 250` renders that cue (0..1000) on open. */
        const val EXTRA_CUE = "cue"

        /** `--ez unplug true` previews the simplified unplug timeline. */
        const val EXTRA_UNPLUG = "unplug"

        private const val SCRUB_STEPS = 1000
        private const val MAX_OFFSET_DP = 160
    }
}
