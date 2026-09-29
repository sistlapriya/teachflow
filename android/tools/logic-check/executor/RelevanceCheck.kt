import com.teachflow.agent.accessibility.*
import com.teachflow.agent.learning.*
import com.teachflow.agent.nlu.CommandParser
fun main() { // RelevanceCheckKt
    val z = "com.application.zomato"
    fun n(t: String, r: SemanticRole) = NodeSnapshot(0, z, "android.view.ViewGroup", t, null, null, null, r, .6f, emptyList(), true, false, true, false, false, false, false, true, true, false, 0, -1, true, false, Bounds(0,0,10,10), 1, null, emptyList(), null)
    var id = 0L
    fun a(t: String, r: SemanticRole, before: String, after: String?) = ObservedAction(++id, 1000L * id, ActionType.CLICK, z, n(t, r), null, null, false, null, before, after)
    val p = CommandParser { listOf("Zomato") }
    val cmd = "Order a Margherita pizza from Domino's on Zomato"
    val s = TeachingSession(cmd, p.parse(cmd), z, "Zomato")
    s.onAction(a("Offers for you", SemanticRole.BUTTON, "home", "offers"), false, null)      // opens offers…
    s.onAction(a("Search", SemanticRole.SEARCH_INPUT, "home", "search"), false, null)        // …then back on home: detour
    s.onAction(a("Veg only", SemanticRole.BUTTON, "search", null), false, null)              // no visible effect
    s.onAction(a("Domino's Pizza", SemanticRole.LIST_ITEM, "search", "menu"), false, null)
    RelevanceFilter(emptySet()).judge(s).forEach { println("  %-16s %-10s %-7s %s".format(it.rec.action.displayLabel, it.relevance, it.decision, it.reason)) }
}
