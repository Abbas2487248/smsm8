package com.abbas.smsm8

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequestCode = 4103

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "smsm8 — Pool Aim Guide"
            textSize = 22f
            setPadding(24, 32, 24, 16)
        }
        val info = TextView(this).apply {
            text = "يوضح خطوط التصويب ومسار الكرة وارتدادها عند اكتشاف الكرة البيضاء وعصا التصويب."
            textSize = 16f
            setPadding(24, 8, 24, 24)
        }
        val overlayButton = Button(this).apply {
            text = "تشغيل خطوط التصويب"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    startService(Intent(this@MainActivity, AimOverlayService::class.java))
                }
            }
        }
        val captureButton = Button(this).apply {
            text = "السماح بالتقاط الشاشة"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
            }
        }
        val stopButton = Button(this).apply {
            text = "إيقاف التقاط الشاشة"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(title)
            addView(info)
            addView(overlayButton)
            addView(captureButton)
            addView(stopButton)
        })
    }

    @Deprecated("Deprecated by Android; retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequestCode && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
}
