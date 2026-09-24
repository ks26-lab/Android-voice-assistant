package com.chockXlate.teachablevoice.app.service

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.chockXlate.teachablevoice.safety.RuntimeSafetyPolicy

/** Scan labels and input metadata before reading any editable value. Fail closed on large trees. */
internal object TeachingPrivacyGuard {
    private val policy = RuntimeSafetyPolicy()
    fun protectedField(node: AccessibilityNodeInfo): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        return node.isPassword ||
            (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    fun blocks(root: AccessibilityNodeInfo): Boolean {
        var remaining = 1500
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (--remaining < 0 || depth > 60 || protectedField(node)) return true
            val labels = listOfNotNull(node.viewIdResourceName, node.hintText?.toString(),
                node.contentDescription?.toString(), if (!node.isEditable) node.text?.toString() else null)
            if (labels.any(policy::credentialText)) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (visit(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return visit(root, 0)
    }
}
