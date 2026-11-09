package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Best-effort Accessibility Service for Quick Settings Wi-Fi / Bluetooth tile actuation.
 *
 * When enabled by the user in the Setup Wizard (after allowing Restricted Settings on
 * Android 13+ for sideloaded APKs), attempts to locate and click the matching Quick Settings
 * tile. If not connected or the OEM Quick Settings layout doesn't expose the tile, returns
 * `false` immediately so `SkyTaskExecutor` uses the guaranteed `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`
 * or `Settings.ACTION_BLUETOOTH_SETTINGS` fallback.
 */
class SkyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive; only acts when invoked by user voice command via tryToggleQuickSettingTile
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    private fun clickQuickSettingByLabels(vararg labels: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            for (label in labels) {
                val nodes = root.findAccessibilityNodeInfosByText(label)
                val clickable = nodes?.firstOrNull { it.isClickable }
                    ?: nodes?.firstOrNull()?.parent?.takeIf { it.isClickable }
                if (clickable != null) {
                    val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        @Volatile
        private var activeInstance: SkyAccessibilityService? = null

        fun isInstanceConnected(): Boolean = activeInstance != null

        fun tryToggleQuickSettingTile(vararg labels: String): Boolean {
            val service = activeInstance ?: return false
            return service.clickQuickSettingByLabels(*labels)
        }
    }
}
