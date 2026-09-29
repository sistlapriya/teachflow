package com.teachflow.agent.accessibility

import android.text.InputType

/** Everything the classifier may look at. No package names: roles must be app-agnostic. */
data class NodeFeatures(
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val resourceId: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val password: Boolean,
    val inputType: Int,
    val maxTextLength: Int,
    val insideScrollable: Boolean,
    val bounds: Bounds,
    val screenWidth: Int,
    val screenHeight: Int,
    val childCount: Int,
)

data class RoleResult(val role: SemanticRole, val score: Float, val evidence: List<String>)

/**
 * Multi-signal, rule-based role inference. Every decision carries human-readable evidence.
 *
 * Safety bias: for editable fields, any credential-like signal at or above [SENSITIVE_THRESHOLD]
 * wins over every other role. When uncertain whether a field holds a secret, treat it as one.
 */
object SemanticRoleClassifier {

    private const val SENSITIVE_THRESHOLD = 0.35f

    private fun words(vararg w: String): List<Regex> =
        w.map { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }

    private val PASSWORD_W = words("password", "passcode", "passwd")
    private val OTP_W = words("otp", "one time password", "one-time password", "verification code", "enter code", "sms code")
    private val PIN_W = words("pin", "mpin", "upi pin", "atm pin")
    private val CVV_W = words("cvv", "cvv2", "cvc", "card verification", "security code")
    private val CARD_W = words("card number", "card no", "debit card number", "credit card number")
    private val SEARCH_W = words("search")
    private val PAYMENT_W = words("pay", "pay now", "proceed to pay", "make payment", "payment", "upi", "net banking", "netbanking", "wallet")
    private val LOGIN_W = words("log in", "login", "sign in", "signin", "sign up", "continue with google", "continue with phone", "continue with email")
    private val CHECKOUT_W = words("checkout", "check out", "proceed", "place order", "buy now")
    private val ADD_W = words("add to cart", "add to bag", "add to basket", "add item", "add to trolley")
    private val ADD_EXACT = setOf("add", "add +", "+ add", "add+", "+add")
    private val CART_W = words("cart", "bag", "basket", "view cart", "go to cart", "trolley")
    private val QTY_W = words("increase", "decrease", "increment", "decrement", "quantity", "qty")
    private val QTY_EXACT = setOf("+", "-", "\u2212", "\u2013")
    private val ADDR_W = words("address", "deliver to", "delivering to", "delivery location", "change address", "select address", "pincode", "pin code", "zip code", "postal code")
    private val BACK_W = words("navigate up", "back", "go back")
    private val DISMISS_W = words("close", "dismiss", "not now", "skip", "cancel", "no thanks", "maybe later", "later", "got it")
    private val DISMISS_EXACT = setOf("x", "\u00D7", "\u2715", "\u2716")
    private val NAV_RID = words("tab", "bottom", "navigation", "nav", "menu", "toolbar")
    private val CONTAINER_CLASSES = listOf("Layout", "ViewGroup", "CardView", "RecyclerView", "ListView")

