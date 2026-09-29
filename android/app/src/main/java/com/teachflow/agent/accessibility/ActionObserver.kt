package com.teachflow.agent.accessibility

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.teachflow.agent.core.AgentBus

enum class ActionType { CLICK, LONG_CLICK, INPUT_TEXT, SCROLL }
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT, UNKNOWN }

data class ObservedAction(
    val id: Long,
    val timestamp: Long,
    val type: ActionType,
    val packageName: String,
    val target: NodeSnapshot?,
    /** Event text when the source node was unavailable. Null when sensitive. */
    val fallbackLabel: String?,
    /** Typed value; "[REDACTED]" for sensitive fields. */
    val inputValue: String?,
    val redacted: Boolean,
    val scrollDirection: ScrollDirection?,
    val screenBefore: String?,
    val screenAfter: String? = null,
) {
    val displayLabel: String get() = target?.displayLabel ?: fallbackLabel ?: "(unlabelled)"
}

/**
 * Turns raw accessibility events into [ObservedAction]s. Typing and scrolling are coalesced so a
 * word becomes one INPUT_TEXT action and a fling becomes one SCROLL action.
 */
class ActionObserver(private val parser: AccessibilityTreeParser) {

    /** Called with (action, isUpdateOfPrevious). */
    var listener: ((ObservedAction, Boolean) -> Unit)? = null

    private var nextId = 1L
    private var lastInput: ObservedAction? = null
    private var lastScroll: ObservedAction? = null

    fun onEvent(e: AccessibilityEvent, screenW: Int, screenH: Int, fingerprintBefore: String?, before: ScreenSnapshot?) {
        val pkg = e.packageName?.toString() ?: return
        val type = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> ActionType.CLICK
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> ActionType.LONG_CLICK
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> ActionType.INPUT_TEXT
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> ActionType.SCROLL
            else -> return
        }
        val source = e.source
        val target = source?.let { parser.snapshotSingle(it, screenW, screenH) }
        source?.let { AccessibilityTreeParser.recycleCompat(it) }

        val sensitive = e.isPassword || target?.redacted == true
        val fallback = if (sensitive) null
        else e.text?.joinToString(" ")?.takeIf { it.isNotBlank() }?.take(160) ?: e.contentDescription?.toString()
        val now = System.currentTimeMillis()

        when (type) {
            ActionType.INPUT_TEXT -> {
                val value = if (sensitive) REDACTED else e.text?.joinToString("")?.take(200)
                val prev = lastInput
                if (prev != null && prev.packageName == pkg && sameNode(prev.target, target) && now - prev.timestamp < 2500 &&
                    AgentBus.actions.value.firstOrNull()?.id == prev.id
                ) {
                    val upd = prev.copy(timestamp = now, inputValue = value, redacted = sensitive)
                    lastInput = upd
                    emit(upd, true)
                } else {
                    val a = ObservedAction(nextId++, now, type, pkg, target, fallback, value, sensitive, null, fingerprintBefore)
                    lastInput = a
                    emit(a, false)
                }
            }
            ActionType.SCROLL -> {
                val dir = direction(e)
                val prev = lastScroll
                if (prev != null && prev.packageName == pkg && sameNode(prev.target, target) && now - prev.timestamp < 900 &&
                    AgentBus.actions.value.firstOrNull()?.id == prev.id
                ) {
                    val upd = prev.copy(timestamp = now, scrollDirection = if (dir == ScrollDirection.UNKNOWN) prev.scrollDirection else dir)
                    lastScroll = upd
                    emit(upd, true)
                } else {
                    val a = ObservedAction(nextId++, now, type, pkg, target, fallback, null, false, dir, fingerprintBefore)
                    lastScroll = a
                    emit(a, false)
                }
            }
            else -> {
                lastInput = null
                lastScroll = null
                emit(ObservedAction(nextId++, now, type, pkg, target, fallback, null, sensitive, null, fingerprintBefore), false)
            }
        }
    }

    private fun emit(a: ObservedAction, update: Boolean) {
        if (update) AgentBus.replaceLatest(a) else AgentBus.recordAction(a)
        listener?.invoke(a, update)
    }

    private fun direction(e: AccessibilityEvent): ScrollDirection {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return ScrollDirection.UNKNOWN
        val dx = e.scrollDeltaX
        val dy = e.scrollDeltaY
        if (dx == -1 && dy == -1) return ScrollDirection.UNKNOWN
        return when {
            dy > 0 -> ScrollDirection.DOWN
            dy < 0 -> ScrollDirection.UP
            dx > 0 -> ScrollDirection.RIGHT
            dx < 0 -> ScrollDirection.LEFT
            else -> ScrollDirection.UNKNOWN
        }
    }

    private fun sameNode(a: NodeSnapshot?, b: NodeSnapshot?): Boolean {
        if (a == null || b == null) return false
        if (a.className != b.className) return false
        return if (a.resourceId != null || b.resourceId != null) a.resourceId == b.resourceId else a.bounds == b.bounds
    }

    companion object {
        const val REDACTED = "[REDACTED]"
    }
}
