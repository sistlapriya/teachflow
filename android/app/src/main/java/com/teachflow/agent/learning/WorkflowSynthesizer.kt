package com.teachflow.agent.learning

import com.teachflow.agent.accessibility.ActionType
import com.teachflow.agent.accessibility.NodeSnapshot
import com.teachflow.agent.accessibility.ScreenSnapshot
import com.teachflow.agent.accessibility.SemanticRole
import com.teachflow.agent.nlu.TextMatch
import com.teachflow.agent.skills.DiscardedAction
import com.teachflow.agent.skills.Skill
import com.teachflow.agent.skills.SkillStep
import com.teachflow.agent.skills.SlotDef
import com.teachflow.agent.skills.SlotType
import com.teachflow.agent.skills.StepKind
import com.teachflow.agent.skills.TargetSpec
import kotlin.math.abs

data class SynthesisResult(
    val skill: Skill,
    val judged: List<Judged>,
    val observed: Int,
    val relevant: Int,
    val ignored: Int,
    val warnings: List<String>,
    val stopReason: String?,
)

/**
 * Raw demonstration → parameterised semantic workflow.
 *
 * Values from the spoken command (ITEM, RESTAURANT, QUANTITY, ADDRESS) are located in what the user
 * typed and tapped; those elements become slot references, so the same skill works for new values.
 */
class WorkflowSynthesizer {

    fun synthesize(session: TeachingSession, judged: List<Judged>): SynthesisResult {
        val cmdSlots = session.parsed.slots
        val steps = ArrayList<SkillStep>()
        val warnings = ArrayList<String>()
        var pendingScroll = false
        var boundaryHit = false
        var addIndex: Int? = null
        var qtyIndex: Int? = null
        var addressDemonstrated = false
        val usedSlots = HashSet<String>()

        for (j in judged) {
            if (boundaryHit) break
            val a = j.rec.action
            when (j.decision) {
                Decision.DISCARD -> continue
                Decision.FOLD -> { if (a.type == ActionType.SCROLL) pendingScroll = true; continue }
                Decision.KEEP -> Unit
            }
            val t = a.target
            val before = j.rec.before
            when (a.type) {
                ActionType.INPUT_TEXT -> {
                    if (a.redacted || t?.role?.sensitive == true) { boundaryHit = true; break }
                    val value = a.inputValue.orEmpty()
                    val slot = bestSlotFor(value, cmdSlots, null, exactish = true)
                    slot?.let { usedSlots += it }
                    steps += SkillStep(
                        kind = StepKind.INPUT,
                        target = TargetSpec(
                            role = t?.role?.takeIf { it == SemanticRole.SEARCH_INPUT } ?: SemanticRole.TEXT_INPUT,
                            anchorLabel = t?.hint ?: t?.contentDescription,
                            resourceIdName = t?.resourceIdName,
                            className = t?.className,
                            demoBounds = t?.bounds?.toString(),
                        ),
                        valueTemplate = slot?.let { "{$it}" } ?: value,
                        mayNeedScroll = pendingScroll,
                        evidence = "Typed \"$value\"" + (slot?.let { " → {$it}" } ?: " (literal)"),
                    )
                }
                ActionType.CLICK, ActionType.LONG_CLICK -> {
                    val role = t?.role ?: SemanticRole.UNKNOWN
                    val label = a.displayLabel
                    if (role == SemanticRole.PAYMENT || role.safetyBoundary) { boundaryHit = true; break }
                    if (role == SemanticRole.QUANTITY_CONTROL) {
                        if (qtyIndex == null) {
                            steps += SkillStep(StepKind.SET_QUANTITY, TargetSpec(SemanticRole.QUANTITY_CONTROL, nearSlot = "ITEM"), optional = true,
                                evidence = "Quantity taps become one SET_QUANTITY {QUANTITY} step")
                            qtyIndex = steps.lastIndex
                        }
                        continue
                    }
                    val weights = before?.let { s -> TextMatch.screenWeights(s.nodes.filter { it.visible }.map { it.displayLabel }) }
                    // "add the first result": the user asked for a position, not for a specific title.
                    val positional = session.parsed.ordinal != null && (role == SemanticRole.PRODUCT || role == SemanticRole.LIST_ITEM)
                    val slot = if (positional) null else bestSlotFor(label, cmdSlots, weights, exactish = false)
                    slot?.let { usedSlots += it }
                    if (role == SemanticRole.ADDRESS_SELECTOR || slot == "ADDRESS") addressDemonstrated = true
                    val ordinal = if (slot == null && (role == SemanticRole.PRODUCT || role == SemanticRole.LIST_ITEM) && t != null && before != null)
                        ordinalOf(t, before) else null
                    val near = if (role == SemanticRole.ADD_TO_CART && slot == null && t != null && before != null)
                        nearSlot(t, before, cmdSlots, "ITEM") else null
                    steps += SkillStep(
                        kind = StepKind.CLICK,
                        target = TargetSpec(
                            role = role,
                            slotRef = slot,
                            anchorLabel = if (slot == null && ordinal == null) label.take(60) else null,
                            nearSlot = near,
                            ordinal = ordinal,
                            ordinalRole = if (ordinal != null) role else null,
                            resourceIdName = t?.resourceIdName,
                            className = t?.className,
                            demoBounds = t?.bounds?.toString(),
                        ),
                        mayNeedScroll = pendingScroll,
                        evidence = "Tapped \"${label.take(50)}\" (${role.name})" + when {
                            slot != null -> " → {$slot}"
                            ordinal != null -> " → result #$ordinal"
                            near != null -> " → next to {$near}"
                            else -> ""
                        },
                    )
                    if (role == SemanticRole.ADD_TO_CART) addIndex = steps.lastIndex
                }
                ActionType.SCROLL -> pendingScroll = true
            }
            pendingScroll = pendingScroll && a.type == ActionType.SCROLL
        }

        // Quantity: one parameterised step right after "add to cart" (or after selecting ITEM).
        if (qtyIndex == null) {
            val anchor = addIndex ?: steps.indexOfLast { it.target?.slotRef == "ITEM" && it.kind == StepKind.CLICK }.takeIf { it >= 0 }
            if (anchor != null) {
                steps.add(anchor + 1, SkillStep(StepKind.SET_QUANTITY, TargetSpec(SemanticRole.QUANTITY_CONTROL, nearSlot = "ITEM"),
                    optional = true, evidence = "Added automatically: adjusts quantity when you ask for more than one"))
            }
        }
        // Address: if not demonstrated, an optional step before the stop point.
        if (!addressDemonstrated) {
            steps += SkillStep(StepKind.SELECT_ADDRESS, TargetSpec(SemanticRole.ADDRESS_SELECTOR, slotRef = "ADDRESS"), optional = true,
                evidence = "Added automatically: runs only when you name an address")
        }
        steps += SkillStep(StepKind.STOP_AT_BOUNDARY, evidence = session.stopReason ?: "Automation always stops before payment or sign-in")

        // Slots.
        val slots = ArrayList<SlotDef>()
        cmdSlots["ITEM"]?.let { slots += SlotDef("ITEM", SlotType.STRING, it) }
        cmdSlots["RESTAURANT"]?.let { slots += SlotDef("RESTAURANT", SlotType.ENTITY, it) }
        slots += SlotDef("QUANTITY", SlotType.NUMBER, cmdSlots["QUANTITY"] ?: "1")
        slots += SlotDef("ADDRESS", SlotType.ADDRESS, cmdSlots["ADDRESS"])

        for (s in listOf("ITEM", "RESTAURANT")) {
            if (s in cmdSlots && s !in usedSlots) warnings += "{$s} (\"${cmdSlots[s]}\") wasn't found in anything you typed or tapped, so changing it may not work."
        }
        judged.filter { it.relevance == Relevance.UNCERTAIN && it.decision == Decision.KEEP }.forEach {
            warnings += "\"${it.rec.action.displayLabel.take(40)}\" had no visible effect during teaching. If it wasn't part of the task, discard and teach again."
        }
        if (steps.none { it.kind == StepKind.CLICK || it.kind == StepKind.INPUT }) warnings += "No usable steps were recorded in ${session.targetApp}."

        val discarded = judged.filter { it.decision == Decision.DISCARD }.map { DiscardedAction(it.rec.action.displayLabel.take(60), it.rec.action.packageName, it.reason) }
        val now = System.currentTimeMillis()
        val skill = Skill(
            id = "skill_$now",
            name = templateName(session.command, cmdSlots),
            intent = session.parsed.intentName,
            verbs = session.parsed.verbs.map { it.name }.toSet(),
            exampleCommands = listOf(session.command),
            contentTokens = session.parsed.contentTokens,
            targetPackage = session.targetPackage,
            targetApp = session.targetApp,
            slots = slots,
            steps = steps,
            discarded = discarded,
            createdAt = now,
            updatedAt = now,
        )
        return SynthesisResult(
            skill = skill,
            judged = judged,
            observed = judged.size,
            relevant = judged.count { it.decision != Decision.DISCARD },
            ignored = judged.count { it.decision == Decision.DISCARD },
            warnings = warnings,
            stopReason = session.stopReason,
        )
    }

