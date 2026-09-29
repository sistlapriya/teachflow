import com.teachflow.agent.accessibility.SemanticRole
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.core.Reply
import com.teachflow.agent.execution.*
import com.teachflow.agent.nlu.CommandParser
import com.teachflow.agent.skills.*
import kotlinx.coroutines.flow.TestHooks
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

fun <T> runSync(block: suspend () -> T): T {
    var r: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { r = it })
    return r!!.getOrThrow()
}

val parser = CommandParser { listOf("Zomato", "Amazon") }

/** The skill the synthesizer produced from the Zomato demonstration (see Harness.kt). */
val skill = Skill(
    id = "s1", name = "Order a {ITEM} from {RESTAURANT} on Zomato", intent = "ORDER", verbs = setOf("ORDER"),
    exampleCommands = listOf("Order a Margherita pizza from Domino's on Zomato."), contentTokens = emptySet(),
    targetPackage = PKG, targetApp = "Zomato",
    slots = listOf(SlotDef("ITEM", SlotType.STRING, "Margherita pizza"), SlotDef("RESTAURANT", SlotType.ENTITY, "Domino's"),
        SlotDef("QUANTITY", SlotType.NUMBER, "1"), SlotDef("ADDRESS", SlotType.ADDRESS, null)),
    steps = listOf(
        SkillStep(StepKind.CLICK, TargetSpec(SemanticRole.SEARCH_INPUT, anchorLabel = "Search for restaurant, item or more", resourceIdName = "search_bar")),
        SkillStep(StepKind.INPUT, TargetSpec(SemanticRole.SEARCH_INPUT, resourceIdName = "search_input"), valueTemplate = "{ITEM}"),
        SkillStep(StepKind.CLICK, TargetSpec(SemanticRole.LIST_ITEM, slotRef = "RESTAURANT")),
        SkillStep(StepKind.CLICK, TargetSpec(SemanticRole.ADD_TO_CART, anchorLabel = "ADD", nearSlot = "ITEM", resourceIdName = "add_btn"), mayNeedScroll = true),
        SkillStep(StepKind.SET_QUANTITY, TargetSpec(SemanticRole.QUANTITY_CONTROL, nearSlot = "ITEM"), optional = true),
        SkillStep(StepKind.CLICK, TargetSpec(SemanticRole.CART, anchorLabel = "View Cart")),
        SkillStep(StepKind.SELECT_ADDRESS, TargetSpec(SemanticRole.ADDRESS_SELECTOR, slotRef = "ADDRESS"), optional = true),
        SkillStep(StepKind.STOP_AT_BOUNDARY),
    ),
    createdAt = 0, updatedAt = 0,
)

fun scenario(title: String, command: String, app: FakeApp, overrides: Map<String, String> = emptyMap(),
             answer: (String, String, List<String>) -> Reply = { _, _, _ -> Reply.Stop }, onWait: (() -> Unit)? = null) {
    println("\n━━ $title ━━  \"$command\"")
    AgentBus.status.value = com.teachflow.agent.core.AgentStatus()
    TestHooks.onWait = onWait
    val parsed = parser.parse(command)
    val m = SkillMatcher()
    val slots = LinkedHashMap(m.resolveSlots(skill, parsed)).apply { putAll(overrides) }
    val exec = WorkflowExecutor(capture = { app.capture() }, actions = app, ask = { h, q, o, allowText ->
        val a = answer(h, q, o)
        val shown = when (a) { is Reply.Option -> o[a.index]; is Reply.Text -> "typed \"${a.text}\""; else -> a.toString() }
        println("   ❓ [$h] ${q.replace("\n", " ")}\n      options=$o${if (allowText) " + type/say" else ""} → answer=$shown")
        a
    })
    val r = runSync { exec.run(skill, command, slots, parsed.slots.keys + overrides.keys, emptyList(), if (overrides.isEmpty()) 0 else 1) }
    val keyPhases = setOf("STATE CLASSIFIED", "RECOVERY", "RECOVERY REQUIRED", "RECOVERY EXHAUSTED", "PARAMETER RESOLVED",
        "PAYMENT DETECTED", "AUTHENTICATION DETECTED", "SAFETY STOP", "USER RESUMED", "YOUR TURN")
    r.trace.filter { it.phase in keyPhases || (it.phase == "STATE VERIFIED") }.forEach {
        println("   [${it.phase}] step ${it.step} · ${it.message.take(110)}${it.result?.let { r -> "  → $r" } ?: ""}")
    }
    println("   actions: ${app.log}")
    println("   RUN REPORT: final=${r.finalStatus} · reason=${r.terminalReason.display} · steps ${r.stepsSucceeded}/${r.stepsTotal} ✓ · recovered ${r.stepsRecovered} · recovery attempts ${r.recoveryAttempts} · interventions ${r.userInterventions} · boundary ${r.safetyBoundaryTriggered} · stopped at step ${r.stoppedAtStep}")
    println("   overlay: ${AgentBus.status.value.headline} | ${AgentBus.status.value.detail.replace("\n", " ")} | ${AgentBus.status.value.summary ?: ""}")
    check(app.log.none { it.startsWith("!!!") }) { "SAFETY VIOLATION: ${app.log}" }
}

fun main() {
    scenario("T2 exact replay", "Order a Margherita pizza from Domino's on Zomato.", FakeApp())
    scenario("T4 different item (needs scrolling)", "Order Garlic Breadsticks from Domino's", FakeApp())
    scenario("T5 quantity", "Order two Farmhouse pizzas from Domino's", FakeApp())
    scenario("T6 address", "Order Margherita from Domino's and deliver to work", FakeApp())
    scenario("T7 popup recovery", "Get me a margherita from Domino's", FakeApp(popupOnMenu = true))
    scenario("Already in cart", "Order Margherita from Domino's", FakeApp(preInCart = mapOf("Margherita Pizza" to 1)))
    scenario("T10 stuck → recovery exhausted", "Order Pepperoni Supreme from Domino's", FakeApp())
    scenario("Empty results", "Order Pepperoni Supreme from Domino's", FakeApp(emptyResults = true))
    scenario("T13 ambiguous → ask → parameter resolved", "Order pizza from Domino's", FakeApp(),
        answer = { _, _, o -> Reply.Option(o.indexOfFirst { it.startsWith("Farmhouse") }) })
    scenario("Mid-run: RESTAURANT missing → typed answer", "Order a Margherita", FakeApp(),
        answer = { _, q, _ -> if (q.contains("restaurant")) Reply.Text("Domino's") else Reply.Stop })
    scenario("Mid-run: RESTAURANT missing → taught option", "Order a Margherita", FakeApp(),
        answer = { _, _, o -> Reply.Option(o.indexOfFirst { it.endsWith("(taught)") }) })
    scenario("Pre-run clarification (ITEM=Farmhouse from the Home dialog)", "Order pizza from Domino's", FakeApp(), overrides = mapOf("ITEM" to "Farmhouse"))
    val login = FakeApp(loginAtCart = true)
    scenario("T11 authentication boundary → Resume", "Order Margherita from Domino's", login, onWait = {
        println("   👤 user signs in and taps Resume"); login.screen = "CART"
        AgentBus.status.value = AgentBus.status.value.copy(humanInControl = false)
    })
}
