package com.example.vrbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

/**
 * Draws two HUD buttons in the top-right corner of BOTH eye viewports (the
 * screen is split left/right by the stereo renderer, and each half needs to
 * show the same UI):
 *
 *  - "options": a kebab menu (three vertical dots), outermost. Still purely
 *    visual - not wired to anything yet.
 *  - "clickH": a ring, just to its left. Toggles [hoverClickEnabled] -
 *    whether GazeCursorOverlay's dwell-to-click mechanic is live - and gets
 *    a small green center dot while that's on.
 *
 * This view stays non-clickable/non-focusable by default so ordinary
 * touches pass straight through to the WebView underneath (see
 * MainActivity.forwardToWebView). GazeCursorOverlay flips
 * isClickable/isFocusable on here for the moment the gaze cursor is
 * actually hovering clickH, via [setClickHHovered].
 */
class TopButtonsOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Whether GazeCursorOverlay's dwell-to-click mechanic is currently live. */
    var hoverClickEnabled: Boolean = true
        private set

    private val density = resources.displayMetrics.density
    private val buttonRadius = 22f * density
    private val margin = 20f * density
    private val gap = 14f * density

    // Button background is 70% transparent (30% opaque).
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = (255 * 0.3f).toInt()
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    // Small center dot shown on clickH only while hoverClickEnabled is true.
    private val enabledFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80) // material green
        style = Paint.Style.FILL
    }

    init {
        isClickable = false
        isFocusable = false
        setOnClickListener { toggleHoverClick() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val halfWidth = width / 2f
        val clickH = clickHCenter(halfWidth)

        for (eye in 0..1) {
            val eyeLeft = eye * halfWidth
            val centerY = margin + buttonRadius

            // "options" - flush with this eye's top-right corner.
            val optionsCenterX = eyeLeft + halfWidth - margin - buttonRadius
            drawOptionsButton(canvas, optionsCenterX, centerY)

            // "clickH" - just to the left of "options".
            drawClickHButton(canvas, eyeLeft + clickH.x, clickH.y)
        }
    }

    private fun drawOptionsButton(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, buttonRadius, backgroundPaint)
        val dotRadius = buttonRadius * 0.09f
        val spacing = buttonRadius * 0.4f
        canvas.drawCircle(cx, cy - spacing, dotRadius, dotPaint)
        canvas.drawCircle(cx, cy, dotRadius, dotPaint)
        canvas.drawCircle(cx, cy + spacing, dotRadius, dotPaint)
    }

    private fun drawClickHButton(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, buttonRadius, backgroundPaint)
        canvas.drawCircle(cx, cy, buttonRadius * 0.5f, ringPaint)
        if (hoverClickEnabled) {
            canvas.drawCircle(cx, cy, buttonRadius * 0.22f, enabledFillPaint)
        }
    }

    /**
     * clickH's center, in ONE eye's local coordinate space (i.e. as if
     * eyeLeft == 0). Both eyes share identical geometry, so this single
     * calculation - shared by onDraw and [isPointOnClickH] - covers either
     * half; callers add their own eye's left offset when actually drawing.
     */
    private fun clickHCenter(halfWidth: Float): PointF {
        val centerY = margin + buttonRadius
        val optionsCenterX = halfWidth - margin - buttonRadius
        val clickHCenterX = optionsCenterX - buttonRadius * 2f - gap
        return PointF(clickHCenterX, centerY)
    }

    /**
     * Whether (xInEye, yInEye) - a point already expressed in one eye's
     * local space, e.g. from GazeCursorOverlay - falls within clickH's
     * button circle.
     */
    fun isPointOnClickH(xInEye: Float, yInEye: Float): Boolean {
        val halfWidth = width / 2f
        if (halfWidth <= 0f) return false
        val c = clickHCenter(halfWidth)
        val dx = xInEye - c.x
        val dy = yInEye - c.y
        return dx * dx + dy * dy <= buttonRadius * buttonRadius
    }

    /** Flips isClickable/isFocusable on only while actually gaze-hovered, so this view stays click-through everywhere else. */
    fun setClickHHovered(hovered: Boolean) {
        if (isClickable == hovered) return
        isClickable = hovered
        isFocusable = hovered
    }

    fun toggleHoverClick() {
        hoverClickEnabled = !hoverClickEnabled
        invalidate()
    }
}
