package com.teachflow.agent.safety

import com.teachflow.agent.accessibility.NodeSnapshot
import com.teachflow.agent.accessibility.ScreenSnapshot
import com.teachflow.agent.accessibility.SemanticRole

data class SafetyVerdict(val stop: Boolean, val kind: String = "", val reason: String = "") {
    companion object { val OK = SafetyVerdict(false) }
}

/**
 * Deterministic safety layer. No model is consulted: these rules alone decide whether automation
 * may continue. It runs before every step and before every tap.
 */
object SafetyGuardian {

    private val PAYMENT_PHRASES = listOf(
        "upi", "credit card", "debit card", "net banking", "netbanking", "wallets", "payment method", "payment methods",
        "payment options", "select payment", "choose payment", "pay using", "pay via", "cash on delivery", "card number",
        "cvv", "add new card", "saved cards", "enter upi id", "pay \u20B9", "pay rs",
    )

    private val OTP_PHRASES = listOf("otp", "one time password", "one-time password", "verification code", "enter the code", "enter code sent")
    private val BIOMETRIC_PHRASES = listOf("fingerprint", "biometric", "face unlock", "face id", "use your screen lock", "verify it's you", "verify its you")

    /** kind is PAYMENT, LOGIN or OTP. */
    fun checkScreen(s: ScreenSnapshot): SafetyVerdict {
        val visible = s.nodes.filter { it.visible }

        visible.firstOrNull { it.role.sensitive }?.let { n ->
            return when (n.role) {
                SemanticRole.OTP -> SafetyVerdict(true, "OTP", "A one-time password (OTP) is requested.")
                SemanticRole.PASSWORD -> SafetyVerdict(true, "LOGIN", "A password is requested.")
                SemanticRole.PIN -> SafetyVerdict(true, "LOGIN", "A PIN is requested.")
                else -> SafetyVerdict(true, "PAYMENT", "Payment details requested (${n.role.name.lowercase().replace('_', ' ')}).")
            }
        }

        val texts = visible.mapNotNull { n -> if (n.redacted) null else (n.text ?: n.contentDescription)?.lowercase() }
        val hits = PAYMENT_PHRASES.filter { p -> texts.any { it.contains(p) } }
        if (hits.size >= 2) return SafetyVerdict(true, "PAYMENT", "Payment screen detected (${hits.take(3).joinToString()}).")

        val anyEditable = visible.any { it.editable && it.role != SemanticRole.SEARCH_INPUT }
        if (anyEditable && OTP_PHRASES.any { p -> texts.any { t -> Regex("\\b" + Regex.escape(p) + "\\b").containsMatchIn(t) } }) {
            return SafetyVerdict(true, "OTP", "The app is asking for a verification code.")
        }
        if (BIOMETRIC_PHRASES.any { p -> texts.any { it.contains(p) } }) {
            return SafetyVerdict(true, "LOGIN", "Biometric authentication requested.")
        }

        val loginAction = visible.any { it.role == SemanticRole.LOGIN && it.clickable && it.roleScore >= 0.6f }
        if (loginAction && anyEditable) return SafetyVerdict(true, "LOGIN", "The app is asking you to sign in.")

        return SafetyVerdict.OK
    }

    fun checkAction(target: NodeSnapshot): SafetyVerdict = when {
        target.role.sensitive -> SafetyVerdict(true, if (target.role == SemanticRole.CVV || target.role == SemanticRole.CARD_NUMBER) "PAYMENT" else "LOGIN", "I never interact with ${target.role.name.lowercase().replace('_', ' ')} fields.")
        target.role == SemanticRole.PAYMENT -> SafetyVerdict(true, "PAYMENT", "The next tap (\"${target.displayLabel.take(40)}\") would start payment.")
        target.role == SemanticRole.LOGIN -> SafetyVerdict(true, "LOGIN", "The next tap (\"${target.displayLabel.take(40)}\") is a sign-in action.")
        else -> SafetyVerdict.OK
    }
}
