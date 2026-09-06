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
        val width = (pillSizePx + peekPx).toInt()
        val height = pillSizePx.toInt()

        layoutParams = FrameLayout.LayoutParams(width, height).apply {
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
            (gestureProgress / 0.88f)
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
    panel.setActiveGestureState()
}

    GestureState.INACTIVE -> {
        val gestureProgress =
            (distancePx / armDistancePx)
                .coerceIn(0f, 1f)

        val progress =
            (gestureProgress / 0.88f)
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
        }

    private val arrowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.5f * density
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.ROUND
        }

    /*
     * ------------------------------------------------------------------------
     * Dimensions
     * ------------------------------------------------------------------------
     */

    private val fullSize =
        48f * density

    private val compressedWidth =
        fullSize * 0.17f

    private val compressedHeight =
        fullSize * 0.78f

    private val squareCornerRadius =
        fullSize * 0.32f

    private val circleCornerRadius =
        fullSize / 2f

    /*
     * The root window is 48dp + peek wide.
     *
     * The actual background is always drawn starting from the screen edge:
     *
     * LEFT:
     *   [ background ][ extra space ]
     *
     * RIGHT:
     *   [ extra space ][ background ]
     *
     * Therefore horizontal movement is represented by the View's
     * translationX, not by translating the Canvas inside a 48dp View.
     */

/*
 * Horizontal positions.
 *
 * The whole indicator in the previous version was too far from the
 * screen edge. Keep the fully expanded 48x48 rounded square close
 * to the edge, then move only a small distance inward before it
 * becomes the circle.
 */

private val edgeMargin =
    0f

/*
 * Position of the fully expanded 48x48 rounded square.
 *
 * Keep it close to the screen edge.
 */
private val activeMargin =
    4f * density

/*
 * Additional inward movement after the pop, before morphing into
 * the 48x48 circle.
 */
