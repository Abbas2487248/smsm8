package com.abbas.smsm8

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class AimOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var guideView: GuideView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        guideView = GuideView()
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
        guideView?.let { windowManager?.removeView(it) }
        guideView = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class GuideView : View(this@AimOverlayService) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 70, 220, 255)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val bouncePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 210, 70)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val startX = width * 0.5f
            val startY = height * 0.72f
            val endX = width * 0.82f
            val endY = height * 0.28f

            canvas.drawLine(startX, startY, endX, endY, linePaint)

            val bounce = Path().apply {
                moveTo(endX, endY)
                lineTo(width * 0.96f, height * 0.08f)
                lineTo(width * 0.55f, height * 0.52f)
            }
            canvas.drawPath(bounce, bouncePaint)

            canvas.drawCircle(startX, startY, 10f, linePaint)
            canvas.drawCircle(endX, endY, 8f, bouncePaint)
        }
    }
}
