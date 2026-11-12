package com.abbas.smsm8

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.sin

class AimOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var guideView: AimGuideView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        guideView = AimGuideView(this)
        val type = if (android.os.Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(guideView, params)
    }

    override fun onDestroy() {
        guideView?.let { windowManager.removeView(it) }
        guideView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private class AimGuideView(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 40, 220, 255)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 210, 50)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.58f
        val length = height * 0.34f

        // Temporary visual guide; later replaced by geometry from screen analysis.
        for (offset in -3..3) {
            val angle = Math.toRadians(offset * 4.0 - 90.0)
            val endX = cx + cos(angle).toFloat() * length
            val endY = cy + sin(angle).toFloat() * length
            canvas.drawLine(cx, cy, endX, endY, linePaint)
        }
        canvas.drawCircle(cx, cy, 13f, markerPaint)
    }
}