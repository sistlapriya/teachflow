package com.teachflow.agent.nlu

/** Verb families. Paraphrases map to the same family ("get me", "I want", "order" → ORDER). */
enum class Verb { ORDER, SEARCH, ADD, BOOK, OTHER }

data class ParsedCommand(
    val raw: String,
    val verbs: Set<Verb>,
    /** Slot name → value as the user said it. Only slots actually present in the utterance. */
    val slots: Map<String, String>,
    /** Launcher label of the app named in the command, if any. */
    val appLabel: String?,
    /** Remaining informative tokens, used for paraphrase similarity. */
    val contentTokens: Set<String>,
    /** "the first result" → 1. Positional choice the user asked for explicitly. */
    val ordinal: Int? = null,
) {
    val intentName: String
        get() = when {
            Verb.SEARCH in verbs && Verb.ADD in verbs -> "SEARCH_AND_ADD"
            Verb.ORDER in verbs -> "ORDER"
            Verb.BOOK in verbs -> "BOOK"
            Verb.SEARCH in verbs -> "SEARCH"
            Verb.ADD in verbs -> "ADD"
            else -> "TASK"
        }
}

/**
 * Offline, rule-based intent + slot extraction. No network, no model, no per-test-case rules:
 * it only knows English sentence structure ("from X", "on <app>", "deliver to Y", numbers).
 *
 * Slots: ITEM, RESTAURANT (the "from X" merchant), QUANTITY, ADDRESS.
 */
class CommandParser(private val knownAppLabels: () -> Collection<String>) {

