package com.example.dhxyauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutomationAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set

        @Volatile
        var isConnected: Boolean = false
            private set

        fun isServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = ComponentName(context, AutomationAccessibilityService::class.java)
            val full = component.flattenToString()
            val short = component.flattenToShortString()
            return enabled.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        }

        fun isServiceReady(context: Context): Boolean {
            return isConnected && instance != null && isServiceEnabled(context)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        isConnected = false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isConnected = false
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        if (instance === this) instance = null
    }

    fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun goBack(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun clickNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickNodeRecursive(root, text)
    }

    private fun clickNodeRecursive(node: AccessibilityNodeInfo, text: String): Boolean {
        val currentText = node.text?.toString().orEmpty()
        if (currentText.contains(text, ignoreCase = true) && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickNodeRecursive(child, text)) return true
        }
        return false
    }
}
