package com.ogesture.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.animation.ValueAnimator

/**
 * A gesture-navigation style back arrow that peeks out from a side edge while the user
 * drags, mirroring the system back indicator: it slides out with the drag, pulses when
 * the gesture arms, and retracts (or fades out) when the finger lifts.
 *
 * Lives in its own non-touchable full-height overlay window so it can be drawn without
 * affecting the touch zones.
 */
class BackIndicator(
    context: Context,
    private val windowManager: WindowManager,
    private val fromLeftEdge: Boolean,
    private val armDistancePx: Float,
    /**
     * How far in from the physical edge the arrow should peek — the nav-bar inset when
     * the 3-button bar occupies this edge (landscape), else 0. Without it the arrow
     * would slide out underneath the opaque bar and never be seen.
     */
    private val edgeOffsetPx: Int = 0,
    private val zoneLengthPx: Int = 0,
) : OverlayIndicator {
    private val density = context.resources.displayMetrics.density
    private val pillSizePx = (PILL_SIZE_DP * density)
    private val peekPx = (PEEK_DP * density)

    private val root = FrameLayout(context)
    private val arrow = BackArrowView(context, fromLeftEdge).apply {
        val size = pillSizePx.toInt()
        layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = (if (fromLeftEdge) Gravity.START else Gravity.END) or Gravity.TOP
        }
        alpha = 0f
    }
    private var attached = false
    private var windowHidden = false
    private val windowLocation = IntArray(2)
    private var anchorRawY = 0f

    init {
        root.addView(arrow)
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
            // Indicator is cosmetic; the gesture keeps working without it.
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
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        val newAlpha = if (hidden) 0f else 1f
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
        return Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
    }

    fun onGestureStart(rawY: Float) {
        arrow.animate().cancel()
        arrow.scaleX = 1f
        arrow.scaleY = 1f
        arrow.alpha = 1f
        anchorRawY = rawY
        
        arrow.translationY = pillY(rawY).coerceIn(
            0f,
            (root.height - pillSizePx).coerceAtLeast(0f)
        )
        applyProgress(0f)
    }

    fun onGestureProgress(distancePx: Float, rawY: Float) {
        arrow.translationY = pillY(followedRawY(rawY)).coerceIn(
            0f,
            (root.height - pillSizePx).coerceAtLeast(0f)
        )
        applyProgress((distancePx / armDistancePx).coerceIn(0f, 1f))
    }

    /**
     * The pill anchors where the gesture began and only drifts a fraction of the finger's
     * vertical travel, so it nods toward the drag without chasing the finger.
     */
    private fun followedRawY(rawY: Float): Float =
        anchorRawY + (rawY - anchorRawY) * FOLLOW_FRACTION

    /** rawY is in display coordinates; the window may not start at display y=0. */
    private fun pillY(rawY: Float): Float {
        root.getLocationOnScreen(windowLocation)
        return rawY -
    windowLocation[1] -
    pillSizePx / 2f -
    48f * density
}

    fun onArmed() {
        applyProgress(1f)
    }

    fun onGestureEnd(fired: Boolean) {
        val retractX = if (fromLeftEdge) -pillSizePx else pillSizePx
        arrow.animate()
            .translationX(retractX)
            .alpha(0f)
            .setDuration(if (fired) 120L else 180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()
    }

    /** 0 = fully hidden behind the edge, 1 = fully peeked out. */
    
    private fun applyProgress(fraction: Float) {
    val edgeMargin = 4f * density
    val activeMargin = 14f * density

    arrow.translationX = if (fromLeftEdge) {
        edgeMargin + (activeMargin - edgeMargin) * fraction
    } else {
        -edgeMargin - (activeMargin - edgeMargin) * fraction
    }

    arrow.setRevealProgress(fraction)
}

    private companion object {
        const val PILL_SIZE_DP = 48f
        const val PEEK_DP = 18f

        // Vertical follow: the pill moves this fraction of the finger's vertical travel,
        // so it hints at the drag direction without tracking it.
        const val FOLLOW_FRACTION = 1f
    }
}