    private val verbWords: List<Pair<Regex, Verb>> = listOf(
        "order" to Verb.ORDER, "get me" to Verb.ORDER, "get" to Verb.ORDER, "buy" to Verb.ORDER,
        "i want" to Verb.ORDER, "i'd like" to Verb.ORDER, "i would like" to Verb.ORDER, "i need" to Verb.ORDER,
        "grab" to Verb.ORDER, "bring me" to Verb.ORDER, "fetch" to Verb.ORDER, "deliver" to Verb.ORDER,
        "search for" to Verb.SEARCH, "search" to Verb.SEARCH, "find" to Verb.SEARCH, "look for" to Verb.SEARCH,
        "look up" to Verb.SEARCH, "show me" to Verb.SEARCH,
        "add" to Verb.ADD, "put" to Verb.ADD,
        "book" to Verb.BOOK, "reserve" to Verb.BOOK, "call a" to Verb.BOOK,
    ).map { (w, v) -> Regex("\\b" + Regex.escape(w) + "\\b", RegexOption.IGNORE_CASE) to v }

    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "a couple of" to 2, "a pair of" to 2, "couple of" to 2,
    )

    private val stop = setOf(
        "a", "an", "the", "to", "of", "for", "me", "my", "i", "want", "wanna", "please", "on", "in", "from", "and",
        "with", "some", "can", "you", "would", "like", "could", "it", "id", "need", "get", "order", "buy", "search",
        "find", "add", "look", "show", "using", "via", "app", "just", "now", "quickly",
        "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "couple", "pair",
    )

    fun parse(raw: String): ParsedCommand {
        // Commas and semicolons separate clauses ("…from Domino's, deliver to work"), so treat them like "and".
        var text = " " + raw.trim().replace("\u2019", "'").trimEnd('.', '!', '?').replace(Regex("\\s*[,;]\\s*"), " and ") + " "
        val slots = LinkedHashMap<String, String>()

        // App: a known launcher label, preferably introduced by on/in/using/via.
        var appLabel: String? = null
        val apps = knownAppLabels().filter { it.length >= 3 }.sortedByDescending { it.length }
        for (label in apps) {
            val intro = Regex("\\b(on|in|using|via|through)\\s+(the\\s+)?" + Regex.escape(label) + "(\\s+app)?\\b", RegexOption.IGNORE_CASE)
            val m = intro.find(text)
            if (m != null) { appLabel = label; text = text.removeRange(m.range).let { " $it " }; break }
        }
        if (appLabel == null) {
            for (label in apps) {
                val bare = Regex("\\b" + Regex.escape(label) + "\\b", RegexOption.IGNORE_CASE)
                val m = bare.find(text) ?: continue
                appLabel = label; text = text.removeRange(m.range).let { " $it " }; break
            }
        }

        // Address: "deliver to work", "send it to my home", "to the office".
        Regex("\\b(?:deliver(?:ed)?|send|ship)(?:\\s+it)?\\s+to\\s+(?:my\\s+|the\\s+)?([\\p{L}\\p{N}' ]+?)(?=\\s+(?:and|on|in|from|please)\\b|\\s*$)", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                slots["ADDRESS"] = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                text = text.removeRange(m.range).let { " $it " }
            }
        if ("ADDRESS" !in slots) {
            Regex("\\bto\\s+(?:my\\s+|the\\s+)?(home|work|office)\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                slots["ADDRESS"] = m.groupValues[1].replaceFirstChar { it.uppercase() }
                text = text.removeRange(m.range).let { " $it " }
            }
        }

        // Merchant: "from Domino's".
        Regex("\\bfrom\\s+(?:the\\s+)?([\\p{L}\\p{N}'&. ]+?)(?=\\s+(?:and|to|deliver|please|for)\\b|\\s*$)", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                slots["RESTAURANT"] = m.groupValues[1].trim()
                text = text.removeRange(m.range).let { " $it " }
            }

        // Verbs.
        val verbs = LinkedHashSet<Verb>()
        verbWords.forEach { (r, v) -> if (r.containsMatchIn(text)) verbs.add(v) }
        if (verbs.isEmpty()) verbs.add(Verb.OTHER)

        // Item: the object after the first verb, up to a clause boundary.
        val verbAlt = "order|get me|get|buy|want(?: to order| to buy)?|would like(?: to order)?|'d like(?: to order)?|need|grab|bring me|fetch|search for|search|find|look for|look up|show me|add|book|reserve"
        val itemRe = Regex("\\b(?:$verbAlt)\\s+(.+?)(?=\\s+(?:and|then|to|for|please|with)\\b|\\s*$)", RegexOption.IGNORE_CASE)
        val rawItem = itemRe.find(text)?.groupValues?.get(1)?.trim()
        if (rawItem != null) {
            var phrase: String = rawItem
            // Quantity inside the item phrase: "two farmhouse pizzas", "3 x garlic bread".
            val qtyRe = Regex("^(?:(\\d{1,2})\\s*x?|(" + numberWords.keys.joinToString("|") { Regex.escape(it) } + "))\\s+", RegexOption.IGNORE_CASE)
            val qm = qtyRe.find(phrase)
            if (qm != null) {
                val n = qm.groupValues[1].toIntOrNull() ?: numberWords[qm.groupValues[2].lowercase()]
                if (n != null && n > 0) slots["QUANTITY"] = n.toString()
                phrase = phrase.removeRange(qm.range)
            }
            phrase = phrase.replace(Regex("^(?:a|an|the|some|me a|me an|me)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (phrase.isNotBlank() && !phrase.equals("it", ignoreCase = true)) slots["ITEM"] = phrase
        }

        val ordinal = Regex("\\b(first|1st|top|second|2nd|third|3rd)\\s+(?:search\\s+)?(result|item|product|option|one|listing)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.lowercase()?.let { w ->
                when (w) { "first", "1st", "top" -> 1; "second", "2nd" -> 2; else -> 3 }
            }

        val content = TextMatch.tokens(text).filter { it !in stop && it.length > 1 }.toMutableSet()
        slots.values.forEach { v -> content.removeAll(TextMatch.tokens(v).toSet()) }
        return ParsedCommand(raw.trim(), verbs, slots, appLabel, content, ordinal)
    }
}
