package com.abbas.smsm8

import android.graphics.PointF
import android.graphics.Rect
import android.media.Image
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight first-pass detector for the green pool table and round,
 * non-cloth objects. It reports candidates only; it does not control input.
 */
object TableVisionDetector {
    data class Result(val table: Rect?, val balls: List<PointF>)

    private const val SAMPLE_STEP = 3

    fun detect(image: Image): Result {
        val plane = image.planes.firstOrNull() ?: return Result(null, emptyList())
        val buffer = plane.buffer
        val base = buffer.position()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val sampleWidth = (image.width + SAMPLE_STEP - 1) / SAMPLE_STEP
        val sampleHeight = (image.height + SAMPLE_STEP - 1) / SAMPLE_STEP
        val green = BooleanArray(sampleWidth * sampleHeight)

        var minX = sampleWidth
        var minY = sampleHeight
        var maxX = -1
        var maxY = -1

        for (sy in 0 until sampleHeight) {
            val y = min(sy * SAMPLE_STEP, image.height - 1)
            for (sx in 0 until sampleWidth) {
                val x = min(sx * SAMPLE_STEP, image.width - 1)
                val offset = base + y * rowStride + x * pixelStride
                if (offset + 2 >= buffer.limit()) continue

                val red = buffer.get(offset).toInt() and 0xff
                val g = buffer.get(offset + 1).toInt() and 0xff
                val blue = buffer.get(offset + 2).toInt() and 0xff
                val isCloth = g > 45 && g > red * 1.12f && g > blue * 1.08f &&
                    (g - red) > 14
                if (isCloth) {
                    green[sy * sampleWidth + sx] = true
                    minX = min(minX, sx)
                    minY = min(minY, sy)
                    maxX = max(maxX, sx)
                    maxY = max(maxY, sy)
                }
            }
        }

        if (maxX <= minX || maxY <= minY) return Result(null, emptyList())

        val table = Rect(
            minX * SAMPLE_STEP,
            minY * SAMPLE_STEP,
            min((maxX + 1) * SAMPLE_STEP, image.width),
            min((maxY + 1) * SAMPLE_STEP, image.height)
        )

        // Ignore the outer cushion edge; search only within the cloth rectangle.
        val left = (minX + 5).coerceAtMost(maxX)
        val right = (maxX - 5).coerceAtLeast(left)
        val top = (minY + 5).coerceAtMost(maxY)
        val bottom = (maxY - 5).coerceAtLeast(top)
        val visited = BooleanArray(sampleWidth * sampleHeight)
        val balls = mutableListOf<PointF>()
        val queue = ArrayDeque<Int>()

        for (startY in top..bottom) {
            for (startX in left..right) {
                val start = startY * sampleWidth + startX
                if (green[start] || visited[start]) continue

                var area = 0
                var cMinX = startX
                var cMaxX = startX
                var cMinY = startY
                var cMaxY = startY
                visited[start] = true
                queue.add(start)

                while (queue.isNotEmpty()) {
                    val index = queue.removeFirst()
                    val x = index % sampleWidth
                    val y = index / sampleWidth
                    area++
                    cMinX = min(cMinX, x)
                    cMaxX = max(cMaxX, x)
                    cMinY = min(cMinY, y)
                    cMaxY = max(cMaxY, y)

                    val neighbors = intArrayOf(
                        if (x > left) index - 1 else -1,
                        if (x < right) index + 1 else -1,
                        if (y > top) index - sampleWidth else -1,
                        if (y < bottom) index + sampleWidth else -1
                    )
                    for (next in neighbors) {
                        if (next >= 0 && !green[next] && !visited[next]) {
                            visited[next] = true
                            queue.add(next)
                        }
                    }
                }

                val boxWidth = cMaxX - cMinX + 1
                val boxHeight = cMaxY - cMinY + 1
                val ratio = boxWidth.toFloat() / boxHeight.toFloat()
                if (area in 8..180 && boxWidth in 3..18 && boxHeight in 3..18 &&
                    ratio in 0.55f..1.8f
                ) {
                    balls.add(
                        PointF(
                            (cMinX + cMaxX) * 0.5f * SAMPLE_STEP,
                            (cMinY + cMaxY) * 0.5f * SAMPLE_STEP
                        )
                    )
                }
            }
        }

        return Result(table, balls)
    }
}