/** A round dark pill with a left-pointing "back" chevron, whichever edge it comes from. */
private class BackArrowView(
    context: Context,
    private val fromLeftEdge: Boolean
) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 255
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var revealProgress = 0f
    private var targetRevealProgress = 0f
    private var revealAnimator: ValueAnimator? = null

    private val widthInterpolator =
        android.view.animation.PathInterpolator(
            0.19f, 1.27f,
            0.71f, 0.86f
        )

    init {
        val resources = context.resources

        val backgroundId = resources.getIdentifier(
            if (isNightMode(resources)) {
                "system_accent2_700"
            } else {
                "system_accent2_100"
            },
            "color",
            "android"
        )

        val arrowId = resources.getIdentifier(
            if (isNightMode(resources)) {
                "system_accent1_200"
            } else {
                "system_accent1_700"
            },
            "color",
            "android"
        )

        pillPaint.color = if (backgroundId != 0) {
            context.getColor(backgroundId)
        } else {
            0xFFD3D9B7.toInt()
        }

        arrowPaint.color = if (arrowId != 0) {
            context.getColor(arrowId)
        } else {
            0xFF3E4229.toInt()
        }
    }

    private fun isNightMode(resources: android.content.res.Resources): Boolean {
        return (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun setRevealProgress(progress: Float) {

    targetRevealProgress = progress.coerceIn(0f, 1f)

    if (targetRevealProgress <= revealProgress) {

        revealProgress = targetRevealProgress

        revealAnimator?.cancel()

        invalidate()

        return

    }

    revealAnimator?.cancel()

    revealAnimator = ValueAnimator.ofFloat(

        revealProgress,

        targetRevealProgress

    ).apply {

        duration = 150L

        interpolator = android.view.animation.DecelerateInterpolator()

        addUpdateListener {

            revealProgress = it.animatedValue as Float

            invalidate()

        }

        start()

    }

}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // AOSP 风格的展开曲线。
        val widthProgress =
            widthInterpolator.getInterpolation(revealProgress)

        val minWidth = viewHeight * 0.17f

        val currentWidth =
            minWidth + (viewHeight - minWidth) * widthProgress

        val halfHeight = viewHeight / 2f

        val rect = if (fromLeftEdge) {
            android.graphics.RectF(
                0f,
                0f,
                currentWidth,
                viewHeight
            )
        } else {
            android.graphics.RectF(
                viewWidth - currentWidth,
                0f,
                viewWidth,
                viewHeight
            )
        }

        canvas.drawRoundRect(
            rect,
            halfHeight,
            halfHeight,
            pillPaint
        )

        // 箭头在背景展开到约 23% 后开始出现。
        val arrowProgress =
            ((revealProgress - 0.23f) / 0.77f)
                .coerceIn(0f, 1f)

        arrowPaint.alpha = (arrowProgress * 255f).toInt()

        if (arrowProgress > 0f) {
            /*
             * 无论从哪一侧触发，Back 指示器都保持 <。
             * 这与当前 Pixel 上观察到的 AOSP 表现一致。
             */
            val arrowCenterX =
                if (fromLeftEdge) {
                    currentWidth / 2f
                } else {
                    viewWidth - currentWidth / 2f
                }

            val arrowCenterY = viewHeight / 2f

            val arm = viewWidth * 0.13f
            val tip = arrowCenterX - arm * 0.7f
            val tail = arrowCenterX + arm * 0.7f

            val chevron = Path()

            chevron.moveTo(
                tail,
                arrowCenterY - arm * 1.4f
            )

            chevron.lineTo(
                tip,
                arrowCenterY
            )

            chevron.lineTo(
                tail,
                arrowCenterY + arm * 1.4f
            )

            canvas.drawPath(
                chevron,
                arrowPaint
            )
        }
    }
}
