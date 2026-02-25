package com.example.dhxyauto

import android.content.Context
import java.security.MessageDigest
import java.util.ArrayDeque

class AutomationEngine(
    context: Context,
    private val client: DecisionClient
) {
    private val appContext = context.applicationContext
    private val history = ArrayDeque<HistoryItem>()
    private var lastFrameHash: String? = null
    private var sameFrameCount = 0
    private var latestFrameHash: String? = null

    @Volatile
    private var useMockDecision = false

    private val goals = mutableListOf(
        GoalItem("mainline_unlock", "主线推进直到解锁日常入口", false, 1),
        GoalItem("level_gate_45", "升级到阶段等级门槛", false, 2),
        GoalItem("one_click_build", "完成一键配点和技能方案", false, 3),
        GoalItem("gear_baseline", "装备提升到最低通关线", false, 4),
        GoalItem("daily_loop", "完成师门和日常循环", false, 5),
        GoalItem("trade_loop", "执行搬砖上架和清包流程", false, 6)
    )

    fun decideNextAction(): DecisionResult {
        if (!ScreenCaptureManager.initIfNeeded(appContext)) {
            return DecisionResult.Failure(
                errorCode = "capture_not_ready",
                errorMessage = "screen capture not initialized",
                requestId = "",
                httpStatus = -1
            )
        }

        val pngBytes = ScreenCaptureManager.capturePngBytes() ?: return DecisionResult.Failure(
            errorCode = "capture_frame_empty",
            errorMessage = "failed to capture latest frame",
            requestId = "",
            httpStatus = -1
        )

        val frameHash = sha1Hex(pngBytes)
        latestFrameHash = frameHash
        val effect = if (lastFrameHash == null) {
            "unknown"
        } else if (lastFrameHash == frameHash) {
            "no_change"
        } else {
            "changed"
        }
        updateLatestHistoryEffect(effect)

        if (effect == "no_change") {
            sameFrameCount += 1
        } else {
            sameFrameCount = 0
        }

        return client.decide(goals, history.toList(), pngBytes, useMockDecision)
    }

    fun setMockDecision(enabled: Boolean) {
        useMockDecision = enabled
    }

    fun executeAction(result: DecisionResponse): Boolean {
        val safeResult = validate(result)
        val width = appContext.resources.displayMetrics.widthPixels
        val height = appContext.resources.displayMetrics.heightPixels
        val x = (safeResult.xNorm * width).toInt().coerceIn(0, width - 1)
        val y = (safeResult.yNorm * height).toInt().coerceIn(0, height - 1)

        val executor = AutomationAccessibilityService.instance ?: return false
        val ok = when (safeResult.action) {
            "tap" -> executor.tap(x, y, safeResult.durationMs.toLong())
            "swipe" -> {
                val toX = (safeResult.swipeToXNorm * width).toInt().coerceIn(0, width - 1)
                val toY = (safeResult.swipeToYNorm * height).toInt().coerceIn(0, height - 1)
                executor.swipe(x, y, toX, toY, safeResult.durationMs.toLong())
            }
            "back" -> executor.goBack()
            "wait" -> true
            "stop" -> true
            else -> true
        }

        pushHistory(
            HistoryItem(
                action = safeResult.action,
                x = x,
                y = y,
                result = if (ok) "ok" else "failed",
                reason = safeResult.reason,
                confidence = safeResult.confidence,
                goalId = safeResult.goalId,
                effect = "pending",
                stuckSignal = sameFrameCount >= 2,
                timestampMs = System.currentTimeMillis()
            )
        )
        lastFrameHash = latestFrameHash
        return ok
    }

    fun shouldTriggerRolePanelSequence(reason: String): Boolean {
        val hitKeyword = reason.contains("角色属性") || reason.contains("属性面板") || reason.contains("弹窗遮挡")
        return hitKeyword && sameFrameCount >= 2
    }

    private fun validate(result: DecisionResponse): DecisionResponse {
        return result.copy(
            xNorm = result.xNorm.coerceIn(0.0, 1.0),
            yNorm = result.yNorm.coerceIn(0.0, 1.0),
            swipeToXNorm = result.swipeToXNorm.coerceIn(0.0, 1.0),
            swipeToYNorm = result.swipeToYNorm.coerceIn(0.0, 1.0),
            confidence = result.confidence.coerceIn(0.0, 1.0),
            durationMs = result.durationMs.coerceIn(50, 1200),
            nextCaptureMs = result.nextCaptureMs.coerceIn(300, 5000)
        )
    }

    private fun pushHistory(item: HistoryItem) {
        if (history.size >= 5) history.removeFirst()
        history.addLast(item)
    }

    private fun updateLatestHistoryEffect(effect: String) {
        if (history.isEmpty()) return
        val last = history.removeLast()
        history.addLast(last.copy(effect = effect, stuckSignal = sameFrameCount >= 2))
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
