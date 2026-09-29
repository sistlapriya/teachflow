package com.teachflow.agent.accessibility

/**
 * Semantic roles inferred from the accessibility tree.
 *
 * [sensitive] roles hold secrets: their text is never read into memory, logged or stored.
 * [safetyBoundary] roles mark screens where automation must hand control to the user.
 */
enum class SemanticRole(val sensitive: Boolean = false, val safetyBoundary: Boolean = false) {
    PASSWORD(sensitive = true, safetyBoundary = true),
    OTP(sensitive = true, safetyBoundary = true),
    PIN(sensitive = true, safetyBoundary = true),
    CVV(sensitive = true, safetyBoundary = true),
    CARD_NUMBER(sensitive = true, safetyBoundary = true),
    PAYMENT(safetyBoundary = true),
    LOGIN(safetyBoundary = true),
    SEARCH_INPUT,
    ADD_TO_CART,
    QUANTITY_CONTROL,
    CHECKOUT,
    CART,
    ADDRESS_SELECTOR,
    DIALOG_DISMISS,
    BACK,
    PRODUCT,
    /** Reserved. Merchants are found through the {RESTAURANT} slot value, not a dedicated role. */
    RESTAURANT,
    LIST_ITEM,
    NAVIGATION,
    BUTTON,
    TEXT_INPUT,
    CONTAINER,
    TEXT,
    UNKNOWN;

    companion object {
        /** Tie-break order: earlier wins. Safety-relevant roles come first on purpose. */
        val PRIORITY: List<SemanticRole> = entries.toList()
    }
}

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    override fun toString() = "[$left,$top][$right,$bottom]"
}

/** An immutable copy of one AccessibilityNodeInfo. Safe to keep after the node is recycled. */
data class NodeSnapshot(
    val nodeId: Int,
    val packageName: String?,
    val className: String?,
    /** Null when [redacted]. */
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val resourceId: String?,
    val role: SemanticRole,
    /** Heuristic evidence score in 0..1. Not a calibrated probability. */
    val roleScore: Float,
    val roleEvidence: List<String>,
    val clickable: Boolean,
    val longClickable: Boolean,
    val focusable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val password: Boolean,
    val inputType: Int,
    val maxTextLength: Int,
    val insideScrollable: Boolean,
    /** True when this node's text was withheld because it may contain a secret. */
    val redacted: Boolean,
    val bounds: Bounds,
    val depth: Int,
    val parentId: Int?,
    val childIds: List<Int>,
    /** Readable text gathered from descendants (never from redacted nodes). */
    val subtreeLabel: String? = null,
) {
    val shortClass: String get() = className?.substringAfterLast('.') ?: "View"
    val resourceIdName: String? get() = resourceId?.substringAfter(":id/")

    val displayLabel: String
        get() = when {
            redacted -> hint?.takeIf { it.isNotBlank() }?.let { "$it [REDACTED]" } ?: "[REDACTED]"
            !text.isNullOrBlank() -> text
            !contentDescription.isNullOrBlank() -> contentDescription
            !subtreeLabel.isNullOrBlank() -> subtreeLabel
            !hint.isNullOrBlank() -> hint
            !resourceIdName.isNullOrBlank() -> "#$resourceIdName"
            else -> shortClass
        }

    val isInteractive: Boolean get() = clickable || longClickable || editable || scrollable
}

data class ScreenSnapshot(
    val packageName: String,
    val capturedAt: Long,
    val nodes: List<NodeSnapshot>,
    /** Structural fingerprint: changes when interactive structure changes, not when text changes. */
    val fingerprint: String,
    val truncated: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    val semanticCandidates: List<NodeSnapshot>
        get() = nodes
            .filter { it.visible && it.role !in PASSIVE_ROLES }
            .sortedWith(compareByDescending<NodeSnapshot> { it.role.sensitive || it.role.safetyBoundary }.thenByDescending { it.roleScore })

    companion object {
        val PASSIVE_ROLES = setOf(SemanticRole.TEXT, SemanticRole.CONTAINER, SemanticRole.UNKNOWN)
    }
}
