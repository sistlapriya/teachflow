import com.teachflow.agent.accessibility.*
import com.teachflow.agent.nlu.*
import com.teachflow.agent.skills.*
import com.teachflow.agent.learning.*
import com.teachflow.agent.execution.*

val apps = listOf("Zomato", "Amazon", "Myntra", "Uber", "Swiggy")
val parser = CommandParser { apps }
var nid = 0
fun node(text: String?, role: SemanticRole, top: Int, clickable: Boolean = true, editable: Boolean = false, desc: String? = null,
         insideScroll: Boolean = true, rid: String? = null, parent: Int? = null, sub: String? = null) =
    NodeSnapshot(nid++, "com.application.zomato", if (editable) "android.widget.EditText" else "android.view.ViewGroup", text, desc, null,
        rid?.let { "com.x:id/$it" }, role, 0.6f, emptyList(), clickable, false, true, editable, false, false, false, true, true, false, 0, -1,
        insideScroll, false, Bounds(0, top, 1080, top + 120), 3, parent, emptyList(), sub)
fun screen(vararg n: NodeSnapshot) = ScreenSnapshot("com.application.zomato", 0, n.toList(), "fp${n.size}${n.firstOrNull()?.text}", false, 1080, 2400)

fun main() { mainZomato(); amazon() }
fun mainZomato() {
    println("== PARSER ==")
    listOf(
        "Order a Margherita pizza from Domino's on Zomato.",
        "Get me a margherita from Domino's.",
        "I want to order margherita pizza on Zomato.",
        "Order Farmhouse from Domino's.",
        "Order two Margherita pizzas from Domino's.",
        "Order Margherita from Domino's and deliver to work.",
        "Search for wireless earbuds on Amazon and add the first result to cart.",
        "Order pizza.",
        "Book a cab to the airport.",
    ).forEach { val p = parser.parse(it); println("${it.padEnd(72)} -> ${p.intentName} ${p.slots} app=${p.appLabel} tokens=${p.contentTokens}") }

    println("\n== TEACH (simulated demonstration events) ==")
    nid = 0
    val home = screen(node("Search for restaurant, item or more", SemanticRole.SEARCH_INPUT, 200, rid = "search_bar", insideScroll = false))
    val results = screen(
        node("Margherita", SemanticRole.SEARCH_INPUT, 200, editable = true, rid = "search_input", insideScroll = false),
        node(null, SemanticRole.LIST_ITEM, 400, sub = "Domino's Pizza · Pizza, Fast Food · 30 min"),
        node(null, SemanticRole.LIST_ITEM, 560, sub = "La Pino'z Pizza · Pizza · 25 min"))
    val menu = screen(
        node("Margherita Pizza", SemanticRole.TEXT, 700, clickable = false),
        node("ADD", SemanticRole.ADD_TO_CART, 720, rid = "add_btn"),
        node("Farmhouse Pizza", SemanticRole.TEXT, 950, clickable = false),
        node("ADD", SemanticRole.ADD_TO_CART, 970, rid = "add_btn"),
        node("Garlic Breadsticks", SemanticRole.TEXT, 1200, clickable = false),
        node("ADD", SemanticRole.ADD_TO_CART, 1220, rid = "add_btn"))
    val z = "com.application.zomato"
    var id = 1L
    fun act(type: ActionType, target: NodeSnapshot?, pkg: String = z, value: String? = null, label: String? = null) =
        ObservedAction(id++, System.currentTimeMillis() + id * 2000, type, pkg, target, label, value, false, null, null)
    val s = TeachingSession("Order a Margherita pizza from Domino's on Zomato.", parser.parse("Order a Margherita pizza from Domino's on Zomato."), z, "Zomato")
    s.onAction(act(ActionType.CLICK, home.nodes[0]), false, home)
    s.onAction(act(ActionType.CLICK, results.nodes[0]), false, results)
    s.onAction(act(ActionType.INPUT_TEXT, results.nodes[0], value = "Margherita"), false, results)
    s.onAction(act(ActionType.CLICK, null, pkg = "com.google.android.dialer", label = "Decline"), false, null)
    s.onAction(act(ActionType.CLICK, results.nodes[1]), false, results)
    s.onAction(act(ActionType.SCROLL, null), false, menu)
    s.onAction(act(ActionType.CLICK, menu.nodes[1]), false, menu)
    s.onAction(act(ActionType.CLICK, node("View Cart", SemanticRole.CART, 2200, insideScroll = false)), false, menu)
    s.onAction(act(ActionType.CLICK, node("Proceed to Pay", SemanticRole.PAYMENT, 2200, insideScroll = false)), false, menu)
    val judged = RelevanceFilter(setOf("com.sec.android.app.launcher")).judge(s)
    judged.forEach { println("  %-28s %-7s %-7s %s".format(it.rec.action.displayLabel.take(28), it.relevance, it.decision, it.reason)) }
    val r = WorkflowSynthesizer().synthesize(s, judged)
    println("LEARNED: ${r.skill.name}\n  observed=${r.observed} relevant=${r.relevant} ignored=${r.ignored}")
    println("  slots=" + r.skill.slots.joinToString { "${it.name}=${it.defaultValue}" })
    r.skill.steps.forEachIndexed { i, st -> println("  ${i + 1}. ${st.title}${if (st.optional) " (optional)" else ""}  — ${st.evidence}") }
    r.warnings.forEach { println("  WARN $it") }
    val skill = r.skill

    println("\n== MATCH ==")
    val amazon = skill.copy(id = "amz", name = "Search for {ITEM} on Amazon and add the first result to cart", intent = "SEARCH_AND_ADD",
        verbs = setOf("SEARCH", "ADD"), targetApp = "Amazon", targetPackage = "in.amazon", contentTokens = setOf("first", "result", "cart"),
        slots = listOf(SlotDef("ITEM", SlotType.STRING, "wireless earbuds"), SlotDef("QUANTITY", SlotType.NUMBER, "1")))
    val m = SkillMatcher()
    listOf("Order a Margherita pizza from Domino's on Zomato.", "Get me a margherita from Domino's.", "I want to order margherita pizza on Zomato.",
        "Order Farmhouse from Domino's.", "Order two Margherita pizzas from Domino's.", "Order Margherita from Domino's and deliver to work.",
        "Search for a bluetooth speaker on Amazon and add the first result to cart.", "Order pizza.", "Book a cab to the airport.",
        "Order a Margherita on Amazon").forEach { c ->
        val out = m.match(parser.parse(c), listOf(skill, amazon))
        val desc = when (out) {
            is MatchOutcome.Matched -> "MATCH ${out.result.skill.targetApp} %.2f slots=${m.resolveSlots(out.result.skill, parser.parse(c))}".format(out.result.score)
            is MatchOutcome.Ambiguous -> "AMBIGUOUS ${out.options.map { it.skill.targetApp }}"
            is MatchOutcome.NoMatch -> "NO MATCH (best %.2f) -> offer to teach".format(out.best?.score ?: 0.0)
        }
        println("  ${c.padEnd(76)} $desc")
    }

    println("\n== GROUNDING on the menu screen ==")
    val g = SemanticGrounder()
    val addStep = skill.steps.first { it.target?.role == SemanticRole.ADD_TO_CART }.target!!
    for (item in listOf("Margherita pizza", "Farmhouse", "Garlic Bread", "pizza", "Pepperoni")) {
        val res = g.ground(addStep, mapOf("ITEM" to item), menu, false)
        val d = when (res) {
            is Grounding.Found -> "FOUND ADD at y=${res.best.node.bounds.top} score=%.2f".format(res.best.score)
            is Grounding.Ambiguous -> "AMBIGUOUS -> ask: " + res.options.map { it.node.bounds.top }
            is Grounding.NotFound -> "NOT FOUND -> ${res.reason}"
        }
        println("  ITEM=${item.padEnd(18)} $d")
    }
    val restStep = skill.steps.first { it.target?.slotRef == "RESTAURANT" }.target!!
    val rr = g.ground(restStep, mapOf("RESTAURANT" to "Domino's"), results, false)
    println("  RESTAURANT=Domino's -> ${if (rr is Grounding.Found) "FOUND \"${rr.best.label}\"" else rr}")
}

