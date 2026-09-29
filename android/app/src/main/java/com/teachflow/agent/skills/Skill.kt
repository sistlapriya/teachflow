package com.teachflow.agent.skills

import com.teachflow.agent.accessibility.SemanticRole

enum class SlotType { STRING, ENTITY, NUMBER, ADDRESS }

data class SlotDef(val name: String, val type: SlotType, val defaultValue: String?)

enum class StepKind { INPUT, CLICK, SET_QUANTITY, SELECT_ADDRESS, STOP_AT_BOUNDARY }

/**
 * How to find a UI element again. No coordinates: [demoBounds] is kept only as a record of the
 * demonstration and is never used to act.
 */
data class TargetSpec(
    val role: SemanticRole,
    /** The element's label refers to this slot's value (e.g. ITEM). */
    val slotRef: String? = null,
    /** Literal label seen in the demonstration (e.g. "ADD", "View cart"). */
    val anchorLabel: String? = null,
    /** Pick the element closest to the element matching this slot (e.g. the ADD next to ITEM). */
    val nearSlot: String? = null,
    /** Positional choice among visible elements of [ordinalRole], 1-based ("the first result"). */
    val ordinal: Int? = null,
    val ordinalRole: SemanticRole? = null,
    val resourceIdName: String? = null,
    val className: String? = null,
    val demoBounds: String? = null,
)

data class SkillStep(
    val kind: StepKind,
    val target: TargetSpec? = null,
    /** For INPUT: "{ITEM}" or a literal. */
    val valueTemplate: String? = null,
    /** Optional steps run only when the user supplied the slot they depend on. */
    val optional: Boolean = false,
    /** A scroll preceded this step in the demonstration; replay may need to scroll to find it. */
    val mayNeedScroll: Boolean = false,
    /** Human-readable description of what was demonstrated. */
    val evidence: String = "",
) {
    /** Semantic action name, e.g. SEARCH {ITEM}, SELECT RESTAURANT {RESTAURANT}, ADD {ITEM} TO CART. */
    val title: String
        get() = when (kind) {
            StepKind.INPUT -> if (target?.role == SemanticRole.SEARCH_INPUT) "SEARCH ${valueTemplate ?: ""}" else "TYPE ${valueTemplate ?: ""}"
            StepKind.CLICK -> {
                val t = target
                when {
                    t == null -> "TAP"
                    t.role == SemanticRole.SEARCH_INPUT -> "OPEN SEARCH"
                    t.slotRef == "RESTAURANT" -> "SELECT RESTAURANT {RESTAURANT}"
                    t.slotRef == "ITEM" -> "SELECT PRODUCT {ITEM}"
                    t.slotRef == "ADDRESS" -> "SELECT ADDRESS {ADDRESS}"
                    t.slotRef != null -> "SELECT {${t.slotRef}}"
                    t.role == SemanticRole.ADD_TO_CART -> if (t.nearSlot != null) "ADD {${t.nearSlot}} TO CART" else "ADD TO CART"
                    t.ordinal != null -> "SELECT RESULT #${t.ordinal}"
                    t.role == SemanticRole.CART -> "OPEN CART"
                    t.role == SemanticRole.CHECKOUT -> "OPEN CHECKOUT"
                    else -> "TAP \"${(t.anchorLabel ?: t.role.name).take(30)}\""
                }
            }
            StepKind.SET_QUANTITY -> "SET QUANTITY {QUANTITY}"
            StepKind.SELECT_ADDRESS -> "SELECT ADDRESS {ADDRESS}"
            StepKind.STOP_AT_BOUNDARY -> "STOP_AT_PAYMENT"
        }
}

data class DiscardedAction(val label: String, val packageName: String, val reason: String)

data class Skill(
    val id: String,
    /** Command with values replaced by slots, e.g. "Order {ITEM} from {RESTAURANT} on Zomato". */
    val name: String,
    val intent: String,
    val verbs: Set<String>,
    val exampleCommands: List<String>,
    val contentTokens: Set<String>,
    val targetPackage: String,
    val targetApp: String,
    val slots: List<SlotDef>,
    val steps: List<SkillStep>,
    val safetyBoundary: String = "PAYMENT",
    val recoveryStrategies: List<String> = DEFAULT_RECOVERY,
    val discarded: List<DiscardedAction> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    /** Counted from real run reports only. */
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val handoffCount: Int = 0,
    /** Number of demonstrations this skill was learned from. */
    val demonstrations: Int = 1,
    /** ACTIVE, or DISABLED if the user turns it off. Run outcomes are shown separately from real reports. */
    val status: String = "ACTIVE",
) {
    fun slot(name: String) = slots.firstOrNull { it.name == name }

    companion object {
        val DEFAULT_RECOVERY = listOf("DISMISS_SAFE_POPUP", "SCROLL_TO_FIND", "SUBMIT_SEARCH", "DETECT_ALREADY_DONE", "ASK_USER")
    }
}
