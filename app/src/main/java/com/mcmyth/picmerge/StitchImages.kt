package com.mcmyth.picmerge

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import java.io.IOException
import java.io.OutputStream
import kotlin.math.roundToInt

internal data class StitchImage(
    val uri: Uri,
    val width: Int,
    val height: Int,
    val preview: Bitmap,
    var top: Int = 0,
    var bottom: Int = height
) {
    val keptHeight: Int get() = bottom - top

    fun setEdge(edge: CropEdge, position: Int) {
        when (edge) {
            CropEdge.TOP -> top = position.coerceIn(0, bottom - 1)
            CropEdge.BOTTOM -> bottom = position.coerceIn(top + 1, height)
        }
    }

    fun position(edge: CropEdge) = if (edge == CropEdge.TOP) top else bottom
}

internal enum class CropEdge(val label: String) { TOP("起点"), BOTTOM("终点") }

internal object StitchImages {
    private const val PREVIEW_EDGE = 1200
    private const val PREVIEW_PIXELS = 480_000.0
    private const val MAX_OUTPUT_EDGE = 32_768
    private const val MAX_OUTPUT_PIXELS = 20_000_000L

    fun load(resolver: ContentResolver, uri: Uri): StitchImage {
        var width = 0
        var height = 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            // CropStage and the composited overview both need mutable software-readable pixels.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            width = info.size.width
            height = info.size.height
            val scale = minOf(
                1.0,
                PREVIEW_EDGE.toDouble() / maxOf(width, height),
                kotlin.math.sqrt(PREVIEW_PIXELS / (width.toDouble() * height))
            )
            decoder.setTargetSize(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1)
            )
        }
        bitmap.density = Bitmap.DENSITY_NONE
        return StitchImage(uri, width, height, bitmap)
    }

    fun scaledHeight(image: StitchImage, width: Int): Int =
        (image.keptHeight.toDouble() * width / image.width).roundToInt().coerceAtLeast(1)

    fun outputWidth(images: List<StitchImage>): Int {
        val pixelBudget = minOf(MAX_OUTPUT_PIXELS, Runtime.getRuntime().maxMemory() / 16)
        var low = 1
        var high = minOf(MAX_OUTPUT_EDGE, images.minOf { it.width })
        while (low < high) {
            val width = (low + high + 1) / 2
            val height = images.sumOf { scaledHeight(it, width).toLong() }
            if (height <= MAX_OUTPUT_EDGE && height * width <= pixelBudget) {
                low = width
            } else {
                high = width - 1
            }
        }
        return low
    }

    /**
     * Builds a lightweight, complete composited bitmap for the editor's overview.
     * This deliberately uses pixel copies instead of a custom Canvas UI: the result is
     * displayed by a normal ImageView and never replaces the full-resolution export path.
     */
    fun createOverview(images: List<StitchImage>, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (images.isEmpty() || maxWidth <= 0 || maxHeight <= 0) return null
        val ratio = images.sumOf { it.keptHeight.toDouble() / it.width }
        if (ratio <= 0.0) return null
        val width = minOf(maxWidth, (maxHeight / ratio).roundToInt()).coerceAtLeast(1)
        val heights = images.map { scaledHeight(it, width) }
        val height = heights.sum().coerceAtLeast(1)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * maxOf(1, heights.max()))
        var destinationTop = 0
        images.zip(heights).forEach { (image, destinationHeight) ->
            val sourceTop = (image.top.toDouble() * image.preview.height / image.height).roundToInt()
                .coerceIn(0, image.preview.height - 1)
            val sourceBottom = (image.bottom.toDouble() * image.preview.height / image.height).roundToInt()
                .coerceIn(sourceTop + 1, image.preview.height)
            val source = Bitmap.createBitmap(
                image.preview, 0, sourceTop.coerceIn(0, image.preview.height - 1),
                image.preview.width, (sourceBottom - sourceTop).coerceAtLeast(1)
            )
            val scaled = Bitmap.createScaledBitmap(source, width, destinationHeight, true)
            var row = 0
            while (row < destinationHeight) {
                val rows = minOf(destinationHeight - row, pixels.size / width)
                scaled.getPixels(pixels, 0, width, 0, row, width, rows)
                result.setPixels(pixels, 0, width, 0, destinationTop + row, width, rows)
                row += rows
            }
            // Do not recycle source/scaled here. Android may return image.preview itself
            // for a full-size crop, and that bitmap is also used by CropStage/thumbnails.
            destinationTop += destinationHeight
        }
        return result
    }

    fun writePng(resolver: ContentResolver, images: List<StitchImage>, stream: OutputStream) {
        val width = outputWidth(images)
        val height = images.sumOf { scaledHeight(it, width).toLong() }
        if (height > MAX_OUTPUT_EDGE) throw IOException("图片过长，请缩短裁切范围后重试")
        val output = Bitmap.createBitmap(width, height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            val rowsPerCopy = maxOf(1, 65_536 / width)
            val pixels = IntArray(width * rowsPerCopy)
            var destinationTop = 0
            for (image in images) {
                val segment = decodeSegment(resolver, image, width)
                try {
                    var row = 0
                    while (row < segment.height) {
                        val rows = minOf(rowsPerCopy, segment.height - row)
                        segment.getPixels(pixels, 0, width, 0, row, width, rows)
                        output.setPixels(pixels, 0, width, 0, destinationTop + row, width, rows)
                        row += rows
                    }
                    destinationTop += segment.height
                } finally {
                    segment.recycle()
                }
            }
            if (!output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("无法写入 PNG")
            }
        } finally {
            output.recycle()
        }
    }

    private fun decodeSegment(resolver: ContentResolver, image: StitchImage, width: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, image.uri)) { decoder, info, _ ->
            if (info.size.width != image.width || info.size.height != image.height) {
                throw IOException("原图尺寸已改变，请重新导入")
            }
            val scale = width.toDouble() / image.width
            val height = scaledHeight(image, width)
            val top = (image.top * scale).roundToInt()
            val fullHeight = maxOf((image.height * scale).roundToInt(), top + height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(width, fullHeight)
            decoder.crop = Rect(0, top, width, top + height)
        }
}
