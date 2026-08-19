package com.example.vrbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.hypot

/** Something the gaze cursor can dwell-click on. */
interface HoverClickTarget {
    /** [xInEye]/[yInEye] are in one eye's local space (0..width/2, 0..height) - see GazeCursorOverlay. */
    fun hitTest(xInEye: Float, yInEye: Float): Boolean
    fun onHoverEnter() {}
    fun onHoverExit() {}
    fun onHoverClick()
}

/**
 * Gaze-style cursor for headset use: a screen-space dot whose position is
 * driven by the gyroscope, treated as a relative pointer (there's no
 * rotation-vector/magnetometer fusion here, so this is a simple "tilt to
 * steer" pointer, not true absolute head tracking, and it will drift like
 * any raw gyro integration does).
 *
 * Drawn once per eye viewport, mirrored at the same relative position in
 * both halves - the same convention TopButtonsOverlay uses for its HUD
 * buttons, which is what makes hit-testing between the two straightforward
 * (see [HoverClickTarget]).
 *
 * Interaction model - dwell to click, no physical click button needed:
 *  - Sitting still (or near-still) over a clickable target fills a ring
 *    around the dot over [DWELL_MS]; on completion the target is clicked,
 *    the dot flashes green, and the ring disappears.
 *  - After firing, continuing to sit still will NOT re-trigger another
 *    click - the user has to actually move the cursor (past
 *    [MOVEMENT_THRESHOLD_RAD_S]) to re-arm the dwell mechanic.
 *  - The dot is 70% transparent by default; after sitting over something
 *    non-clickable for [FADE_DELAY_MS] it gradually settles to a more
 *    solid 35% transparent, so it's easier to spot when there's nothing to
 *    interact with.
 *
 * [masterTarget] (wired to clickH in MainActivity) is always dwell-active
 * regardless of [isHoverClickEnabled] - otherwise turning hover-click off
 * would have no way to be turned back on via gaze. Everything else in
 * [targets] only responds while [isHoverClickEnabled] returns true. This is
 * deliberately pluggable rather than hard-coded to clickH, so more targets
 * (eventually including WebView page content) can register later.
 */
class GazeCursorOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    companion object {
        private const val TAG = "GazeCursorOverlay"

        private const val DWELL_MS = 1500f
        private const val CLICK_FLASH_MS = 300L
        private const val FADE_DELAY_MS = 500L
        private const val MOVEMENT_THRESHOLD_RAD_S = 0.05f
        private const val ALPHA_LERP_RATE = 0.12f
        private const val DEFAULT_ALPHA = 0.30f // "70% transparent"
        private const val SETTLED_ALPHA = 0.65f // "35% transparent"

        // How far a sustained 1 rad/s turn moves the cursor in one second,
        // in dp. Tune this to taste - there's no real calibration source
        // (no magnetometer fusion), so it's a "feel" number.
        private const val SENSITIVITY_DP_PER_RAD = 700f

