package com.teachflow.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.core.AgentRequest
import com.teachflow.agent.core.AgentState
import com.teachflow.agent.core.Reply
import com.teachflow.agent.core.Question
import com.teachflow.agent.core.TfLog
import com.teachflow.agent.execution.ActionExecutor
import com.teachflow.agent.execution.CapturedScreen
import com.teachflow.agent.execution.RunReportStore
import com.teachflow.agent.execution.RunStatus
import com.teachflow.agent.execution.TraceEvent
import com.teachflow.agent.execution.WorkflowExecutor
import com.teachflow.agent.learning.RelevanceFilter
import com.teachflow.agent.learning.TeachingSession
import com.teachflow.agent.learning.WorkflowSynthesizer
import com.teachflow.agent.nlu.AppResolver
import com.teachflow.agent.nlu.CommandParser
import com.teachflow.agent.safety.SafetyGuardian
import com.teachflow.agent.skills.SkillMatcher
import com.teachflow.agent.skills.SkillRepository
import com.teachflow.agent.ui.MainActivity
import com.teachflow.agent.ui.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The real agent. Observes the foreground app through the accessibility tree, records
 * demonstrations, and executes learned skills with AccessibilityNodeInfo actions.
 */
class TeachFlowAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val parser = AccessibilityTreeParser()
    private val observer = ActionObserver(parser)
    private var overlay: OverlayController? = null
    private lateinit var apps: AppResolver
    private lateinit var commandParser: CommandParser

    private var captureJob: Job? = null
    private var lastCaptureAt = 0L
    private var session: TeachingSession? = null
    private var runJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        apps = AppResolver(this)
        commandParser = CommandParser { apps.apps().map { it.label } }
        observer.listener = { action, isUpdate ->
            session?.let { s ->
                val before = AgentBus.snapshot.value?.takeIf { it.packageName == action.packageName }
                s.onAction(action, isUpdate, before)
                AgentBus.status.update { it.copy(observedCount = s.recorded.size) }
            }
        }
        AgentBus.serviceConnected.value = true
        overlay = OverlayController(this, scope).also { it.start() }
        scope.launch { AgentBus.requests.collect { handle(it) } }
        scheduleCapture(0)
        TfLog.i("SERVICE", "Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return // ignore TeachFlow's own UI and overlay
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleCapture(150)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleCapture(350)
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val dm = resources.displayMetrics
                val snap = AgentBus.snapshot.value
                observer.onEvent(e, dm.widthPixels, dm.heightPixels, snap?.fingerprint, snap)
                scheduleCapture(300)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        overlay?.stop()
        scope.cancel()
        AgentBus.serviceConnected.value = false
        super.onDestroy()
    }

    // ---- requests -----------------------------------------------------------------------------

    private fun handle(r: AgentRequest) {
        when (r) {
            is AgentRequest.StartTeaching -> {
                runJob?.cancel()
                val parsed = commandParser.parse(r.command)
                session = TeachingSession(r.command, parsed, r.targetPackage, r.targetApp)
                TfLog.i("TEACH", "Started for ${r.targetApp}; slots=${parsed.slots}")
                AgentBus.status.value = com.teachflow.agent.core.AgentStatus(
                    state = AgentState.LEARNING, headline = "Teaching · ${r.targetApp}",
                    detail = "Do the task once. Stop at checkout, or I'll stop automatically at payment or sign-in.",
                )
            }
            AgentRequest.StopTeaching -> finishTeaching("You stopped teaching.")
            is AgentRequest.Run -> runSkill(r)
            AgentRequest.Cancel -> {
                runJob?.cancel(); runJob = null
                session = null
                AgentBus.setStatus(AgentState.READY, "Ready")
            }
            AgentRequest.TakeControl -> AgentBus.status.update {
                it.copy(state = AgentState.WAITING_FOR_USER, headline = "Human control", detail = "TeachFlow waits. Tap Resume when you're ready.", humanInControl = true, question = null)
            }
            AgentRequest.Resume -> AgentBus.status.update {
                it.copy(state = AgentState.EXECUTING, humanInControl = false, headline = "Resuming", detail = "", boundary = null)
            }
        }
    }

    private fun finishTeaching(reason: String) {
        val s = session ?: return
        session = null
        if (s.stopReason == null) s.stopReason = reason
        val judged = RelevanceFilter(apps.launcherPackages()).judge(s)
        val result = WorkflowSynthesizer().synthesize(s, judged)
        AgentBus.pendingSynthesis.value = result
        TfLog.i("LEARN", "Observed ${result.observed}, relevant ${result.relevant}, ignored ${result.ignored}; steps ${result.skill.steps.size}")
        AgentBus.setStatus(AgentState.COMPLETED, "Workflow synthesized", "${result.relevant} relevant · ${result.ignored} ignored. Review it in TeachFlow.")
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            TfLog.w("TEACH", "Could not bring TeachFlow to the front", e)
        }
    }

    private fun runSkill(req: AgentRequest.Run) {
        val repo = SkillRepository.get(this)
        val taught = repo.get(req.skillId) ?: return
        val transfer = req.transferPackage != null && req.transferPackage != taught.targetPackage
        // EXPERIMENTAL: same semantic workflow, grounded against a different app. Not validated; never counted in skill stats.
        val skill = if (transfer) taught.copy(
            targetPackage = req.transferPackage!!, targetApp = req.transferApp ?: req.transferPackage,
            name = "${taught.name} [EXPERIMENTAL → ${req.transferApp ?: req.transferPackage}]",
        ) else taught
        val command = req.command
        runJob?.cancel()
        val exec = WorkflowExecutor(capture = { captureLive() }, actions = ActionExecutor(this), ask = ::askUser)
        runJob = scope.launch {
            val pre = ArrayList<TraceEvent>()
            fun t(phase: String, msg: String, result: String? = null, reason: String? = null) {
                pre += TraceEvent(System.currentTimeMillis(), phase, msg, result = result, reason = reason); TfLog.i(phase, msg)
            }
            t("VOICE RECEIVED", "\"$command\"")
            val parsed = commandParser.parse(command)
            val matcher = SkillMatcher()
            val match = matcher.score(parsed, skill)
            t("INTENT MATCHED", "${parsed.intentName}", result = "score %.2f".format(match.score), reason = match.reasons.joinToString("; "))
            val slots = LinkedHashMap(matcher.resolveSlots(skill, parsed))
            slots.putAll(req.overrides)
            val sources = slots.entries.joinToString { (k, v) ->
                "$k=$v" + when { k in req.overrides -> " (you clarified)"; k in parsed.slots -> ""; else -> " (taught default)" }
            }
            t("SLOTS EXTRACTED", sources)
            req.notes.forEach { (phase, msg) -> t(phase, msg) }
            t("SKILL SELECTED", "${skill.name} · ${skill.targetApp}")
            if (transfer) t("EXPERIMENTAL", "Cross-app transfer: skill taught in ${taught.targetApp}, grounded against ${skill.targetApp}. " +
                "Same semantic steps; no app-specific data. Not validated. Not counted in the skill's statistics.", result = "EXPERIMENTAL")

            AgentBus.setStatus(AgentState.MATCHING, "Opening ${skill.targetApp}", skill.name)
            val start = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - start < 8000) {
                if (captureLive()?.snapshot?.packageName == skill.targetPackage) break
                delay(400)
            }
            delay(800)

            val explicit = parsed.slots.keys + req.overrides.keys
            try {
                val report = exec.run(skill, command, slots, explicit, pre, req.interventions)
                store(report, skill.id, countInStats = !transfer)
            } catch (e: CancellationException) {
                store(exec.partialReport(), skill.id, countInStats = !transfer)
                AgentBus.setStatus(AgentState.READY, "Run stopped", "You stopped the run.")
                throw e
            }
        }
    }

    private fun store(report: com.teachflow.agent.execution.RunReport, skillId: String, countInStats: Boolean = true) {
        RunReportStore.get(this).add(report)
        if (countInStats) SkillRepository.get(this).recordOutcome(
            skillId,
            success = report.status == RunStatus.SUCCESS,
            handoff = report.status == RunStatus.HUMAN_HANDOFF || report.status == RunStatus.CANCELLED,
        )
    }

    /** Shows a question in the overlay and suspends until the user answers. */
    private suspend fun askUser(headline: String, text: String, options: List<String>, allowText: Boolean): Reply {
        val id = System.nanoTime()
        AgentBus.status.update {
            it.copy(state = AgentState.WAITING_FOR_USER, headline = headline, detail = text,
                question = Question(id, text, options, allowText, headline), boundary = null)
        }
        val ans = AgentBus.answers.first { it.first == id }.second
        if (ans == Reply.TakeControl) {
            AgentBus.status.update { it.copy(question = null, humanInControl = true, headline = "Human control", detail = "TeachFlow waits. Tap Resume when you're ready.") }
            AgentBus.status.first { !it.humanInControl }
            return Reply.TakeControl
        }
        AgentBus.status.update { it.copy(question = null) }
        return ans
    }

    // ---- screen capture -----------------------------------------------------------------------

    private fun appRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow
        if (r != null && r.packageName?.toString() != packageName) return r
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root ?: continue
            if (root.packageName?.toString() != packageName) return root
        }
        return null
    }

    /** Fresh capture with live node handles, for execution. */
    private suspend fun captureLive(): CapturedScreen? {
        val root = appRoot() ?: return null
        val dm = resources.displayMetrics
        val live = ArrayList<AccessibilityNodeInfo>()
        val snap = withContext(Dispatchers.Default) { parser.parse(root, dm.widthPixels, dm.heightPixels, live) }
        AgentBus.publishSnapshot(snap)
        return CapturedScreen(snap, live)
    }

    private fun scheduleCapture(delayMs: Long) {
        val overdue = SystemClock.uptimeMillis() - lastCaptureAt > 1200
        if (captureJob?.isActive == true && overdue) return
        captureJob?.cancel()
        captureJob = scope.launch {
            delay(if (overdue) 0 else delayMs)
            captureForObservation()
        }
    }

    private suspend fun captureForObservation() {
        if (runJob?.isActive == true) return // the executor captures on its own schedule
        val root = appRoot() ?: return
        val dm = resources.displayMetrics
        val snap = withContext(Dispatchers.Default) { parser.parse(root, dm.widthPixels, dm.heightPixels) }
        AccessibilityTreeParser.recycleCompat(root)
        lastCaptureAt = SystemClock.uptimeMillis()
        AgentBus.publishSnapshot(snap)

        val s = session
        s?.attachAfter(snap.fingerprint, snap.capturedAt)
        if (s != null && snap.packageName == s.targetPackage) {
            val v = SafetyGuardian.checkScreen(snap)
            if (v.stop) {
                s.stopReason = "Safe stop point reached: ${v.reason}"
                finishTeaching(s.stopReason!!)
            }
        }
    }
}
