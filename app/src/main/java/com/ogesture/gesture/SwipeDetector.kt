package com.ogesture.gesture

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.ogesture.data.SwipeDirection
import kotlin.math.abs

/** One recorded point of a touch, in display coordinates. */
data class TouchSample(val x: Float, val y: Float, val timeMs: Long)

class SwipeDetector(
    context: Context,
    private val direction: SwipeDirection,
    private val onShortSwipe: () -> Unit,
    private val onLongSwipe: (() -> Unit)? = null,
    minDistanceDp: Float = 24f,
    private val holdMs: Long = 100L,
    private val maxDurationMs: Long = 1000L,
    holdStillnessDp: Float = 12f,
    private val feedback: Feedback? = null,
    /**
     * Called when a touch the zone consumed ends without firing any action (a tap, a
     * long-press, a drag in the wrong direction...), so the caller can replay it to the
     * UI underneath. Not called for cancelled or multi-finger touches.
     */
    private val onUnusedTouch: ((List<TouchSample>) -> Unit)? = null,
    /** Called at ACTION_DOWN, before anything else: this zone now owns the touch stream. */
    private val onStreamStart: (() -> Unit)? = null,
    /**
     * Called when the stream ends (ACTION_UP or ACTION_CANCEL), after any onShortSwipe /
     * onUnusedTouch callback for it has been dispatched.
     */
    private val onStreamEnd: (() -> Unit)? = null,
) : View.OnTouchListener {

    /** Progress hooks for drawing gesture indicators. All calls happen on the UI thread. */
    interface Feedback {
        fun onStart(rawX: Float, rawY: Float)
        fun onProgress(distancePx: Float, rawX: Float, rawY: Float)
        fun onArmed()
        fun onEnd(fired: Boolean)
    }

    private val density = context.resources.displayMetrics.density
    val minDistancePx = minDistanceDp * density
    private val holdStillnessPx = holdStillnessDp * density

    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var anchorX = 0f
    private var anchorY = 0f
    private var tracking = false
    private var thresholdCrossed = false
    private var longFired = false
    private var anchorView: View? = null
    private val samples = ArrayList<TouchSample>(64)
    private var replayable = false

    // Armed while the finger is stationary after the threshold; any movement beyond
    // holdStillnessPx re-anchors and restarts it, so the hold can happen anywhere
    // along the swipe, not just at the threshold point.
    private val longRunnable = Runnable {
        if (!tracking || !thresholdCrossed || longFired) return@Runnable
        longFired = true
        onLongSwipe?.invoke()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        anchorView = v
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onStreamStart?.invoke()
                reset()
                startX = event.rawX
                startY = event.rawY
                startTime = event.eventTime
                tracking = true
                replayable = onUnusedTouch != null
                addSample(event)
                feedback?.onStart(event.rawX, event.rawY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                addSample(event)
                if (!tracking) return true
                val distance = when (direction) {
                    SwipeDirection.UP -> startY - event.rawY
                    SwipeDirection.RIGHT -> event.rawX - startX
                    SwipeDirection.LEFT -> startX - event.rawX
                }
                feedback?.onProgress(distance, event.rawX, event.rawY)
                if (!thresholdCrossed) {
                    if ((event.eventTime - startTime) > maxDurationMs) {
                        tracking = false
                        feedback?.onEnd(false)
                        return true
                    }
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val triggered = when (direction) {
                        SwipeDirection.UP -> -dy >= minDistancePx && abs(dx) <= -dy
                        SwipeDirection.RIGHT -> dx >= minDistancePx && abs(dy) <= dx
                        SwipeDirection.LEFT -> -dx >= minDistancePx && abs(dy) <= -dx
                    }
                    if (triggered && event.eventTime - startTime >= MIN_SWIPE_MS) {
                        thresholdCrossed = true
                        feedback?.onArmed()
                        if (onLongSwipe != null) {
                            anchorX = event.rawX
                            anchorY = event.rawY
                            v.postDelayed(longRunnable, holdMs)
                        }
                        // The short action fires on ACTION_UP so indicators can show the
                        // armed state (and a long action can still take over).
                    }
                } else {
                    val currentDistance = when (direction) {
                        SwipeDirection.UP -> startY - event.rawY
                        SwipeDirection.RIGHT -> event.rawX - startX
                        SwipeDirection.LEFT -> startX - event.rawX
                    }
                    if (currentDistance < minDistancePx / 2f) {
                        thresholdCrossed = false
                         replayable = false
                        feedback?.onEnd(false)
                    } else if (!longFired) {
                        val moved = abs(event.rawX - anchorX) > holdStillnessPx ||
                            abs(event.rawY - anchorY) > holdStillnessPx
                        if (moved && onLongSwipe != null) {
                            anchorX = event.rawX
                            anchorY = event.rawY
                            v.removeCallbacks(longRunnable)
                            v.postDelayed(longRunnable, holdMs)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPending()
                if (tracking) feedback?.onEnd(false)
                tracking = false
                dropSamples()
                return true
            }
            MotionEvent.ACTION_UP -> {
                addSample(event)
                val crossed = thresholdCrossed
                val wasTracking = tracking
                val didLong = longFired
                cancelPending()
                tracking = false
                val fires = wasTracking && crossed && !didLong
                feedback?.onEnd(fires || didLong)
                if (fires) onShortSwipe()
                if (!fires && !didLong && replayable && samples.isNotEmpty()) {
                    onUnusedTouch?.invoke(samples.toList())
                }
                dropSamples()
                onStreamEnd?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPending()
                if (tracking) feedback?.onEnd(false)
                tracking = false
                dropSamples()
                onStreamEnd?.invoke()
                return true
            }
        }
        return false
    }

    private fun cancelPending() {
        anchorView?.removeCallbacks(longRunnable)
    }

    private fun addSample(event: MotionEvent) {
        if (replayable && samples.size < MAX_SAMPLES) {
            samples.add(TouchSample(event.rawX, event.rawY, event.eventTime))
        }
    }

    private fun dropSamples() {
        samples.clear()
        replayable = false
    }

    private fun reset() {
        cancelPending()
        thresholdCrossed = false
        longFired = false
        dropSamples()
    }

    private companion object {
        const val MAX_SAMPLES = 400
        private const val MIN_SWIPE_MS = 100L
    }
}
