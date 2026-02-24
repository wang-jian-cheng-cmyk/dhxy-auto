package com.example.dhxyauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.net.Uri
import android.provider.Settings
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingControlService : Service() {
    companion object {
        const val ACTION_SHOW = "show"
        private const val CHANNEL_ID = "dhxy_auto_channel"
        private const val NOTIFICATION_ID = 7
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loopJob: Job? = null

    private val decisionClient by lazy {
        DecisionClient(baseUrl = "http://127.0.0.1:8787")
    }
    private val engine by lazy {
        AutomationEngine(this, decisionClient)
    }
    private var useMockDecision = false

    private val waitFallbackThreshold = 3
    private val fallbackTapXNorm = 0.88
    private val fallbackTapYNorm = 0.32

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

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val statusText = TextView(this).apply {
            text = "状态: 待机"
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val toggleBtn = Button(this).apply { text = "收起" }

        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val startBtn = Button(this).apply { text = "开始脚本" }
        val pauseBtn = Button(this).apply { text = "暂停" }
        val stopBtn = Button(this).apply { text = "停止" }
        val modeBtn = Button(this).apply { text = "模式: REAL" }
        val testTapBtn = Button(this).apply { text = "测试点击" }
        val openA11yBtn = Button(this).apply { text = "打开无障碍设置" }
        val openAppDetailsBtn = Button(this).apply { text = "打开应用详情" }
        val diagnoseBtn = Button(this).apply { text = "连接诊断" }

        var collapsed = false

        fun setCollapsed(value: Boolean) {
            collapsed = value
            controlsLayout.visibility = if (collapsed) View.GONE else View.VISIBLE
            statusText.visibility = if (collapsed) View.GONE else View.VISIBLE
            toggleBtn.text = if (collapsed) "◉" else "收起"
            layout.setPadding(
                if (collapsed) 8 else 24,
                if (collapsed) 8 else 24,
                if (collapsed) 8 else 24,
                if (collapsed) 8 else 24,
            )
        }

        toggleBtn.setOnClickListener {
            setCollapsed(!collapsed)
        }

        startBtn.setOnClickListener {
            if (loopJob?.isActive == true) return@setOnClickListener
            statusText.text = "状态: 运行中"
            startLoop(statusText)
            setCollapsed(true)
        }

        pauseBtn.setOnClickListener {
            statusText.text = "状态: 已暂停"
            stopLoop("用户暂停")
        }

        stopBtn.setOnClickListener {
            stopLoop("用户停止")
            stopSelf()
        }

        testTapBtn.setOnClickListener {
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            val svc = AutomationAccessibilityService.instance
            if (!AutomationAccessibilityService.isServiceReady(this)) {
                statusText.text = "状态: ${diagnoseAccessibilityState()}"
                return@setOnClickListener
            }

            val ok = svc?.tap((width * 0.5).toInt(), (height * 0.6).toInt(), 120) == true
            statusText.text = if (ok) "状态: 测试点击已发送" else "状态: 测试点击失败"
        }

        openA11yBtn.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        openAppDetailsBtn.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        diagnoseBtn.setOnClickListener {
            statusText.text = "状态: ${diagnoseAccessibilityState()}"
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

        headerLayout.addView(statusText)
        headerLayout.addView(toggleBtn)
        controlsLayout.addView(startBtn)
        controlsLayout.addView(pauseBtn)
        controlsLayout.addView(stopBtn)
        controlsLayout.addView(modeBtn)
        controlsLayout.addView(testTapBtn)
        controlsLayout.addView(openA11yBtn)
        controlsLayout.addView(openAppDetailsBtn)
        controlsLayout.addView(diagnoseBtn)
        layout.addView(headerLayout)
        layout.addView(controlsLayout)

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
        loopJob = serviceScope.launch(Dispatchers.IO) {
            var nextCaptureMs = 1000L
            var consecutiveWaits = 0
            while (isActive) {
                try {
                    if (!AutomationAccessibilityService.isServiceReady(this@FloatingControlService)) {
                        withContext(Dispatchers.Main) {
                            statusText.text = "状态: ${diagnoseAccessibilityState()}"
                        }
                        delay(1000)
                        continue
                    }

                    if (!ScreenCaptureManager.hasProjectionPermission()) {
                        withContext(Dispatchers.Main) {
                            statusText.text = "状态: 请先在主界面申请录屏权限"
                        }
                        delay(1200)
                        continue
                    }

                    val result = engine.decideNextAction()
                    if (result == null) {
                        withContext(Dispatchers.Main) {
                            statusText.text = "状态: 网关异常，等待重试"
                        }
                        delay(1500)
                        continue
                    }

                    val finalResult = if (result.action == "wait") {
                        consecutiveWaits += 1
                        if (consecutiveWaits >= waitFallbackThreshold) {
                            consecutiveWaits = 0
                            result.copy(
                                action = "tap",
                                xNorm = fallbackTapXNorm,
                                yNorm = fallbackTapYNorm,
                                swipeToXNorm = 0.0,
                                swipeToYNorm = 0.0,
                                durationMs = 120,
                                nextCaptureMs = 1200,
                                confidence = 0.8,
                                reason = "wait_fallback_task_tap"
                            )
                        } else {
                            result
                        }
                    } else {
                        consecutiveWaits = 0
                        result
                    }

                    val executed = engine.executeAction(finalResult)
                    withContext(Dispatchers.Main) {
                        statusText.text = if (executed) {
                            "状态: ${finalResult.action} (${finalResult.nextCaptureMs}ms)"
                        } else {
                            "状态: 无障碍未连接"
                        }
                    }

                    if (finalResult.action == "stop") {
                        withContext(Dispatchers.Main) {
                            statusText.text = "状态: 已停止"
                        }
                        break
                    }

                    nextCaptureMs = finalResult.nextCaptureMs.coerceIn(300, 5000).toLong()
                    delay(nextCaptureMs)
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "状态: 循环异常，重试中"
                    }
                    delay(1500)
                }
            }
        }
    }

    private fun stopLoop(reason: String) {
        loopJob?.cancel()
        loopJob = null
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(reason))
    }

    private fun diagnoseAccessibilityState(): String {
        val enabled = AutomationAccessibilityService.isServiceEnabled(this)
        val connected = AutomationAccessibilityService.isConnected
        return when {
            !enabled -> "无障碍未启用"
            enabled && !connected -> "无障碍已启用但服务未运行"
            !AutomationAccessibilityService.isServiceReady(this) -> "无障碍状态异常"
            else -> "无障碍已连接"
        }
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
