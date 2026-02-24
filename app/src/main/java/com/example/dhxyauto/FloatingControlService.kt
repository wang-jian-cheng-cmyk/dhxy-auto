package com.example.dhxyauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingControlService : Service() {
    companion object {
        const val ACTION_SHOW = "show"
        private const val CHANNEL_ID = "dhxy_auto_channel"
        private const val NOTIFICATION_ID = 7
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var loopJob: Job? = null

    private val decisionClient by lazy {
        DecisionClient(baseUrl = "http://127.0.0.1:8787")
    }
    private val engine by lazy {
        AutomationEngine(this, decisionClient)
    }
    private var useMockDecision = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("悬浮服务运行中"))
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingWindow()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLoop("服务停止")
        rootView?.let { windowManager.removeView(it) }
        ScreenCaptureManager.release()
        serviceScope.cancel()
    }

    private fun showFloatingWindow() {
        if (rootView != null) return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC101820.toInt())
            setPadding(24, 24, 24, 24)
        }

        val statusText = TextView(this).apply {
            text = "状态: 待机"
            setTextColor(0xFFFFFFFF.toInt())
        }

        val startBtn = Button(this).apply { text = "开始脚本" }
        val pauseBtn = Button(this).apply { text = "暂停" }
        val stopBtn = Button(this).apply { text = "停止" }
        val modeBtn = Button(this).apply { text = "模式: REAL" }

        startBtn.setOnClickListener {
            if (loopJob?.isActive == true) return@setOnClickListener
            statusText.text = "状态: 运行中"
            startLoop(statusText)
        }

        pauseBtn.setOnClickListener {
            statusText.text = "状态: 已暂停"
            stopLoop("用户暂停")
        }

        stopBtn.setOnClickListener {
            stopLoop("用户停止")
            stopSelf()
        }

        modeBtn.setOnClickListener {
            useMockDecision = !useMockDecision
            engine.setMockDecision(useMockDecision)
            modeBtn.text = if (useMockDecision) "模式: MOCK" else "模式: REAL"
            statusText.text = if (useMockDecision) {
                "状态: 待机 (MOCK决策)"
            } else {
                "状态: 待机 (真实决策)"
            }
        }

        layout.addView(statusText)
        layout.addView(startBtn)
        layout.addView(pauseBtn)
        layout.addView(stopBtn)
        layout.addView(modeBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 160
        }

        windowManager.addView(layout, params)
        rootView = layout
    }

    private fun startLoop(statusText: TextView) {
        engine.setMockDecision(useMockDecision)
        loopJob = serviceScope.launch {
            var nextCaptureMs = 1000L
            while (isActive) {
                val result = engine.decideNextAction()
                if (result == null) {
                    statusText.text = "状态: 网关异常，等待重试"
                    delay(1500)
                    continue
                }

                val executed = engine.executeAction(result)
                statusText.text = if (executed) {
                    "状态: ${result.action} (${result.nextCaptureMs}ms)"
                } else {
                    "状态: 无障碍未连接"
                }

                nextCaptureMs = result.nextCaptureMs.coerceIn(300, 5000).toLong()
                delay(nextCaptureMs)
            }
        }
    }

    private fun stopLoop(reason: String) {
        loopJob?.cancel()
        loopJob = null
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(reason))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DHXY Auto",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DHXY Auto")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
