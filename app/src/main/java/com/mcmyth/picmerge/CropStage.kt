package com.mcmyth.picmerge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

internal object EditorColors {
    val background = Color.rgb(15, 20, 25)
    val workArea = Color.rgb(8, 12, 16)
    val surface = Color.rgb(28, 37, 44)
    val text = Color.rgb(241, 246, 249)
    val muted = Color.rgb(166, 184, 193)
    val accent = Color.rgb(121, 240, 206)
    val ink = Color.rgb(13, 43, 35)
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun Context.pill(color: Int, radius: Int, border: Int = Color.TRANSPARENT) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (border != Color.TRANSPARENT) setStroke(dp(2), border)
    }

internal class CropStage(
    context: Context,
    private val onSelect: (CropEdge) -> Unit,
    private val onCrop: (CropEdge, Int) -> Unit
) : FrameLayout(context) {
    private val picture = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        pivotX = 0f
        pivotY = 0f
    }
    private val shadeTop = shade()
    private val shadeBottom = shade()
    private val topHandle = EdgeHandle(CropEdge.TOP)
    private val bottomHandle = EdgeHandle(CropEdge.BOTTOM)
    private val handleHeight = context.dp(46)
    private var image: StitchImage? = null
    private var baseWidth = 0
    private var baseHeight = 0
    private var zoom = 1f
    private var pictureX = 0f
    private var pictureY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pinching = false
    private var focusX = 0f
    private var focusY = 0f
    private var previousSpan = 0f
    private var panningAfterPinch = false

    private fun transform(event: MotionEvent) {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        val span = hypot(dx, dy).coerceAtLeast(1f)
        val centerX = (event.getX(0) + event.getX(1)) / 2f
        val centerY = (event.getY(0) + event.getY(1)) / 2f
        if (previousSpan > 0f) {
            val next = (zoom * span / previousSpan).coerceIn(1f, MAX_ZOOM)
            val ratio = next / zoom
            pictureX = centerX - (focusX - pictureX) * ratio
            pictureY = centerY - (focusY - pictureY) * ratio
            zoom = next
            positionPicture()
        }
        previousSpan = span
        focusX = centerX
        focusY = centerY
    }

    init {
        setBackgroundColor(EditorColors.workArea)
        addView(picture, LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        addView(shadeTop, LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        addView(shadeBottom, LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        addView(topHandle, LayoutParams(-1, handleHeight, Gravity.TOP or Gravity.LEFT))
        addView(bottomHandle, LayoutParams(-1, handleHeight, Gravity.TOP or Gravity.LEFT))
    }

    fun show(item: StitchImage, edge: CropEdge, animate: Boolean) {
        if (image !== item) {
            image = item
            picture.setImageBitmap(item.preview)
            baseWidth = 0
            requestLayout()
            if (animate) {
                picture.animate().cancel()
                picture.alpha = 0.55f
                picture.animate().alpha(1f).setDuration(170).start()
            }
        }
        select(edge)
        updateCrop()
    }

    fun clear() {
        image = null
        picture.setImageDrawable(null)
    }

    fun select(edge: CropEdge) {
        topHandle.setActive(edge == CropEdge.TOP)
        bottomHandle.setActive(edge == CropEdge.BOTTOM)
        (if (edge == CropEdge.TOP) topHandle else bottomHandle).bringToFront()
    }

    fun resetZoom() {
        zoom = 1f
        positionPicture()
    }

    fun reveal(edge: CropEdge) {
        val item = image ?: return
        val lineY = pictureY + yFor(item.position(edge))
        val margin = handleHeight / 2f
        if (lineY in margin..(height - margin)) return
        pictureY += height / 2f - lineY
        positionPicture()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewportHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (baseWidth == 0 || viewportWidth != measuredWidth || viewportHeight != measuredHeight) {
            measurePicture(viewportWidth, viewportHeight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (image != null && baseWidth > 0) positionPicture()
    }

    private fun measurePicture(viewportWidth: Int, viewportHeight: Int) {
        val item = image ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val usableWidth = (viewportWidth - context.dp(16)).coerceAtLeast(1)
        val usableHeight = (viewportHeight - handleHeight - context.dp(12)).coerceAtLeast(1)
        val factor = minOf(usableWidth.toDouble() / item.width, usableHeight.toDouble() / item.height)
        baseWidth = (item.width * factor).roundToInt().coerceAtLeast(1)
        baseHeight = (item.height * factor).roundToInt().coerceAtLeast(1)
        picture.layoutParams.width = baseWidth
        picture.layoutParams.height = baseHeight
        picture.forceLayout()
        zoom = 1f
    }

    private fun positionPicture() {
        val w = baseWidth * zoom
        val h = baseHeight * zoom
        val margin = handleHeight / 2f
        pictureX = boundedOffset(pictureX, w, width.toFloat(), 0f)
        pictureY = boundedOffset(pictureY, h, height.toFloat(), margin)
        picture.translationX = pictureX
        picture.translationY = pictureY
        picture.scaleX = zoom
        picture.scaleY = zoom
        updateCrop()
    }

    private fun boundedOffset(offset: Float, size: Float, viewport: Float, margin: Float): Float {
        return if (size <= viewport - 2f * margin) (viewport - size) / 2f
        else offset.coerceIn(viewport - margin - size, margin)
    }

    fun updateCrop() {
        val item = image ?: return
        val top = yFor(item.top)
        val bottom = yFor(item.bottom)
        val right = pictureX + baseWidth * zoom
        shadeTop.translationX = pictureX
        shadeTop.translationY = pictureY
        shadeTop.scaleX = baseWidth * zoom
        shadeTop.scaleY = top
        shadeBottom.translationX = pictureX
        shadeBottom.translationY = pictureY + bottom
        shadeBottom.scaleX = baseWidth * zoom
        shadeBottom.scaleY = (baseHeight * zoom - bottom).coerceAtLeast(0f)
        topHandle.place(pictureY + top, pictureX, right, item.top)
        bottomHandle.place(pictureY + bottom, pictureX, right, item.bottom)
    }

    private fun yFor(sourceY: Int): Float {
        val item = image ?: return 0f
        return (sourceY.toDouble() * baseHeight * zoom / item.height).toFloat()
    }

    private fun shade() = View(context).apply {
        setBackgroundColor(0xA6000000.toInt())
        pivotY = 0f
        pivotX = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinching = false
                panningAfterPinch = false
                previousSpan = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinching = true
                panningAfterPinch = false
                previousSpan = 0f
                transform(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                previousSpan = 0f
                if (event.pointerCount == 2) {
                    val remaining = 1 - event.actionIndex
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    panningAfterPinch = true
                }
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount > 1) transform(event)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = pinching

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && (!pinching || panningAfterPinch) && zoom > 1f) {
                pictureX += event.x - lastX
                pictureY += event.y - lastY
                lastX = event.x
                lastY = event.y
                positionPicture()
            }
        }
        return true
    }

    private inner class EdgeHandle(private val edge: CropEdge) : FrameLayout(context) {
        private val line = View(context).apply { pivotX = 0f }
        private val title = TextView(context).apply {
            text = "≡  ${edge.label}"
            textSize = 12f
            setPadding(context.dp(9), 0, context.dp(9), 0)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private var initialTouch = 0f
        private var initialPosition = 0
        private var dragging = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        init {
            addView(line, LayoutParams(1, context.dp(2), Gravity.CENTER_VERTICAL or Gravity.LEFT))
            addView(title, LayoutParams(-2, context.dp(28), Gravity.CENTER))
            setOnClickListener { onSelect(edge) }
            setOnTouchListener { _, event ->
                val item = image ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouch = event.rawY
                        initialPosition = item.position(edge)
                        dragging = false
                        onSelect(edge)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = event.rawY - initialTouch
                        if (abs(delta) >= touchSlop) dragging = true
                        if (dragging && baseHeight > 0) {
                            val position = initialPosition + delta.toDouble() * item.height / (baseHeight * zoom)
                            onCrop(edge, position.roundToInt())
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!dragging) performClick()
                }
                true
            }
        }

        fun place(y: Float, left: Float, right: Float, position: Int) {
            val visibleLeft = left.coerceAtLeast(0f)
            val visibleRight = right.coerceAtMost(this@CropStage.width.toFloat())
            translationY = y - handleHeight / 2f
            visibility = if (y >= 0 && y <= this@CropStage.height) VISIBLE else INVISIBLE
            line.translationX = visibleLeft
            line.scaleX = (visibleRight - visibleLeft).coerceAtLeast(1f)
            title.translationX = (visibleLeft + visibleRight - this@CropStage.width) / 2f
            val description = "${edge.label}裁切线，$position 像素，拖动调整"
            if (contentDescription != description) contentDescription = description
        }

        fun setActive(active: Boolean) {
            val tint = if (active) EditorColors.accent else EditorColors.text
            line.setBackgroundColor(tint)
            title.setTextColor(EditorColors.ink)
            title.background = RippleDrawable(
                ColorStateList.valueOf(0x20000000), context.pill(tint, 12), null
            )
        }
    }

    private companion object {
        const val MAX_ZOOM = 8f
    }
}
