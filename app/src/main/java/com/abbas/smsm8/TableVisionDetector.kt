package com.abbas.smsm8

import android.graphics.PointF
import android.graphics.Rect
import android.media.Image
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detects the dominant green cloth region, then looks for compact round
 * non-cloth components inside it. Temporal smoothing reduces overlay jitter.
 */
object TableVisionDetector {
    data class Result(val table: Rect?, val balls: List<PointF>)

    private data class Track(val point: PointF, val hits: Int, val missed: Int = 0)
    private var previousTracks: List<Track> = emptyList()
    private var previousTable: Rect? = null

    private const val SAMPLE_STEP = 3
    private const val REQUIRED_FRAMES = 3

    @Synchronized
    fun detect(image: Image): Result {
        val plane = image.planes.firstOrNull() ?: return Result(null, emptyList())
        val buffer = plane.buffer
        val base = buffer.position()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val sampleWidth = (image.width + SAMPLE_STEP - 1) / SAMPLE_STEP
        val sampleHeight = (image.height + SAMPLE_STEP - 1) / SAMPLE_STEP
        val green = BooleanArray(sampleWidth * sampleHeight)

        for (sy in 0 until sampleHeight) {
            val y = min(sy * SAMPLE_STEP, image.height - 1)
            for (sx in 0 until sampleWidth) {
                val x = min(sx * SAMPLE_STEP, image.width - 1)
                val offset = base + y * rowStride + x * pixelStride
                if (offset + 2 >= buffer.limit()) continue

                val red = buffer.get(offset).toInt() and 0xff
                val g = buffer.get(offset + 1).toInt() and 0xff
                val blue = buffer.get(offset + 2).toInt() and 0xff
                green[sy * sampleWidth + sx] =
                    g > 42 && g > red * 1.08f && g > blue * 1.04f &&
                    (g - red) > 12 && (g - blue) > 6
            }
        }

        // Use the largest connected green region, not isolated green pixels
        // elsewhere on the screen, to estimate the playing surface.
        val greenVisited = BooleanArray(green.size)
        val queue = ArrayDeque<Int>()
        var bestArea = 0
        var minX = 0
        var minY = 0
        var maxX = -1
        var maxY = -1

        for (start in green.indices) {
            if (!green[start] || greenVisited[start]) continue
            var area = 0
            var cMinX = start % sampleWidth
            var cMaxX = cMinX
            var cMinY = start / sampleWidth
            var cMaxY = cMinY
            greenVisited[start] = true
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
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until sampleWidth || ny !in 0 until sampleHeight) continue
                    val next = ny * sampleWidth + nx
                    if (green[next] && !greenVisited[next]) {
                        greenVisited[next] = true
                        queue.add(next)
                    }
                }
            }
            if (area > bestArea) {
                bestArea = area
                minX = cMinX
                maxX = cMaxX
                minY = cMinY
                maxY = cMaxY
            }
        }

        if (bestArea < (sampleWidth * sampleHeight * 0.015f).toInt() ||
            maxX <= minX || maxY <= minY
        ) {
            previousTracks = emptyList()
            previousTable = null
            return Result(null, emptyList())
        }

        val rawTable = Rect(
            minX * SAMPLE_STEP,
            minY * SAMPLE_STEP,
            min((maxX + 1) * SAMPLE_STEP, image.width),
            min((maxY + 1) * SAMPLE_STEP, image.height)
        )
        val oldTable = previousTable
        val table = if (oldTable == null ||
            abs(rawTable.left - oldTable.left) > image.width * 0.08f ||
            abs(rawTable.top - oldTable.top) > image.height * 0.08f ||
            abs(rawTable.right - oldTable.right) > image.width * 0.08f ||
            abs(rawTable.bottom - oldTable.bottom) > image.height * 0.08f
        ) {
            Rect(rawTable)
        } else {
            Rect(
                (oldTable.left * 0.75f + rawTable.left * 0.25f).toInt(),
                (oldTable.top * 0.75f + rawTable.top * 0.25f).toInt(),
                (oldTable.right * 0.75f + rawTable.right * 0.25f).toInt(),
                (oldTable.bottom * 0.75f + rawTable.bottom * 0.25f).toInt()
            )
        }
        previousTable = Rect(table)

        val marginX = ((maxX - minX + 1) * 0.025f).toInt().coerceAtLeast(2)
        val marginY = ((maxY - minY + 1) * 0.035f).toInt().coerceAtLeast(2)
        val left = (minX + marginX).coerceAtMost(maxX)
        val right = (maxX - marginX).coerceAtLeast(left)
        val top = (minY + marginY).coerceAtMost(maxY)
        val bottom = (maxY - marginY).coerceAtLeast(top)

        val visited = BooleanArray(green.size)
        val candidates = mutableListOf<PointF>()
        val expectedDiameter = ((maxX - minX + 1) / 40f).coerceAtLeast(3f)
        val minDiameter = (expectedDiameter * 0.55f).coerceAtLeast(3f)
        val maxDiameter = expectedDiameter * 1.55f
        val minArea = (expectedDiameter * expectedDiameter * 0.28f).toInt().coerceAtLeast(4)
        val maxArea = (expectedDiameter * expectedDiameter * 1.65f).toInt().coerceAtLeast(minArea + 1)

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

                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in left..right || ny !in top..bottom) continue
                        val next = ny * sampleWidth + nx
                        if (!green[next] && !visited[next]) {
                            visited[next] = true
                            queue.add(next)
                        }
                    }
                }

                val boxWidth = cMaxX - cMinX + 1
                val boxHeight = cMaxY - cMinY + 1
                val ratio = boxWidth.toFloat() / boxHeight.toFloat()
                val fill = area.toFloat() / (boxWidth * boxHeight).coerceAtLeast(1)
                val diameter = (boxWidth + boxHeight) * 0.5f
                val roundEnough = ratio in 0.72f..1.38f && fill >= 0.48f
                val ballSized = diameter in minDiameter..maxDiameter &&
                    area in minArea..maxArea

                if (roundEnough && ballSized) {
                    candidates.add(
                        PointF(
                            (cMinX + cMaxX) * 0.5f * SAMPLE_STEP,
                            (cMinY + cMaxY) * 0.5f * SAMPLE_STEP
                        )
                    )
                }
            }
        }

        val matchDistance = (expectedDiameter * SAMPLE_STEP * 0.85f).coerceAtLeast(12f)
        val usedPrevious = BooleanArray(previousTracks.size)
        val nextTracks = mutableListOf<Track>()
        val stableBalls = mutableListOf<PointF>()

        for (candidate in candidates) {
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            for (i in previousTracks.indices) {
                if (usedPrevious[i]) continue
                val old = previousTracks[i].point
                val distance = sqrt(
                    (candidate.x - old.x) * (candidate.x - old.x) +
                    (candidate.y - old.y) * (candidate.y - old.y)
                )
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = i
                }
            }

            val matched = bestIndex >= 0 && bestDistance <= matchDistance
            val oldTrack = if (matched) previousTracks[bestIndex] else null
            if (matched) usedPrevious[bestIndex] = true
            val hits = (oldTrack?.hits ?: 0) + 1
            val point = if (oldTrack != null) {
                PointF(
                    oldTrack.point.x * 0.8f + candidate.x * 0.2f,
                    oldTrack.point.y * 0.8f + candidate.y * 0.2f
                )
            } else candidate
            nextTracks.add(Track(point, hits))
            if (hits >= REQUIRED_FRAMES) stableBalls.add(point)
        }

        for (i in previousTracks.indices) {
            if (usedPrevious[i]) continue
            val old = previousTracks[i]
            val missed = old.missed + 1
            if (old.hits >= REQUIRED_FRAMES && missed <= 2) {
                nextTracks.add(Track(old.point, old.hits, missed))
                stableBalls.add(old.point)
            }
        }

        previousTracks = nextTracks
        return Result(table, stableBalls)
    }
}
