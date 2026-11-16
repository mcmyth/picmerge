package com.mcmyth.picmerge

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToLong

internal class PreviewDrawer(context: Context) : LinearLayout(context) {
    var peekHeightPx: Int = context.dp(117)
    var onProgress: (Float) -> Unit = {}
    var onExpandedChanged: (Boolean) -> Unit = {}

    private var collapsedOffset = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var moved = false
    private var expanded = false
    private var velocity: VelocityTracker? = null

    init {
        orientation = VERTICAL
    }

    fun useHandle(handle: View) {
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animate().cancel()
                    downY = event.rawY
                    startOffset = translationY
                    moved = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(event)
                    val distance = event.rawY - downY
                    if (abs(distance) > context.dp(3)) moved = true
                    setOffset((startOffset + distance).coerceIn(0f, collapsedOffset))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.addMovement(event)
                    velocity?.computeCurrentVelocity(1000)
                    val speed = velocity?.yVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    val open = when {
                        !moved -> !expanded
                        speed < -900f -> true
                        speed > 900f -> false
                        else -> translationY < collapsedOffset * 0.56f
                    }
                    settle(open)
                    true
                }
                else -> true
            }
        }
    }

    fun expand(animate: Boolean = true) = moveTo(true, animate)

    fun collapse(animate: Boolean = true) = moveTo(false, animate)

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        collapsedOffset = (height - peekHeightPx).coerceAtLeast(0).toFloat()
        setOffset(if (expanded) 0f else collapsedOffset)
    }

    private fun moveTo(open: Boolean, animate: Boolean) {
        expanded = open
        if (!animate || collapsedOffset == 0f) {
            setOffset(if (open) 0f else collapsedOffset)
            onExpandedChanged(open)
            return
        }
        settle(open)
    }

    private fun settle(open: Boolean) {
        expanded = open
        val target = if (open) 0f else collapsedOffset
        val duration = (120 + abs(target - translationY) / height.coerceAtLeast(1) * 180).roundToLong()
        animate()
            .translationY(target)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { onExpandedChanged(open) }
            .setUpdateListener { onProgress(progressFor(translationY)) }
            .start()
    }

    private fun setOffset(offset: Float) {
        translationY = offset
        onProgress(progressFor(offset))
    }

    private fun progressFor(offset: Float): Float {
        if (collapsedOffset <= 0f) return if (expanded) 1f else 0f
        return (1f - offset / collapsedOffset).coerceIn(0f, 1f)
    }
}
