package com.lemoneko.endfieldcharge.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.lemoneko.endfieldcharge.core.BatterySnapshot
import com.lemoneko.endfieldcharge.core.HudPlayMode
import com.lemoneko.endfieldcharge.core.timeline.AnimationOptions
import com.lemoneko.endfieldcharge.core.timeline.HudTimeline
import com.lemoneko.endfieldcharge.ui.EndfieldHudView
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the HUD to a deterministic PNG sequence, one file per cue.
 *
 * Screenshots and screen recordings of the real overlay are unreliable for judging the animation:
 * capture latency is seconds on this device, the frame rate is variable, and the compositor may or
 * may not include the window. Rendering the same [EndfieldHudView] off screen at known cues
 * removes all of that, and the sheets can be diffed against the reference implementation.
 */
object FrameSheetRenderer {

    private const val BACKGROUND = 0xFF9A9A9A.toInt()

    /** A mid-range sample so the badge arc, the numbers and the low battery colour are visible. */
    val SAMPLE_SNAPSHOT = BatterySnapshot(
        level = 76,
        plugged = 1,
        status = 2,
        voltageUv = 4_400_000L,
        chargeFullUah = 6_200_000L,
        chargeCounterRaw = 4_712L,
    )

    fun render(
        context: Context,
        outDir: File,
        mode: HudPlayMode,
        frameCount: Int = 41,
        options: AnimationOptions = AnimationOptions.Default,
    ): List<File> {
        outDir.mkdirs()

        val timeline = HudTimeline(options)
        val view = EndfieldHudView(context)
        view.setSnapshot(SAMPLE_SNAPSHOT)

        // onMeasure ignores the incoming specs and reports the fixed design canvas size.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val width = view.measuredWidth
        val height = view.measuredHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val written = ArrayList<File>(frameCount)
        for (index in 0 until frameCount) {
            val cue = index.toDouble() / (frameCount - 1).toDouble()
            view.render(
                when (mode) {
                    HudPlayMode.CHARGE -> timeline.evaluateCharge(cue)
                    HudPlayMode.UNPLUG -> timeline.evaluateUnplug(cue)
                },
            )

            bitmap.eraseColor(Color.TRANSPARENT)
            canvas.drawColor(BACKGROUND)
            view.draw(canvas)

            val file = File(outDir, "%s_%03d_cue%04d.png".format(mode.name.lowercase(), index, (cue * 1000).toInt()))
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            written.add(file)
        }

        bitmap.recycle()
        return written
    }
}