fun amazon() {
    println("\n== AMAZON: teach 'first result', replay with a new query ==")
    nid = 0
    val pkg = "in.amazon.mShop.android.shopping"
    fun n(text: String?, role: SemanticRole, top: Int, sub: String? = null, editable: Boolean = false) =
        node(text, role, top, editable = editable, sub = sub).copy(packageName = pkg)
    val results = ScreenSnapshot(pkg, 0, listOf(
        n("wireless earbuds", SemanticRole.SEARCH_INPUT, 100, editable = true),
        n(null, SemanticRole.PRODUCT, 400, sub = "Sponsored boAt Airdopes 141 wireless earbuds ₹1,299"),
        n(null, SemanticRole.PRODUCT, 900, sub = "Noise Buds VS104 ₹999")), "r1", false, 1080, 2400)
    val page = ScreenSnapshot(pkg, 0, listOf(n("Add to Cart", SemanticRole.ADD_TO_CART, 1800)), "p1", false, 1080, 2400)
    var id = 100L
    fun act(type: ActionType, t: NodeSnapshot?, v: String? = null) = ObservedAction(id++, System.currentTimeMillis() + id * 1500, type, pkg, t, null, v, false, null, null)
    val cmd = "Search for wireless earbuds on Amazon and add the first result to cart."
    val s = TeachingSession(cmd, parser.parse(cmd), pkg, "Amazon")
    s.onAction(act(ActionType.INPUT_TEXT, results.nodes[0], "wireless earbuds"), false, results)
    s.onAction(act(ActionType.CLICK, results.nodes[1]), false, results)
    s.onAction(act(ActionType.CLICK, page.nodes[0]), false, page)
    val r = WorkflowSynthesizer().synthesize(s, RelevanceFilter(emptySet()).judge(s))
    println("LEARNED: ${r.skill.name}")
    r.skill.steps.forEachIndexed { i, st -> println("  ${i + 1}. ${st.title}${if (st.optional) " (optional)" else ""}  — ${st.evidence}") }
    val g = SemanticGrounder()
    val newResults = ScreenSnapshot(pkg, 0, listOf(
        n("bluetooth speaker", SemanticRole.SEARCH_INPUT, 100, editable = true),
        n(null, SemanticRole.PRODUCT, 400, sub = "JBL Go 3 Portable Speaker ₹2,499"),
        n(null, SemanticRole.PRODUCT, 900, sub = "boAt Stone 350 ₹1,499")), "r2", false, 1080, 2400)
    val pick = g.ground(r.skill.steps.first { it.target?.ordinal != null }.target!!, mapOf("ITEM" to "bluetooth speaker"), newResults, false)
    println("  replay 'bluetooth speaker': first result -> ${(pick as? Grounding.Found)?.best?.label ?: pick}")
    val newPage = ScreenSnapshot(pkg, 0, listOf(n("Add to Cart", SemanticRole.ADD_TO_CART, 1800), n("Buy Now", SemanticRole.CHECKOUT, 1950)), "p2", false, 1080, 2400)
    val add = g.ground(r.skill.steps.first { it.target?.role == SemanticRole.ADD_TO_CART }.target!!, mapOf("ITEM" to "bluetooth speaker"), newPage, false)
    println("  product page: Add to cart -> ${(add as? Grounding.Found)?.let { "FOUND \"${it.best.label}\" %.2f".format(it.best.score) } ?: add}")
}