    private fun bestSlotFor(label: String, slots: Map<String, String>, weights: ((String) -> Double)?, exactish: Boolean): String? {
        var best: String? = null
        var bestSim = 0.0
        for (name in listOf("ITEM", "RESTAURANT", "ADDRESS")) {
            val v = slots[name] ?: continue
            var sim = if (weights != null) TextMatch.similarity(v, label, weights) else TextMatch.similarity(v, label)
            // Typing is often a prefix of the spoken value ("Margherita" for "Margherita pizza").
            if (exactish && (TextMatch.isSubset(label, v) || TextMatch.isSubset(v, label))) sim = maxOf(sim, 0.9)
            val need = if (exactish) 0.75 else 0.5
            if (sim >= need && sim > bestSim) { best = name; bestSim = sim }
        }
        return best
    }

    /** 1-based position among visible elements of the same role, top-to-bottom then left-to-right. */
    private fun ordinalOf(t: NodeSnapshot, s: ScreenSnapshot): Int? {
        val list = s.nodes.filter { it.visible && it.role == t.role }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val i = list.indexOfFirst { it.bounds == t.bounds }
        return if (i >= 0) i + 1 else null
    }

    private fun nearSlot(t: NodeSnapshot, s: ScreenSnapshot, slots: Map<String, String>, slot: String): String? {
        val v = slots[slot] ?: return null
        val cy = (t.bounds.top + t.bounds.bottom) / 2
        val limit = s.screenHeight * 0.2
        val hit = s.nodes.any { n ->
            n.visible && !n.redacted && TextMatch.similarity(v, n.text ?: n.contentDescription) >= 0.6 &&
                abs((n.bounds.top + n.bounds.bottom) / 2 - cy) < limit
        }
        return if (hit) slot else null
    }

    private fun templateName(command: String, slots: Map<String, String>): String {
        var name = command.trim().trimEnd('.', '!', '?')
        for ((k, v) in slots.entries.sortedByDescending { it.value.length }) {
            if (k == "QUANTITY") continue
            name = name.replace(Regex(Regex.escape(v), RegexOption.IGNORE_CASE), "{$k}")
        }
        return name.replaceFirstChar { it.uppercase() }
    }
}
