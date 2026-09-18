package com.lemoneko.endfieldcharge.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.lemoneko.endfieldcharge.BuildConfig
import com.lemoneko.endfieldcharge.core.HudMetrics
import com.lemoneko.endfieldcharge.core.HudPlayMode
import com.lemoneko.endfieldcharge.core.settings.HudSettings
import com.lemoneko.endfieldcharge.core.timeline.HudTimeline
import com.lemoneko.endfieldcharge.debug.FrameSheetRenderer
import com.lemoneko.endfieldcharge.debug.SysfsProbe
import com.lemoneko.endfieldcharge.settings.SettingsRepository
import java.io.File

/**
 * Settings and preview screen.
 *
 * The HUD normally runs inside SystemUI behind an Xposed hook, which makes iteration slow and hard
 * to observe. This screen runs the exact same view and timeline in an ordinary app process, so the
 * animation can be played, scrubbed and dumped without touching the system UI.
 *
 * Edits go to a local store and are mirrored into the framework's remote preferences, which is how
 * the hooked SystemUI process reads them.
 *
 * The preview strip's top edge represents the top of the screen, so the offset slider value is
 * directly the distance from the top of the screen, the same number the overlay uses.
 */
class MainActivity : Activity() {

    private lateinit var preview: EndfieldHudView
    private lateinit var status: TextView
    private lateinit var scrub: SeekBar
    private lateinit var connection: TextView

    private var settings = HudSettings.Default
    private var animator: ValueAnimator? = null
    private var mode = HudPlayMode.CHARGE
    private var insetPx = 0
    private var density = 1f
    private var lastCue = 0.0
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsRepository.start(this)