        // Flip these if the cursor moves the wrong way on real hardware -
        // depends on which edge of the phone is "up" once it's slotted
        // into the viewer.
        private const val INVERT_X = false
        private const val INVERT_Y = false
    }

    /** Always dwell-clickable, regardless of [isHoverClickEnabled] (e.g. clickH itself). */
    var masterTarget: HoverClickTarget? = null

    /** Dwell-clickable only while [isHoverClickEnabled] returns true. */
    var targets: List<HoverClickTarget> = emptyList()

    /** Gate for [targets] (not [masterTarget], which is always live). */
    var isHoverClickEnabled: () -> Boolean = { true }

    private val density = resources.displayMetrics.density
    private val sensitivityPxPerRad = SENSITIVITY_DP_PER_RAD * density
    private val dotRadiusPx = 10f * density
    private val ringRadiusPx = 16f * density
    private val ringStrokeWidthPx = 3f * density

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var sensorRegistered = false
    private var lastSensorTimestamp = 0L

    private val colorDefault = Color.rgb(33, 150, 243) // material blue
    private val colorClicked = Color.rgb(76, 175, 80) // material green

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStrokeWidthPx
        color = Color.WHITE
    }
    private val ringRect = RectF()

    // Cursor position, in one eye's local space (0..width/2, 0..height).
    private var cursorX = 0f
    private var cursorY = 0f
    private var positionInitialized = false

    // Instantaneous angular speed from the latest gyro sample, rad/s.
    private var angularSpeed = 0f

    private var hoveredTarget: HoverClickTarget? = null
    private var dwellArmed = true
    private var dwellStartMs = 0L
    private var dwellProgress = 0f // 0..1
    private var flashGreenUntilMs = 0L
    private var notClickableSinceMs = 0L

    private var currentAlpha = DEFAULT_ALPHA
    private var targetAlpha = DEFAULT_ALPHA

    init {
        // Purely an overlay - never intercepts touches; MainActivity's
        // GLSurfaceView touch forwarding handles real taps/drags.
        isClickable = false
        isFocusable = false
        if (gyroSensor == null) {
            Log.w(TAG, "No gyroscope on this device - gaze cursor will stay centered.")
        }
    }

    private val frameTick = object : Runnable {
        override fun run() {
            update()
            invalidate()
            if (isAttachedToWindow) postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerSensor()
        postOnAnimation(frameTick)
    }

    override fun onDetachedFromWindow() {
        unregisterSensor()
        super.onDetachedFromWindow()
    }

    /** Call from the host Activity's onPause. */
    fun onHostPause() = unregisterSensor()

    /** Call from the host Activity's onResume. */
    fun onHostResume() = registerSensor()

    private fun registerSensor() {
        if (sensorRegistered) return
        val sensor = gyroSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        sensorRegistered = true
        lastSensorTimestamp = 0L
    }

    private fun unregisterSensor() {
        if (!sensorRegistered) return
        sensorManager.unregisterListener(this)
        sensorRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val now = event.timestamp
        val previous = lastSensorTimestamp
        lastSensorTimestamp = now
        if (previous == 0L) return // first sample after (re)registering: just establish a baseline

        val dt = (now - previous) / 1_000_000_000f // ns -> s
        if (dt <= 0f || dt > 0.5f) return // ignore bogus/huge gaps

        // Landscape-locked headset: map the phone's physical pitch/yaw
        // axes onto screen movement (see INVERT_X/INVERT_Y above if this
        // needs flipping on real hardware).
        val yawRate = event.values[1] * (if (INVERT_X) -1f else 1f)
        val pitchRate = event.values[0] * (if (INVERT_Y) -1f else 1f)

        val halfWidth = width / 2f
        if (halfWidth > 0f) {
            if (!positionInitialized) {
                cursorX = halfWidth / 2f
                cursorY = height / 2f
                positionInitialized = true
            }
            cursorX = (cursorX + yawRate * dt * sensitivityPxPerRad).coerceIn(0f, halfWidth)
            cursorY = (cursorY - pitchRate * dt * sensitivityPxPerRad).coerceIn(0f, height.toFloat())
        }

        angularSpeed = hypot(yawRate.toDouble(), pitchRate.toDouble()).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used.
    }

    private fun currentHoverTarget(xInEye: Float, yInEye: Float): HoverClickTarget? {
        masterTarget?.let { if (it.hitTest(xInEye, yInEye)) return it }
        if (!isHoverClickEnabled()) return null
        return targets.firstOrNull { it.hitTest(xInEye, yInEye) }
    }

    private fun update() {
        val halfWidth = width / 2f
        if (halfWidth <= 0f) return
        val now = SystemClock.elapsedRealtime()

        val target = currentHoverTarget(cursorX, cursorY)
        if (target !== hoveredTarget) {
            hoveredTarget?.onHoverExit()
            target?.onHoverEnter()
            hoveredTarget = target
        }

        val isStill = angularSpeed <= MOVEMENT_THRESHOLD_RAD_S
        if (!isStill) {
            // Real movement always re-arms the dwell mechanic and cancels
            // any fill in progress.
            dwellArmed = true
            dwellStartMs = 0L
            dwellProgress = 0f
        } else if (target != null && dwellArmed) {
            if (dwellStartMs == 0L) dwellStartMs = now
            dwellProgress = ((now - dwellStartMs) / DWELL_MS).coerceIn(0f, 1f)
            if (dwellProgress >= 1f) {
                target.onHoverClick()
                dwellArmed = false // needs fresh movement before it can fire again
                dwellStartMs = 0L
                dwellProgress = 0f
                flashGreenUntilMs = now + CLICK_FLASH_MS
            }
        } else {
            // Still, but nothing to click here, or we already fired and
            // are waiting on movement to re-arm - no ring.
            dwellStartMs = 0L
            dwellProgress = 0f
        }

        targetAlpha = if (target != null) {
            notClickableSinceMs = 0L
            DEFAULT_ALPHA
        } else {
            if (notClickableSinceMs == 0L) notClickableSinceMs = now
            if (now - notClickableSinceMs >= FADE_DELAY_MS) SETTLED_ALPHA else DEFAULT_ALPHA
        }
        currentAlpha += (targetAlpha - currentAlpha) * ALPHA_LERP_RATE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val halfWidth = width / 2f
        if (halfWidth <= 0f || !positionInitialized) return

        val showGreen = SystemClock.elapsedRealtime() < flashGreenUntilMs
        dotPaint.color = if (showGreen) colorClicked else colorDefault
        dotPaint.alpha = (currentAlpha * 255).toInt().coerceIn(0, 255)

        for (eye in 0..1) {
            val eyeLeft = eye * halfWidth
            val cx = eyeLeft + cursorX
            val cy = cursorY

            canvas.drawCircle(cx, cy, dotRadiusPx, dotPaint)

            if (dwellProgress > 0f) {
                ringRect.set(cx - ringRadiusPx, cy - ringRadiusPx, cx + ringRadiusPx, cy + ringRadiusPx)
                canvas.drawArc(ringRect, -90f, 360f * dwellProgress, false, ringPaint)
            }
        }
    }
}
