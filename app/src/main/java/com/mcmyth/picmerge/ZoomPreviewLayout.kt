package com.mcmyth.picmerge

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout

/** A two-dimensional pan/zoom viewport that does not rely on ScrollView gesture logic. */
internal class ZoomPreviewLayout(context: Context) : FrameLayout(context) {
    private var content: View? = null
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var moved = false

    private val scaler = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val view = content ?: return false
                val old = zoom
                val next = (old * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)

                // Follow two-finger movement, then scale around the live focal point.
                offsetX += detector.focusX - lastFocusX
                offsetY += detector.focusY - lastFocusY
                if (next != old) {
                    val ratio = next / old
                    offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
                    offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
                    zoom = next
                }
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                applyTransform(view)
                return true
            }
        })

    init {
        clipChildren = true
        clipToPadding = true
        isClickable = true
    }

    fun setContent(view: View) {
        removeAllViews()
        content = view
        view.pivotX = 0f
        view.pivotY = 0f
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        post(::resetZoom)
    }

    fun resetZoom() {
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        content?.let(::applyTransform)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                lastX = event.x
                lastY = event.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && event.pointerCount == 1) {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > context.dp(2)) moved = true
                offsetX += dx
                offsetY += dy
                lastX = event.x
                lastY = event.y
                content?.let(::applyTransform)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
            }
            MotionEvent.ACTION_UP -> if (!moved && !scaler.isInProgress) performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // The viewport keeps the screen size, while its child is allowed to measure to
        // the complete stitched height. FrameLayout's default AT_MOST measurement would
        // otherwise truncate long content to one screen.
        val child = content ?: return
        val childWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(1)
        child.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { content?.let(::applyTransform) }
    }

    private fun applyTransform(view: View) {
        if (width <= 0 || height <= 0 || view.width <= 0 || view.height <= 0) return
        val scaledWidth = view.width * zoom
        val scaledHeight = view.height * zoom
        offsetX = bound(offsetX, scaledWidth, width.toFloat())
        offsetY = bound(offsetY, scaledHeight, height.toFloat())
        view.scaleX = zoom
        view.scaleY = zoom
        view.translationX = offsetX
        view.translationY = offsetY
    }

    private fun bound(value: Float, contentSize: Float, viewportSize: Float): Float =
        if (contentSize <= viewportSize) (viewportSize - contentSize) / 2f
        else value.coerceIn(viewportSize - contentSize, 0f)

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
    }
}
