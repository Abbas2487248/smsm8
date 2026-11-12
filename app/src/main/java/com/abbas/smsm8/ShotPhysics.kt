package com.abbas.smsm8

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Screenshot-driven 2D pool simulation.
 *
 * Coordinates are in captured-screen pixels. It deliberately does not read
 * game memory or inject code into the game process.
 */
object ShotPhysics {
    data class BallPath(val isCueBall: Boolean, val points: List<PointF>)
    data class Result(val paths: List<BallPath>, val firstObjectHit: PointF?)

    private data class Body(
        var x: Float,
        var y: Float,
        var vx: Float = 0f,
        var vy: Float = 0f,
        val cue: Boolean = false,
        val path: MutableList<PointF> = mutableListOf()
    )

    fun predict(
        cue: PointF,
        detectedBalls: List<PointF>,
        table: RectF,
        direction: PointF,
        radius: Float
    ): Result {
        if (radius <= 1f) return Result(emptyList(), null)
        val magnitude = hypot(direction.x, direction.y)
        if (magnitude < 0.001f) return Result(emptyList(), null)

        val left = table.left + radius
        val right = table.right - radius
        val top = table.top + radius
        val bottom = table.bottom - radius
        if (right <= left || bottom <= top) return Result(emptyList(), null)

        val bodies = mutableListOf(
            Body(cue.x, cue.y,
                direction.x / magnitude * INITIAL_SPEED,
                direction.y / magnitude * INITIAL_SPEED,
                cue = true).also { it.path.add(PointF(cue.x, cue.y)) }
        )
        for (point in detectedBalls) {
            if (hypot(point.x - cue.x, point.y - cue.y) < radius * 1.25f) continue
            bodies.add(Body(point.x, point.y).also { it.path.add(PointF(point.x, point.y)) })
        }

        var firstHit: PointF? = null
        repeat(MAX_STEPS) { step ->
            // Integrate movement and cushion response.
            for (body in bodies) {
                val speed = hypot(body.vx, body.vy)
                if (speed < STOP_SPEED) {
                    body.vx = 0f
                    body.vy = 0f
                    continue
                }
                body.x += body.vx * DT
                body.y += body.vy * DT

                if (body.x < left) {
                    body.x = left
                    body.vx = kotlin.math.abs(body.vx) * CUSHION_RESTITUTION
                } else if (body.x > right) {
                    body.x = right
                    body.vx = -kotlin.math.abs(body.vx) * CUSHION_RESTITUTION
                }
                if (body.y < top) {
                    body.y = top
                    body.vy = kotlin.math.abs(body.vy) * CUSHION_RESTITUTION
                } else if (body.y > bottom) {
                    body.y = bottom
                    body.vy = -kotlin.math.abs(body.vy) * CUSHION_RESTITUTION
                }

                val nextSpeed = max(0f, hypot(body.vx, body.vy) - FRICTION * DT)
                if (speed > 0f && nextSpeed < speed) {
                    val scale = nextSpeed / speed
                    body.vx *= scale
                    body.vy *= scale
                }
            }

            // Equal-mass sphere collision: exchange normal velocity components.
            for (i in bodies.indices) {
                for (j in i + 1 until bodies.size) {
                    val a = bodies[i]
                    val b = bodies[j]
                    var dx = b.x - a.x
                    var dy = b.y - a.y
                    val distance = hypot(dx, dy)
                    val diameter = radius * 2f
                    if (distance <= 0.001f || distance >= diameter) continue
                    dx /= distance
                    dy /= distance

                    val relative = (a.vx - b.vx) * dx + (a.vy - b.vy) * dy
                    if (relative > 0f) {
                        val impulse = relative * BALL_RESTITUTION
                        a.vx -= impulse * dx
                        a.vy -= impulse * dy
                        b.vx += impulse * dx
                        b.vy += impulse * dy
                    }
                    val overlap = (diameter - distance) * 0.5f
                    a.x -= dx * overlap
                    a.y -= dy * overlap
                    b.x += dx * overlap
                    b.y += dy * overlap
                    if (firstHit == null && (a.cue || b.cue)) {
                        val objectBody = if (a.cue) b else a
                        firstHit = PointF(objectBody.x, objectBody.y)
                    }
                }
            }

            if (step % TRACE_EVERY == 0) {
                for (body in bodies) {
                    if (body.path.isEmpty() ||
                        hypot(body.path.last().x - body.x, body.path.last().y - body.y) >= radius * 0.22f
                    ) body.path.add(PointF(body.x, body.y))
                }
            }
            if (bodies.all { hypot(it.vx, it.vy) < STOP_SPEED }) return@repeat
        }

        val paths = bodies.mapNotNull { body ->
            if (body.path.size < 2) null else BallPath(body.cue, body.path.toList())
        }
        return Result(paths, firstHit)
    }

    private const val DT = 0.01f
    private const val MAX_STEPS = 420
    private const val TRACE_EVERY = 2
    private const val INITIAL_SPEED = 1050f
    private const val FRICTION = 210f
    private const val STOP_SPEED = 18f
    private const val CUSHION_RESTITUTION = 0.82f
    private const val BALL_RESTITUTION = 0.96f
}
