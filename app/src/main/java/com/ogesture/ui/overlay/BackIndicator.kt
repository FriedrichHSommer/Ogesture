package com.ogesture.ui.overlay

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
        panel.animate().cancel()

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

        panel.translationY = clampPanelY(anchorPanelY)

        currentState = GestureState.ENTRY

        panel.setVisualState(
            horizontalProgress = 0f,
            backgroundProgress = 0f,
            arrowProgress = 0f,
        )
    }

    fun onGestureProgress(
        distancePx: Float,
        rawY: Float,
    ) {
        updateVelocity(distancePx)

        val gestureProgress =
            (distancePx / armDistancePx)
                .coerceIn(0f, 1f)

        // AOSP-style ENTRY / ACTIVE transition.
        currentState =
            if (gestureProgress < ACTIVE_THRESHOLD) {
                GestureState.ENTRY
            } else {
                GestureState.ACTIVE
            }

        // --------------------------------------------------------------------
        // Vertical rubber-band.
        //
        // This replaces the old FOLLOW_FRACTION model.
        // --------------------------------------------------------------------

        panel.translationY =
            clampPanelY(
                rubberBandPanelY(rawY)
            )

        // AOSP uses different curves for different visual properties.

        val horizontalProgress =
            RUBBER_BAND_INTERPOLATOR.getInterpolation(
                gestureProgress
            )

        /*
         * 第一阶段只使用前 62% 的手势距离。
         *
         * 到达圆角正方形以后保持在那里，
         * 不会因为继续慢慢拉就偷偷变圆。
         */
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
    }

    fun onArmed() {
        currentState = GestureState.ACTIVE

        panel.setVisualState(
            horizontalProgress = 1f,
            backgroundProgress = 1f,
            arrowProgress = 1f,
        )

        panel.activate()
    }

    fun onGestureEnd(fired: Boolean) {
        val now = SystemClock.uptimeMillis()

        val gestureDuration =
            now - gestureStartTime

        val isFling =
            fired &&
                abs(lastVelocityPxPerSec) >= MIN_FLING_VELOCITY

        when {
            isFling -> finishFling(gestureDuration)

            fired -> commitGesture()

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
        FLUNG,
        COMMITTED,
        CANCELLED,
    }

    private companion object {

        const val PILL_SIZE_DP = 48f
        const val PEEK_DP = 18f

        const val ACTIVE_THRESHOLD = 0.55f

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

    private val pillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            alpha = 255
        }

    private val arrowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private var horizontalProgress = 0f
    private var backgroundProgress = 0f
    private var arrowProgress = 0f

    private var active = false
    private var jumpOffset = 0f

    private val propertyInterpolator =
        PathInterpolator(
            0.19f,
            1.27f,
            0.71f,
            0.86f,
        )

    private val animatedHorizontal =
        android.animation.ValueAnimator.ofFloat(0f, 0f)

    private val animatedBackground =
        android.animation.ValueAnimator.ofFloat(0f, 0f)

    private val animatedArrow =
        android.animation.ValueAnimator.ofFloat(0f, 0f)

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

        pillPaint.color =
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

        updateHorizontalTranslation()

        invalidate()
    }

    fun activate() {
        if (active) return

        active = true

        performHapticFeedback(
            HapticFeedbackConstants.CONFIRM
        )

        android.animation.ValueAnimator
            .ofFloat(0f, 1f, 0f)
            .apply {
                duration = 120L

                interpolator =
                    DecelerateInterpolator()

                addUpdateListener {
                    jumpOffset =
                        (it.animatedValue as Float) *
                            5f * density

                    updateHorizontalTranslation()
                }

                start()
            }

        invalidate()
    }

    /**
     * Continue the current visual state toward the final ACTIVE state.
     *
     * Used only for fast fling gestures.
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

        animatedHorizontal.cancel()
        animatedBackground.cancel()
        animatedArrow.cancel()

        animatedHorizontal.setFloatValues(
            this.horizontalProgress,
            horizontalProgress,
        )

        animatedBackground.setFloatValues(
            this.backgroundProgress,
            backgroundProgress,
        )

        animatedArrow.setFloatValues(
            this.arrowProgress,
            arrowProgress,
        )

        animatedHorizontal.duration = duration
        animatedBackground.duration = duration
        animatedArrow.duration = duration

        val interpolator =
            DecelerateInterpolator()

        animatedHorizontal.interpolator = interpolator
        animatedBackground.interpolator = interpolator
        animatedArrow.interpolator = interpolator

        animatedHorizontal.removeAllUpdateListeners()
        animatedBackground.removeAllUpdateListeners()
        animatedArrow.removeAllUpdateListeners()

        animatedHorizontal.addUpdateListener {
            this.horizontalProgress =
                it.animatedValue as Float

            updateHorizontalTranslation()
            invalidate()
        }

        animatedBackground.addUpdateListener {
            this.backgroundProgress =
                it.animatedValue as Float

            invalidate()
        }

        animatedArrow.addUpdateListener {
            this.arrowProgress =
                it.animatedValue as Float

            invalidate()
        }

        animatedArrow.removeAllListeners()

        animatedArrow.addListener(
            object : android.animation.AnimatorListenerAdapter() {

                override fun onAnimationEnd(
                    animation: android.animation.Animator,
                ) {
                    onEnd()
                }
            },
        )

        animatedHorizontal.start()
        animatedBackground.start()
        animatedArrow.start()
    }

    private fun updateHorizontalTranslation() {

        val edgeMargin =
            4f * density

        val activeMargin =
            14f * density

        val normalTranslation =
            edgeMargin +
                (activeMargin - edgeMargin) *
                horizontalProgress

        translationX =
            if (fromLeftEdge) {
                normalTranslation + jumpOffset
            } else {
                -(normalTranslation + jumpOffset)
            }
    }

    override fun onDraw(
        canvas: Canvas,
    ) {

        super.onDraw(canvas)

        val viewWidth =
            width.toFloat()

        val viewHeight =
            height.toFloat()

        // --------------------------------------------------------------------
        // Background
        // --------------------------------------------------------------------

        val shapeProgress =
            backgroundProgress.coerceIn(0f, 1f)

        val widthProgress =
            propertyInterpolator.getInterpolation(shapeProgress)
                .coerceIn(0f, 1f)

        /*
         * AOSP-style entry:
         *
         * Start as a narrow rounded rectangle near the edge,
         * then gradually become a complete circle.
         */

        val minWidth =
            viewHeight * 0.17f

        val currentWidth =
            minWidth +
                (viewHeight - minWidth) *
                widthProgress

        /*
         * Height changes slightly during entry rather than remaining completely
         * fixed. This makes the initial shape feel more like a compressed panel.
         */

        val minHeight =
            viewHeight * 0.78f

        /*
         * 第一阶段只负责展开到正方形。
         *
         * 到达正方形以后，高度不再变化。
         */
        val currentHeight =
            minHeight +
                (viewHeight - minHeight) *
                shapeProgress

        val top =
            (viewHeight - currentHeight) / 2f

        val bottom =
            top + currentHeight

        val rect =
            if (fromLeftEdge) {

                RectF(
                    0f,
                    top,
                    currentWidth,
                    bottom,
                )

            } else {

                RectF(
                    viewWidth - currentWidth,
                    top,
                    viewWidth,
                    bottom,
                )
            }

        /*
         * The edge and far corners intentionally evolve differently.
         *
         * This is closer to the AOSP BackPanel model than using one fixed
         * round-rect radius.
         */

        /*
         * 第一阶段保持“圆角正方形”的感觉。
         *
         * 第二阶段（snapProgress）才逐渐把四个角变成完整圆形。
         */
        val squareCornerRadius =
            currentHeight * 0.32f

        val circleCornerRadius =
            currentHeight / 2f

        val cornerRadius =
            if (active) {
                circleCornerRadius
            } else {
                squareCornerRadius
            }

        val edgeRadius =
            cornerRadius

        val farRadius =
            cornerRadius

        val radii =
            if (fromLeftEdge) {

                floatArrayOf(
                    edgeRadius, edgeRadius,
                    farRadius, farRadius,
                    farRadius, farRadius,
                    edgeRadius, edgeRadius,
                )

            } else {

                floatArrayOf(
                    farRadius, farRadius,
                    edgeRadius, edgeRadius,
                    edgeRadius, edgeRadius,
                    farRadius, farRadius,
                )
            }

        val backgroundPath =
            Path()

        backgroundPath.addRoundRect(
            rect,
            radii,
            Path.Direction.CW,
        )

        canvas.drawPath(
            backgroundPath,
            pillPaint,
        )

        // --------------------------------------------------------------------
        // Arrow
        // --------------------------------------------------------------------

        val visibleArrowProgress =
            ((arrowProgress - 0.18f) / 0.82f)
                .coerceIn(0f, 1f)

        arrowPaint.alpha =
            (visibleArrowProgress * 255f)
                .toInt()

        if (visibleArrowProgress <= 0f) {
            return
        }

        val arrowCenterX =
            if (fromLeftEdge) {
                currentWidth / 2f
            } else {
                viewWidth - currentWidth / 2f
            }

        val arrowCenterY =
            viewHeight / 2f

        /*
         * Arrow dimensions also stretch independently.
         */

        val minArm =
            viewHeight * 0.05f

        val maxArm =
            viewHeight * 0.13f

        val arm =
            minArm +
                (maxArm - minArm) *
                visibleArrowProgress

        val tip =
            arrowCenterX -
                arm * 0.7f

        val tail =
            arrowCenterX +
                arm * 0.7f

        val chevron =
            Path()

        chevron.moveTo(
            tail,
            arrowCenterY - arm * 1.4f,
        )

        chevron.lineTo(
            tip,
            arrowCenterY,
        )

        chevron.lineTo(
            tail,
            arrowCenterY + arm * 1.4f,
        )

        canvas.drawPath(
            chevron,
            arrowPaint,
        )
    }
}
