package com.abbas.smsm8

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

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
        private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 50, 230, 120)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(240, 255, 70, 70)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val frame = latestVision ?: return
            val sx = width.toFloat() / frame.imageWidth.coerceAtLeast(1)
            val sy = height.toFloat() / frame.imageHeight.coerceAtLeast(1)

            frame.result.table?.let { table ->
                canvas.drawRect(
                    RectF(table.left * sx, table.top * sy, table.right * sx, table.bottom * sy),
                    tablePaint
                )
            }

            frame.result.balls.forEach { ball ->
                canvas.drawCircle(ball.x * sx, ball.y * sy, 13f, ballPaint)
            }

            val status = if (frame.result.table == null) {
                "Table: not detected"
            } else {
                "Table detected | Ball candidates: ${frame.result.balls.size}"
            }
            canvas.drawText(status, 24f, 48f, textPaint)
        }
    }
}