        density = resources.displayMetrics.density
        insetPx = HudMetrics.topInsetPx(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#202124"))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        root.addView(buildPreviewStrip())

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (24 * density).toInt())
        }
        root.addView(
            ScrollView(this).apply { addView(column) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        buildControls(column)
        setContentView(root)

        mode = if (intent?.getBooleanExtra(EXTRA_UNPLUG, false) == true) {
            HudPlayMode.UNPLUG
        } else {
            HudPlayMode.CHARGE
        }

        SettingsRepository.observe { updated ->
            settings = updated
            refresh()
        }

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

    // ---------------- preview ----------------

    private fun buildPreviewStrip() = FrameLayout(this).apply {
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
    }

    private fun buildControls(column: LinearLayout) {
        column.addView(label("cue"))
        scrub = SeekBar(this).apply {
            max = SCRUB_STEPS
            setOnSeekBarChangeListener(listener { value ->
                animator?.cancel()
                showCue(value.toDouble() / SCRUB_STEPS)
            })
        }
        column.addView(scrub)

        column.addView(label("top offset (dp below the display cutout)"))
        column.addView(
            slider(
                max = HudSettings.MAX_TOP_GAP_DP,
                read = { it.topGapDp },
                write = { s, v -> s.copy(topGapDp = v) },
            ),
        )

        column.addView(label("width (% of screen)"))
        column.addView(
            slider(
                max = ((HudSettings.MAX_WIDTH_RATIO - HudSettings.MIN_WIDTH_RATIO) * 100).toInt(),
                read = { ((it.widthRatio - HudSettings.MIN_WIDTH_RATIO) * 100).toInt() },
                write = { s, v -> s.copy(widthRatio = HudSettings.MIN_WIDTH_RATIO + v / 100f) },
                format = { "${(HudSettings.MIN_WIDTH_RATIO * 100).toInt() + it}%" },
            ),
        )

        column.addView(label("duration"))
        column.addView(
            slider(
                max = ((HudSettings.MAX_DURATION - HudSettings.MIN_DURATION) * 10).toInt(),
                read = { ((it.durationSeconds - HudSettings.MIN_DURATION) * 10).toInt() },
                write = { s, v -> s.copy(durationSeconds = HudSettings.MIN_DURATION + v / 10.0) },
                format = { "%.1f s".format(HudSettings.MIN_DURATION + it / 10.0) },
            ),
        )

        column.addView(label("bounce"))
        column.addView(
            slider(
                max = 50,
                read = { (it.bounceStrength * 100).toInt() },
                write = { s, v -> s.copy(bounceStrength = v / 100.0) },
                format = { "%.2f".format(it / 100.0) },
            ),
        )

        column.addView(label("ripple intensity"))
        column.addView(
            slider(
                max = 200,
                read = { (it.rippleIntensity * 100).toInt() },
                write = { s, v -> s.copy(rippleIntensity = v / 100.0) },
                format = { "%.2f".format(it / 100.0) },
            ),
        )

        column.addView(label("ripple spread"))
        column.addView(
            slider(
                max = 100,
                read = { ((it.rippleSpread - 0.5) * 100).toInt() },
                write = { s, v -> s.copy(rippleSpread = 0.5 + v / 100.0) },
                format = { "%.2f".format(0.5 + it / 100.0) },
            ),
        )

        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    toggle("Replace the ROM animation", { it.enabled }) { s, v ->
                        s.copy(enabled = v)
                    },
                )
                addView(
                    toggle("Wake the screen on plug", { it.wakeOnPlug }) { s, v ->
                        s.copy(wakeOnPlug = v)
                    },
                )
                addView(
                    toggle("Play on unplug", { it.playOnUnplug }) { s, v ->
                        s.copy(playOnUnplug = v)
                    },
                )
            },
        )

        column.addView(label("language"))
        column.addView(languageSpinner())

        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Play charge") { play(HudPlayMode.CHARGE) })
                addView(button("Play unplug") { play(HudPlayMode.UNPLUG) })
            },
        )
        column.addView(button("Render frame sheets") { renderSheets() })
        column.addView(button("Read battery sysfs") { status.text = SysfsProbe.report() })

        connection = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), 0)
        }
        column.addView(connection)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), 0)
        }
        column.addView(status)
    }

    // ---------------- settings plumbing ----------------

    /**
     * Controls register a binder that pushes the stored value back into the widget. [refresh] runs
     * them with [binding] set, because otherwise observing the store and editing it would feed
     * each other.
     */
    private val binders = mutableListOf<(HudSettings) -> Unit>()

    private fun slider(
        max: Int,
        read: (HudSettings) -> Int,
        write: (HudSettings, Int) -> HudSettings,
        format: (Int) -> String = { it.toString() },
    ): LinearLayout {
        val caption = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }
        val bar = SeekBar(this).apply {
            this.max = max
            setOnSeekBarChangeListener(listener { value ->
                caption.text = format(value)
                if (!binding && value != read(settings)) {
                    SettingsRepository.update(write(settings, value))
                }
            })
        }
        binders += { current ->
            val value = read(current).coerceIn(0, max)
            bar.progress = value
            caption.text = format(value)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(caption)
        }
    }

    private fun toggle(
        label: String,
        read: (HudSettings) -> Boolean,
        write: (HudSettings, Boolean) -> HudSettings,
    ): Switch {
        val widget = Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
            setOnCheckedChangeListener { _, checked ->
                // A widget callback is not proof of a user action: setChecked and the first layout
                // both fire it. Only a value that actually differs is a real edit.
                if (!binding && checked != read(settings)) {
                    SettingsRepository.update(write(settings, checked))
                }
            }
        }
        binders += { current -> widget.isChecked = read(current) }
        return widget
    }

    /** Language is the one setting with more than two states, so it gets a dropdown. */
    private fun languageSpinner(): Spinner {
        val values = listOf(
            HudSettings.LANGUAGE_AUTO,
            HudSettings.LANGUAGE_ZH,
            HudSettings.LANGUAGE_EN,
        )
        val labels = listOf("Auto (system)", "中文", "English")

        val widget = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels)
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    // Spinner fires this once during its first layout, which is not a user edit.
                    if (!binding && values[position] != settings.language) {
                        SettingsRepository.update(settings.copy(language = values[position]))
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins((16 * density).toInt(), 0, (16 * density).toInt(), 0) }
        }
        binders += { current ->
            val index = values.indexOf(current.language).coerceAtLeast(0)
            if (widget.selectedItemPosition != index) widget.setSelection(index)
        }
        return widget
    }

    /** Pushes the current settings into every control, the preview and the status lines. */
    private fun refresh() {
        binding = true
        try {
            binders.forEach { it(settings) }
            preview.widthRatio = settings.widthRatio
            preview.language = settings.language
            applyOffset()
            showCue(lastCue)

            connection.text =
                "changes are stored by SettingsProvider and picked up by SystemUI live"
        } finally {
            binding = false
        }
    }

    private fun applyOffset() {
        // The strip already starts below the status bar, so shift by the difference to make the
        // slider read as "distance from the top of the screen".
        val margin = (HudMetrics.topMarginDp(this, settings.topGapDp) * density).toInt() - insetPx
        (preview.layoutParams as FrameLayout.LayoutParams).topMargin = margin
        preview.requestLayout()
    }

    private fun listener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
            if (fromUser) onChange(value)
        }

        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }

    private fun label(text: String) = TextView(this).apply {
        setTextColor(Color.LTGRAY)
        textSize = 11f
        setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), 0)
        this.text = text
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    // ---------------- playback ----------------

    private fun showCue(cue: Double) {
        lastCue = cue
        val run = HudTimeline(settings.animationOptions())
        preview.render(
            when (mode) {
                HudPlayMode.CHARGE -> run.evaluateCharge(cue)
                HudPlayMode.UNPLUG -> run.evaluateUnplug(cue)
            },
        )
        status.text = "%s · cue %.3f · %d ms · build %s".format(
            mode.name.lowercase(),
            cue,
            run.durationMillis,
            BuildConfig.VERSION_NAME,
        )
    }

    private fun play(playMode: HudPlayMode) {
        animator?.cancel()
        mode = playMode
        scrub.progress = 0
        val run = HudTimeline(settings.animationOptions())

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = run.durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val cue = (animation.animatedValue as Float).toDouble()
                scrub.progress = (cue * SCRUB_STEPS).toInt()
                showCue(cue)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = showCue(lastCue)
            })
        }.also { it.start() }
    }

    private fun renderSheets() {
        val dir = File(getExternalFilesDir(null), "frames")
        val options = settings.animationOptions()
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
    }
}
