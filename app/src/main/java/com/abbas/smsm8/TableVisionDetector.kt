package com.abbas.smsm8

import android.graphics.PointF
import android.graphics.Rect
import android.media.Image
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * First-pass pool-table detector. It deliberately favors fewer, steadier
 * circular candidates over drawing every non-cloth patch as a ball.
 */
object TableVisionDetector {
    data class Result(val table: Rect?, val balls: List<PointF>)

    private data class Track(val point: PointF, val hits: Int, val missed: Int = 0)
    private var previousTracks: List<Track> = emptyList()

    private const val SAMPLE_STEP = 3
    private const val REQUIRED_FRAMES = 2

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
                val isCloth = g > 48 && g > red * 1.16f && g > blue * 1.10f &&
                    (g - red) > 18 && (g - blue) > 10
                if (isCloth) {
                    green[sy * sampleWidth + sx] = true
                    minX = min(minX, sx)
                    minY = min(minY, sy)
                    maxX = max(maxX, sx)
                    maxY = max(maxY, sy)
                }
            }
        }

        if (maxX <= minX || maxY <= minY) {
            previousTracks = emptyList()
            return Result(null, emptyList())
        }

        val table = Rect(
            minX * SAMPLE_STEP,
            minY * SAMPLE_STEP,
            min((maxX + 1) * SAMPLE_STEP, image.width),
            min((maxY + 1) * SAMPLE_STEP, image.height)
        )

        // Search inside the cloth, leaving a wider margin for cushions and rails.
        val left = (minX + 6).coerceAtMost(maxX)
        val right = (maxX - 6).coerceAtLeast(left)
        val top = (minY + 6).coerceAtMost(maxY)
        val bottom = (maxY - 6).coerceAtLeast(top)
        val visited = BooleanArray(sampleWidth * sampleHeight)
        val candidates = mutableListOf<PointF>()
        val queue = ArrayDeque<Int>()

        // A pool ball is roughly one twentieth of the playing-surface width.
        val expectedDiameter = ((maxX - minX + 1) / 40f).coerceAtLeast(3f)
        val minDiameter = (expectedDiameter * 0.55f).coerceAtLeast(3f)
        val maxDiameter = expectedDiameter * 1.55f
        val minArea = (expectedDiameter * expectedDiameter * 0.22f).toInt().coerceAtLeast(5)
        val maxArea = (expectedDiameter * expectedDiameter * 1.25f).toInt().coerceAtLeast(minArea + 1)

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

                    // Eight-way connectivity keeps sampled round objects together.
                    for (dy in -1..1) {
                        for (dx in -1..1) {
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
                }

                val boxWidth = cMaxX - cMinX + 1
                val boxHeight = cMaxY - cMinY + 1
                val ratio = boxWidth.toFloat() / boxHeight.toFloat()
                val fill = area.toFloat() / (boxWidth * boxHeight).coerceAtLeast(1)
                val diameter = (boxWidth + boxHeight) * 0.5f
                val roundEnough = ratio in 0.78f..1.28f && fill >= 0.58f
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

        // Match detections across frames. Keep a track briefly when one frame
        // misses it, preventing flicker from small capture/lighting variations.
        val matchDistance = (expectedDiameter * SAMPLE_STEP * 0.65f).coerceAtLeast(7f)
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
            // Smooth the center to reduce small frame-to-frame jumps.
            val point = if (oldTrack != null) {
                PointF(
                    oldTrack.point.x * 0.65f + candidate.x * 0.35f,
                    oldTrack.point.y * 0.65f + candidate.y * 0.35f
                )
            } else candidate
            nextTracks.add(Track(point, hits, 0))
            if (hits >= REQUIRED_FRAMES) stableBalls.add(point)
        }

        // Retain unmatched confirmed detections for at most two frames.
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
