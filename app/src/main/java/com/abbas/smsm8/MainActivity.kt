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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "smsm8 — Aim Guide Prototype"
            textSize = 22f
            setPadding(24, 32, 24, 16)
        }
        val info = TextView(this).apply {
            text = "First step: allow display over other apps, then show the transparent guide overlay. Live ball tracking and screen analysis come in the next stage."
            textSize = 16f
            setPadding(24, 8, 24, 24)
        }
        val action = Button(this).apply {
            text = "Enable overlay and show guide"
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

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(title)
            addView(info)
            addView(action)
        })
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            // User can tap the button to show the prototype overlay.
        }
    }
}
