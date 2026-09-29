package com.teachflow.agent.execution

import com.teachflow.agent.accessibility.NodeSnapshot
import com.teachflow.agent.accessibility.ScreenSnapshot
import com.teachflow.agent.accessibility.SemanticRole
import com.teachflow.agent.nlu.TextMatch
import com.teachflow.agent.skills.TargetSpec
import kotlin.math.abs

data class Candidate(
    val node: NodeSnapshot,
    val label: String,
    /** Evidence score normalised to 0..1 over the signals this step can use. Not a probability. */
    val score: Double,
    val why: List<String>,
    /** Nearby text that tells candidates apart (e.g. the product next to an ADD button). */
    val context: String? = null,
    /** Structured evidence shown in the trace (never free-form reasoning). */
    val checks: List<Pair<String, Boolean>> = emptyList(),
) {
    fun evidence(decision: String): String =
        checks.joinToString(" · ") { (k, ok) -> "$k ${if (ok) "\u2713" else "\u2717"}" } + " \u2192 DECISION: $decision"

    val question: String get() = listOfNotNull(label.take(40).ifBlank { node.displayLabel.take(40) }, context?.take(40)).joinToString(" · ")
}

sealed interface Grounding {
    data class Found(val best: Candidate, val runnerUp: Candidate?) : Grounding
    data class Ambiguous(val options: List<Candidate>) : Grounding
    data class NotFound(val reason: String, val best: Candidate?) : Grounding
}

/**
 * Finds the element on the current screen that plays the role a workflow step needs.
 * Ranking uses role, slot value, demonstrated label, resource id, class and proximity.
 * It never uses demonstration coordinates.
 */
