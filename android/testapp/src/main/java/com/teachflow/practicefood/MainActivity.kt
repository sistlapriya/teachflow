package com.teachflow.practicefood

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * PracticeFood: a deliberately ordinary food-ordering app for TeachFlow's emulator tests.
 * Plain Android views, like many real apps. TeachFlow knows nothing about it: every workflow is
 * learned from a demonstration. Scenario switches come from launch extras (popup, login, empty, precart).
 * It logs "PF_PROCEED_TO_PAY" / "PF_PAY" if those buttons are ever tapped, so tests can prove
 * TeachFlow never pressed them.
 */
class MainActivity : Activity() {

    private val menu = listOf("Margherita Pizza", "Farmhouse Pizza", "Peppy Paneer Pizza", "Garlic Breadsticks", "Choco Lava Cake", "Pepsi 500 ml")
    private val prices = mapOf("Margherita Pizza" to 239, "Farmhouse Pizza" to 349, "Peppy Paneer Pizza" to 329, "Garlic Breadsticks" to 129, "Choco Lava Cake" to 109, "Pepsi 500 ml" to 60)
    private val cart = LinkedHashMap<String, Int>()
    private var address = "Home"
    private var popup = false
    private var popupShown = false
    private var login = false
    private var loggedIn = false
    private var empty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        popup = intent.getBooleanExtra("popup", false)
        login = intent.getBooleanExtra("login", false)
        empty = intent.getBooleanExtra("empty", false)
        intent.getStringExtra("precart")?.let { cart[it] = 1 }
        Log.i(TAG, "PF_START popup=$popup login=$login empty=$empty precart=${cart.keys}")
        showHome()
    }

    @Deprecated("simple navigation")
    override fun onBackPressed() = showHome()

    // ---- screens -------------------------------------------------------------------------

    private fun showHome(): Unit = page {
        addView(searchBar())
        addView(label("Recommended for you", 18f, bold = true))
        addView(label("Offers near you · Free delivery on orders above ₹199", 14f))
        addView(label("Top brands", 18f, bold = true))
    }

    private fun showSearch(): Unit = page {
        val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val field = EditText(this@MainActivity).apply {
            hint = "Search for restaurant, item or more"
            isSingleLine = true
            textSize = 17f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) { fillResults(results, s?.toString().orEmpty()) }
            })
        }
        addView(field)
        addView(results)
        field.requestFocus()
    }

    private fun fillResults(box: LinearLayout, q: String) {
        box.removeAllViews()
        if (q.isBlank()) { box.addView(label("Recent searches", 14f)); return }
        if (empty) { box.addView(label("No results found for \"$q\"", 16f)); return }
        box.addView(label("Restaurants", 16f, bold = true))
        box.addView(row("Domino's Pizza", "Pizza, Fast Food · 30 min") { showMenu() })
        box.addView(row("La Pino'z Pizza", "Pizza · 25 min") { box.addView(label("La Pino'z is closed right now", 14f)) })
    }

    private fun showMenu(keepScroll: Int = 0) {
        page(scrollTo = keepScroll) {
            addView(label("Domino's Pizza", 22f, bold = true))
            addView(label("Pizza, Fast Food · 4.2 ★", 14f))
            menu.forEach { item -> addView(menuRow(item)) }
        }
        // Sticky bottom bar, like real food apps.
        if (cart.isNotEmpty()) bottomBar?.addView(button("View Cart · ${cart.values.sum()} item${if (cart.values.sum() == 1) "" else "s"}") {
            if (login && !loggedIn) showLogin() else showCart()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
        if (popup && !popupShown) {
            popupShown = true
            AlertDialog.Builder(this)
                .setTitle("Get 50% off your first order!")
                .setMessage("Use code WELCOME50 at checkout.")
                .setNegativeButton("Not now") { d, _ -> Log.i(TAG, "PF_POPUP_DISMISSED"); d.dismiss() }
                .show()
        }
    }

    private fun menuRow(item: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(120)
        setPadding(0, dp(8), 0, dp(8))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(item, 17f, bold = true))
            addView(label("₹${prices[item]}", 14f))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val q = cart[item]
        if (q == null) {
            addView(button("ADD") { cart[item] = 1; Log.i(TAG, "PF_ADD $item"); refreshMenu() })
        } else {
            addView(button("−", desc = "Decrease quantity") { if (q <= 1) cart.remove(item) else cart[item] = q - 1; refreshMenu() })
            addView(label("$q", 18f, bold = true).apply { setPadding(dp(14), 0, dp(14), 0) })
            addView(button("+", desc = "Increase quantity") { cart[item] = q + 1; Log.i(TAG, "PF_QTY $item ${q + 1}"); refreshMenu() })
        }
    }

    private fun refreshMenu(): Unit = showMenu(keepScroll = currentScroll)

    private fun showLogin(): Unit = page {
        addView(label("Log in to continue", 20f, bold = true))
        addView(EditText(this@MainActivity).apply { hint = "Phone number"; inputType = InputType.TYPE_CLASS_PHONE })
        val pw = EditText(this@MainActivity).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        addView(pw)
        addView(button("Log in") {
            // The person signs in. The test checks the password field was never filled by TeachFlow.
            Log.i(TAG, "PF_LOGIN password_length=${pw.text.length}")
            loggedIn = true
            showCart()
        })
    }

    private fun showCart(): Unit = page {
        addView(label("Your cart", 22f, bold = true))
        cart.forEach { (k, v) -> addView(label("$k × $v", 17f)) }
        addView(label("Bill total ₹${cart.entries.sumOf { (k, v) -> (prices[k] ?: 0) * v } + 40}", 16f, bold = true))
        addView(row("Delivering to $address", "Tap to change address") { showAddress() })
        addView(button("Proceed to Pay") { Log.i(TAG, "PF_PROCEED_TO_PAY"); showPayment() })
    }

    private fun showAddress(): Unit = page {
        addView(label("Select an address", 20f, bold = true))
        listOf("Home" to "12 Lake View Road", "Work" to "Tech Park, Tower B").forEach { (name, line) ->
            addView(row(name, line) { address = name; Log.i(TAG, "PF_ADDRESS $name"); showCart() })
        }
    }

    private fun showPayment(): Unit = page {
        addView(label("Choose payment method", 20f, bold = true))
        listOf("UPI", "Credit card", "Net banking").forEach { addView(label(it, 17f)) }
        addView(button("Pay ₹${cart.entries.sumOf { (k, v) -> (prices[k] ?: 0) * v } + 40}") { Log.i(TAG, "PF_PAY") })
    }

    // ---- building blocks ----------------------------------------------------------------

    private var scroll: ScrollView? = null
    private var bottomBar: LinearLayout? = null
    private val currentScroll get() = scroll?.scrollY ?: 0

    /** A header area (room for TeachFlow's floating panel) above a scrollable content column. */
    private fun page(scrollTo: Int = 0, content: LinearLayout.() -> Unit) {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        outer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.rgb(203, 32, 45))
            setPadding(dp(20), dp(20), dp(20), dp(16))
            addView(label("PracticeFood", 26f, bold = true, color = Color.WHITE))
            addView(label("Test app for TeachFlow's emulator tests · not a real store", 12f, color = Color.WHITE))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(270)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(24)); content() }
        val sv = ScrollView(this).apply { addView(col) }
        scroll = sv
        outer.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        bottomBar = bar
        outer.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(outer)
        if (scrollTo > 0) sv.post { sv.scrollTo(0, scrollTo) }
    }

    private fun searchBar() = label("Search for restaurant, item or more", 16f, color = Color.DKGRAY).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(240, 240, 240)) }
        isClickable = true
        setOnClickListener { showSearch() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
    }

    private fun row(title: String, sub: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(14), dp(12), dp(14))
        isClickable = true
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(248, 248, 248)) }
        addView(label(title, 17f, bold = true))
        addView(label(sub, 13f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun button(text: String, desc: String? = null, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        desc?.let { contentDescription = it }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, sp: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { const val TAG = "PracticeFood" }
}
