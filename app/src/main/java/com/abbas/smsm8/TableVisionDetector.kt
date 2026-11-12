package com.abbas.smsm8

import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.media.Image
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the dominant table-cloth hue, extracts the table surface, and detects
 * only compact, round, cloth-surrounded components as balls.
 */
object TableVisionDetector {
    data class Result(
        val table: Rect?,
        val balls: List<PointF>,
        val cueBall: PointF? = null,
        val aimDirection: PointF? = null
    )

    private data class Candidate(val point: PointF, val saturation: Float, val value: Float)
    private data class Track(val point: PointF, val hits: Int, val missed: Int = 0)
    private var previousTracks: List<Track> = emptyList()
    private var previousTable: Rect? = null

    private const val SAMPLE_STEP = 3
    private const val REQUIRED_FRAMES = 3
    private const val HUE_BINS = 36

    @Synchronized
    fun detect(image: Image): Result {
        val plane = image.planes.firstOrNull() ?: return Result(null, emptyList())
        val buffer = plane.buffer
        val base = buffer.position()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val sampleWidth = (image.width + SAMPLE_STEP - 1) / SAMPLE_STEP
        val sampleHeight = (image.height + SAMPLE_STEP - 1) / SAMPLE_STEP
        val count = sampleWidth * sampleHeight
        val hues = FloatArray(count)
        val saturations = FloatArray(count)
        val values = FloatArray(count)
        val histogram = IntArray(HUE_BINS)
        val hsv = FloatArray(3)

        // Estimate the cloth color from the central playing area, not from
        // unrelated colorful controls around the edges of the game screen.
        val roiLeft = sampleWidth / 10
        val roiRight = sampleWidth * 9 / 10
        val roiTop = sampleHeight / 10
        val roiBottom = sampleHeight * 9 / 10
        for (sy in roiTop until roiBottom) {
            val y = min(sy * SAMPLE_STEP, image.height - 1)
            for (sx in roiLeft until roiRight) {
                val x = min(sx * SAMPLE_STEP, image.width - 1)
                val offset = base + y * rowStride + x * pixelStride
                if (offset + 2 >= buffer.limit()) continue
                val red = buffer.get(offset).toInt() and 0xff
                val green = buffer.get(offset + 1).toInt() and 0xff
                val blue = buffer.get(offset + 2).toInt() and 0xff
                Color.RGBToHSV(red, green, blue, hsv)
                val index = sy * sampleWidth + sx
                hues[index] = hsv[0]
                saturations[index] = hsv[1]
                values[index] = hsv[2]
                if (hsv[1] >= 0.28f && hsv[2] in 0.16f..0.96f) {
                    val bin = ((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                    histogram[bin]++
                }
            }
        }

        val dominantBin = histogram.indices.maxByOrNull { histogram[it] } ?: 0
        if (histogram[dominantBin] < (sampleWidth * sampleHeight * 0.006f).toInt()) {
            previousTracks = emptyList()
            previousTable = null
            return Result(null, emptyList())
        }
        val dominantHue = (dominantBin + 0.5f) * 360f / HUE_BINS
        val cloth = BooleanArray(count)
        for (i in 0 until count) {
            var delta = abs(hues[i] - dominantHue)
            if (delta > 180f) delta = 360f - delta
            cloth[i] = saturations[i] >= 0.20f &&
                values[i] in 0.12f..0.98f && delta <= 22f
        }

        // Largest connected region of the dominant cloth color is the table.
        val visitedCloth = BooleanArray(count)
        val queue = ArrayDeque<Int>()
        var bestArea = 0
        var minX = 0
        var minY = 0
        var maxX = -1
        var maxY = -1
        for (start in cloth.indices) {
            if (!cloth[start] || visitedCloth[start]) continue
            var area = 0
            var cMinX = start % sampleWidth
            var cMaxX = cMinX
            var cMinY = start / sampleWidth
            var cMaxY = cMinY
            visitedCloth[start] = true
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
                    if (cloth[next] && !visitedCloth[next]) {
                        visitedCloth[next] = true
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

        if (bestArea < (count * 0.015f).toInt() || maxX <= minX || maxY <= minY) {
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
        ) Rect(rawTable) else Rect(
            (oldTable.left * 0.65f + rawTable.left * 0.35f).toInt(),
            (oldTable.top * 0.65f + rawTable.top * 0.35f).toInt(),
            (oldTable.right * 0.65f + rawTable.right * 0.35f).toInt(),
            (oldTable.bottom * 0.65f + rawTable.bottom * 0.35f).toInt()
        )
        previousTable = Rect(table)

        val marginX = ((maxX - minX + 1) * 0.025f).toInt().coerceAtLeast(2)
        val marginY = ((maxY - minY + 1) * 0.035f).toInt().coerceAtLeast(2)
        val left = (minX + marginX).coerceAtMost(maxX)
        val right = (maxX - marginX).coerceAtLeast(left)
        val top = (minY + marginY).coerceAtMost(maxY)
        val bottom = (maxY - marginY).coerceAtLeast(top)

        val visited = BooleanArray(count)
        val candidates = mutableListOf<Candidate>()
        // On a standard pool table the ball diameter is about 1/16 of its
        // long dimension. Use that scale instead of accepting tiny speckles.
        val expectedDiameter = ((maxX - minX + 1) / 16f).coerceAtLeast(4f)
        val minDiameter = expectedDiameter * 0.62f
        val maxDiameter = expectedDiameter * 1.42f
        val minArea = (expectedDiameter * expectedDiameter * 0.34f).toInt().coerceAtLeast(8)
        val maxArea = (expectedDiameter * expectedDiameter * 1.75f).toInt().coerceAtLeast(minArea + 1)

        for (startY in top..bottom) {
            for (startX in left..right) {
                val start = startY * sampleWidth + startX
                if (cloth[start] || visited[start]) continue
                var area = 0
                var saturationSum = 0f
                var valueSum = 0f
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
                    saturationSum += saturations[index]
                    valueSum += values[index]
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
                        if (!cloth[next] && !visited[next]) {
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
                val roundEnough = ratio in 0.62f..1.55f && fill >= 0.36f
                val ballSized = diameter in (minDiameter * 0.72f)..(maxDiameter * 1.18f) &&
                    area.toFloat() in (minArea * 0.45f)..(maxArea * 1.35f)

                // A true ball should be surrounded by table cloth on most of
                // its perimeter. This rejects holes and stray screen markings.
                var perimeter = 0
                var clothNeighbors = 0
                for (x in cMinX - 1..cMaxX + 1) {
                    for (y in listOf(cMinY - 1, cMaxY + 1)) {
                        if (x in left..right && y in top..bottom) {
                            perimeter++
                            if (cloth[y * sampleWidth + x]) clothNeighbors++
                        }
                    }
                }
                for (y in cMinY..cMaxY) {
                    for (x in listOf(cMinX - 1, cMaxX + 1)) {
                        if (x in left..right && y in top..bottom) {
                            perimeter++
                            if (cloth[y * sampleWidth + x]) clothNeighbors++
                        }
                    }
                }
                val enclosed = perimeter > 0 && clothNeighbors.toFloat() / perimeter >= 0.42f

                if (roundEnough && ballSized && enclosed) {
                    candidates.add(Candidate(
                        PointF(
                            (cMinX + cMaxX) * 0.5f * SAMPLE_STEP,
                            (cMinY + cMaxY) * 0.5f * SAMPLE_STEP
                        ),
                        saturationSum / area.coerceAtLeast(1),
                        valueSum / area.coerceAtLeast(1)
                    ))
                }
            }
        }

        // Temporal matching and the stricter shape checks suppress transient
        // candidates without arbitrarily dropping a real ball at the table edge.
        val limitedCandidates = candidates
        val matchDistance = (expectedDiameter * SAMPLE_STEP * 0.55f).coerceAtLeast(10f)
        val usedPrevious = BooleanArray(previousTracks.size)
        val nextTracks = mutableListOf<Track>()
        val stableBalls = mutableListOf<PointF>()
        for (candidate in limitedCandidates) {
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            for (i in previousTracks.indices) {
                if (usedPrevious[i]) continue
                val old = previousTracks[i].point
                val distance = sqrt(
                    (candidate.point.x - old.x) * (candidate.point.x - old.x) +
                    (candidate.point.y - old.y) * (candidate.point.y - old.y)
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
            val point = if (oldTrack != null) PointF(
                oldTrack.point.x * 0.8f + candidate.point.x * 0.2f,
                oldTrack.point.y * 0.8f + candidate.point.y * 0.2f
            ) else candidate.point
            nextTracks.add(Track(point, hits))
            if (hits >= REQUIRED_FRAMES) stableBalls.add(point)
        }
        for (i in previousTracks.indices) {
            if (usedPrevious[i]) continue
            val old = previousTracks[i]
            val missed = old.missed + 1
            if (old.hits >= REQUIRED_FRAMES && missed <= 1) {
                nextTracks.add(Track(old.point, old.hits, missed))
                stableBalls.add(old.point)
            }
        }
        previousTracks = nextTracks

        // The cue ball is normally the brightest, least-saturated ball.
        val cueCandidate = limitedCandidates
            .filter { it.saturation <= 0.38f && it.value >= 0.62f }
            .maxByOrNull { it.value - it.saturation * 0.65f }
        val cueBall = cueCandidate?.let { candidate ->
            stableBalls.minByOrNull { point ->
                val dx = point.x - candidate.point.x
                val dy = point.y - candidate.point.y
                dx * dx + dy * dy
            }?.takeIf { point ->
                val dx = point.x - candidate.point.x
                val dy = point.y - candidate.point.y
                dx * dx + dy * dy <= expectedDiameter * expectedDiameter * SAMPLE_STEP * SAMPLE_STEP
            }
        }
        val aimDirection = cueBall?.let {
            estimateShotDirection(it, expectedDiameter * SAMPLE_STEP * 0.5f,
                sampleWidth, sampleHeight, saturations, values, cloth)
        }
        return Result(table, stableBalls, cueBall, aimDirection)
    }

    /** Finds the cue shaft behind the white ball and returns the opposite (shot) direction. */
    private fun estimateShotDirection(
        cue: PointF,
        ballRadiusPixels: Float,
        sampleWidth: Int,
        sampleHeight: Int,
        saturation: FloatArray,
        value: FloatArray,
        cloth: BooleanArray
    ): PointF? {
        val cx = cue.x / SAMPLE_STEP
        val cy = cue.y / SAMPLE_STEP
        val radius = ballRadiusPixels / SAMPLE_STEP
        var bestAngle = 0f
        var bestScore = 0f
        for (degree in 0 until 360 step 3) {
            val angle = Math.toRadians(degree.toDouble())
            val ux = kotlin.math.cos(angle).toFloat()
            val uy = kotlin.math.sin(angle).toFloat()
            val px = -uy
            val py = ux
            var score = 0f
            var samples = 0
            var distance = radius * 1.7f
            while (distance <= radius * 9.0f) {
                var bestAtDistance = 0f
                for (offset in -1..1) {
                    val x = (cx + ux * distance + px * offset).toInt()
                    val y = (cy + uy * distance + py * offset).toInt()
                    if (x !in 0 until sampleWidth || y !in 0 until sampleHeight) continue
                    val index = y * sampleWidth + x
                    if (!cloth[index] && saturation[index] in 0.08f..0.82f &&
                        value[index] in 0.22f..0.96f) bestAtDistance = 1f
                }
                score += bestAtDistance
                samples++
                distance += 1.25f
            }
            if (samples > 0) {
                val normalized = score / samples
                if (normalized > bestScore) {
                    bestScore = normalized
                    bestAngle = degree.toFloat()
                }
            }
        }
        if (bestScore < 0.28f) return null
        val radians = Math.toRadians(bestAngle.toDouble())
        return PointF(-kotlin.math.cos(radians).toFloat(), -kotlin.math.sin(radians).toFloat())
    }
}