    fun classify(f: NodeFeatures): RoleResult {
        val scores = LinkedHashMap<SemanticRole, Float>()
        val evidence = LinkedHashMap<SemanticRole, MutableList<String>>()
        fun add(role: SemanticRole, weight: Float, why: String) {
            scores[role] = ((scores[role] ?: 0f) + weight).coerceAtMost(1f)
            evidence.getOrPut(role) { mutableListOf() }.add(why)
        }

        val label = listOfNotNull(f.text, f.contentDescription, f.hint).joinToString(" | ").trim()
        val lab = label.lowercase()
        val exact = lab.trim()
        val rid = f.resourceId?.substringAfter(":id/")?.replace('_', ' ')?.replace('-', ' ')?.lowercase().orEmpty()
        val cls = f.className?.substringAfterLast('.').orEmpty()
        val interactive = f.clickable || f.longClickable

        fun match(ws: List<Regex>, src: String): String? {
            if (src.isEmpty()) return null
            for (r in ws) r.find(src)?.let { return it.value }
            return null
        }
        fun signal(ws: List<Regex>, role: SemanticRole, wLabel: Float, wRid: Float) {
            match(ws, lab)?.let { add(role, wLabel, "label contains \"$it\"") }
            match(ws, rid)?.let { add(role, wRid, "resource-id contains \"$it\"") }
        }

        if (f.editable) {
            val typeClass = f.inputType and InputType.TYPE_MASK_CLASS
            val variation = f.inputType and InputType.TYPE_MASK_VARIATION
            val passwordType =
                (typeClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                )) || (typeClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val shortNumeric = typeClass == InputType.TYPE_CLASS_NUMBER && f.maxTextLength in 3..8
            val addressHit = match(ADDR_W, lab) ?: match(ADDR_W, rid)

            if (f.password) add(SemanticRole.PASSWORD, 1f, "isPassword flag set")
            if (passwordType) add(SemanticRole.PASSWORD, 0.9f, "password input type")
            signal(PASSWORD_W, SemanticRole.PASSWORD, 0.7f, 0.6f)
            signal(OTP_W, SemanticRole.OTP, 0.8f, 0.7f)
            if (addressHit == null) signal(PIN_W, SemanticRole.PIN, 0.8f, 0.7f)
            signal(CVV_W, SemanticRole.CVV, 0.85f, 0.7f)
            signal(CARD_W, SemanticRole.CARD_NUMBER, 0.85f, 0.7f)
            if (shortNumeric && match(QTY_W, lab) == null) add(SemanticRole.OTP, 0.35f, "short numeric input (max ${f.maxTextLength} chars)")

            signal(SEARCH_W, SemanticRole.SEARCH_INPUT, 0.7f, 0.6f)
            if (cls.contains("SearchView") || cls.contains("AutoComplete")) add(SemanticRole.SEARCH_INPUT, 0.3f, "class $cls")
            if (addressHit != null) add(SemanticRole.ADDRESS_SELECTOR, 0.6f, "label contains \"$addressHit\"")
            add(SemanticRole.TEXT_INPUT, 0.45f, "editable field")

            val sensitive = scores.filterKeys { it.sensitive }.maxByOrNull { it.value }
            if (sensitive != null && sensitive.value >= SENSITIVE_THRESHOLD) {
                return RoleResult(sensitive.key, sensitive.value, evidence[sensitive.key].orEmpty() + "safety bias: credential-like field")
            }
        } else if (interactive) {
            signal(PAYMENT_W, SemanticRole.PAYMENT, 0.75f, 0.6f)
            signal(LOGIN_W, SemanticRole.LOGIN, 0.7f, 0.6f)
            signal(CHECKOUT_W, SemanticRole.CHECKOUT, 0.7f, 0.55f)
            signal(ADD_W, SemanticRole.ADD_TO_CART, 0.85f, 0.7f)
            if (exact in ADD_EXACT) add(SemanticRole.ADD_TO_CART, 0.6f, "label is \"$exact\"")
            signal(CART_W, SemanticRole.CART, 0.6f, 0.55f)
            if (exact in QTY_EXACT) add(SemanticRole.QUANTITY_CONTROL, 0.7f, "label is \"$exact\"")
            signal(QTY_W, SemanticRole.QUANTITY_CONTROL, 0.7f, 0.6f)
            signal(ADDR_W, SemanticRole.ADDRESS_SELECTOR, 0.6f, 0.55f)
            signal(BACK_W, SemanticRole.BACK, 0.7f, 0.6f)
            signal(DISMISS_W, SemanticRole.DIALOG_DISMISS, 0.7f, 0.6f)
            if (exact in DISMISS_EXACT) add(SemanticRole.DIALOG_DISMISS, 0.7f, "label is \"$exact\"")
            match(NAV_RID, rid)?.let { add(SemanticRole.NAVIGATION, 0.4f, "resource-id contains \"$it\"") }
            if (f.screenHeight > 0 && f.bounds.top > f.screenHeight * 0.88 && f.bounds.height < f.screenHeight * 0.12) {
                add(SemanticRole.NAVIGATION, 0.25f, "sits in bottom bar region")
            }
            if (f.insideScrollable && CONTAINER_CLASSES.any { cls.contains(it) }) {
                add(SemanticRole.LIST_ITEM, 0.4f, "clickable container inside a scrollable list")
            }
            when {
                cls.contains("Button") -> add(SemanticRole.BUTTON, 0.5f, "class $cls")
                label.isNotBlank() -> add(SemanticRole.BUTTON, 0.35f, "clickable with a label")
                SemanticRole.LIST_ITEM !in scores -> add(SemanticRole.BUTTON, 0.2f, "clickable, no own label")
            }
        } else {
            signal(PAYMENT_W, SemanticRole.PAYMENT, 0.4f, 0.3f)
            signal(LOGIN_W, SemanticRole.LOGIN, 0.35f, 0.3f)
            if (f.scrollable) add(SemanticRole.CONTAINER, 0.4f, "scrollable container")
            if (label.isNotBlank()) add(SemanticRole.TEXT, 0.3f, "static text")
            else if (f.childCount > 0) add(SemanticRole.CONTAINER, 0.2f, "layout container")
        }

        if (scores.isEmpty()) return RoleResult(SemanticRole.UNKNOWN, 0f, listOf("no usable signals"))
        val best = scores.entries.sortedWith(
            compareByDescending<Map.Entry<SemanticRole, Float>> { it.value }
                .thenBy { SemanticRole.PRIORITY.indexOf(it.key) }
        ).first()
        return RoleResult(best.key, best.value, evidence[best.key].orEmpty())
    }
}
