import com.teachflow.agent.nlu.CommandParser
import com.teachflow.agent.skills.*
fun main() {
    val p = CommandParser { listOf("Zomato", "Amazon") }
    val m = SkillMatcher()
    for (c in listOf("Order pizza from Domino's", "Order pizza.", "Order from Domino's", "Order Farmhouse from Domino's", "Get me a margherita")) {
        println("%-34s -> %s".format(c, m.missingDetail(skill, p.parse(c))))
    }
    val amazon = skill.copy(steps = listOf(SkillStep(StepKind.INPUT, valueTemplate = "{ITEM}"), SkillStep(StepKind.CLICK, TargetSpec(com.teachflow.agent.accessibility.SemanticRole.PRODUCT, ordinal = 1, ordinalRole = com.teachflow.agent.accessibility.SemanticRole.PRODUCT))),
        slots = listOf(SlotDef("ITEM", SlotType.STRING, "wireless earbuds")))
    println("Amazon 'Search for earbuds on Amazon and add the first result' -> " + m.missingDetail(amazon, p.parse("Search for earbuds on Amazon and add the first result to cart")))
}
