package com.abbas.smsm8

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val overlayRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "smsm8 — Aim Guide"
            textSize = 24f
        })
        layout.addView(TextView(this).apply {
            text = "نسخة تجريبية: خطوط توضيحية فقط. تحليل الكرات وتتبع عصا التصويب غير مضافين بعد."
            textSize = 16f
            setPadding(0, 24, 0, 24)
        })
        layout.addView(Button(this).apply {
            text = "تفعيل خطوط الاختبار"
            setOnClickListener { enableOverlay() }
        })
        layout.addView(Button(this).apply {
            text = "إيقاف الخطوط"
            setOnClickListener { stopService(Intent(this@MainActivity, AimOverlayService::class.java)) }
        })
        setContentView(layout)
    }

    private fun enableOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, overlayRequest)
        } else {
            startService(Intent(this, AimOverlayService::class.java))
        }
    }

    @Deprecated("Deprecated by Android; retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == overlayRequest && Settings.canDrawOverlays(this)) {
            startService(Intent(this, AimOverlayService::class.java))
        }
    }
}