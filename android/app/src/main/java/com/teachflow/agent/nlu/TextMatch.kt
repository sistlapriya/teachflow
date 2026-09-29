package com.teachflow.agent.nlu

import kotlin.math.ln

/** Normalisation and fuzzy matching shared by the parser, matcher and grounder. */
object TextMatch {

    /** Lowercase, unify apostrophes (Domino's = Dominos), strip punctuation. */
    fun normalize(s: String): String =
        s.lowercase()
            .replace("\u2019", "").replace("\u2018", "").replace("'", "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun singular(t: String): String = when {
        t.length > 4 && t.endsWith("ies") -> t.dropLast(3) + "y"
        t.length > 4 && (t.endsWith("ches") || t.endsWith("shes") || t.endsWith("xes") || t.endsWith("sses")) -> t.dropLast(2)
        t.length > 3 && t.endsWith("s") && !t.endsWith("ss") -> t.dropLast(1)
        else -> t
    }

    fun tokens(s: String?): List<String> =
        if (s.isNullOrBlank()) emptyList() else normalize(s).split(' ').filter { it.isNotBlank() }.map(::singular)

    /**
     * Weighted similarity of a wanted value to a candidate label, in 0..1.
     * Half Dice overlap, half "how much of the wanted value the label covers".
     * [weight] lets callers down-weight tokens that are common on the current screen.
     */
    private fun tokenMatch(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)))

    /**
     * Weighted similarity of a wanted value to a candidate label, in 0..1.
     * Half Dice overlap, half "how much of the wanted value the label covers".
     * Tokens match exactly or by prefix ("bread" ~ "breadsticks").
     * [weight] lets callers down-weight tokens that are common on the current screen.
     */
    fun similarity(wanted: String?, label: String?, weight: (String) -> Double = { 1.0 }): Double {
        val a = tokens(wanted).toSet()
        val b = tokens(label).toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val matchedA = a.filter { x -> b.any { tokenMatch(x, it) } }
        if (matchedA.isEmpty()) return 0.0
        val matchedB = b.filter { y -> a.any { tokenMatch(it, y) } }
        val wa = a.sumOf(weight)
        val wb = b.sumOf(weight)
        val dice = (matchedA.sumOf(weight) + matchedB.sumOf(weight)) / (wa + wb)
        val cover = matchedA.sumOf(weight) / wa
        return (0.5 * dice + 0.5 * cover).coerceIn(0.0, 1.0)
    }

    /** True when every token of [part] appears (exactly or by prefix) in [whole]. */
    fun isSubset(part: String?, whole: String?): Boolean {
        val a = tokens(part).toSet()
        val b = tokens(whole).toSet()
        return a.isNotEmpty() && a.all { x -> b.any { tokenMatch(x, it) } }
    }

    /** Token weight from how often a token appears among on-screen labels: common = less informative. */
    fun screenWeights(labels: List<String>): (String) -> Double {
        val freq = HashMap<String, Int>()
        labels.forEach { l -> tokens(l).toSet().forEach { freq[it] = (freq[it] ?: 0) + 1 } }
        return { t -> 1.0 / (1.0 + ln(1.0 + (freq[t] ?: 0).toDouble())) }
    }
}
