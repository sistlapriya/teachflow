package com.teachflow.agent.learning

import com.teachflow.agent.accessibility.ObservedAction
import com.teachflow.agent.accessibility.ScreenSnapshot
import com.teachflow.agent.nlu.ParsedCommand

/** One observed interaction plus the screen it happened on. */
data class RecordedAction(val action: ObservedAction, val before: ScreenSnapshot?)

class TeachingSession(
    val command: String,
    val parsed: ParsedCommand,
    val targetPackage: String,
    val targetApp: String,
) {
    val startedAt = System.currentTimeMillis()
    private val entries = ArrayList<RecordedAction>()
    var stopReason: String? = null

    val recorded: List<RecordedAction> get() = synchronized(entries) { entries.toList() }

    /** Records which screen an action led to (from the next capture), for state-transition relevance. */
    fun attachAfter(fingerprint: String, at: Long) {
        synchronized(entries) {
            val i = entries.indexOfLast { true }
            if (i < 0) return
            val a = entries[i].action
            if (a.screenAfter == null && a.screenBefore != fingerprint && at - a.timestamp in 0..4000) {
                entries[i] = entries[i].copy(action = a.copy(screenAfter = fingerprint))
            }
        }
    }

    fun onAction(a: ObservedAction, isUpdate: Boolean, before: ScreenSnapshot?) {
        synchronized(entries) {
            val i = entries.indexOfLast { it.action.id == a.id }
            if (isUpdate && i >= 0) entries[i] = entries[i].copy(action = a)
            else entries.add(RecordedAction(a, before))
        }
    }
}
