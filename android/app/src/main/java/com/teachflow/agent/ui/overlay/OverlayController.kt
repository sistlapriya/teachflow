package com.teachflow.agent.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.core.AgentRequest
import com.teachflow.agent.core.AgentState
import com.teachflow.agent.core.AgentStatus
import com.teachflow.agent.core.Question
import com.teachflow.agent.core.Reply
import com.teachflow.agent.ui.AnswerActivity
import com.teachflow.agent.core.TfLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Compact floating panel drawn by the accessibility service above other apps
 * (TYPE_ACCESSIBILITY_OVERLAY, so no extra permission). Drag to move; tap the header to collapse.
 */
class OverlayController(private val service: AccessibilityService, private val scope: CoroutineScope) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private lateinit var stateView: TextView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var body: LinearLayout
    private lateinit var buttons: LinearLayout
    private lateinit var summary: TextView
    private lateinit var panelBg: GradientDrawable
    private val jobs = ArrayList<Job>()

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(140) }

    fun start() {
        jobs += scope.launch { AgentBus.overlayEnabled.collect { if (it) show() else hide() } }
        jobs += scope.launch { AgentBus.status.collect { render(it) } }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        hide()
    }

    private fun show() {
        if (root != null) return
        val v = build()
        try {
            wm.addView(v, params)
            root = v
            render(AgentBus.status.value)
        } catch (e: Exception) {
            TfLog.w("OVERLAY", "Could not add overlay", e)
        }
    }

    private fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun render(s: AgentStatus) {
        if (root == null) return
        val (dot, color) = when {
            s.boundary != null -> "\uD83D\uDEE1" to 0xFFF59E0B.toInt()
            s.state == AgentState.WAITING_FOR_USER -> "\u25CF" to 0xFFF59E0B.toInt()
            s.state == AgentState.BLOCKED -> "\u25CF" to 0xFFF43F5E.toInt()
            s.state == AgentState.COMPLETED -> "\u2713" to 0xFF34D399.toInt()
            s.state == AgentState.RECOVERING -> "\u25CF" to 0xFFA78BFA.toInt()
            s.state == AgentState.READY -> "\u25CF" to 0xFF34D399.toInt()
            else -> "\u25CF" to 0xFF22D3EE.toInt()
        }
        stateView.text = if (s.boundary != null) "$dot SAFETY BOUNDARY" else "$dot ${s.state.label}"
        stateView.setTextColor(color)
        panelBg.setStroke(dp(if (s.boundary != null) 2 else 1), if (s.boundary != null) 0xFFF59E0B.toInt() else 0x663B82F6)
        headline.text = s.headline
        headline.setTextColor(if (s.boundary != null || s.headline.startsWith("RECOVERY EXHAUSTED")) 0xFFF59E0B.toInt() else 0xFFF4F7FB.toInt())
        val extra = if (s.state == AgentState.LEARNING) "\nObserved: ${s.observedCount} actions" else ""
        detail.text = s.detail + extra
        detail.visibility = if (detail.text.isNullOrBlank()) View.GONE else View.VISIBLE
        summary.text = s.summary ?: ""
        summary.visibility = if (s.summary.isNullOrBlank()) View.GONE else View.VISIBLE

        buttons.removeAllViews()
        val q = s.question
        when {
            q != null -> {
                q.options.forEachIndexed { i, o -> addButton(o, primary = i == 0) { AgentBus.answer(q.id, Reply.Option(i)) } }
                if (q.allowText) addButton("\u270E Type or say it", primary = q.options.isEmpty()) { openAnswerScreen(q) }
                addButton("Take control") { AgentBus.answer(q.id, Reply.TakeControl) }
                addButton("Stop") { AgentBus.answer(q.id, Reply.Stop) }
            }
            s.humanInControl -> {
                // Resume only on this explicit tap; never automatically.
                addButton(if (s.boundary != null) "Resume" else "Resume TeachFlow", primary = true) { AgentBus.request(AgentRequest.Resume) }
                addButton("Stop") { AgentBus.request(AgentRequest.Cancel) }
            }
            s.state == AgentState.LEARNING -> {
                addButton("Stop teaching", primary = true) { AgentBus.request(AgentRequest.StopTeaching) }
                addButton("Cancel") { AgentBus.request(AgentRequest.Cancel) }
            }
            s.state in setOf(AgentState.MATCHING, AgentState.EXECUTING, AgentState.VERIFYING, AgentState.RECOVERING) -> {
                addButton("Take control") { AgentBus.request(AgentRequest.TakeControl) }
                addButton("Stop") { AgentBus.request(AgentRequest.Cancel) }
            }
            s.state in setOf(AgentState.WAITING_FOR_USER, AgentState.COMPLETED, AgentState.BLOCKED) -> {
                addButton("Done", primary = true) { AgentBus.setStatus(AgentState.READY, "Ready") }
            }
        }
        buttons.visibility = if (buttons.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun openAnswerScreen(q: Question) {
        try {
            service.startActivity(AnswerActivity.intent(service, q))
        } catch (e: Exception) {
            TfLog.w("OVERLAY", "Could not open the answer screen", e)
        }
    }

    private fun addButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
        val b = TextView(service).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (primary) 0xFF2563EB.toInt() else 0x33FFFFFF)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        buttons.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); marginEnd = dp(6)
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): LinearLayout {
        val ctx = service
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            panelBg = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF00B1018.toInt())
                setStroke(dp(1), 0x663B82F6)
            }
            background = panelBg
            minimumWidth = dp(220)
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("TEACHFLOW", 11f, 0xFF8A98B0.toInt(), mono = true))
        stateView = text("\u25CF READY", 11f, 0xFF34D399.toInt(), mono = true)
        header.addView(stateView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        headline = text("Ready", 14f, 0xFFF4F7FB.toInt()).apply { typeface = Typeface.DEFAULT_BOLD }
        detail = text("", 12f, 0xFFB4C0D4.toInt()).apply { maxWidth = dp(260) }
        summary = text("", 11f, 0xFF8A98B0.toInt(), mono = true).apply { maxWidth = dp(260) }
        buttons = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(headline); body.addView(detail); body.addView(summary); body.addView(buttons)
        panel.addView(header); panel.addView(body)
        header.setOnTouchListener(DragOrTap())
        return panel
    }

    private fun text(s: String, sp: Float, color: Int, mono: Boolean = false) = TextView(service).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (mono) typeface = Typeface.MONOSPACE
    }

    private inner class DragOrTap : View.OnTouchListener {
        private var sx = 0f; private var sy = 0f; private var px = 0; private var py = 0; private var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = params.x; py = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { params.x = px + dx; params.y = py + dy; root?.let { wm.updateViewLayout(it, params) } }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    v.performClick()
                }
            }
            return true
        }
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()
}
