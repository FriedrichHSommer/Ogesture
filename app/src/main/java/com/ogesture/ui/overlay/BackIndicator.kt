package com.ogesture.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.sign
import android.view.HapticFeedbackConstants
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Edge-back indicator for Ogesture.
 *
 * The overlay/window handling remains specific to Ogesture, while the gesture
 * motion model follows the AOSP BackPanel behaviour more closely:
 *
 * - ENTRY -> ACTIVE states
 * - independent horizontal/background/arrow progress
 * - AOSP-style vertical rubber-banding
 * - fling minimum appearance duration
 */
class BackIndicator(
    context: Context,
    private val windowManager: WindowManager,
    private val fromLeftEdge: Boolean,
    private val armDistancePx: Float,
    private val edgeOffsetPx: Int = 0,
    private val zoneLengthPx: Int = 0,
) : OverlayIndicator {

    private val density = context.resources.displayMetrics.density

    private val pillSizePx = PILL_SIZE_DP * density
    private val peekPx = PEEK_DP * density

    private val root = FrameLayout(context)

    private val panel = BackArrowView(context, fromLeftEdge).apply {
        val size = pillSizePx.toInt()

        layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity =
                (if (fromLeftEdge) Gravity.START else Gravity.END) or
                    Gravity.TOP
        }

        alpha = 0f
    }

    private var attached = false
    private var windowHidden = false

    private val windowLocation = IntArray(2)

    private var anchorRawY = 0f
    private var anchorPanelY = 0f

    private var gestureStartTime = 0L

    private var lastProgressTime = 0L
    private var lastProgressDistance = 0f
    private var lastVelocityPxPerSec = 0f

    private var currentState = GestureState.GONE
    private var previousDistancePx = 0f
    private var totalTouchDeltaPx = 0f

    init {
        root.addView(panel)
    }

    override fun attach() {
        if (attached) return

        val params = WindowManager.LayoutParams(
            (pillSizePx + peekPx).toInt(),
            zoneLengthPx + 2 * pillSizePx.toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {

            gravity =
                (if (fromLeftEdge) Gravity.START else Gravity.END) or
                    Gravity.CENTER_VERTICAL

            x = edgeOffsetPx

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

        try {
            windowManager.addView(root, params)
            attached = true
        } catch (_: Throwable) {
            // Indicator is cosmetic; the gesture itself can continue without it.
        }
    }

    override fun detach() {
        if (!attached) return

        try {
            windowManager.removeView(root)
        } catch (_: Throwable) {
        }

        attached = false
    }

    override fun setWindowHidden(hidden: Boolean) {
        if (!attached) return

        windowHidden = hidden

        val lp =
            root.layoutParams as? WindowManager.LayoutParams
                ?: return

        val newAlpha =
            if (hidden) 0f else 1f

        if (lp.alpha == newAlpha) return

        lp.alpha = newAlpha

        try {
            windowManager.updateViewLayout(root, lp)
        } catch (_: Throwable) {
        }
    }

    override fun windowBounds(): Rect? {
        if (!attached || windowHidden) return null

        val loc = IntArray(2)

        root.getLocationOnScreen(loc)

        return Rect(
            loc[0],
            loc[1],
            loc[0] + root.width,
            loc[1] + root.height,
        )
    }

    // ------------------------------------------------------------------------
    // Gesture lifecycle
    // ------------------------------------------------------------------------
   
fun onGestureStart(rawY: Float) {
    panel.resetForGesture()

    panel.alpha = 1f
    panel.scaleX = 1f
    panel.scaleY = 1f

    anchorRawY = rawY
    anchorPanelY = pillY(rawY)

    val now = SystemClock.uptimeMillis()

    gestureStartTime = now
    lastProgressTime = now
    lastProgressDistance = 0f
    lastVelocityPxPerSec = 0f
    previousDistancePx = 0f
    totalTouchDeltaPx = 0f

    panel.translationY = clampPanelY(anchorPanelY)

    currentState = GestureState.ENTRY
}

    fun onGestureProgress(
        distancePx: Float,
        rawY: Float,
    ) {
        updateVelocity(distancePx)

        val xDelta = distancePx - previousDistancePx
        previousDistancePx = distancePx

        if (abs(xDelta) > 0f) {
            if (sign(xDelta) == sign(totalTouchDeltaPx)) {
                totalTouchDeltaPx += xDelta
            } else {
                totalTouchDeltaPx = xDelta
            }
        }

        val yOffset = rawY - anchorRawY
        val yTranslation = abs(yOffset)

        val minDeltaForSwitch =
            32f * density

        when (currentState) {
            GestureState.ACTIVE -> {
                if (
                    (totalTouchDeltaPx < 0f &&
                        -totalTouchDeltaPx > minDeltaForSwitch) ||
                    (yTranslation > distancePx * 2f)
                ) {
                    deactivate(distancePx)
                }
            }

            GestureState.INACTIVE -> {
                if (
                    totalTouchDeltaPx > 0f &&
                        totalTouchDeltaPx > minDeltaForSwitch
                ) {
                    totalTouchDeltaPx = 0f

                    panel.activate {
                        currentState = GestureState.ACTIVE
                    }
                }
            }

            else -> {
                // ENTRY / FLUNG / COMMITTED / CANCELLED / GONE
            }
        }

        panel.translationY =
            clampPanelY(
                rubberBandPanelY(rawY)
            )

when (currentState) {
    GestureState.ENTRY -> {
        val gestureProgress =
            (distancePx / armDistancePx)
                .coerceIn(0f, 1f)

        val horizontalProgress =
            RUBBER_BAND_INTERPOLATOR.getInterpolation(
                gestureProgress
            )

        val squareProgress =
            (gestureProgress / 0.62f)
                .coerceIn(0f, 1f)

        val arrowProgress =
            RUBBER_BAND_INTERPOLATOR.getInterpolation(
                gestureProgress
            )

        panel.setVisualState(
            horizontalProgress = horizontalProgress,
            backgroundProgress = squareProgress,
            arrowProgress = arrowProgress,
        )

        panel.setShapeProgress(0f)
    }

GestureState.ACTIVE -> {
    panel.setVisualState(
        horizontalProgress = 1f,
        backgroundProgress = 1f,
        arrowProgress = 1f,
    )
}

    GestureState.INACTIVE -> {
        val gestureProgress =
            (distancePx / armDistancePx)
                .coerceIn(0f, 1f)

        val progress =
            (gestureProgress / 0.62f)
                .coerceIn(0f, 1f)

        panel.setVisualState(
            horizontalProgress =
                RUBBER_BAND_INTERPOLATOR
                    .getInterpolation(gestureProgress),

            backgroundProgress = progress,

            arrowProgress =
                RUBBER_BAND_INTERPOLATOR
                    .getInterpolation(gestureProgress),
        )

        panel.setShapeProgress(0f)
    }

    else -> {
    }
}
}

private fun deactivate(
    distancePx: Float,
) {
    if (currentState != GestureState.ACTIVE) return

    currentState = GestureState.INACTIVE

    val gestureProgress =
        (distancePx / armDistancePx)
            .coerceIn(0f, 1f)

    val compressedBackgroundProgress =
        (gestureProgress / 0.62f)
            .coerceIn(0f, 1f)

    panel.deactivate(
        compressedBackgroundProgress
    )
}

fun onArmed() {
        if (currentState == GestureState.ACTIVE) return

        totalTouchDeltaPx = 0f

        panel.activate {
            currentState = GestureState.ACTIVE
        }
    }

    fun onGestureEnd(fired: Boolean) {
        val now = SystemClock.uptimeMillis()

        val gestureDuration =
            now - gestureStartTime

        val shouldCommit =
            fired && currentState == GestureState.ACTIVE

        val isFling =
            shouldCommit &&
            abs(lastVelocityPxPerSec) >= MIN_FLING_VELOCITY

        when {
            isFling -> finishFling(gestureDuration)
            shouldCommit -> commitGesture()
            else -> cancelGesture()
        }
    }

    // ------------------------------------------------------------------------
    // End states
    // ------------------------------------------------------------------------

    private fun finishFling(
        gestureDuration: Long,
    ) {
        currentState = GestureState.FLUNG

        val remaining =
            (FLING_MIN_APPEARANCE_DURATION - gestureDuration)
                .coerceAtLeast(0L)

        /*
         * Important:
         *
         * Unlike the old version, this is a real property animation.
         * The panel continues from its CURRENT visual state toward ACTIVE.
         */

        panel.animateToState(
            horizontalProgress = 1f,
            backgroundProgress = 1f,
            arrowProgress = 1f,
            duration = remaining,
        ) {
            commitGesture()
        }
    }

    private fun commitGesture() {
        currentState = GestureState.COMMITTED

        val retractX =
            if (fromLeftEdge) {
                -pillSizePx
            } else {
                pillSizePx
            }

        panel.animate()
            .translationX(retractX)
            .alpha(0f)
            .setDuration(COMMIT_DURATION)
            .setInterpolator(
                DecelerateInterpolator(2f)
            )
            .withEndAction {
                currentState = GestureState.GONE
            }
            .start()
    }

    private fun cancelGesture() {
        currentState = GestureState.CANCELLED

        val retractX =
            if (fromLeftEdge) {
                -pillSizePx
            } else {
                pillSizePx
            }

        panel.animate()
            .translationX(retractX)
            .alpha(0f)
            .setDuration(CANCEL_DURATION)
            .setInterpolator(
                DecelerateInterpolator(2f)
            )
            .withEndAction {
                currentState = GestureState.GONE
            }
            .start()
    }

    // ------------------------------------------------------------------------
    // Velocity
    // ------------------------------------------------------------------------

    private fun updateVelocity(
        distancePx: Float,
    ) {
        val now = SystemClock.uptimeMillis()

        val dt =
            now - lastProgressTime

        if (dt > 0L) {
            val distanceDelta =
                distancePx - lastProgressDistance

            lastVelocityPxPerSec =
                distanceDelta / dt.toFloat() * 1000f
        }

        lastProgressTime = now
        lastProgressDistance = distancePx
    }

    // ------------------------------------------------------------------------
    // Vertical positioning
    // ------------------------------------------------------------------------

    private fun rubberBandPanelY(
        rawY: Float,
    ): Float {

        val fingerOffset =
            rawY - anchorRawY

        /*
         * The maximum normal vertical movement is approximately half of the
         * available indicator window. Larger finger movement is compressed
         * through the AOSP rubber-band curve.
         */

        val availableRange =
            ((root.height - pillSizePx) / 2f)
                .coerceAtLeast(1f)

        val progress =
            (
                abs(fingerOffset) /
                    (availableRange * RUBBER_BAND_AMOUNT)
                )
                .coerceIn(0f, 1f)

        val rubberBandDistance =
            RUBBER_BAND_INTERPOLATOR.getInterpolation(progress) *
                availableRange *
                sign(fingerOffset)

        return anchorPanelY + rubberBandDistance
    }

    private fun clampPanelY(
        y: Float,
    ): Float {
        return y.coerceIn(
            0f,
            (root.height - pillSizePx)
                .coerceAtLeast(0f),
        )
    }

    /**
     * rawY is in display coordinates; convert it into overlay coordinates.
     *
     * The indicator remains slightly above the finger, matching the behaviour
     * you observed from the Pixel system gesture.
     */
    private fun pillY(
        rawY: Float,
    ): Float {

        root.getLocationOnScreen(windowLocation)

        return rawY -
            windowLocation[1] -
            pillSizePx / 2f -
            48f * density
    }

    private enum class GestureState {
        GONE,
        ENTRY,
        ACTIVE,
        INACTIVE,
        FLUNG,
        COMMITTED,
        CANCELLED,
    }

    private companion object {

        const val PILL_SIZE_DP = 48f
        const val PEEK_DP = 18f

        // const val ACTIVE_THRESHOLD = 0.55f

        const val RUBBER_BAND_AMOUNT = 15f

        const val MIN_FLING_VELOCITY = 3000f
        const val FLING_MIN_APPEARANCE_DURATION = 320L

        const val COMMIT_DURATION = 180L
        const val CANCEL_DURATION = 220L

        val RUBBER_BAND_INTERPOLATOR =
            PathInterpolator(
                0.2f,
                1f,
                1f,
                1f,
            )

        val DECELERATE_INTERPOLATOR =
            DecelerateInterpolator()
    }
}

/**
 * Visual component of the back gesture indicator.
 *
 * Each visual property is controlled independently instead of deriving the
 * entire shape from a single revealProgress value.
 */

private class BackArrowView(
    context: Context,
    private val fromLeftEdge: Boolean,
) : View(context) {

    private val density =
        context.resources.displayMetrics.density

    private val backgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            alpha = 255
        }

    private val arrowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.ROUND
        }

    /*
     * ------------------------------------------------------------------------
     * AOSP-style independent animated properties
     * ------------------------------------------------------------------------
     *
     * These deliberately do NOT share one "shapeProgress".
     *
     * Width, height, corner radius, scale and translation are independent,
     * following the structure of AOSP BackPanel.
     */

    private val backgroundWidth =
        AnimatedFloat(
            name = "backgroundWidth",
            minimumValue = 0f,
        )

    private val backgroundHeight =
        AnimatedFloat(
            name = "backgroundHeight",
            minimumValue = 0f,
        )

    private val backgroundEdgeCornerRadius =
        AnimatedFloat(
            name = "backgroundEdgeCornerRadius",
            minimumValue = 0f,
        )

    private val backgroundFarCornerRadius =
        AnimatedFloat(
            name = "backgroundFarCornerRadius",
            minimumValue = 0f,
        )

    private val scale =
        AnimatedFloat(
            name = "scale",
            minimumValue = 0f,
        )

    private val scalePivotX =
        AnimatedFloat(
            name = "scalePivotX",
            minimumValue = 0f,
        )

    private val horizontalTranslation =
        AnimatedFloat(
            name = "horizontalTranslation",
        )

    private val verticalTranslation =
        AnimatedFloat(
            name = "verticalTranslation",
        )

    private val arrowLength =
        AnimatedFloat(
            name = "arrowLength",
            minimumValue = 0f,
        )

    private val arrowHeight =
        AnimatedFloat(
            name = "arrowHeight",
            minimumValue = 0f,
        )

    private val arrowAlpha =
        AnimatedFloat(
            name = "arrowAlpha",
            minimumValue = 0f,
            maximumValue = 1f,
        )

    private val backgroundAlpha =
        AnimatedFloat(
            name = "backgroundAlpha",
            minimumValue = 0f,
            maximumValue = 1f,
        )

    /*
     * Gesture-controlled resting values.
     *
     * These correspond to the current Ogesture progress model.
     */
    private var horizontalProgress = 0f
    private var backgroundProgress = 0f
    private var arrowProgress = 0f

    /*
     * Animation generation.
     *
     * A cancelled activation must never continue into the
     * "move inward -> become circle" phase.
     */
    private var animationGeneration = 0L

    /*
     * ------------------------------------------------------------------------
     * AOSP-inspired dimensions
     * ------------------------------------------------------------------------
     */

    private val fullSize =
        48f * density

    /*
     * Initial compressed state.
     *
     * This intentionally remains a rounded rectangle rather than a
     * tiny capsule.
     */
    private val compressedWidth =
        fullSize * 0.17f

    private val compressedHeight =
        fullSize * 0.78f

    /*
     * The ACTIVE resting shape is exactly 48 x 48.
     *
     * This is important:
     *
     * rounded square = 48 x 48
     * circle         = 48 x 48
     *
     * The transition between them never changes these dimensions.
     */
    private val activeWidth =
        fullSize

    private val activeHeight =
        fullSize

    private val squareCornerRadius =
        fullSize * 0.32f

    private val circleCornerRadius =
        fullSize / 2f

    /*
     * Base edge margin used while following the gesture.
     *
     * This preserves the existing Ogesture positioning model.
     */
    private val edgeMargin =
        4f * density

    private val activeMargin =
        14f * density

    /*
     * After the ACTIVE pop has finished, the 48 x 48 background moves
     * slightly toward the center of the screen.
     *
     * This is intentionally separate from the pop.
     */
    private val circleAdditionalInset =
        8f * density

    /*
     * ------------------------------------------------------------------------
     * AOSP-style spring properties
     * ------------------------------------------------------------------------
     *
     * AOSP's AnimatedFloat uses SpringAnimation + SpringForce.
     * We use the same mechanism here.
     */

    private val defaultSpring =
        SpringForce().apply {
            dampingRatio =
                SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            stiffness =
                SpringForce.STIFFNESS_MEDIUM
        }

    /*
     * A slightly less bouncy spring for the final circle morph.
     *
     * The pop itself uses the AOSP-style medium-bouncy spring.
     */
    private val settleSpring =
        SpringForce().apply {
            dampingRatio =
                SpringForce.DAMPING_RATIO_LOW_BOUNCY
            stiffness =
                SpringForce.STIFFNESS_MEDIUM
        }

    init {
        val resources =
            context.resources

        val backgroundId =
            resources.getIdentifier(
                if (isNightMode(resources)) {
                    "system_accent2_700"
                } else {
                    "system_accent2_100"
                },
                "color",
                "android",
            )

        val arrowId =
            resources.getIdentifier(
                if (isNightMode(resources)) {
                    "system_accent1_200"
                } else {
                    "system_accent1_700"
                },
                "color",
                "android",
            )

        backgroundPaint.color =
            if (backgroundId != 0) {
                context.getColor(backgroundId)
            } else {
                0xFFD3D9B7.toInt()
            }

        arrowPaint.color =
            if (arrowId != 0) {
                context.getColor(arrowId)
            } else {
                0xFF3E4229.toInt()
            }

        /*
         * Initial resting values.
         */
        backgroundWidth.snapTo(0f)
        backgroundHeight.snapTo(0f)

        backgroundEdgeCornerRadius.snapTo(0f)
        backgroundFarCornerRadius.snapTo(0f)

        scale.snapTo(1f)
        scalePivotX.snapTo(0f)

        horizontalTranslation.snapTo(
            edgeMargin
        )
        verticalTranslation.snapTo(0f)

        arrowLength.snapTo(0f)
        arrowHeight.snapTo(0f)

        arrowAlpha.snapTo(0f)
        backgroundAlpha.snapTo(0f)

        applySpring(defaultSpring)
    }

    private fun isNightMode(
        resources: android.content.res.Resources,
    ): Boolean {
        return (
            resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            ) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /*
     * ------------------------------------------------------------------------
     * AOSP-style AnimatedFloat
     * ------------------------------------------------------------------------
     *
     * This follows the structure of AOSP BackPanel.AnimatedFloat:
     *
     * restingPosition
     * current pos
     * SpringAnimation
     * snapTo()
     * stretchTo()
     * updateRestingPosition()
     */

    private inner class AnimatedFloat(
        private val name: String,
        private val minimumValue: Float? = null,
        private val maximumValue: Float? = null,
    ) {

        private var restingPosition = 0f

        var pos = 0f
            private set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        private val animation: SpringAnimation

        init {
            val property =
                object : FloatPropertyCompat<AnimatedFloat>(
                    name
                ) {
                    override fun setValue(
                        animatedFloat: AnimatedFloat,
                        value: Float,
                    ) {
                        animatedFloat.pos = value
                    }

                    override fun getValue(
                        animatedFloat: AnimatedFloat,
                    ): Float {
                        return animatedFloat.pos
                    }
                }

            animation =
                SpringAnimation(
                    this,
                    property,
                ).apply {
                    spring =
                        SpringForce().apply {
                            dampingRatio =
                                SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                            stiffness =
                                SpringForce.STIFFNESS_MEDIUM
                        }

                    this@AnimatedFloat.minimumValue?.let {
                        setMinValue(it)
                    }

                    this@AnimatedFloat.maximumValue?.let {
                        setMaxValue(it)
                    }
                }
        }

        fun snapTo(
            newPosition: Float,
        ) {
            animation.cancel()

            restingPosition =
                newPosition

            animation.spring.finalPosition =
                newPosition

            pos =
                newPosition
        }

        fun updateRestingPosition(
            newPosition: Float,
            animated: Boolean = true,
        ) {
            restingPosition =
                newPosition

            if (animated) {
                animation.animateToFinalPosition(
                    newPosition
                )
            } else {
                snapTo(newPosition)
            }
        }

        /*
         * Same conceptual operation as AOSP AnimatedFloat.stretchTo().
         */
        fun stretchTo(
            stretchAmount: Float,
            startingVelocity: Float? = null,
            springForce: SpringForce? = null,
        ) {
            animation.apply {
                startingVelocity?.let {
                    cancel()
                    setStartVelocity(it)
                }

                springForce?.let {
                    spring = it
                }

                animateToFinalPosition(
                    restingPosition + stretchAmount
                )
            }
        }

        fun setSpring(
            springForce: SpringForce,
        ) {
            animation.cancel()
            animation.spring =
                springForce
        }

        fun addEndListener(
            listener: (
                canceled: Boolean,
            ) -> Unit,
        ) {
            animation.addEndListener {
                    _,
                    canceled,
                    _,
                    _,
                ->
                listener(canceled)
            }
        }

        fun cancel() {
            animation.cancel()
        }
    }

    private fun applySpring(
        spring: SpringForce,
    ) {
        backgroundWidth.setSpring(spring)
        backgroundHeight.setSpring(spring)
        backgroundEdgeCornerRadius.setSpring(spring)
        backgroundFarCornerRadius.setSpring(spring)
        scale.setSpring(spring)
        scalePivotX.setSpring(spring)
        horizontalTranslation.setSpring(spring)
        verticalTranslation.setSpring(spring)
        arrowLength.setSpring(spring)
        arrowHeight.setSpring(spring)
        arrowAlpha.setSpring(spring)
        backgroundAlpha.setSpring(spring)
    }

    /*
     * ------------------------------------------------------------------------
     * Gesture reset
     * ------------------------------------------------------------------------
     */

    fun resetForGesture() {
        animationGeneration++

        cancelAnimations()

        horizontalProgress = 0f
        backgroundProgress = 0f
        arrowProgress = 0f

        backgroundWidth.snapTo(0f)
        backgroundHeight.snapTo(0f)

        backgroundEdgeCornerRadius.snapTo(
            squareCornerRadius
        )
        backgroundFarCornerRadius.snapTo(
            squareCornerRadius
        )

        scale.snapTo(1f)
        scalePivotX.snapTo(0f)

        horizontalTranslation.snapTo(
            edgeMargin
        )
        verticalTranslation.snapTo(0f)

        arrowLength.snapTo(0f)
        arrowHeight.snapTo(0f)

        arrowAlpha.snapTo(0f)
        backgroundAlpha.snapTo(0f)

        invalidate()
    }

    /*
     * Kept for compatibility with the existing BackIndicator state machine.
     *
     * Shape is now controlled by independent corner properties, so this
     * method intentionally does nothing.
     */
    fun setShapeProgress(
        progress: Float,
    ) {
        // Intentionally unused.
    }

    /*
     * ------------------------------------------------------------------------
     * Direct gesture state
     * ------------------------------------------------------------------------
     */

    fun setVisualState(
        horizontalProgress: Float,
        backgroundProgress: Float,
        arrowProgress: Float,
    ) {
        this.horizontalProgress =
            horizontalProgress.coerceIn(0f, 1f)

        this.backgroundProgress =
            backgroundProgress.coerceIn(0f, 1f)

        this.arrowProgress =
            arrowProgress.coerceIn(0f, 1f)

        /*
         * Direct gesture updates must cancel any physics animation.
         */
        cancelAnimations()

        val widthProgress =
            AOSP_ENTRY_WIDTH_INTERPOLATOR
                .getInterpolation(
                    this.backgroundProgress
                )
                .coerceIn(0f, 1f)

        val heightProgress =
            AOSP_ENTRY_HEIGHT_INTERPOLATOR
                .getInterpolation(
                    this.backgroundProgress
                )
                .coerceIn(0f, 1f)

        val currentWidth =
            compressedWidth +
                (activeWidth - compressedWidth) *
                widthProgress

        val currentHeight =
            compressedHeight +
                (activeHeight - compressedHeight) *
                heightProgress

        val cornerProgress =
            this.backgroundProgress

        val edgeCorner =
            interpolate(
                compressedHeight * 0.32f,
                squareCornerRadius,
                cornerProgress,
            )

        val farCorner =
            interpolate(
                compressedHeight * 0.32f,
                squareCornerRadius,
                cornerProgress,
            )

        backgroundWidth.snapTo(
            currentWidth
        )

        backgroundHeight.snapTo(
            currentHeight
        )

        backgroundEdgeCornerRadius.snapTo(
            edgeCorner.coerceAtMost(
                currentHeight / 2f
            )
        )

        backgroundFarCornerRadius.snapTo(
            farCorner.coerceAtMost(
                currentHeight / 2f
            )
        )

        scale.snapTo(1f)

        scalePivotX.snapTo(
            currentWidth / 2f
        )

        val margin =
            edgeMargin +
                (activeMargin - edgeMargin) *
                this.horizontalProgress

        horizontalTranslation.snapTo(
            margin
        )

        verticalTranslation.snapTo(0f)

        val visibleArrowProgress =
            (
                (this.arrowProgress - 0.18f) /
                    0.82f
                )
                    .coerceIn(0f, 1f)

        arrowLength.snapTo(
            interpolate(
                fullSize * 0.10f,
                fullSize * 0.26f,
                visibleArrowProgress,
            )
        )

        arrowHeight.snapTo(
            interpolate(
                fullSize * 0.05f,
                fullSize * 0.13f,
                visibleArrowProgress,
            )
        )

        arrowAlpha.snapTo(
            visibleArrowProgress
        )

        backgroundAlpha.snapTo(
            this.backgroundProgress
        )

        invalidate()
    }

    /*
     * ------------------------------------------------------------------------
     * ACTIVE transition
     * ------------------------------------------------------------------------
     *
     * Required sequence:
     *
     * 1. 48 x 48 rounded square
     * 2. AOSP-style pop at that position
     * 3. Move the complete 48 x 48 shape inward
     * 4. Only then morph the corners into a circle
     *
     * Width and height remain 48 x 48 during steps 3 and 4.
     */

    fun activate(
        onActivated: (() -> Unit)? = null,
    ) {
        val generation =
            ++animationGeneration

        cancelAnimations()

        /*
         * The ACTIVE starting state is explicitly forced to
         * a complete 48 x 48 rounded square.
         */
        horizontalProgress = 1f
        backgroundProgress = 1f
        arrowProgress = 1f

        backgroundWidth.snapTo(
            activeWidth
        )

        backgroundHeight.snapTo(
            activeHeight
        )

        backgroundEdgeCornerRadius.snapTo(
            squareCornerRadius
        )

        backgroundFarCornerRadius.snapTo(
            squareCornerRadius
        )

        scale.snapTo(1f)

        scalePivotX.snapTo(
            activeWidth / 2f
        )

        horizontalTranslation.snapTo(
            activeMargin
        )

        verticalTranslation.snapTo(0f)

        arrowLength.snapTo(
            fullSize * 0.26f
        )

        arrowHeight.snapTo(
            fullSize * 0.13f
        )

        arrowAlpha.snapTo(1f)
        backgroundAlpha.snapTo(1f)

        performHapticFeedback(
            HapticFeedbackConstants.CONFIRM
        )

        /*
         * AOSP's popOffEdge():
         *
         * heightStretchAmount = velocity * 50
         * widthStretchAmount  = velocity * 150
         * scaleStretchAmount  = velocity * 0.8
         *
         * We use the same relationship, with a small fixed activation
         * velocity because Ogesture does not have AOSP's internal
         * BackPanelController velocity source at this point.
         */
        val startingVelocity =
            0.045f

        val heightStretchAmount =
            startingVelocity * 50f * density

        val widthStretchAmount =
            startingVelocity * 150f * density

        val scaleStretchAmount =
            startingVelocity * 0.8f

        var widthFinished = false
        var heightFinished = false
        var scaleFinished = false

        fun continueAfterPop() {
            if (generation != animationGeneration) {
                return
            }

            if (!widthFinished ||
                !heightFinished ||
                !scaleFinished
            ) {
                return
            }

            startMoveToCirclePosition(
                generation
            )
        }

        backgroundWidth.addEndListener { canceled ->
            if (!canceled) {
                widthFinished = true
                continueAfterPop()
            }
        }

        backgroundHeight.addEndListener { canceled ->
            if (!canceled) {
                heightFinished = true
                continueAfterPop()
            }
        }

        scale.addEndListener { canceled ->
            if (!canceled) {
                scaleFinished = true
                continueAfterPop()
            }
        }

        /*
         * This is intentionally the same three-property pop structure
         * as AOSP BackPanel.popOffEdge().
         */
        backgroundHeight.stretchTo(
            stretchAmount = 0f,
            startingVelocity = -heightStretchAmount,
        )

        backgroundWidth.stretchTo(
            stretchAmount = 0f,
            startingVelocity = widthStretchAmount,
        )

        scale.stretchTo(
            stretchAmount = 0f,
            startingVelocity = -scaleStretchAmount,
        )

        invalidate()

        onActivated?.invoke()
    }

    /*
     * ------------------------------------------------------------------------
     * After pop: move inward, then become a circle.
     * ------------------------------------------------------------------------
     */

    private fun startMoveToCirclePosition(
        generation: Long,
    ) {
        if (generation != animationGeneration) {
            return
        }

        /*
         * Keep the background exactly 48 x 48.
         */
        backgroundWidth.snapTo(
            activeWidth
        )

        backgroundHeight.snapTo(
            activeHeight
        )

        scale.snapTo(1f)

        scalePivotX.snapTo(
            activeWidth / 2f
        )

        /*
         * Move toward the center.
         *
         * Left edge: positive X.
         * Right edge: negative X.
         */
        val targetTranslation =
            activeMargin +
                circleAdditionalInset

        horizontalTranslation.updateRestingPosition(
            targetTranslation,
            animated = true,
        )

        /*
         * The circle morph starts after the inward movement has begun.
         *
         * Width and height are NOT animated here.
         */
        horizontalTranslation.addEndListener { canceled ->
            if (canceled) return@addEndListener

            if (generation != animationGeneration) {
                return@addEndListener
            }

            startCircleMorph(
                generation
            )
        }

        invalidate()
    }

    private fun startCircleMorph(
        generation: Long,
    ) {
        if (generation != animationGeneration) {
            return
        }

        /*
         * Width and height remain locked at 48 x 48.
         */
        backgroundWidth.snapTo(
            activeWidth
        )

        backgroundHeight.snapTo(
            activeHeight
        )

        scale.snapTo(1f)

        scalePivotX.snapTo(
            activeWidth / 2f
        )

        /*
         * Only the corner radii change here.
         */
        backgroundEdgeCornerRadius.updateRestingPosition(
            circleCornerRadius,
            animated = true,
        )

        backgroundFarCornerRadius.updateRestingPosition(
            circleCornerRadius,
            animated = true,
        )

        invalidate()
    }

    /*
     * ------------------------------------------------------------------------
     * INACTIVE / cancel
     * ------------------------------------------------------------------------
     *
     * Directly turn the active circle into a compressed rounded rectangle.
     * There is deliberately no full-square intermediate state.
     */

    fun deactivate(
        compressedBackgroundProgress: Float,
    ) {
        val generation =
            ++animationGeneration

        cancelAnimations()

        val progress =
            compressedBackgroundProgress
                .coerceIn(0f, 1f)

        /*
         * The desired cancel state is the compressed rounded rectangle.
         *
         * Width and height are immediately assigned from the current
         * gesture progress, while the corners are immediately returned
         * to rounded-square values.
         */
        val widthProgress =
            AOSP_ENTRY_WIDTH_INTERPOLATOR
                .getInterpolation(progress)
                .coerceIn(0f, 1f)

        val heightProgress =
            AOSP_ENTRY_HEIGHT_INTERPOLATOR
                .getInterpolation(progress)
                .coerceIn(0f, 1f)

        val targetWidth =
            compressedWidth +
                (activeWidth - compressedWidth) *
                widthProgress

        val targetHeight =
            compressedHeight +
                (activeHeight - compressedHeight) *
                heightProgress

        backgroundWidth.snapTo(
            targetWidth
        )

        backgroundHeight.snapTo(
            targetHeight
        )

        val targetCorner =
            targetHeight * 0.32f

        backgroundEdgeCornerRadius.snapTo(
            targetCorner.coerceAtMost(
                targetHeight / 2f
            )
        )

        backgroundFarCornerRadius.snapTo(
            targetCorner.coerceAtMost(
                targetHeight / 2f
            )
        )

        scale.snapTo(1f)

        scalePivotX.snapTo(
            targetWidth / 2f
        )

        /*
         * Keep the current edge position rather than using jumpOffset.
         */
        horizontalTranslation.snapTo(
            edgeMargin +
                (activeMargin - edgeMargin) *
                horizontalProgress
        )

        /*
         * Arrow stays consistent with the compressed shape.
         */
        arrowLength.snapTo(
            fullSize * 0.26f *
                arrowProgress
        )

        arrowHeight.snapTo(
            fullSize * 0.13f *
                arrowProgress
        )

        arrowAlpha.snapTo(
            arrowProgress
        )

        backgroundAlpha.snapTo(1f)

        /*
         * AOSP-style outward spring.
         *
         * We use the same independent-property idea as popOffEdge(),
         * but the resting position is the already-compressed state.
         */
        val outwardVelocity =
            0.04f

        val translationStretch =
            5f * density

        horizontalTranslation.stretchTo(
            stretchAmount =
                if (fromLeftEdge) {
                    -translationStretch
                } else {
                    translationStretch
                },
            startingVelocity =
                if (fromLeftEdge) {
                    -outwardVelocity * 100f
                } else {
                    outwardVelocity * 100f
                },
        )

        backgroundAlpha.stretchTo(
            stretchAmount = -1f,
            startingVelocity = -outwardVelocity
        )

        /*
         * Ensure this generation does not continue into any old
         * circle-morph callback.
         */
        if (generation != animationGeneration) {
            return
        }

        invalidate()
    }

    /*
     * ------------------------------------------------------------------------
     * Fling continuation
     * ------------------------------------------------------------------------
     */

    fun animateToState(
        horizontalProgress: Float,
        backgroundProgress: Float,
        arrowProgress: Float,
        duration: Long,
        onEnd: () -> Unit,
    ) {
        if (duration <= 0L) {
            setVisualState(
                horizontalProgress,
                backgroundProgress,
                arrowProgress,
            )
            onEnd()
            return
        }

        /*
         * Keep this path simple and deterministic.
         *
         * The fast fling only needs to reach the ACTIVE geometry.
         */
        val startGeneration =
            ++animationGeneration

        cancelAnimations()

        val startHorizontal =
            this.horizontalProgress

        val startBackground =
            this.backgroundProgress

        val startArrow =
            this.arrowProgress

        val animator =
            ValueAnimator.ofFloat(
                0f,
                1f,
            ).apply {
                this.duration = duration
                interpolator =
                    DecelerateInterpolator()

                addUpdateListener {
                    if (startGeneration != animationGeneration) {
                        return@addUpdateListener
                    }

                    val progress =
                        it.animatedValue as Float

                    val interpolated =
                        DECELERATE_INTERPOLATOR
                            .getInterpolation(progress)

                    setVisualState(
                        horizontalProgress =
                            interpolate(
                                startHorizontal,
                                horizontalProgress,
                                interpolated,
                            ),
                        backgroundProgress =
                            interpolate(
                                startBackground,
                                backgroundProgress,
                                interpolated,
                            ),
                        arrowProgress =
                            interpolate(
                                startArrow,
                                arrowProgress,
                                interpolated,
                            ),
                    )
                }

                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(
                            animation: Animator,
                        ) {
                            if (
                                startGeneration ==
                                    animationGeneration
                            ) {
                                onEnd()
                            }
                        }
                    }
                )
            }

        animator.start()
    }

    /*
     * ------------------------------------------------------------------------
     * Animation cancellation
     * ------------------------------------------------------------------------
     */

    private fun cancelAnimations() {
        backgroundWidth.cancel()
        backgroundHeight.cancel()
        backgroundEdgeCornerRadius.cancel()
        backgroundFarCornerRadius.cancel()
        scale.cancel()
        scalePivotX.cancel()
        horizontalTranslation.cancel()
        verticalTranslation.cancel()
        arrowLength.cancel()
        arrowHeight.cancel()
        arrowAlpha.cancel()
        backgroundAlpha.cancel()
    }

    /*
     * ------------------------------------------------------------------------
     * Drawing
     * ------------------------------------------------------------------------
     *
     * This intentionally follows AOSP BackPanel.onDraw():
     *
     * canvas translation
     *      ↓
     * scale around scalePivotX
     *      ↓
     * draw backgroundWidth x backgroundHeight
     *      ↓
     * independently rounded corners
     *      ↓
     * center arrow using backgroundWidth
     */

    override fun onDraw(
        canvas: Canvas,
    ) {
        super.onDraw(canvas)

        val canvasWidth =
            width.toFloat()

        val halfHeight =
            backgroundHeight.pos / 2f

        val currentWidth =
            backgroundWidth.pos

        val edgeCorner =
            backgroundEdgeCornerRadius.pos

        val farCorner =
            backgroundFarCornerRadius.pos

        canvas.save()

        /*
         * Mirror the drawing coordinate system for the right edge.
         */
        if (!fromLeftEdge) {
            canvas.scale(
                -1f,
                1f,
                canvasWidth / 2f,
                0f,
            )
        }

        canvas.translate(
            horizontalTranslation.pos,
            height * 0.5f +
                verticalTranslation.pos,
        )

        canvas.scale(
            scale.pos,
            scale.pos,
            scalePivotX.pos,
            0f,
        )

        val rect =
            RectF(
                0f,
                -halfHeight,
                currentWidth,
                halfHeight,
            )

        val radii =
            floatArrayOf(
                edgeCorner,
                edgeCorner,

                farCorner,
                farCorner,

                farCorner,
                farCorner,

                edgeCorner,
                edgeCorner,
            )

        val backgroundPath =
            Path().apply {
                addRoundRect(
                    rect,
                    radii,
                    Path.Direction.CW,
                )
            }

        backgroundPaint.alpha =
            (
                255f *
                    backgroundAlpha.pos
            )
                .toInt()
                .coerceIn(0, 255)

        canvas.drawPath(
            backgroundPath,
            backgroundPaint,
        )

        /*
         * AOSP centers the arrow using:
         *
         * (backgroundWidth - arrowLength) / 2
         */
        val dx =
            arrowLength.pos

        val dy =
            arrowHeight.pos

        val arrowOffset =
            (currentWidth - dx) / 2f

        canvas.translate(
            arrowOffset,
            0f,
        )

        arrowPaint.alpha =
            (
                255f *
                    minOf(
                        arrowAlpha.pos,
                        backgroundAlpha.pos,
                    )
            )
                .toInt()
                .coerceIn(0, 255)

        if (
            arrowPaint.alpha > 0 &&
            dx > 0f &&
            dy > 0f
        ) {
            val arrowPath =
                Path().apply {
                    moveTo(
                        dx,
                        -dy,
                    )

                    lineTo(
                        0f,
                        0f,
                    )

                    lineTo(
                        dx,
                        dy,
                    )

                    moveTo(
                        dx,
                        -dy,
                    )
                }

            /*
             * The path above points inward for the left panel.
             * Mirror it for the right panel after the canvas itself
             * has been mirrored.
             */
            if (!fromLeftEdge) {
                canvas.scale(
                    -1f,
                    1f,
                    0f,
                    0f,
                )

                canvas.translate(
                    -dx,
                    0f,
                )
            }

            canvas.drawPath(
                arrowPath,
                arrowPaint,
            )
        }

        canvas.restore()
    }

    override fun hasOverlappingRendering(): Boolean {
        return false
    }

    /*
     * ------------------------------------------------------------------------
     * Small helpers
     * ------------------------------------------------------------------------
     */

    private fun interpolate(
        start: Float,
        end: Float,
        progress: Float,
    ): Float {
        return start +
            (end - start) *
            progress.coerceIn(0f, 1f)
    }

    private val AOSP_ENTRY_WIDTH_INTERPOLATOR =
        PathInterpolator(
            0.19f,
            1.27f,
            0.71f,
            0.86f,
        )

    private val AOSP_ENTRY_HEIGHT_INTERPOLATOR =
        PathInterpolator(
            1f,
            0.05f,
            0.9f,
            -0.29f,
        )

    private val DECELERATE_INTERPOLATOR =
        DecelerateInterpolator()
}
