package com.teachflow.agent.recovery

import com.teachflow.agent.accessibility.NodeSnapshot
import com.teachflow.agent.accessibility.ScreenSnapshot
import com.teachflow.agent.accessibility.SemanticRole
import com.teachflow.agent.nlu.TextMatch
import com.teachflow.agent.skills.SkillStep
import com.teachflow.agent.skills.StepKind
import kotlin.math.abs

/** A small set of conservative, reliable recovery strategies. Anything else goes to the user. */
object RecoveryEngine {
    const val MAX_ATTEMPTS = 3

    private val SAFE_DISMISS = setOf("close", "dismiss", "not now", "skip", "no thanks", "maybe later", "later", "got it", "x", "\u00D7", "\u2715", "\u2716")
    private val DESTRUCTIVE = listOf("remove", "delete", "clear", "cancel order", "log out", "logout", "unsubscribe", "empty")

    /** A clearly non-destructive dismiss control on a popup, or null. */
    fun safeDismiss(s: ScreenSnapshot): NodeSnapshot? {
        val dialogPresent = s.nodes.any { it.visible && (it.className?.contains("Dialog") == true || it.resourceIdName?.contains("dialog") == true) }
        return s.nodes.filter { it.visible && it.enabled && (it.clickable || it.longClickable) && it.role == SemanticRole.DIALOG_DISMISS }
            .firstOrNull { n ->
                val l = TextMatch.normalize(n.text ?: n.contentDescription ?: "")
                val raw = (n.text ?: n.contentDescription ?: "").trim().lowercase()
                DESTRUCTIVE.none { l.contains(it) } && (l in SAFE_DISMISS || raw in SAFE_DISMISS || (l == "cancel" && dialogPresent))
            }
    }

    /** ADD_TO_CART step whose item already shows a quantity stepper: the item is already in the cart. */
    fun alreadyDone(step: SkillStep, s: ScreenSnapshot, slots: Map<String, String>): String? {
        if (step.kind != StepKind.CLICK || step.target?.role != SemanticRole.ADD_TO_CART) return null
        val item = slots["ITEM"] ?: return null
        val anchors = s.nodes.filter { it.visible && TextMatch.similarity(item, it.text ?: it.contentDescription) >= 0.6 }
        if (anchors.isEmpty()) return null
        val steppers = s.nodes.filter { it.visible && it.role == SemanticRole.QUANTITY_CONTROL }
        val limit = s.screenHeight * 0.18
        val near = steppers.any { st -> anchors.any { a -> abs(cy(a) - cy(st)) < limit } }
        return if (near) "\"$item\" already shows a quantity control, so it is already in the cart. Not adding it again." else null
    }

    fun largestScrollable(s: ScreenSnapshot): NodeSnapshot? =
        s.nodes.filter { it.visible && it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }

    private fun cy(n: NodeSnapshot) = (n.bounds.top + n.bounds.bottom) / 2.0
}
