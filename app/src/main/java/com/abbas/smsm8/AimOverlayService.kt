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
        // Thin alternate routes mirror the multi-colour trajectory style in the reference.
        private val alternateRoutePaints = listOf(
            Color.rgb(80, 245, 115),
            Color.rgb(55, 145, 255),
            Color.rgb(255, 90, 95),
            Color.rgb(255, 175, 95)
        ).map { routeColor ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(205, Color.red(routeColor), Color.green(routeColor), Color.blue(routeColor))
                strokeWidth = 2.6f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        }
        private val routePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            Log.i("smsm8-overlay", "Overlay size: ${w}x${h}")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val frame = latestVision ?: return
            val table = frame.result.table ?: return
            // Capture coordinates are absolute display pixels. An overlay window can
            // have a non-zero screen origin (status/navigation/cutout insets), so
            // translate screen coordinates into this View's local canvas coordinates.
            val screenOrigin = IntArray(2)
            getLocationOnScreen(screenOrigin)
            val sx = width.toFloat() / frame.imageWidth.coerceAtLeast(1)
            val sy = height.toFloat() / frame.imageHeight.coerceAtLeast(1)
            fun mapX(x: Float) = x * sx - screenOrigin[0]
            fun mapY(y: Float) = y * sy - screenOrigin[1]
            val left = mapX(table.left.toFloat())
            val top = mapY(table.top.toFloat())
            val right = mapX(table.right.toFloat())
            val bottom = mapY(table.bottom.toFloat())

            // Diagnostic markers: verify detector coordinates before drawing shot guides.
            canvas.drawRect(left, top, right, bottom, tableDebug)
            val ballDiameter = (table.width() * sx / 16f).coerceAtLeast(8f)
            // Keep diagnostic rings subtle; they mark centers, not the full ball.
            val markerRadius = (ballDiameter * 0.30f).coerceIn(4f, 12f)
            val ballRadius = ballDiameter * 0.5f
            for (ball in frame.result.balls) {
                canvas.drawCircle(mapX(ball.x), mapY(ball.y), markerRadius, ballDebug)
            }

            val cue = frame.result.cueBall ?: return
            val cueX = mapX(cue.x)
            val cueY = mapY(cue.y)
            canvas.drawCircle(cueX, cueY, markerRadius + 2f, cueDebug)
            canvas.drawCircle(cueX, cueY, 3f, centerDebug)
            canvas.drawLine(cueX - 12f, cueY, cueX + 12f, cueY, centerDebug)
            canvas.drawLine(cueX, cueY - 12f, cueX, cueY + 12f, centerDebug)

            val direction = frame.result.aimDirection ?: return
            val simulation = ShotPhysics.predict(
                cue = cue,
                detectedBalls = frame.result.balls,
                table = RectF(table),
                direction = direction,
                radius = table.width() / 32f
            )

            // Render the simulated cue-ball and object-ball paths in overlay space.
            for (path in simulation.paths) {
                val paint = if (path.isCueBall) mainLine else objectLine
                for (index in 0 until path.points.lastIndex) {
                    val from = path.points[index]
                    val to = path.points[index + 1]
                    canvas.drawLine(
                        mapX(from.x), mapY(from.y),
                        mapX(to.x), mapY(to.y),
                        paint
                    )
                }
            }
            simulation.firstObjectHit?.let { hit ->
                canvas.drawCircle(mapX(hit.x), mapY(hit.y), markerRadius * 1.25f, routePointPaint)
            }
        }
    }
}
