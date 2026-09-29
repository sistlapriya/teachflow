package com.teachflow.agent.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Converts a live AccessibilityNodeInfo tree into immutable [NodeSnapshot]s.
 *
 * Privacy: text of password fields and of any node classified as a credential role is never
 * copied out of the AccessibilityNodeInfo.
 */
class AccessibilityTreeParser(
    private val maxNodes: Int = 1200,
    private val maxDepth: Int = 60,
) {
    private val price = Regex("""(₹|\$|€|£|\brs\.?\s?|\binr\s?)\s?\d""", RegexOption.IGNORE_CASE)

    /**
     * @param live when non-null, receives the live AccessibilityNodeInfo for each snapshot, aligned by
     * nodeId, so the executor can act on grounded nodes. Those nodes are not recycled here.
     */
    fun parse(root: AccessibilityNodeInfo, screenW: Int, screenH: Int, live: MutableList<AccessibilityNodeInfo>? = null): ScreenSnapshot {
        val slots = ArrayList<NodeSnapshot?>()
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, depth: Int, parentId: Int?, insideScrollable: Boolean): Int? {
            if (slots.size >= maxNodes || depth > maxDepth) {
                truncated = true
                return null
            }
            val id = slots.size
            slots.add(null)
            live?.add(node)
            val childIds = ArrayList<Int>()
            val scrollableForChildren = insideScrollable || node.isScrollable
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1, id, scrollableForChildren)?.let { childIds.add(it) }
                if (live == null) recycleCompat(child)
            }
            slots[id] = build(node, id, parentId, childIds, depth, insideScrollable, screenW, screenH)
            return id
        }

        visit(root, 0, null, false)
        val nodes = enrich(slots.map { requireNotNull(it) })
        val pkg = root.packageName?.toString() ?: "unknown"
        return ScreenSnapshot(
            packageName = pkg,
            capturedAt = System.currentTimeMillis(),
            nodes = nodes,
            fingerprint = fingerprint(pkg, nodes),
            truncated = truncated,
            screenWidth = screenW,
            screenHeight = screenH,
        )
    }

    /** Snapshot of a single event source node, with a shallow label from its children. */
    fun snapshotSingle(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): NodeSnapshot {
        val base = build(node, -1, null, emptyList(), -1, isInsideScrollable(node), screenW, screenH)
        val texts = ArrayList<String>()
        shallowTexts(node, 0, texts)
        val label = texts.joinToString(" · ").take(160).ifBlank { null }
        return relabel(base.copy(subtreeLabel = label))
    }

    private fun build(
        node: AccessibilityNodeInfo, id: Int, parentId: Int?, childIds: List<Int>, depth: Int,
        insideScrollable: Boolean, screenW: Int, screenH: Int,
    ): NodeSnapshot {
        val r = Rect()
        node.getBoundsInScreen(r)
        val bounds = Bounds(r.left, r.top, r.right, r.bottom)
        val cls = node.className?.toString()
        val rawText = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val hint = node.hintText?.toString()
        val features = NodeFeatures(
            className = cls,
            // A password field's text never reaches the classifier either.
            text = if (node.isPassword) null else rawText,
            contentDescription = desc,
            hint = hint,
            resourceId = node.viewIdResourceName,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            password = node.isPassword,
            inputType = node.inputType,
            maxTextLength = node.maxTextLength,
            insideScrollable = insideScrollable,
            bounds = bounds,
            screenWidth = screenW,
            screenHeight = screenH,
            childCount = childIds.size,
        )
        val role = SemanticRoleClassifier.classify(features)
        val redacted = node.isPassword || role.role.sensitive
        return NodeSnapshot(
            nodeId = id,
            packageName = node.packageName?.toString(),
            className = cls,
            text = if (redacted) null else rawText?.take(300),
            contentDescription = if (redacted && node.isEditable) null else desc?.take(300),
            hint = hint?.take(120),
            resourceId = node.viewIdResourceName,
            role = role.role,
            roleScore = role.score,
            roleEvidence = role.evidence,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            focusable = node.isFocusable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            password = node.isPassword,
            inputType = node.inputType,
            maxTextLength = node.maxTextLength,
            insideScrollable = insideScrollable,
            redacted = redacted,
            bounds = bounds,
            depth = depth,
            parentId = parentId,
            childIds = childIds,
        )
    }

    /** Second pass: gather descendant labels for clickable containers and re-check their role. */
    private fun enrich(nodes: List<NodeSnapshot>): List<NodeSnapshot> {
        fun collect(id: Int, out: MutableList<String>, budget: IntArray) {
            if (budget[0]-- <= 0) return
            val n = nodes[id]
            if (!n.redacted) {
                val t = n.text?.takeIf { it.isNotBlank() } ?: n.contentDescription?.takeIf { it.isNotBlank() }
                if (t != null) out.add(t.trim())
            }
            for (c in n.childIds) collect(c, out, budget)
        }
        return nodes.map { n ->
            if (!(n.clickable || n.role == SemanticRole.LIST_ITEM) || n.childIds.isEmpty()) return@map n
            val texts = ArrayList<String>()
            val budget = intArrayOf(40)
            n.childIds.forEach { collect(it, texts, budget) }
            val label = texts.distinct().joinToString(" · ").take(160).ifBlank { null } ?: return@map n
            relabel(n.copy(subtreeLabel = label))
        }
    }

    /** Uses a clickable container's descendant label as extra evidence. */
    private fun relabel(n: NodeSnapshot): NodeSnapshot {
        val label = n.subtreeLabel ?: return n
        if (n.role.sensitive || n.role.safetyBoundary && n.roleScore >= 0.6f) return n
        var out = n
        val hasOwnLabel = !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
        if (!hasOwnLabel && label.length <= 40 && (n.clickable || n.longClickable)) {
            val r = SemanticRoleClassifier.classify(featuresOf(n, label))
            val generic = r.role in setOf(SemanticRole.BUTTON, SemanticRole.LIST_ITEM, SemanticRole.TEXT, SemanticRole.CONTAINER, SemanticRole.UNKNOWN)
            if (!generic && r.score >= n.roleScore) {
                out = n.copy(role = r.role, roleScore = r.score, roleEvidence = r.evidence.map { "child label: $it" })
            }
        }
        val genericNow = out.role in setOf(SemanticRole.LIST_ITEM, SemanticRole.BUTTON, SemanticRole.UNKNOWN, SemanticRole.CONTAINER)
        if (genericNow && price.containsMatchIn(label) && (out.insideScrollable || out.role == SemanticRole.LIST_ITEM)) {
            out = out.copy(
                role = SemanticRole.PRODUCT,
                roleScore = maxOf(out.roleScore, 0.55f),
                roleEvidence = out.roleEvidence + "descendant text contains a price",
            )
        }
        return out
    }

    private fun featuresOf(n: NodeSnapshot, text: String?) = NodeFeatures(
        className = n.className, text = text, contentDescription = null, hint = n.hint, resourceId = n.resourceId,
        clickable = n.clickable, longClickable = n.longClickable, editable = n.editable, scrollable = n.scrollable,
        checkable = n.checkable, password = n.password, inputType = n.inputType, maxTextLength = n.maxTextLength,
        insideScrollable = n.insideScrollable, bounds = n.bounds, screenWidth = 0, screenHeight = 0, childCount = n.childIds.size,
    )

    private fun shallowTexts(node: AccessibilityNodeInfo, depth: Int, out: MutableList<String>) {
        if (depth > 3 || out.size >= 6) return
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (!c.isPassword) {
                val t = c.text?.toString()?.takeIf { it.isNotBlank() } ?: c.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                if (t != null && !c.isEditable) out.add(t.trim())
                shallowTexts(c, depth + 1, out)
            }
            recycleCompat(c)
        }
    }

    private fun isInsideScrollable(node: AccessibilityNodeInfo): Boolean {
        var p = node.parent
        var hops = 0
        while (p != null && hops < 12) {
            if (p.isScrollable) {
                recycleCompat(p)
                return true
            }
            val next = p.parent
            recycleCompat(p)
            p = next
            hops++
        }
        p?.let { recycleCompat(it) }
        return false
    }

    private fun fingerprint(pkg: String, nodes: List<NodeSnapshot>): String {
        val sig = nodes.asSequence()
            .filter { it.visible && (it.clickable || it.editable || it.scrollable) }
            .joinToString(";") { "${it.shortClass}|${it.resourceIdName}|${it.role}" }
        return "$pkg:" + Integer.toHexString(sig.hashCode())
    }

    companion object {
        @Suppress("DEPRECATION")
        fun recycleCompat(node: AccessibilityNodeInfo) {
            // recycle() is a no-op from API 33; still required on older releases.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
        }
    }
}