class SemanticGrounder(
    private val minScore: Double = 0.45,
    private val ambiguityMargin: Double = 0.07,
    /** Minimum label similarity for slot values and demonstrated labels. Lowered for "alternate" matches. */
    private val labelThreshold: Double = 0.5,
) {
    private val specificRoles = setOf(
        SemanticRole.ADD_TO_CART, SemanticRole.CART, SemanticRole.CHECKOUT, SemanticRole.SEARCH_INPUT,
        SemanticRole.QUANTITY_CONTROL, SemanticRole.ADDRESS_SELECTOR,
    )

    fun ground(spec: TargetSpec, slots: Map<String, String>, snap: ScreenSnapshot, forInput: Boolean): Grounding {
        val nodes = snap.nodes
        val visible = nodes.filter { it.visible && it.enabled && !it.redacted }
        val weights = TextMatch.screenWeights(visible.map { it.displayLabel })

        spec.ordinal?.let { k -> return byOrdinal(k, spec.ordinalRole ?: spec.role, visible) }

        val scored = HashMap<Int, Candidate>()
        fun offer(actionable: NodeSnapshot, label: String, score: Double, why: List<String>, context: String?, checks: List<Pair<String, Boolean>>) {
            val prev = scored[actionable.nodeId]
            if (prev == null || score > prev.score) scored[actionable.nodeId] = Candidate(actionable, label, score, why, context, checks)
        }
        // Maximum reachable evidence for this spec, so scores are comparable across steps.
        val maxScore = 0.25 +
            (if (spec.slotRef != null) 0.55 else if (spec.anchorLabel != null) 0.4 else 0.0) +
            (if (spec.resourceIdName != null) 0.15 else 0.0) +
            (if (spec.className != null) 0.05 else 0.0) +
            (if (spec.nearSlot != null) 0.5 else 0.0)

        val nearAnchors: List<NodeSnapshot> = spec.nearSlot?.let { slot ->
            val v = slots[slot]
            if (v == null) emptyList() else visible.filter { TextMatch.similarity(v, it.text ?: it.contentDescription, weights) >= 0.6 }
        } ?: emptyList()

        for (n in visible) {
            val actionable = if (forInput) n.takeIf { it.editable } else actionableFor(n, nodes)
            if (actionable == null || !actionable.visible || !actionable.enabled) continue
            val label = when {
                forInput -> listOfNotNull(n.hint, n.contentDescription, n.text).joinToString(" ")
                !n.text.isNullOrBlank() -> n.text
                !n.contentDescription.isNullOrBlank() -> n.contentDescription
                else -> n.subtreeLabel ?: ""
            }
            var s = 0.0
            val why = ArrayList<String>()
            var context: String? = null
            var textOk = false
            var nearOk: Boolean? = null
            val roleOk = actionable.role == spec.role || n.role == spec.role

            if (roleOk) { s += 0.25; why += "role ${spec.role}" }
            else if (spec.role in specificRoles && !forInput) { /* no role credit */ }
            else if (forInput) { s += 0.15; why += "editable field" }

            if (spec.slotRef != null) {
                val v = slots[spec.slotRef] ?: continue
                val sim = TextMatch.similarity(v, label, weights)
                if (sim < labelThreshold) continue
                s += 0.5 * sim
                textOk = true
                why += "{${spec.slotRef}}=\"$v\" ~ \"${label.take(40)}\" (%.2f)".format(sim)
                if (TextMatch.normalize(label).startsWith(TextMatch.normalize(v))) { s += 0.05; why += "label starts with value" }
            } else if (spec.anchorLabel != null) {
                val sim = TextMatch.similarity(spec.anchorLabel, label)
                if (sim < labelThreshold && !(spec.role in specificRoles && actionable.role == spec.role)) continue
                s += 0.4 * sim
                textOk = sim >= labelThreshold
                if (sim > 0) why += "label ~ \"${spec.anchorLabel.take(30)}\" (%.2f)".format(sim)
            } else if (actionable.role != spec.role && n.role != spec.role) continue

            if (spec.resourceIdName != null && spec.resourceIdName == actionable.resourceIdName) { s += 0.15; why += "same resource id" }
            if (spec.className != null && spec.className == actionable.className) { s += 0.05; why += "same class" }

            // Proximity only counts when the slot value is visible (e.g. a menu list).
            // On a product page without it, a single "Add to cart" still grounds on role and label.
            if (spec.nearSlot != null && nearAnchors.isNotEmpty()) {
                val cy = (actionable.bounds.top + actionable.bounds.bottom) / 2.0
                val nearest = nearAnchors.minBy { abs((it.bounds.top + it.bounds.bottom) / 2.0 - cy) }
                val d = abs((nearest.bounds.top + nearest.bounds.bottom) / 2.0 - cy)
                val closeness = (1.0 - d / (snap.screenHeight * 0.12).coerceAtLeast(1.0)).coerceAtLeast(0.0)
                if (closeness == 0.0) continue
                nearOk = true
                s += 0.5 * closeness
                context = nearest.text ?: nearest.contentDescription
                why += "near \"${context?.take(30)}\" (%.2f)".format(closeness)
            }
            val checks = ArrayList<Pair<String, Boolean>>()
            if (spec.slotRef != null || spec.anchorLabel != null) checks += "Text match" to textOk
            checks += "Role match" to roleOk
            checks += "Visible" to actionable.visible
            checks += "Enabled" to actionable.enabled
            checks += (if (forInput) "Editable" else "Clickable") to (if (forInput) actionable.editable else actionable.clickable || actionable.longClickable)
            if (spec.slotRef != null) checks += "Task slot {${spec.slotRef}} match" to textOk
            nearOk?.let { checks += "Next to {${spec.nearSlot}}" to it }
            if (spec.resourceIdName != null) checks += "Same resource id" to (spec.resourceIdName == actionable.resourceIdName)
            offer(actionable, label, (s / maxScore).coerceIn(0.0, 1.0), why, context, checks)
        }

        val ranked = scored.values.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return Grounding.NotFound("No element on screen matches ${describe(spec, slots)}.", null)
        // Several identical buttons and the item they belong to isn't visible: look for it (scroll) before asking.
        if (spec.nearSlot != null && nearAnchors.isEmpty() && ranked.size > 1) {
            return Grounding.NotFound("\"${slots[spec.nearSlot] ?: spec.nearSlot}\" isn't visible on this screen.", best)
        }
        if (best.score < minScore) return Grounding.NotFound("Best match for ${describe(spec, slots)} is too weak (%.2f).".format(best.score), best)
        val close = ranked.filter { best.score - it.score < ambiguityMargin }
            .distinctBy { it.node.nodeId }
        if (close.size > 1) return Grounding.Ambiguous(close.take(4))
        return Grounding.Found(best, ranked.getOrNull(1))
    }

    private fun byOrdinal(k: Int, role: SemanticRole, visible: List<NodeSnapshot>): Grounding {
        val ids = visible.map { it.nodeId }.toSet()
        val list = visible.filter { it.role == role }
            .filter { n -> generateSequence(n.parentId) { pid -> visible.firstOrNull { it.nodeId == pid }?.parentId }
                .none { pid -> pid in ids && visible.first { it.nodeId == pid }.role == role } }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val n = list.getOrNull(k - 1) ?: return Grounding.NotFound("Only ${list.size} ${role.name.lowercase()} element(s) visible; need #$k.", null)
        return Grounding.Found(Candidate(n, n.displayLabel, 0.8, listOf("position #$k of ${list.size} ${role.name}"),
            checks = listOf("Role match" to true, "Position #$k" to true, "Visible" to n.visible, "Clickable" to (n.clickable || n.longClickable))), null)
    }

    /** The node itself if clickable, otherwise its nearest clickable ancestor (max 6 levels). */
    fun actionableFor(n: NodeSnapshot, all: List<NodeSnapshot>): NodeSnapshot? {
        var cur: NodeSnapshot? = n
        var hops = 0
        while (cur != null && hops <= 6) {
            if (cur.clickable || cur.longClickable) return cur
            cur = cur.parentId?.let { all.getOrNull(it) }
            hops++
        }
        return null
    }

    fun describe(spec: TargetSpec, slots: Map<String, String>): String = buildString {
        append(spec.role.name.lowercase().replace('_', ' '))
        spec.slotRef?.let { append(" \"${slots[it] ?: "{$it}"}\"") }
        if (spec.slotRef == null) spec.anchorLabel?.let { append(" \"$it\"") }
        spec.nearSlot?.let { append(" next to \"${slots[it] ?: it}\"") }
    }
}
