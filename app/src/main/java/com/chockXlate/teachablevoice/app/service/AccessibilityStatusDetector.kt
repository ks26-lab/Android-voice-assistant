package com.chockXlate.teachablevoice.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Standalone utility responsible exclusively for detecting whether this application's
 * Accessibility Service (TeachableVoiceAccessibilityService) is currently enabled in Android.
 *
 * Isolated system-state prerequisite. Does not depend on workflows, skills, or runtime execution.
 */
object AccessibilityStatusDetector {

    /**
     * Optional provider for testing/mocking in unit tests without requiring a real Android device.
     */
    var statusProvider: ((Context) -> Boolean)? = null

    /**
     * Returns true if this application's TeachableVoiceAccessibilityService is currently enabled.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        statusProvider?.let { return it(context) }

        // 1. If service instance is already bound and running in memory
        if (TeachableVoiceAccessibilityService.instance != null) {
            return true
        }

        // 2. Query AccessibilityManager for enabled accessibility services
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                val expectedPackage = context.packageName
                val expectedServiceClass = TeachableVoiceAccessibilityService::class.java.name
                val isEnabledInManager = enabledServices.any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo
                    (serviceInfo != null &&
                        serviceInfo.packageName == expectedPackage &&
                        serviceInfo.name == expectedServiceClass) ||
                        info.id == "$expectedPackage/$expectedServiceClass"
                }
                if (isEnabledInManager) return true
            }
        } catch (_: Throwable) {
        }

        // 3. Query Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES as authoritative fallback
        return try {
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expectedComponent1 = ComponentName(context, TeachableVoiceAccessibilityService::class.java).flattenToString()
            val expectedComponent2 = "${context.packageName}/${TeachableVoiceAccessibilityService::class.java.name}"
            val expectedComponent3 = "${context.packageName}/.app.service.TeachableVoiceAccessibilityService"

            enabledServicesSetting.split(':').any { entry ->
                val trimmed = entry.trim()
                trimmed.equals(expectedComponent1, ignoreCase = true) ||
                    trimmed.equals(expectedComponent2, ignoreCase = true) ||
                    trimmed.equals(expectedComponent3, ignoreCase = true) ||
                    (trimmed.startsWith("${context.packageName}/") && trimmed.endsWith("TeachableVoiceAccessibilityService"))
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns the Intent to navigate to the Android Accessibility Settings screen.
     */
    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
