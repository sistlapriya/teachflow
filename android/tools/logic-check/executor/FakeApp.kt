import com.teachflow.agent.accessibility.*
import com.teachflow.agent.execution.*

const val PKG = "com.application.zomato"

/**
 * A scripted fake food app: HOME → SEARCH → RESULTS → MENU → CART (→ LOGIN / ADDRESS / PAYMENT).
 * It exposes accessibility-like snapshots and reacts to the executor's actions. Handles are string tags.
 */
class FakeApp(
    val menu: List<String> = listOf("Margherita Pizza", "Farmhouse Pizza", "Peppy Paneer Pizza", "Garlic Breadsticks", "Choco Lava Cake", "Pepsi 500ml"),
    var popupOnMenu: Boolean = false,
    var loginAtCart: Boolean = false,
    var emptyResults: Boolean = false,
    var preInCart: Map<String, Int> = emptyMap(),
) : UiActions {
    var screen = "HOME"
    var search = ""
    var scroll = 0
    val cart = LinkedHashMap<String, Int>(preInCart)
    var address = "Home"
    val log = ArrayList<String>()

    private val nodes = ArrayList<NodeSnapshot>()
    private val tags = ArrayList<Any>()
    private fun n(text: String?, role: SemanticRole, top: Int, tag: String = "none", clickable: Boolean = false, editable: Boolean = false,
                  scrollable: Boolean = false, rid: String? = null, sub: String? = null, cls: String? = null, redacted: Boolean = false) {
        val id = nodes.size
        nodes += NodeSnapshot(id, PKG, cls ?: if (editable) "android.widget.EditText" else "android.view.ViewGroup", text, null, null,
            rid?.let { "$PKG:id/$it" }, role, 0.7f, emptyList(), clickable, false, clickable || editable, editable, scrollable, false, false,
            true, true, redacted, 0, -1, true, redacted, Bounds(0, top, 1080, top + 110), 3, null, emptyList(), sub)
        tags += tag
    }

    fun capture(): CapturedScreen {
        nodes.clear(); tags.clear()
        when (screen) {
            "HOME" -> {
                n("Search for restaurant, item or more", SemanticRole.SEARCH_INPUT, 200, "open_search", clickable = true, rid = "search_bar")
                n("Recommended for you", SemanticRole.TEXT, 500); n("Offers near you", SemanticRole.TEXT, 800); n("Top brands", SemanticRole.TEXT, 1100)
            }
            "SEARCH" -> {
                n(search, SemanticRole.SEARCH_INPUT, 120, "field", editable = true, rid = "search_input")
                if (search.isNotEmpty()) {
                    if (emptyResults) n("No results found for \"$search\"", SemanticRole.TEXT, 500)
                    else {
                        n("Restaurants", SemanticRole.TEXT, 300)
                        n(null, SemanticRole.LIST_ITEM, 400, "rest:dominos", clickable = true, sub = "Domino's Pizza · Pizza, Fast Food · 30 min")
                        n(null, SemanticRole.LIST_ITEM, 560, "rest:lapinoz", clickable = true, sub = "La Pino'z Pizza · Pizza · 25 min")
                        n("Dishes", SemanticRole.TEXT, 720)
                    }
                } else n("Recent searches", SemanticRole.TEXT, 300)
            }
            "MENU" -> {
                n("Domino's Pizza", SemanticRole.TEXT, 120); n("Pizza, Fast Food · 4.2 ★", SemanticRole.TEXT, 200)
                n(null, SemanticRole.UNKNOWN, 300, "scroll", scrollable = true)
                if (popupOnMenu) {
                    n("Get 50% off your first order!", SemanticRole.TEXT, 900, cls = "android.app.Dialog")
                    n("Not now", SemanticRole.DIALOG_DISMISS, 1100, "popup_close", clickable = true)
                } else {
                    menu.drop(scroll * 3).take(3).forEachIndexed { i, item ->
                        val top = 400 + i * 300
                        n(item, SemanticRole.TEXT, top)
                        val q = cart[item]
                        if (q == null) n("ADD", SemanticRole.ADD_TO_CART, top + 40, "add:$item", clickable = true, rid = "add_btn")
                        else {
                            n("-", SemanticRole.QUANTITY_CONTROL, top + 40, "minus:$item", clickable = true)
                            n("$q", SemanticRole.TEXT, top + 40)
                            n("+", SemanticRole.QUANTITY_CONTROL, top + 40, "plus:$item", clickable = true)
                        }
                    }
                    if (cart.isNotEmpty()) n("View Cart", SemanticRole.CART, 2200, "view_cart", clickable = true)
                }
            }
            "CART" -> {
                n("Your cart", SemanticRole.TEXT, 120)
                cart.forEach { (k, v) -> n("$k × $v", SemanticRole.TEXT, 300 + cart.keys.indexOf(k) * 120) }
                n("Delivering to $address", SemanticRole.ADDRESS_SELECTOR, 1500, "addr", clickable = true)
                n("Proceed to Pay", SemanticRole.PAYMENT, 2200, "pay", clickable = true)
            }
            "ADDRESS" -> {
                n("Select an address", SemanticRole.TEXT, 900)
                n("Home", SemanticRole.LIST_ITEM, 1100, "addr:Home", clickable = true)
                n("Work", SemanticRole.LIST_ITEM, 1250, "addr:Work", clickable = true)
            }
            "LOGIN" -> {
                n("Log in to continue", SemanticRole.TEXT, 300)
                n(null, SemanticRole.TEXT_INPUT, 600, "phone", editable = true)
                n(null, SemanticRole.PASSWORD, 750, "pw", editable = true, redacted = true)
                n("Log in", SemanticRole.LOGIN, 950, "login", clickable = true)
            }
            "PAYMENT" -> { n("UPI", SemanticRole.TEXT, 300); n("Credit card", SemanticRole.TEXT, 500); n("Pay ₹478", SemanticRole.PAYMENT, 2200, clickable = true) }
        }
        val fp = "$screen|$search|$scroll|$popupOnMenu|$cart|$address|${emptyResults}"
        return CapturedScreen(ScreenSnapshot(PKG, System.currentTimeMillis(), nodes.toList(), fp, false, 1080, 2400), tags.toList())
    }

    override fun click(handle: Any, bounds: Bounds): String? {
        val t = handle as String
        log += "click $t"
        when {
            t == "open_search" -> screen = "SEARCH"
            t.startsWith("rest:dominos") -> { screen = "MENU"; scroll = 0 }
            t == "popup_close" -> popupOnMenu = false
            t.startsWith("add:") -> cart[t.removePrefix("add:")] = 1
            t.startsWith("plus:") -> cart.merge(t.removePrefix("plus:"), 1, Int::plus)
            t.startsWith("minus:") -> cart.computeIfPresent(t.removePrefix("minus:")) { _, v -> v - 1 }
            t == "view_cart" -> screen = if (loginAtCart) "LOGIN" else "CART"
            t == "addr" -> screen = "ADDRESS"
            t.startsWith("addr:") -> { address = t.removePrefix("addr:"); screen = "CART" }
            t == "pay" -> { screen = "PAYMENT"; log += "!!! PAYMENT TAPPED" }
            t == "pw" || t == "login" -> log += "!!! CREDENTIAL UI TOUCHED"
            else -> return null
        }
        return "ACTION_CLICK"
    }
    override fun setText(handle: Any, value: String): Boolean {
        if (handle != "field") { log += "!!! TYPED INTO $handle"; return false }
        search = value; log += "type \"$value\""; return true
    }
    override fun imeEnter(handle: Any): Boolean { log += "ime"; return true }
    override fun scrollForward(handle: Any): Boolean {
        if (screen != "MENU" || (scroll + 1) * 3 >= menu.size) return false
        scroll++; log += "scroll→$scroll"; return true
    }
    override fun scrollBackward(handle: Any): Boolean { if (scroll == 0) return false; scroll--; log += "scroll←$scroll"; return true }
}
