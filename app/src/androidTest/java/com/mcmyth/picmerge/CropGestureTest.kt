package com.mcmyth.picmerge

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGestureTest {
    @Test
    fun pinchKeepsCropAndDragUsesZoomedCoordinates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val image = StitchImage(Uri.EMPTY, 400, 800, Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888))
            var cropped: CropEdge? = null
            val stage = CropStage(context, {}, { edge, position ->
                cropped = edge
                image.setEdge(edge, position)
            })
            stage.show(image, CropEdge.TOP, false)
            stage.measure(exact(640), exact(1200))
            stage.layout(0, 0, 640, 1200)
            val picture = stage.getChildAt(0)
            val start = SystemClock.uptimeMillis()
            send(stage, start, 0, MotionEvent.ACTION_DOWN, 250f, 500f)
            send(stage, start, 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 250f, 500f, 390f, 500f)
            for (offset in 1..6) {
                send(stage, start, 20L + offset * 16, MotionEvent.ACTION_MOVE, 250f - 18 * offset, 500f, 390f + 18 * offset, 500f)
            }
            send(stage, start, 140, MotionEvent.ACTION_POINTER_UP or (1 shl 8), 142f, 500f, 498f, 500f)
            send(stage, start, 156, MotionEvent.ACTION_UP, 142f, 500f)
            assertTrue("Pinch zoom", picture.scaleY > 1.2f)
            assertEquals(0, image.top)
            assertEquals(800, image.bottom)

            stage.reveal(CropEdge.TOP)
            val top = picture.translationY
            val expected = kotlin.math.round(150f * image.height / (picture.height * picture.scaleY)).toInt()
            val drag = start + 200
            send(stage, drag, 0, MotionEvent.ACTION_DOWN, 320f, top)
            send(stage, drag, 48, MotionEvent.ACTION_MOVE, 320f, top + 150f)
            send(stage, drag, 64, MotionEvent.ACTION_UP, 320f, top + 150f)
            assertEquals(CropEdge.TOP, cropped)
            assertEquals(expected, image.top)

            val enlargedScale = picture.scaleY
            val shrink = drag + 100
            send(stage, shrink, 0, MotionEvent.ACTION_DOWN, 100f, 600f)
            send(stage, shrink, 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 100f, 600f, 540f, 600f)
            send(stage, shrink, 40, MotionEvent.ACTION_MOVE, 170f, 600f, 470f, 600f)
            send(stage, shrink, 60, MotionEvent.ACTION_POINTER_UP or (1 shl 8), 170f, 600f, 470f, 600f)
            send(stage, shrink, 80, MotionEvent.ACTION_UP, 170f, 600f)
            assertTrue("Pinch out", picture.scaleY < enlargedScale)
            assertEquals(expected, image.top)
            stage.resetZoom()
            assertEquals(1f, picture.scaleY, 0f)
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun send(stage: CropStage, start: Long, time: Long, action: Int, vararg points: Float) {
        val count = points.size / 2
        val event = MotionEvent.obtain(
            start, start + time, action, count,
            Array(count) { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } },
            Array(count) { id -> MotionEvent.PointerCoords().apply {
                x = points[id * 2]
                y = points[id * 2 + 1]
                pressure = 1f
                size = 1f
            } },
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            stage.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
