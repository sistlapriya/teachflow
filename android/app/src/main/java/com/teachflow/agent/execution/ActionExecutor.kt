package com.teachflow.agent.execution

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.teachflow.agent.accessibility.Bounds

/**
 * What the executor can do to a grounded element. The handle is the live node that belongs to
 * the grounded snapshot node (an AccessibilityNodeInfo on a device). The interface exists so the
 * same executor logic can also run against a scripted fake app in tests.
 */
interface UiActions {
    /** Returns how the click was delivered, or null on failure. */
    fun click(handle: Any, bounds: Bounds): String?
    fun setText(handle: Any, value: String): Boolean
    fun imeEnter(handle: Any): Boolean
    fun scrollForward(handle: Any): Boolean
    fun scrollBackward(handle: Any): Boolean
}

/**
 * Real implementation: AccessibilityNodeInfo.performAction on the grounded node.
 * Fallback only when the app's view refuses ACTION_CLICK: a tap at the grounded node's *current*
 * on-screen centre. Demonstration coordinates are never replayed.
 */
class ActionExecutor(private val service: AccessibilityService) : UiActions {

    override fun click(handle: Any, bounds: Bounds): String? {
        var n: AccessibilityNodeInfo? = handle as AccessibilityNodeInfo
        var hops = 0
        while (n != null && hops <= 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ACTION_CLICK"
            n = n.parent
            hops++
        }
        return if (tapCenter(bounds)) "tap on grounded node" else null
    }

    override fun setText(handle: Any, value: String): Boolean {
        val node = handle as AccessibilityNodeInfo
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun imeEnter(handle: Any): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            (handle as AccessibilityNodeInfo).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        else false

    override fun scrollForward(handle: Any): Boolean = (handle as AccessibilityNodeInfo).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    override fun scrollBackward(handle: Any): Boolean = (handle as AccessibilityNodeInfo).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun tapCenter(b: Bounds): Boolean {
        if (b.width <= 0 || b.height <= 0) return false
        val path = Path().apply { moveTo((b.left + b.right) / 2f, (b.top + b.bottom) / 2f) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return service.dispatchGesture(gesture, null, null)
    }
}
