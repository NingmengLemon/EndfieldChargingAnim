package com.ningmenglemon.endfieldcharge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.ningmenglemon.endfieldcharge.core.BatterySnapshot
import com.ningmenglemon.endfieldcharge.core.HudPlayMode
import kotlin.math.min

/**
 * The HUD itself.
 *
 * Stage 1 draws a static placeholder so the window plumbing (type, z-order over the keyguard,
 * cutout handling, scaling) can be verified on hardware before the animation work starts.
 * The timeline driven renderer replaces [onDraw] in stage 2.
 */
class EndfieldHudView(context: Context) : View(context) {

    /** Layout constants taken verbatim from `zmd-charge` (`Views/HudWindow.axaml`). */
    private val scale: Float = run {
        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        min(1f, widthDp * WIDTH_RATIO / PILL_WIDTH_DP)
    }

    private val pillWidthPx = PILL_WIDTH_DP * scale * resources.displayMetrics.density
    private val pillHeightPx = PILL_HEIGHT_DP * scale * resources.displayMetrics.density

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PILL_COLOR
        style = Paint.Style.FILL
    }

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ICON_PLATE_COLOR
        style = Paint.Style.FILL
    }

    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BOLT_COLOR
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f * scale * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        // Paint.letterSpacing is in em, so it stays scale independent.
        letterSpacing = 2f / 26f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }

    private val rect = RectF()
    private val boltPath = Path()

    private var snapshot: BatterySnapshot? = null

    /** Feeds the battery reading that the drawn content is derived from. */
    fun setSnapshot(value: BatterySnapshot?) {
        snapshot = value
        invalidate()
    }

    /**
     * Stage 1 placeholder: a plain fade in. Stage 2 drives this from the ported timeline
     * (`HudTimeline`) and an `Animator` / `Choreographer` loop instead.
     */
    fun play(mode: HudPlayMode) {
        alpha = 0f
        animate().alpha(1f).setDuration(200L).start()
    }

    fun stop() {
        animate().cancel()
        alpha = 1f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The pill grows to 90dp in state B; reserve that so nothing is clipped mid animation.
        setMeasuredDimension(
            pillWidthPx.toInt(),
            (PILL_MAX_HEIGHT_DP * scale * resources.displayMetrics.density).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val left = (w - pillWidthPx) / 2f
        val top = (h - pillHeightPx) / 2f

        rect.set(left, top, left + pillWidthPx, top + pillHeightPx)
        val radius = pillHeightPx / 2f
        canvas.drawRoundRect(rect, radius, radius, pillPaint)

        // Placeholder content: white circle + bolt on the left, title centred.
        val density = resources.displayMetrics.density
        val circleRadius = 16f * scale * density
        val circleCx = left + 32f * scale * density + circleRadius
        val circleCy = h / 2f
        canvas.drawCircle(circleCx, circleCy, circleRadius, platePaint)

        buildBoltPath(circleCx, circleCy, 18f * scale * density)
        canvas.drawPath(boltPath, boltPaint)

        canvas.drawText("超充模式", w / 2f, h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    /**
     * Reproduces `PathIcon`'s `Stretch=Uniform` behaviour for the bolt geometry on its 24x24 grid:
     * the path's own bounds are scaled uniformly into the target box and centred, not stretched.
     */
    private fun buildBoltPath(cx: Float, cy: Float, box: Float) {
        val pathW = BOLT_BOUNDS_W
        val pathH = BOLT_BOUNDS_H
        val factor = box / maxOf(pathW, pathH)
        val drawnW = pathW * factor
        val drawnH = pathH * factor
        val originX = cx - drawnW / 2f - BOLT_MIN_X * factor
        val originY = cy - drawnH / 2f - BOLT_MIN_Y * factor

        boltPath.reset()
        boltPath.moveTo(originX + 13f * factor, originY + 2f * factor)
        boltPath.lineTo(originX + 4f * factor, originY + 13f * factor)
        boltPath.lineTo(originX + 12f * factor, originY + 13f * factor)
        boltPath.lineTo(originX + 18f * factor, originY + 2f * factor)
        boltPath.close()
        boltPath.moveTo(originX + 13f * factor, originY + 11f * factor)
        boltPath.lineTo(originX + 20f * factor, originY + 11f * factor)
        boltPath.lineTo(originX + 13f * factor, originY + 22f * factor)
        boltPath.lineTo(originX + 4f * factor, originY + 22f * factor)
        boltPath.close()
    }

    private companion object {
        const val PILL_WIDTH_DP = 560f
        const val PILL_HEIGHT_DP = 60f
        const val PILL_MAX_HEIGHT_DP = 90f

        /** Fraction of the screen width the pill is allowed to occupy. */
        const val WIDTH_RATIO = 0.92f

        const val PILL_COLOR = 0xFF312F30.toInt()
        const val ICON_PLATE_COLOR = 0xFFE9E7E4.toInt()
        const val BOLT_COLOR = 0xFF141313.toInt()

        /** Bounds of `M13 2 L4 13 L12 13 L18 2 Z M13 11 L20 11 L13 22 L4 22 Z` on its 24x24 grid. */
        const val BOLT_MIN_X = 4f
        const val BOLT_MIN_Y = 2f
        const val BOLT_BOUNDS_W = 16f
        const val BOLT_BOUNDS_H = 20f
    }
}
