package com.sync.xxx

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

class LcdDamageOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.sync.xxx.LCD_DAMAGE_SHOW"
        const val ACTION_HIDE = "com.sync.xxx.LCD_DAMAGE_HIDE"
        
        data class DamageLine(
            val positionRatio: Float,
            val width: Float,
            val color: Int
        )
    }

    private var windowManager: WindowManager? = null
    private var overlayView: LcdDamageView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SHOW -> mainHandler.post { showOverlay() }
                ACTION_HIDE -> mainHandler.post { hideOverlay() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun showOverlay() {
        hideOverlay()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            val damageView = LcdDamageView(this)
            overlayView = damageView
            windowManager?.addView(damageView, params)

            @Suppress("DEPRECATION")
            damageView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

            Log.d("LcdDamage", "LCD damage overlay shown")
        } catch (e: Exception) {
            Log.e("LcdDamage", "showOverlay: ${e.message}")
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        Log.d("LcdDamage", "LCD damage overlay hidden")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // Custom view that draws 5 vertical damage lines across the screen
    private inner class LcdDamageView(context: Context) : View(context) {
        
        private val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        // 5 vertical lines with varying colors/opacity to simulate LCD damage
        private val damageLines = listOf(
            Companion.DamageLine(0.15f, 8f, Color.argb(180, 0, 255, 0)),      // Green line at 15% from left
            Companion.DamageLine(0.32f, 12f, Color.argb(200, 255, 0, 255)),   // Magenta line at 32%
            Companion.DamageLine(0.50f, 6f, Color.argb(160, 255, 255, 255)),  // White line at center
            Companion.DamageLine(0.68f, 10f, Color.argb(190, 0, 255, 255)),   // Cyan line at 68%
            Companion.DamageLine(0.85f, 14f, Color.argb(210, 255, 0, 0))      // Red line at 85%
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val screenWidth = width.toFloat()
            
            // Draw each vertical damage line
            for (line in damageLines) {
                val x = screenWidth * line.positionRatio
                paint.color = line.color
                canvas.drawRect(
                    x - line.width / 2f,
                    0f,
                    x + line.width / 2f,
                    height.toFloat(),
                    paint
                )
            }
        }
    }
}
