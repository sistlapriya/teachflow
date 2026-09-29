package com.teachflow.agent.recovery

import com.teachflow.agent.accessibility.ScreenSnapshot
import com.teachflow.agent.safety.SafetyGuardian
import com.teachflow.agent.skills.SkillStep

enum class UiState {
    EXPECTED_STATE, POPUP, ALREADY_COMPLETED, LOGIN, PAYMENT, OTP, UNKNOWN_STATE,
    MISSING_TARGET, NETWORK_BLOCK, EMPTY_RESULTS, AMBIGUOUS_STATE,
}

data class StateReading(val state: UiState, val evidence: String)

/**
 * Classifies the current screen before choosing a recovery, so failures aren't all treated the same.
 * Rule-based and ordered: safety first, then blocking overlays, then content problems.
 */
object StateClassifier {

    private val NETWORK = listOf(
        "no internet", "you're offline", "you are offline", "youre offline", "no connection", "check your connection",
        "check your internet", "network error", "connection lost", "unable to connect", "couldn't connect", "could not connect",
    )
    private val EMPTY = listOf(
        "no results", "no matching", "0 results", "nothing found", "no items found", "couldn't find any", "could not find any",
        "no restaurants found", "no dishes found", "did not match any", "didn't match any", "no products found", "no result found",
    )

    fun classify(s: ScreenSnapshot, targetPackage: String, step: SkillStep?, slots: Map<String, String>): StateReading {
        val v = SafetyGuardian.checkScreen(s)
        if (v.stop) return StateReading(
            when (v.kind) { "PAYMENT" -> UiState.PAYMENT; "OTP" -> UiState.OTP; else -> UiState.LOGIN }, v.reason,
        )

        RecoveryEngine.safeDismiss(s)?.let { return StateReading(UiState.POPUP, "Popup with a safe \"${it.displayLabel.take(20)}\" control") }

        val texts = s.nodes.filter { it.visible && !it.redacted }.mapNotNull { (it.text ?: it.contentDescription)?.lowercase() }
        NETWORK.firstOrNull { p -> texts.any { it.contains(p) } }?.let { return StateReading(UiState.NETWORK_BLOCK, "Screen says \"$it\"") }
        EMPTY.firstOrNull { p -> texts.any { it.contains(p) } }?.let { return StateReading(UiState.EMPTY_RESULTS, "Screen says \"$it\"") }

        if (step != null) RecoveryEngine.alreadyDone(step, s, slots)?.let { return StateReading(UiState.ALREADY_COMPLETED, it) }

        if (s.packageName != targetPackage) return StateReading(UiState.UNKNOWN_STATE, "Foreground app is ${s.packageName}")
        val labelled = s.nodes.count { it.visible && !it.redacted && !(it.text ?: it.contentDescription).isNullOrBlank() }
        if (labelled < 4) return StateReading(UiState.UNKNOWN_STATE, "Screen is nearly empty (still loading?)")

        return StateReading(UiState.MISSING_TARGET, "App is open and has content, but the target isn't visible")
    }

    /** Non-destructive "retry" control on a network error screen. */
    fun retryControl(s: ScreenSnapshot) = s.nodes.firstOrNull { n ->
        n.visible && n.enabled && (n.clickable || n.longClickable) &&
            (n.text ?: n.contentDescription ?: "").trim().lowercase() in setOf("retry", "try again", "reload", "refresh")
    }
}