private val circleAdditionalInset =
    6f * density

    /*
     * ------------------------------------------------------------------------
     * Gesture progress
     * ------------------------------------------------------------------------
     */

    private var horizontalProgress = 0f
    private var backgroundProgress = 0f
    private var arrowProgress = 0f

    /*
     * ACTIVE animation phase.
     *
     * 0 = normal gesture / rounded square
     * 1 = pop
     * 2 = move inward
     * 3 = circle
     */
    private var activeAnimationGeneration = 0L

    /*
     * ------------------------------------------------------------------------
     * AOSP-style animated properties
     * ------------------------------------------------------------------------
     *
     * AOSP BackPanel keeps these as independent AnimatedFloat properties.
     * The important distinction is restingPosition vs. temporary spring
     * stretch. We retain that distinction here.
     */

    private val backgroundWidth =
        AnimatedFloat(
            "backgroundWidth",
            minimumValue = 0f,
        )

    private val backgroundHeight =
        AnimatedFloat(
            "backgroundHeight",
            minimumValue = 0f,
        )

    private val backgroundEdgeCornerRadius =
        AnimatedFloat(
            "backgroundEdgeCornerRadius",
            minimumValue = 0f,
        )

    private val backgroundFarCornerRadius =
        AnimatedFloat(
            "backgroundFarCornerRadius",
            minimumValue = 0f,
        )

    private val scale =
        AnimatedFloat(
            "scale",
            minimumValue = 0.5f,
        )

    private val scalePivotX =
        AnimatedFloat(
            "scalePivotX",
            minimumValue = 0f,
        )

    private val arrowLength =
        AnimatedFloat(
            "arrowLength",
            minimumValue = 0f,
        )

    private val arrowHeight =
        AnimatedFloat(
            "arrowHeight",
            minimumValue = 0f,
        )

    private val arrowAlpha =
        AnimatedFloat(
            "arrowAlpha",
            minimumValue = 0f,
            maximumValue = 1f,
        )

    private val backgroundAlpha =
        AnimatedFloat(
            "backgroundAlpha",
            minimumValue = 0f,
            maximumValue = 1f,
        )

    /*
     * This property is deliberately NOT drawn by translating the Canvas.
     *
     * Instead, it is exposed through View.translationX.
     *
     * This prevents the 48dp background from being clipped by a 48dp View.
     */
    private val horizontalTranslation =
        AnimatedFloat(
            "horizontalTranslation",
        )

    /*
     * ------------------------------------------------------------------------
     * Spring
     * ------------------------------------------------------------------------
     */

    private val defaultSpring =
        SpringForce().apply {
            dampingRatio =
                SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
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

        backgroundWidth.snapTo(0f)
        backgroundHeight.snapTo(0f)

        backgroundEdgeCornerRadius.snapTo(
            squareCornerRadius
        )

        backgroundFarCornerRadius.snapTo(
            squareCornerRadius
        )

        scale.snapTo(1f)

        scalePivotX.snapTo(
            fullSize / 2f
        )

        horizontalTranslation.snapTo(
            edgeMargin
        )

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

                if (name == "horizontalTranslation") {
                    updateViewTranslation()
                }

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

        fun snapToRestingPosition() {
            snapTo(restingPosition)
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
         * This is the same conceptual mechanism as
         * AOSP AnimatedFloat.stretchTo().
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

        fun cancel() {
            animation.cancel()
        }
    }

    private fun applySpring(
        spring: SpringForce,
    ) {
        backgroundWidth.animationSpring(spring)
        backgroundHeight.animationSpring(spring)
        backgroundEdgeCornerRadius.animationSpring(spring)
        backgroundFarCornerRadius.animationSpring(spring)
        scale.animationSpring(spring)
        scalePivotX.animationSpring(spring)
        horizontalTranslation.animationSpring(spring)
        arrowLength.animationSpring(spring)
        arrowHeight.animationSpring(spring)
        arrowAlpha.animationSpring(spring)
        backgroundAlpha.animationSpring(spring)
    }

    private fun AnimatedFloat.animationSpring(
        spring: SpringForce,
    ) {
        cancel()

        /*
         * We cannot directly replace the private SpringAnimation from
         * outside AnimatedFloat, so this helper intentionally remains empty.
         *
         * The default spring is already installed in AnimatedFloat.init().
         */
    }

    /*
     * ------------------------------------------------------------------------
     * Reset
     * ------------------------------------------------------------------------
     */

    fun resetForGesture() {
        activeAnimationGeneration++

        cancelAnimations()

        horizontalProgress = 0f
        backgroundProgress = 0f
        arrowProgress = 0f

        translationX = 0f

        backgroundWidth.snapTo(0f)
        backgroundHeight.snapTo(0f)

        backgroundEdgeCornerRadius.snapTo(
            squareCornerRadius
        )

        backgroundFarCornerRadius.snapTo(
            squareCornerRadius
        )

        scale.snapTo(1f)

        scalePivotX.snapTo(
            fullSize / 2f
        )

        horizontalTranslation.snapTo(
            edgeMargin
        )

        arrowLength.snapTo(0f)
        arrowHeight.snapTo(0f)

        arrowAlpha.snapTo(0f)
        backgroundAlpha.snapTo(0f)

        invalidate()
    }

    /*
     * Kept because BackIndicator still calls it.
     *
     * Corner radius is now controlled directly by the visual state.
     */
    fun setShapeProgress(
        progress: Float,
    ) {
        // Intentionally unused.
    }

    /*
     * ------------------------------------------------------------------------
     * Normal ENTRY / INACTIVE visual state
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
         * A new direct gesture state invalidates any previous physics
         * animation.
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
                (
                    fullSize - compressedWidth
                ) *
                widthProgress

        val currentHeight =
            compressedHeight +
                (
                    fullSize - compressedHeight
                ) *
                heightProgress

        /*
         * IMPORTANT:
         *
         * Once backgroundProgress reaches 1:
         *
         * width  = 48dp
         * height = 48dp
         *
         * This is the exact rounded-square state used before ACTIVE.
         */
        backgroundWidth.snapTo(
            currentWidth
        )

        backgroundHeight.snapTo(
            currentHeight
        )

        val corner =
            interpolate(
                compressedHeight * 0.32f,
                squareCornerRadius,
                backgroundProgress,
            ).coerceAtMost(
                currentHeight / 2f
            )

        backgroundEdgeCornerRadius.snapTo(
            corner
        )

        backgroundFarCornerRadius.snapTo(
            corner
        )

        scale.snapTo(1f)

        scalePivotX.snapTo(
            currentWidth / 2f
        )

        horizontalTranslation.snapTo(
            edgeMargin +
                (
                    activeMargin - edgeMargin
                ) *
                this.horizontalProgress
        )

        val visibleArrowProgress =
            (
                (this.arrowProgress - 0.18f) /
                    0.82f
            ).coerceIn(0f, 1f)

arrowLength.snapTo(
    interpolate(
        fullSize * 0.08f,
        fullSize * 0.20f,
        visibleArrowProgress,
    )
)

arrowHeight.snapTo(
    interpolate(
        fullSize * 0.08f,
        fullSize * 0.18f,
        visibleArrowProgress,
    )
)

        arrowAlpha.snapTo(
            visibleArrowProgress
        )

        backgroundAlpha.snapTo(
            this.backgroundProgress
        )

        updateViewTranslation()

        invalidate()
    }

    /*
     * ------------------------------------------------------------------------
     * ACTIVE visual state
     * ------------------------------------------------------------------------
     *
     * This is deliberately separate from setVisualState().
     *
     * Once activate() starts the AOSP-style spring animation, ordinary
     * ACTIVE gesture progress must NOT cancel that animation.
     */

    fun setActiveGestureState() {
        horizontalProgress = 1f
        backgroundProgress = 1f
        arrowProgress = 1f

        /*
         * Do not touch:
         *
         * backgroundWidth
         * backgroundHeight
         * scale
         * corner radius
         * horizontalTranslation
         *
         * They belong to the ACTIVE animation sequence.
         */
    }

    /*
     * ------------------------------------------------------------------------
     * ACTIVE sequence
     * ------------------------------------------------------------------------
     *
     * 1. 48 x 48 rounded square
     * 2. AOSP-style pop
     * 3. Return to 48 x 48 rounded square
     * 4. Move the whole 48 x 48 object inward
     * 5. At the new position, morph only the corners to a circle
     */

    fun activate(
        onActivated: (() -> Unit)? = null,
    ) {
        val generation =
            ++activeAnimationGeneration

        cancelAnimations()

        horizontalProgress = 1f
        backgroundProgress = 1f
        arrowProgress = 1f

        /*
         * First frame is explicitly the complete rounded square.
         */
        backgroundWidth.snapTo(
            fullSize
        )

        backgroundHeight.snapTo(
            fullSize
        )

        backgroundEdgeCornerRadius.snapTo(
            squareCornerRadius
        )

        backgroundFarCornerRadius.snapTo(
            squareCornerRadius
        )

        scalePivotX.snapTo(
            fullSize / 2f
        )

        scale.snapTo(1f)

        horizontalTranslation.snapTo(
            activeMargin
        )

arrowLength.snapTo(
    fullSize * 0.20f
)

arrowHeight.snapTo(
    fullSize * 0.18f
)

        arrowAlpha.snapTo(1f)
        backgroundAlpha.snapTo(1f)

        updateViewTranslation()

        performHapticFeedback(
            HapticFeedbackConstants.CONFIRM
        )

        /*
         * AOSP BackPanel.popOffEdge():
         *
         * height = velocity * 50
         * width  = velocity * 150
         * scale  = velocity * 0.8
         *
         * The important difference from the previous implementation is:
         *
         * these are temporary stretches around the already-established
         * 48 x 48 resting state.
         */
        val startingVelocity =
            0.035f * density

        val heightStretch =
            startingVelocity * 50f

        val widthStretch =
            startingVelocity * 150f

        val scaleStretch =
            startingVelocity * 0.8f

        backgroundHeight.stretchTo(
            stretchAmount = 0f,
            startingVelocity = -heightStretch,
        )

        backgroundWidth.stretchTo(
            stretchAmount = 0f,
            startingVelocity = widthStretch,
        )

        scale.stretchTo(
            stretchAmount = 0f,
            startingVelocity = -scaleStretch,
        )

        /*
         * Do NOT call the callback later.
         *
         * BackIndicator needs to enter ACTIVE immediately so that the
         * gesture state machine does not repeatedly call activate().
         *
         * setActiveGestureState() no longer cancels the springs.
         */
        onActivated?.invoke()

        /*
         * The remainder of the animation is driven by a simple delayed
         * transition. The spring itself remains responsible for the pop.
         */
        postDelayed(
            {
                if (
                    generation !=
                        activeAnimationGeneration
                ) {
                    return@postDelayed
                }

                /*
                 * Reset the temporary pop stretch.
                 *
                 * This is exactly the conceptual "resetStretch()" step
                 * from AOSP BackPanel.
                 */
                backgroundWidth.snapTo(fullSize)
                backgroundHeight.snapTo(fullSize)
                scale.snapTo(1f)

                startMoveToCircle(
                    generation
                )
            },
            POP_DURATION,
        )
    }

    /*
     * ------------------------------------------------------------------------
     * Move inward
     * ------------------------------------------------------------------------
     */

    private fun startMoveToCircle(
        generation: Long,
    ) {
        if (
            generation !=
                activeAnimationGeneration
        ) {
            return
        }

        /*
         * Hard invariant:
         *
         * The shape is exactly 48 x 48 during this entire phase.
         */
        backgroundWidth.snapTo(fullSize)
        backgroundHeight.snapTo(fullSize)
        scale.snapTo(1f)

        /*
         * Start from the ACTIVE rounded-square position.
         */
        horizontalTranslation.snapTo(
            activeMargin
        )

        /*
         * The final circle is closer to the center.
         */
        horizontalTranslation.updateRestingPosition(
            activeMargin +
                circleAdditionalInset,
            animated = true,
        )

        postDelayed(
            {
                if (
                    generation !=
                        activeAnimationGeneration
                ) {
                    return@postDelayed
                }

                startCircleMorph(
                    generation
                )
            },
            MOVE_INWARD_DURATION,
        )
    }

    /*
     * ------------------------------------------------------------------------
     * Circle morph
     * ------------------------------------------------------------------------
     */

    private fun startCircleMorph(
        generation: Long,
    ) {
        if (
            generation !=
                activeAnimationGeneration
        ) {
            return
        }

        /*
         * Absolute size invariant:
         *
         * circle = 48 x 48
         */
        backgroundWidth.snapTo(fullSize)
        backgroundHeight.snapTo(fullSize)
        scale.snapTo(1f)

        /*
         * ONLY corner radius changes.
         */
        backgroundEdgeCornerRadius.updateRestingPosition(
            circleCornerRadius,
            animated = true,
        )

        backgroundFarCornerRadius.updateRestingPosition(
            circleCornerRadius,
            animated = true,
        )
    }

    /*
     * ------------------------------------------------------------------------
     * ACTIVE -> INACTIVE
     * ------------------------------------------------------------------------
     *
     * Direct:
     *
     * circle
     *   ↓
     * compressed rounded rectangle
     *   ↓
     * edge
     *
     * No full-square intermediate state.
     */

    fun deactivate(
        compressedBackgroundProgress: Float,
    ) {
        ++activeAnimationGeneration

        cancelAnimations()

        val progress =
            compressedBackgroundProgress
                .coerceIn(0f, 1f)

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
                (
                    fullSize - compressedWidth
                ) *
                widthProgress

        val targetHeight =
            compressedHeight +
                (
                    fullSize - compressedHeight
                ) *
                heightProgress

        /*
         * Immediately remove the circular shape.
         */
        backgroundWidth.snapTo(
            targetWidth
        )

        backgroundHeight.snapTo(
            targetHeight
        )

        val corner =
            (
                targetHeight * 0.32f
            ).coerceAtMost(
                targetHeight / 2f
            )

        backgroundEdgeCornerRadius.snapTo(
            corner
        )

        backgroundFarCornerRadius.snapTo(
            corner
        )

        scale.snapTo(1f)

        scalePivotX.snapTo(
            targetWidth / 2f
        )

        horizontalTranslation.snapTo(
            edgeMargin +
                (
                    activeMargin - edgeMargin
                ) *
                horizontalProgress
        )

        arrowLength.snapTo(
            fullSize *
                0.26f *
                arrowProgress
        )

        arrowHeight.snapTo(
            fullSize *
                0.13f *
                arrowProgress
        )

        arrowAlpha.snapTo(
            arrowProgress
        )

        backgroundAlpha.snapTo(1f)

        updateViewTranslation()

        /*
         * A small outward spring, following the same independent-property
         * philosophy as AOSP.
         */
        val outwardVelocity =
            0.04f * density

        horizontalTranslation.stretchTo(
            stretchAmount =
                if (fromLeftEdge) {
                    -4f * density
                } else {
                    4f * density
                },
            startingVelocity =
                if (fromLeftEdge) {
                    -outwardVelocity * 100f
                } else {
                    outwardVelocity * 100f
                },
        )

        updateViewTranslation()

        invalidate()
    }

    /*
     * ------------------------------------------------------------------------
     * Fling
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

        val startHorizontal =
            this.horizontalProgress

        val startBackground =
            this.backgroundProgress

        val startArrow =
            this.arrowProgress

        ValueAnimator.ofFloat(
            0f,
            1f,
        ).apply {
            this.duration = duration

            interpolator =
                DecelerateInterpolator()

            addUpdateListener {
                val progress =
                    it.animatedValue as Float

                setVisualState(
                    horizontalProgress =
                        interpolate(
                            startHorizontal,
                            horizontalProgress,
                            progress,
                        ),
                    backgroundProgress =
                        interpolate(
                            startBackground,
                            backgroundProgress,
                            progress,
                        ),
                    arrowProgress =
                        interpolate(
                            startArrow,
                            arrowProgress,
                            progress,
                        ),
                )
            }

            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(
                        animation: Animator,
                    ) {
                        onEnd()
                    }
                }
            )

            start()
        }
    }

    /*
     * ------------------------------------------------------------------------
     * Cancel all property springs
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

        arrowLength.cancel()
        arrowHeight.cancel()

        arrowAlpha.cancel()
        backgroundAlpha.cancel()
    }

    /*
     * ------------------------------------------------------------------------
     * View translation
     * ------------------------------------------------------------------------
     *
     * This is the major fix for the clipping bug.
     *
     * The previous implementation translated the Canvas inside a 48dp View.
     * This implementation moves the View itself.
     */

    private fun updateViewTranslation() {
        val amount =
            horizontalTranslation.pos

        translationX =
            if (fromLeftEdge) {
                amount
            } else {
                -amount
            }
    }

    /*
     * ------------------------------------------------------------------------
     * Drawing
     * ------------------------------------------------------------------------
     *
     * The background itself is always drawn inside the View.
     *
     * There is NO horizontal Canvas translation here.
     */

    override fun onDraw(
        canvas: Canvas,
    ) {
        super.onDraw(canvas)

        val currentWidth =
            backgroundWidth.pos

        val currentHeight =
            backgroundHeight.pos

        if (
            currentWidth <= 0f ||
            currentHeight <= 0f
        ) {
            return
        }

        /*
         * The View is 48dp + peek wide.
         *
         * The 48dp background stays attached to the appropriate
         * screen edge inside that View.
         */
        val left =
            if (fromLeftEdge) {
                0f
            } else {
                width.toFloat() - currentWidth
            }

        val top =
            (height.toFloat() - currentHeight) / 2f

        val rect =
            RectF(
                left,
                top,
                left + currentWidth,
                top + currentHeight,
            )

        val edgeCorner =
            backgroundEdgeCornerRadius.pos

        val farCorner =
            backgroundFarCornerRadius.pos

        val radii =
            if (fromLeftEdge) {
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
            } else {
                floatArrayOf(
                    farCorner,
                    farCorner,

                    edgeCorner,
                    edgeCorner,

                    edgeCorner,
                    edgeCorner,

                    farCorner,
                    farCorner,
                )
            }

        val path =
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

        canvas.save()

        /*
         * AOSP-style scale around the background center.
         *
         * Width/height remain independent from scale.
         */
        val pivotX =
            rect.centerX()

        val pivotY =
            rect.centerY()

        canvas.scale(
            scale.pos,
            scale.pos,
            pivotX,
            pivotY,
        )

        canvas.drawPath(
            path,
            backgroundPaint,
        )

        /*
         * Arrow is centered inside the current background.
         */
        val dx =
            arrowLength.pos

        val dy =
            arrowHeight.pos

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
val arrowCenterX =
    rect.centerX() - 5f * density

val arrowCenterY =
    rect.centerY()

    val arrowPath =
    Path().apply {
        /*
         * Android back arrow always points left.
         *
         * This is true for gestures starting from both the
         * left and right screen edges.
         */
        moveTo(
            arrowCenterX + dx,
            arrowCenterY - dy,
        )
        lineTo(
            arrowCenterX,
            arrowCenterY,
        )
        lineTo(
            arrowCenterX + dx,
            arrowCenterY + dy,
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
     * Helpers
     * ------------------------------------------------------------------------
     */

    private fun interpolate(
        start: Float,
        end: Float,
        progress: Float,
    ): Float {
        return start +
            (
                end - start
            ) *
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

    private companion object {
        const val POP_DURATION = 110L
        const val MOVE_INWARD_DURATION = 90L
    }
}
