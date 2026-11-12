package com.abbas.smsm8

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.util.Log

class AimOverlayService : Service() {
    companion object {
        @Volatile private var activeView: View? = null
        @Volatile private var latestVision: VisionFrame? = null

        private data class VisionFrame(
            val result: TableVisionDetector.Result,
            val imageWidth: Int,
            val imageHeight: Int
        )

        fun updateVision(result: TableVisionDetector.Result, imageWidth: Int, imageHeight: Int) {
            latestVision = VisionFrame(result, imageWidth, imageHeight)
            activeView?.postInvalidate()
        }
    }

    private var windowManager: WindowManager? = null
    private var guideView: GuideView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        guideView = GuideView()
        activeView = guideView
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager?.addView(guideView, params)
    }

    override fun onDestroy() {
        if (activeView === guideView) activeView = null
        guideView?.let { windowManager?.removeView(it) }
        guideView = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class GuideView : View(this@AimOverlayService) {
        private val tableDebug = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 80, 255, 120)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        private val ballDebug = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 70, 210)
            strokeWidth = 3.5f
            style = Paint.Style.STROKE
        }
        private val cueDebug = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 255, 255, 255)
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }
        private val centerDebug = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 255, 70, 70)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val mainLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(245, 45, 220, 255)
            strokeWidth = 7f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val objectLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 220, 55)
            strokeWidth = 6f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val cueDeflectionLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 115, 75)
            strokeWidth = 5.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val ghostLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 255, 255, 255)
            strokeWidth = 4f
            style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 9f), 0f)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            Log.i("smsm8-overlay", "Overlay size: ${w}x${h}")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val frame = latestVision ?: return
            val table = frame.result.table ?: return
            val sx = width.toFloat() / frame.imageWidth.coerceAtLeast(1)
            val sy = height.toFloat() / frame.imageHeight.coerceAtLeast(1)
            val left = table.left * sx
            val top = table.top * sy
            val right = table.right * sx
            val bottom = table.bottom * sy

            // Diagnostic markers: verify detector coordinates before drawing shot guides.
            canvas.drawRect(left, top, right, bottom, tableDebug)
            val ballDiameter = (table.width() * sx / 16f).coerceAtLeast(8f)
            val ballRadius = ballDiameter * 0.5f
            for (ball in frame.result.balls) {
                canvas.drawCircle(ball.x * sx, ball.y * sy, ballRadius, ballDebug)
            }

            val cue = frame.result.cueBall ?: return
            val cueX = cue.x * sx
            val cueY = cue.y * sy
            canvas.drawCircle(cueX, cueY, ballRadius + 5f, cueDebug)
            canvas.drawCircle(cueX, cueY, 3f, centerDebug)
            canvas.drawLine(cueX - 12f, cueY, cueX + 12f, cueY, centerDebug)
            canvas.drawLine(cueX, cueY - 12f, cueX, cueY + 12f, centerDebug)

            val direction = frame.result.aimDirection ?: return

            var dx = direction.x
            var dy = direction.y
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            if (length < 0.001f) return
            dx /= length
            dy /= length

            val minX = left + ballRadius
            val maxX = right - ballRadius
            val minY = top + ballRadius
            val maxY = bottom - ballRadius

            fun distanceToRail(x: Float, y: Float, vx: Float, vy: Float): Float {
                var distance = Float.POSITIVE_INFINITY
                if (vx > 0.0001f) distance = minOf(distance, (maxX - x) / vx)
                if (vx < -0.0001f) distance = minOf(distance, (minX - x) / vx)
                if (vy > 0.0001f) distance = minOf(distance, (maxY - y) / vy)
                if (vy < -0.0001f) distance = minOf(distance, (minY - y) / vy)
                return distance.coerceAtLeast(0f)
            }

            val railDistance = distanceToRail(cueX, cueY, dx, dy)
            val balls = frame.result.balls
            var hit: PointF? = null
            var hitDistance = Float.POSITIVE_INFINITY
            var hitOffset = 0f
            for (ball in balls) {
                val bx = ball.x * sx
                val by = ball.y * sy
                val vx = bx - cueX
                val vy = by - cueY
                val projection = vx * dx + vy * dy
                if (projection <= ballDiameter || projection >= railDistance) continue
                val perpendicular = kotlin.math.abs(vx * dy - vy * dx)
                if (perpendicular <= ballDiameter * 0.78f && projection < hitDistance) {
                    hit = PointF(bx, by)
                    hitDistance = projection
                    hitOffset = perpendicular / ballDiameter
                }
            }

            if (hit != null) {
                val target = hit!!
                val ghostX = target.x - dx * ballDiameter
                val ghostY = target.y - dy * ballDiameter
                canvas.drawLine(cueX, cueY, ghostX, ghostY, mainLine)
                canvas.drawLine(ghostX, ghostY, target.x, target.y, ghostLine)

                val objectRail = distanceToRail(target.x, target.y, dx, dy)
                val objectEndX = target.x + dx * objectRail
                val objectEndY = target.y + dy * objectRail
                canvas.drawLine(target.x, target.y, objectEndX, objectEndY, objectLine)

                val side = if ((target.x - cueX) * dy - (target.y - cueY) * dx >= 0f) 1f else -1f
                val deflectAngle = Math.toRadians((24f + hitOffset * 18f).toDouble()) * side
                val deflectX = (dx * kotlin.math.cos(deflectAngle) - dy * kotlin.math.sin(deflectAngle)).toFloat()
                val deflectY = (dx * kotlin.math.sin(deflectAngle) + dy * kotlin.math.cos(deflectAngle)).toFloat()
                val deflectDistance = distanceToRail(ghostX, ghostY, deflectX, deflectY)
                canvas.drawLine(
                    ghostX, ghostY,
                    ghostX + deflectX * deflectDistance,
                    ghostY + deflectY * deflectDistance,
                    cueDeflectionLine
                )
            } else {
                val railX = cueX + dx * railDistance
                val railY = cueY + dy * railDistance
                canvas.drawLine(cueX, cueY, railX, railY, mainLine)

                // One-cushion rebound preview.
                val hitVerticalRail = railX <= minX + 2f || railX >= maxX - 2f
                val bounceX = if (hitVerticalRail) -dx else dx
                val bounceY = if (hitVerticalRail) dy else -dy
                val secondDistance = distanceToRail(railX, railY, bounceX, bounceY)
                canvas.drawLine(
                    railX, railY,
                    railX + bounceX * secondDistance,
                    railY + bounceY * secondDistance,
                    objectLine
                )
            }
        }
    }
}
