package com.abbas.smsm8

import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.media.Image
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
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
    private val openCvReady by lazy { OpenCVLoader.initLocal() }
    private const val REQUIRED_FRAMES = 2
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
        val blackness = ByteArray(count)
        val histogram = IntArray(HUE_BINS)
        val hsv = FloatArray(3)

        // Estimate the cloth color from the central playing area, not from
        // unrelated colorful controls around the edges of the game screen.
        val roiLeft = sampleWidth / 10
        val roiRight = sampleWidth * 9 / 10
        val roiTop = sampleHeight / 10
        val roiBottom = sampleHeight * 9 / 10
        // Read HSV for the entire frame. Only the central playing area votes
        // for the dominant hue, but table-boundary detection must see every row
        // and column (the table can extend into the bottom 10% of the display).
        for (sy in 0 until sampleHeight) {
            val y = min(sy * SAMPLE_STEP, image.height - 1)
            for (sx in 0 until sampleWidth) {
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
                blackness[index] = (255 - max(red, max(green, blue))).toByte()
                if (sx in roiLeft until roiRight && sy in roiTop until roiBottom &&
                    hsv[1] >= 0.24f && hsv[2] in 0.12f..0.99f
                ) {
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
            // Felt lighting and shadows can shift hue across the table.
            // A wider hue tolerance keeps the full playing surface connected.
            cloth[i] = saturations[i] >= 0.14f &&
                values[i] in 0.10f..0.99f && delta <= 44f
        }

        // Pick the largest cloth component, but reject implausible thin bands.
        // The previous detector accepted a long horizontal stripe as the whole table.
        var region = findLargestRegion(cloth, sampleWidth, sampleHeight)
        fun plausible(candidate: Region?): Boolean {
            if (candidate == null) return false
            val regionWidth = candidate.maxX - candidate.minX + 1
            val regionHeight = candidate.maxY - candidate.minY + 1
            val aspect = regionWidth.toFloat() / regionHeight.coerceAtLeast(1)
            return candidate.area >= (count * 0.015f).toInt() &&
                regionWidth >= sampleWidth * 0.35f &&
                regionHeight >= sampleHeight * 0.30f &&
                aspect in 1.25f..2.85f
        }

        if (!plausible(region)) {
            // Hay Day/8 Ball Pool blue felt is cyan-to-blue. This fallback is
            // deliberately independent of the single dominant histogram bin,
            // which can be biased by highlights, overlays, or color gradients.
            for (i in 0 until count) {
                cloth[i] = hues[i] in 145f..260f &&
                    saturations[i] >= 0.08f && values[i] in 0.08f..0.995f
            }
            region = findLargestRegion(cloth, sampleWidth, sampleHeight)
        }

        if (!plausible(region)) {
            previousTracks = emptyList()
            previousTable = null
            return Result(null, emptyList())
        }

        val bestArea = region!!.area
        val minX = region.minX
        val minY = region.minY
        val maxX = region.maxX
        val maxY = region.maxY

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
                // A fresh breadth-first queue for this connected component.
                val queue = ArrayDeque<Int>()
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
        // Prefer a clearly white ball, but tolerate shaded/anti-aliased cue balls.
        val cueCandidate = limitedCandidates
            .maxByOrNull { (1f - it.saturation) * 0.7f + it.value * 0.3f }
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

        // Never guess the shot direction from the nearest object ball:
        // that produces a convincing-looking but incorrect guide when the
        // player is aiming elsewhere. Show no trajectory until the cue shaft
        // is detected with sufficient confidence.
        val aimDirection = cueBall?.let {
            estimateShotDirection(it, expectedDiameter * SAMPLE_STEP * 0.5f,
                sampleWidth, sampleHeight, blackness)
        }
        return Result(table, stableBalls, cueBall, aimDirection)
    }

    private data class Region(
        val area: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

    /** Returns the bounding box of the largest 8-connected true component. */
    private fun findLargestRegion(mask: BooleanArray, width: Int, height: Int): Region? {
        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        var best: Region? = null
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var area = 0
            var minX = start % width
            var maxX = minX
            var minY = start / width
            var maxY = minY
            visited[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val x = index % width
                val y = index / width
                area++
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (mask[next] && !visited[next]) {
                        visited[next] = true
                        queue.add(next)
                    }
                }
            }
            if (best == null || area > best.area) {
                best = Region(area, minX, minY, maxX, maxY)
            }
        }
        return best
    }

    /**
     * Detect the cue shaft as actual line segments in the dark-channel image.
     * This adapts the CMYK/K-channel + Line Segment Detector approach used by
     * open-source 8-ball assistants. A segment is accepted only when it is
     * long and its closest point lies beside the detected cue-ball center.
     */
    private fun estimateShotDirection(
        cue: PointF,
        ballRadiusPixels: Float,
        sampleWidth: Int,
        sampleHeight: Int,
        blackness: ByteArray
    ): PointF? {
        if (!openCvReady || blackness.size != sampleWidth * sampleHeight) return null

        val gray = Mat(sampleHeight, sampleWidth, CvType.CV_8UC1)
        val lines = Mat()
        try {
            gray.put(0, 0, blackness)
            val detector = Imgproc.createLineSegmentDetector()
            detector.detect(gray, lines)
            if (lines.empty()) return null

            val cx = cue.x / SAMPLE_STEP
            val cy = cue.y / SAMPLE_STEP
            val radius = (ballRadiusPixels / SAMPLE_STEP).coerceAtLeast(2f)
            var bestDirection: PointF? = null
            var bestScore = 0f

            for (i in 0 until lines.rows()) {
                val segment = lines.get(i, 0) ?: continue
                if (segment.size < 4) continue
                val x1 = segment[0].toFloat()
                val y1 = segment[1].toFloat()
                val x2 = segment[2].toFloat()
                val y2 = segment[3].toFloat()
                val vx = x2 - x1
                val vy = y2 - y1
                val length = kotlin.math.sqrt(vx * vx + vy * vy)
                if (length < radius * 2.4f) continue

                val lengthSquared = vx * vx + vy * vy
                val t = (((cx - x1) * vx + (cy - y1) * vy) /
                    lengthSquared.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                val nearX = x1 + t * vx
                val nearY = y1 + t * vy
                val offX = nearX - cx
                val offY = nearY - cy
                val distance = kotlin.math.sqrt(offX * offX + offY * offY)

                // The shaft should approach the cue ball from just outside its edge.
                if (distance < radius * 0.45f || distance > radius * 1.9f) continue

                val d1x = x1 - cx
                val d1y = y1 - cy
                val d2x = x2 - cx
                val d2y = y2 - cy
                val farX: Float
                val farY: Float
                if (d1x * d1x + d1y * d1y >= d2x * d2x + d2y * d2y) {
                    farX = d1x
                    farY = d1y
                } else {
                    farX = d2x
                    farY = d2y
                }
                val farLength = kotlin.math.sqrt(farX * farX + farY * farY)
                if (farLength < radius * 2.0f) continue

                // The cue points away from the shaft and through the cue-ball center.
                val score = length / (1f + distance / radius)
                if (score > bestScore) {
                    bestScore = score
                    bestDirection = PointF(-farX / farLength, -farY / farLength)
                }
            }
            return bestDirection
        } catch (error: Exception) {
            android.util.Log.e("smsm8-vision", "Cue line detection failed", error)
            return null
        } finally {
            lines.release()
            gray.release()
        }
    }
}
