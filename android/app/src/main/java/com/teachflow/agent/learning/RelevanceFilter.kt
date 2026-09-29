package com.teachflow.agent.learning

import com.teachflow.agent.accessibility.ActionType
import com.teachflow.agent.accessibility.SemanticRole
import com.teachflow.agent.nlu.TextMatch

enum class Relevance { RELEVANT, UNCERTAIN, IRRELEVANT }
enum class Decision { KEEP, FOLD, DISCARD }

data class Judged(val rec: RecordedAction, val relevance: Relevance, val decision: Decision, val reason: String)

/**
 * Classifies each observed interaction as RELEVANT, UNCERTAIN or IRRELEVANT to the task being taught.
 * Not a list of known distractions: it combines three explainable signals.
 *  1. Task context: did it happen in the target app, or in a phone call, system UI, launcher or another app?
 *  2. Semantic link: does the element relate to a value in the command (ITEM, RESTAURANT…) or to a
 *     task role (search, product, add to cart, cart, quantity, address)?
 *  3. State-transition contribution: did it move the app to a new state that the task continued from,
 *     or was it a detour (opened something, then came back) or a tap with no visible effect?
 * Every decision carries its reason so the review screen can show it.
 */
class RelevanceFilter(private val launcherPackages: Set<String>) {

    private val taskRoles = setOf(
        SemanticRole.SEARCH_INPUT, SemanticRole.TEXT_INPUT, SemanticRole.PRODUCT, SemanticRole.LIST_ITEM,
        SemanticRole.ADD_TO_CART, SemanticRole.CART, SemanticRole.CHECKOUT, SemanticRole.QUANTITY_CONTROL,
        SemanticRole.ADDRESS_SELECTOR, SemanticRole.PAYMENT,
    )

    fun judge(session: TeachingSession): List<Judged> {
        val recs = session.recorded
        val values = session.parsed.slots.filterKeys { it != "QUANTITY" }.values
        val out = ArrayList<Judged>(recs.size)
        var lastKept: RecordedAction? = null
        for ((i, r) in recs.withIndex()) {
            val a = r.action
            val pkg = a.packageName
            val role = a.target?.role
            val next = recs.drop(i + 1).firstOrNull { it.action.packageName == session.targetPackage }?.action
            val linked = values.any { v -> TextMatch.similarity(v, a.displayLabel) >= 0.5 || (a.inputValue != null && TextMatch.similarity(v, a.inputValue) >= 0.5) }
            val taskRole = role in taskRoles
            val changedScreen = a.screenAfter != null && a.screenAfter != a.screenBefore
            val cameBack = a.screenBefore != null && next?.screenBefore == a.screenBefore

            val j: Judged = when {
                isCallUi(pkg) -> Judged(r, Relevance.IRRELEVANT, Decision.DISCARD, "Phone call UI: outside the task, no contribution to it")
                pkg == "com.android.systemui" -> Judged(r, Relevance.IRRELEVANT, Decision.DISCARD, "System UI (notifications or status bar): outside the task")
                pkg in launcherPackages -> Judged(r, Relevance.IRRELEVANT, Decision.DISCARD, "Home screen or app switching: outside the task")
                pkg != session.targetPackage -> Judged(r, Relevance.IRRELEVANT, Decision.DISCARD, "Different app from the task ($pkg)")
                role == SemanticRole.DIALOG_DISMISS ->
                    Judged(r, Relevance.IRRELEVANT, Decision.DISCARD, "Popup dismissal: transient, not part of the task. Recovery handles popups at run time")
                a.type == ActionType.SCROLL -> Judged(r, Relevance.RELEVANT, Decision.FOLD, "Scrolling folded into the next step (replay scrolls only if needed)")
                a.type == ActionType.CLICK && a.target?.editable == true && recs.getOrNull(i + 1)?.action?.type == ActionType.INPUT_TEXT ->
                    Judged(r, Relevance.RELEVANT, Decision.FOLD, "Field focus merged into the typing step")
                a.type == ActionType.INPUT_TEXT && a.inputValue.isNullOrBlank() ->
                    Judged(r, Relevance.IRRELEVANT, Decision.DISCARD, "Field cleared; no value typed")
                isRepeat(lastKept, r) -> Judged(r, Relevance.IRRELEVANT, Decision.DISCARD, "Repeated tap on the same element")
                a.type != ActionType.INPUT_TEXT && !linked && changedScreen && cameBack ->
                    Judged(r, Relevance.IRRELEVANT, Decision.DISCARD, "Detour: opened a screen, then returned without using it (no contribution to task state)")
                a.type == ActionType.INPUT_TEXT || linked ->
                    Judged(r, Relevance.RELEVANT, Decision.KEEP, "Uses a value from the command" + if (taskRole) " · task role ${role?.name}" else "")
                taskRole -> Judged(r, Relevance.RELEVANT, Decision.KEEP, "Task role ${role?.name}" + if (changedScreen) " · advanced the screen" else "")
                changedScreen -> Judged(r, Relevance.RELEVANT, Decision.KEEP, "Advanced ${session.targetApp} to a new state that the task continued from")
                else -> Judged(r, Relevance.UNCERTAIN, Decision.KEEP, "No visible state change and no link to the command. Kept; check it in the review")
            }
            if (j.decision == Decision.KEEP) lastKept = r
            out += j
        }
        return out
    }

    private fun isCallUi(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("dialer") || p.contains("incallui") || p.contains("telecom") || p == "com.android.phone" || p.endsWith(".phone")
    }

    private fun isRepeat(prev: RecordedAction?, cur: RecordedAction): Boolean {
        if (prev == null) return false
        val a = prev.action
        val b = cur.action
        return a.type == ActionType.CLICK && b.type == ActionType.CLICK && b.timestamp - a.timestamp < 800 &&
            a.displayLabel == b.displayLabel && a.target?.role == b.target?.role
    }
}
