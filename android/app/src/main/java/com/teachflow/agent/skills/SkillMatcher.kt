package com.teachflow.agent.skills

import com.teachflow.agent.nlu.ParsedCommand
import com.teachflow.agent.nlu.TextMatch

/** A detail the command leaves open that the workflow needs, e.g. ITEM = "pizza" when a specific pizza must be picked. */
data class MissingDetail(val slot: String, val given: String?, val question: String, val canChooseOnScreen: Boolean)

data class MatchResult(val skill: Skill, val score: Double, val reasons: List<String>)

sealed interface MatchOutcome {
    data class Matched(val result: MatchResult, val runnerUp: MatchResult?) : MatchOutcome
    data class Ambiguous(val options: List<MatchResult>) : MatchOutcome
    data class NoMatch(val best: MatchResult?) : MatchOutcome
}

/**
 * Paraphrase matching by meaning, not wording: app, verb family, slot compatibility and
 * remaining content words. Scores are heuristic evidence sums, shown with their reasons.
 */
class SkillMatcher(private val threshold: Double = 0.5, private val ambiguityMargin: Double = 0.05) {

    fun match(cmd: ParsedCommand, skills: List<Skill>): MatchOutcome {
        if (skills.isEmpty()) return MatchOutcome.NoMatch(null)
        val ranked = skills.map { score(cmd, it) }.sortedByDescending { it.score }
        val best = ranked.first()
        if (best.score < threshold) return MatchOutcome.NoMatch(best)
        val close = ranked.filter { it.score >= threshold && best.score - it.score < ambiguityMargin }
        if (close.size > 1) return MatchOutcome.Ambiguous(close)
        return MatchOutcome.Matched(best, ranked.getOrNull(1))
    }

    fun score(cmd: ParsedCommand, skill: Skill): MatchResult {
        var s = 0.0
        val why = ArrayList<String>()

        when {
            cmd.appLabel == null -> { s += 0.10; why += "no app named (neutral)" }
            cmd.appLabel.equals(skill.targetApp, ignoreCase = true) -> { s += 0.30; why += "same app: ${skill.targetApp}" }
            else -> { s -= 0.50; why += "different app named: ${cmd.appLabel}" }
        }

        val verbs = cmd.verbs.map { it.name }.toSet()
        if (verbs.any { it in skill.verbs }) { s += 0.25; why += "same action type: ${verbs.intersect(skill.verbs).joinToString()}" }

        for ((name, value) in cmd.slots) {
            val def = skill.slot(name) ?: continue
            val learned = def.defaultValue
            when (name) {
                "RESTAURANT" -> {
                    val sim = TextMatch.similarity(value, learned)
                    if (sim >= 0.6) { s += 0.20; why += "same $name as taught ($learned)" } else { s += 0.05; why += "$name slot compatible" }
                }
                "ITEM" -> {
                    val sim = TextMatch.similarity(value, learned)
                    s += 0.10 + 0.15 * sim
                    why += if (sim >= 0.6) "ITEM similar to taught value" else "ITEM slot compatible (new value)"
                }
                else -> { s += 0.05; why += "$name slot compatible" }
            }
        }

        if (cmd.contentTokens.isNotEmpty() && skill.contentTokens.isNotEmpty()) {
            val inter = cmd.contentTokens intersect skill.contentTokens
            val union = cmd.contentTokens union skill.contentTokens
            val j = inter.size.toDouble() / union.size
            if (j > 0) { s += 0.20 * j; why += "shared words: ${inter.joinToString()}" }
        }
        return MatchResult(skill, s.coerceIn(0.0, 1.0), why)
    }

    /**
     * ITEM missing, or only naming the category of what was taught ("pizza" for a skill taught with
     * "Margherita pizza"), while the workflow has to pick one specific element by ITEM.
     */
    fun missingDetail(skill: Skill, cmd: ParsedCommand): MissingDetail? {
        val itemSlot = skill.slot("ITEM") ?: return null
        val picksItem = skill.steps.any {
            it.kind == StepKind.CLICK && (it.target?.slotRef == "ITEM" || it.target?.nearSlot == "ITEM")
        }
        val given = cmd.slots["ITEM"]
        if (given == null) return MissingDetail("ITEM", null, "Which item would you like?", false)
        if (picksItem && isCategoryOnly(given, itemSlot.defaultValue)) {
            return MissingDetail("ITEM", given, "Which ${TextMatch.normalize(given)} would you like?", true)
        }
        return null
    }

    private fun isCategoryOnly(value: String, learned: String?): Boolean {
        val v = TextMatch.tokens(value)
        val l = TextMatch.tokens(learned)
        return v.size == 1 && l.size >= 2 && v[0] == l.last()
    }

    /** Taught defaults overridden by whatever the user said this time. */
    fun resolveSlots(skill: Skill, cmd: ParsedCommand): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // Part K: never silently reuse the taught item or merchant. Those come from the command or
        // are asked for mid-run. Only numeric defaults (quantity) and a taught address are carried over.
        skill.slots.filter { it.type == SlotType.NUMBER || it.type == SlotType.ADDRESS }
            .forEach { d -> d.defaultValue?.let { out[d.name] = it } }
        cmd.slots.forEach { (k, v) -> if (skill.slot(k) != null || k == "QUANTITY" || k == "ADDRESS") out[k] = v }
        if ("QUANTITY" !in out) out["QUANTITY"] = "1"
        return out
    }
}
