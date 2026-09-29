package com.teachflow.agent.execution

import com.teachflow.agent.accessibility.NodeSnapshot
import com.teachflow.agent.accessibility.ScreenSnapshot
import com.teachflow.agent.accessibility.SemanticRole
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.core.AgentState
import com.teachflow.agent.core.AgentStatus
import com.teachflow.agent.core.Reply
import com.teachflow.agent.core.TfLog
import com.teachflow.agent.nlu.TextMatch
import com.teachflow.agent.recovery.RecoveryEngine
import com.teachflow.agent.recovery.StateClassifier
import com.teachflow.agent.recovery.UiState
import com.teachflow.agent.safety.SafetyGuardian
import com.teachflow.agent.safety.SafetyVerdict
import com.teachflow.agent.skills.Skill
import com.teachflow.agent.skills.SkillStep
import com.teachflow.agent.skills.StepKind
import com.teachflow.agent.skills.TargetSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** A screen capture plus live node handles aligned by nodeId, valid until the next capture. */
class CapturedScreen(val snapshot: ScreenSnapshot, val live: List<Any>)

private sealed interface StepOutcome {
    /** Action performed and the expected state observed. */
    data class Passed(val expected: String, val observed: String) : StepOutcome
    /** Target not found, or the action didn't produce the expected state. [question] is what to ask if recovery fails. */
    data class Failed(val reason: String, val question: String) : StepOutcome
    /** [afterAction]: the boundary appeared as the result of this step's action, so after Resume we re-verify instead of repeating it. */
    data class Boundary(val verdict: SafetyVerdict, val afterAction: Boolean = false) : StepOutcome
    data class Terminal(val reason: TerminalReason, val message: String) : StepOutcome
    /** Re-run the step without counting a recovery attempt (e.g. after the user took control). */
    data object Retry : StepOutcome
}

private sealed interface RecoveryResult {
    data object Retry : RecoveryResult
    data object AlreadyDone : RecoveryResult
    data class Boundary(val verdict: SafetyVerdict) : RecoveryResult
    data class Ask(val headline: String, val question: String) : RecoveryResult
}

/**
 * Runs a learned skill. For every step:
 * capture → safety → ground → act → observe → verify → PASS, or classify the state and recover
 * (at most [RecoveryEngine.MAX_ATTEMPTS] times) → then stop safely and ask a specific question.
 *
 * When it knows, it acts. When it isn't sure, it asks. When it is unsafe, it stops.
 */
class WorkflowExecutor(
    private val capture: suspend () -> CapturedScreen?,
    private val actions: UiActions,
    /** Shows a question and suspends until the user answers (TakeControl returns after they tap Resume). */
    private val ask: suspend (headline: String, question: String, options: List<String>, allowText: Boolean) -> Reply,
) {
    private val grounder = SemanticGrounder()
    /** Used from recovery attempt 2: an alternate, looser semantic match. */
    private val relaxedGrounder = SemanticGrounder(minScore = 0.35, labelThreshold = 0.35)

    private val trace = ArrayList<TraceEvent>()
    private val slots = LinkedHashMap<String, String>()
    private var skill: Skill? = null
    private var command = ""
    private var startedAt = 0L
    private var currentStep: Int? = null
    /** What the run ends as if the user stops while we wait for them. */
    private var waitingReason: TerminalReason? = null
    private var lastInputField: Pair<String?, String?>? = null

    private var stepsTotal = 0
    private var stepsSucceeded = 0
    private var stepsRecovered = 0
    private var recoveryAttempts = 0
    private var interventions = 0
    private var attempted = 0
    private var succeeded = 0
    private var boundaryKind: String? = null

    private fun log(
        phase: String, message: String, action: String? = null, target: String? = null, state: String? = null,
        result: String? = null, recovery: Int? = null, reason: String? = null,
    ) {
        trace += TraceEvent(System.currentTimeMillis(), phase, message, currentStep, action, target, state, result, recovery, reason)
        TfLog.i(phase, message)
    }

    // =============================================================================================

    suspend fun run(
        skill: Skill, command: String, initialSlots: Map<String, String>, explicit: Set<String>,
        preTrace: List<TraceEvent>, preInterventions: Int,
    ): RunReport {
        this.skill = skill
        this.command = command
        trace.clear(); trace.addAll(preTrace)
        startedAt = preTrace.firstOrNull()?.t ?: System.currentTimeMillis()
        slots.clear(); slots.putAll(initialSlots)
        interventions = preInterventions

        val n = skill.steps.size
        for ((i, step) in skill.steps.withIndex()) {
            currentStep = i + 1

            if (step.kind == StepKind.STOP_AT_BOUNDARY) {
                val cap = capture()
                val v = cap?.let { SafetyGuardian.checkScreen(it.snapshot) }
                if (v != null && v.stop && v.kind != "PAYMENT") {
                    onBoundary(v, atEnd = true)?.let { return it }
                }
                return onBoundary(
                    if (v?.stop == true && v.kind == "PAYMENT") v
                    else SafetyVerdict(true, "PAYMENT", "Reached the learned stop point: the next step is payment."),
                    atEnd = true,
                )!!
            }
            if (step.optional && !shouldRun(step, skill, explicit)) {
                log("NEXT ACTION", "Skipped \"${step.title}\" (not requested this time)", action = step.kind.name, result = "SKIPPED")
                continue
            }

            stepsTotal++
            // Part J: a slot this step needs but the command didn't give is asked for now, mid-run.
            for (name in slotsUsedBy(step)) {
                if (!slots[name].isNullOrBlank()) continue
                if (!resolveMissingSlot(name, step)) return finish(RunStatus.BLOCKED, TerminalReason.USER_CLARIFICATION, "$name wasn't provided.")
            }
            log("NEXT ACTION", step.title, action = step.kind.name, target = step.target?.let { grounder.describe(it, slots) })
            AgentBus.setStatus(AgentState.EXECUTING, "Step ${i + 1}/$n", step.title)
            var attempt = 0

            stepLoop@ while (true) {
                pauseIfHuman()
                val cap = capture() ?: return finish(RunStatus.FAILED, TerminalReason.ERROR, "I couldn't read the screen (accessibility tree unavailable).")
                log("UI TREE CAPTURED", "${cap.snapshot.nodes.size} nodes · ${cap.snapshot.packageName}", state = cap.snapshot.fingerprint.take(12))

                val screenVerdict = SafetyGuardian.checkScreen(cap.snapshot)
                if (screenVerdict.stop) {
                    onBoundary(screenVerdict, atEnd = noRequiredStepsFrom(i))?.let { return it }
                    continue@stepLoop
                }

                val outcome = try {
                    execute(step, cap, attempt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TfLog.w("EXECUTION", "Step error", e)
                    StepOutcome.Failed("Unexpected error: ${e.message}", specificQuestion(step, exhausted = false))
                }

                when (outcome) {
                    is StepOutcome.Passed -> {
                        log("STATE VERIFIED", "Expected: ${outcome.expected} · Observed: ${outcome.observed}",
                            action = step.kind.name, result = "PASSED ✓", recovery = attempt)
                        stepsSucceeded++
                        if (attempt > 0) stepsRecovered++
                        break@stepLoop
                    }
                    is StepOutcome.Boundary -> {
                        onBoundary(outcome.verdict, atEnd = noRequiredStepsFrom(i + if (outcome.afterAction) 1 else 0))?.let { return it }
                        if (outcome.afterAction) {
                            // The action already happened; check whether its expected state holds now.
                            val now = capture()
                            val observed = now?.let { checkExpected(step, cap.snapshot, it.snapshot) }
                            if (observed != null) {
                                log("STATE VERIFIED", "Expected: ${expectationFor(step)} · Observed: $observed", action = step.kind.name, result = "PASSED ✓", recovery = attempt)
                                stepsSucceeded++
                                if (attempt > 0) stepsRecovered++
                                break@stepLoop
                            }
                        }
                        continue@stepLoop
                    }
                    is StepOutcome.Terminal -> return finish(statusFor(outcome.reason), outcome.reason, outcome.message)
                    StepOutcome.Retry -> continue@stepLoop
                    is StepOutcome.Failed -> {
                        attempt++
                        if (attempt > RecoveryEngine.MAX_ATTEMPTS) {
                            // ---- Section 15: recovery exhausted → safe stop → specific question ----
                            log("RECOVERY EXHAUSTED", "${RecoveryEngine.MAX_ATTEMPTS} / ${RecoveryEngine.MAX_ATTEMPTS} attempts · SAFE STOP · USER INPUT REQUIRED",
                                action = step.kind.name, result = "SAFE STOP", recovery = RecoveryEngine.MAX_ATTEMPTS, reason = outcome.reason)
                            val q = specificQuestion(step, exhausted = true)
                            when (askUser("RECOVERY EXHAUSTED · 3 / 3 ATTEMPTS", "SAFE STOP · USER INPUT REQUIRED\n\n$q",
                                listOf("I've done it, continue"), TerminalReason.RECOVERY_EXHAUSTED)) {
                                Reply.Option(0) -> { userCompletedStep(step); break@stepLoop }
                                Reply.TakeControl -> { attempt = 0; continue@stepLoop }
                                else -> return finish(RunStatus.BLOCKED, TerminalReason.RECOVERY_EXHAUSTED, "No safe match after 3 recovery attempts: ${outcome.reason}")
                            }
                        }
                        recoveryAttempts++
                        log("RECOVERY REQUIRED", outcome.reason, action = step.kind.name, result = "FAILED ✗",
                            recovery = attempt, reason = outcome.reason)
                        AgentBus.setStatus(AgentState.RECOVERING, "Recovery $attempt / ${RecoveryEngine.MAX_ATTEMPTS}", outcome.reason)
                        when (val r = recover(step, attempt)) {
                            RecoveryResult.Retry -> continue@stepLoop
                            RecoveryResult.AlreadyDone -> {
                                log("STATE VERIFIED", "Expected: ${expectationFor(step)} · Observed: already in that state",
                                    action = step.kind.name, result = "PASSED ✓ (redundant action skipped)", recovery = attempt)
                                stepsSucceeded++; stepsRecovered++
                                break@stepLoop
                            }
                            is RecoveryResult.Boundary -> {
                                onBoundary(r.verdict, atEnd = noRequiredStepsFrom(i))?.let { return it }
                                continue@stepLoop
                            }
                            is RecoveryResult.Ask -> when (askUser(r.headline, r.question, listOf("I've done it, continue"), TerminalReason.USER_CLARIFICATION)) {
                                Reply.Option(0) -> { userCompletedStep(step); break@stepLoop }
                                Reply.TakeControl -> { attempt = 0; continue@stepLoop }
                                else -> return finish(RunStatus.BLOCKED, TerminalReason.USER_CLARIFICATION, r.question)
                            }
                        }
                    }
                }
            }
        }
        return finish(RunStatus.SUCCESS, TerminalReason.SUCCESS, "Learned workflow finished.")
    }

    /** Report for a run stopped from outside (overlay Stop / Cancel). */
    fun partialReport(): RunReport {
        val reason = waitingReason ?: TerminalReason.USER_HANDOFF
        val msg = when (reason) {
            TerminalReason.AUTHENTICATION_BOUNDARY -> "Stopped by you at the authentication boundary."
            TerminalReason.RECOVERY_EXHAUSTED -> "Stopped by you after 3 recovery attempts."
            TerminalReason.USER_CLARIFICATION -> "Stopped by you while TeachFlow was waiting for an answer."
            else -> "You stopped the run."
        }
        log("RUN STOPPED", "Reason: ${reason.display} · $msg", result = RunStatus.CANCELLED.name, reason = msg)
        return build(RunStatus.CANCELLED, reason, msg)
    }

    // ---- boundaries, questions, terminal ------------------------------------------------------

    /**
     * PAYMENT ends the run (never resumed past). LOGIN / OTP pause until the user explicitly taps
     * Resume, then return null so the step is re-inspected.
     */
    private suspend fun onBoundary(v: SafetyVerdict, atEnd: Boolean): RunReport? {
        if (v.kind == "PAYMENT") {
            boundaryKind = "PAYMENT"
            log("PAYMENT DETECTED", v.reason, state = "PAYMENT")
            log("SAFETY STOP", "Payment boundary reached. Automation stopped. No payment was made by TeachFlow.", result = "STOPPED")
            log("YOUR TURN", "Complete this step yourself.")
            val r = finish(if (atEnd) RunStatus.SUCCESS else RunStatus.HUMAN_HANDOFF, TerminalReason.PAYMENT_BOUNDARY, v.reason, show = false)
            AgentBus.status.value = AgentStatus(
                state = AgentState.WAITING_FOR_USER, headline = "PAYMENT SCREEN DETECTED",
                detail = "Automation has stopped.\n\nYOUR TURN\nComplete this step yourself.",
                boundary = "PAYMENT", summary = summaryLine(r),
            )
            return r
        }
        boundaryKind = v.kind
        val what = if (v.kind == "OTP") "OTP REQUIRED" else "AUTHENTICATION REQUIRED"
        log("AUTHENTICATION DETECTED", v.reason, state = v.kind)
        log("SAFETY STOP", "Authentication boundary. TeachFlow never enters or submits credentials.", result = "PAUSED")
        log("YOUR TURN", "Complete it yourself, then tap Resume.")
        waitingReason = TerminalReason.AUTHENTICATION_BOUNDARY
        AgentBus.status.value = AgentStatus(
            state = AgentState.WAITING_FOR_USER, headline = what,
            detail = "Automation has paused.\n\nYOUR TURN\nComplete this step yourself, then tap Resume.",
            boundary = "AUTH", humanInControl = true,
        )
        AgentBus.status.first { !it.humanInControl }
        waitingReason = null
        interventions++
        log("USER RESUMED", "You tapped Resume. Re-inspecting the screen.", result = "RESUMED")
        return null
    }

    private suspend fun askUser(headline: String, question: String, options: List<String>, ifStopped: TerminalReason, allowText: Boolean = false): Reply {
        log("USER INPUT REQUIRED", question.lines().last { it.isNotBlank() }, result = "WAITING")
        waitingReason = ifStopped
        val a = ask(headline, question, options, allowText)
        waitingReason = null
        if (a !is Reply.Stop) interventions++
        log("USER ANSWERED", when (a) {
            is Reply.Option -> options.getOrElse(a.index) { "option ${a.index}" }
            is Reply.Text -> "\"${a.text}\""
            Reply.TakeControl -> "You took control, then resumed"
            Reply.Stop -> "Stop"
        })
        return a
    }

    private fun slotsUsedBy(step: SkillStep): List<String> {
        val out = LinkedHashSet<String>()
        step.valueTemplate?.let { t -> Regex("\\{([A-Z_]+)\\}").findAll(t).forEach { out += it.groupValues[1] } }
        step.target?.slotRef?.let { if (step.kind == StepKind.CLICK) out += it }
        step.target?.nearSlot?.let { if (step.kind == StepKind.CLICK) out += it }
        return out.toList()
    }

    /** Asks for a missing slot value in the overlay (options or a typed / spoken answer). */
    private suspend fun resolveMissingSlot(name: String, step: SkillStep): Boolean {
        val taught = skill?.slot(name)?.defaultValue
        val head = taught?.let { TextMatch.normalize(it).split(' ') }?.takeIf { it.size >= 2 }?.last()
        val q = when (name) {
            "ITEM" -> "I can do that. " + (head?.let { "Which $it would you like?" } ?: "Which item would you like?")
            "RESTAURANT" -> "Which restaurant should I use?"
            "QUANTITY" -> "How many?"
            "ADDRESS" -> "Which address should I deliver to?"
            else -> "What should I use for $name?"
        }
        log("STATE CLASSIFIED", "AMBIGUOUS_STATE · $name is needed for \"${step.title}\" but wasn't in the command",
            state = UiState.AMBIGUOUS_STATE.name, reason = "slot missing")
        while (true) {
            val options = listOfNotNull(taught?.let { "$it (taught)" })
            val value = when (val a = askUser("ONE MORE DETAIL", q, options, TerminalReason.USER_CLARIFICATION, allowText = true)) {
                is Reply.Option -> taught
                is Reply.Text -> a.text.trim().ifBlank { null }
                Reply.TakeControl -> null
                Reply.Stop -> return false
            } ?: continue
            slots[name] = value
            log("PARAMETER RESOLVED", "$name = $value ✓", target = value, result = "RESOLVED ✓")
            return true
        }
    }

    private fun userCompletedStep(step: SkillStep) {
        log("STATE VERIFIED", "Completed by you: ${step.title}", action = step.kind.name, result = "DONE BY USER")
        stepsSucceeded++
    }

    private suspend fun pauseIfHuman() {
        if (!AgentBus.status.value.humanInControl) return
        log("USER CONTROL", "Paused: you have control")
        waitingReason = TerminalReason.USER_HANDOFF
        AgentBus.status.first { !it.humanInControl }
        waitingReason = null
        interventions++
        log("USER RESUMED", "Resumed; re-inspecting the screen")
        AgentBus.setStatus(AgentState.EXECUTING, "Resuming", "")
    }

    private fun statusFor(r: TerminalReason) = when (r) {
        TerminalReason.SUCCESS -> RunStatus.SUCCESS
        TerminalReason.ERROR -> RunStatus.FAILED
        TerminalReason.USER_HANDOFF -> RunStatus.CANCELLED
        else -> RunStatus.BLOCKED
    }

    private fun finish(status: RunStatus, reason: TerminalReason, message: String, show: Boolean = true): RunReport {
        log(if (status == RunStatus.SUCCESS) "RUN COMPLETE" else "RUN STOPPED", "Reason: ${reason.display} · $message",
            result = status.name, reason = message)
        val r = build(status, reason, message)
        if (show) {
            AgentBus.status.value = AgentStatus(
                state = if (status == RunStatus.SUCCESS) AgentState.COMPLETED else AgentState.BLOCKED,
                headline = if (status == RunStatus.SUCCESS) "RUN COMPLETE" else "RUN STOPPED",
                detail = "Reason: ${reason.display}\n$message",
                summary = summaryLine(r),
            )
        }
        return r
    }

    private fun summaryLine(r: RunReport) =
        "Steps ${r.stepsSucceeded}/${r.stepsTotal} ✓ · recovered ${r.stepsRecovered} · your interventions ${r.userInterventions}"

    private fun build(status: RunStatus, reason: TerminalReason, message: String): RunReport {
        val s = skill
        val idx = currentStep
        return RunReport(
            id = "run_${System.currentTimeMillis()}", skillId = s?.id, skillName = s?.name, command = command,
            parameters = LinkedHashMap(slots), startedAt = startedAt, completedAt = System.currentTimeMillis(),
            status = status, terminalReason = reason, terminalMessage = message,
            stoppedAtStep = idx, stoppedAtStepTitle = idx?.let { s?.steps?.getOrNull(it - 1)?.title },
            stepsTotal = stepsTotal, stepsSucceeded = stepsSucceeded, stepsRecovered = stepsRecovered,
            recoveryAttempts = recoveryAttempts, userInterventions = interventions,
            actionsAttempted = attempted, actionsSucceeded = succeeded,
            safetyBoundaryTriggered = boundaryKind, trace = trace.toList(),
        )
    }

    private fun noRequiredStepsFrom(i: Int): Boolean =
        skill?.steps?.drop(i)?.none { it.kind != StepKind.STOP_AT_BOUNDARY && !it.optional } ?: false

    private fun shouldRun(step: SkillStep, skill: Skill, explicit: Set<String>): Boolean = when (step.kind) {
        StepKind.SET_QUANTITY -> (slots["QUANTITY"]?.toIntOrNull() ?: 1) != 1
        StepKind.SELECT_ADDRESS -> "ADDRESS" in explicit && !slots["ADDRESS"].equals(skill.slot("ADDRESS")?.defaultValue, ignoreCase = true)
        else -> true
    }

    // ---- step execution -----------------------------------------------------------------------

    private suspend fun execute(step: SkillStep, cap: CapturedScreen, attempt: Int): StepOutcome = when (step.kind) {
        StepKind.INPUT -> doInput(step, cap)
        StepKind.CLICK -> doClick(step, step.target!!, cap, attempt)
        StepKind.SET_QUANTITY -> doQuantity(step, cap)
        StepKind.SELECT_ADDRESS -> doAddress(step, cap)
        StepKind.STOP_AT_BOUNDARY -> StepOutcome.Passed("stop", "stop")
    }

    private suspend fun doClick(step: SkillStep, spec: TargetSpec, cap: CapturedScreen, attempt: Int): StepOutcome {
        val g = if (attempt >= 2) relaxedGrounder else grounder
        if (attempt >= 2) log("GROUNDING", "Trying an alternate semantic match (looser label threshold)", recovery = attempt)
        val chosen: Candidate = when (val res = g.ground(spec, slots, cap.snapshot, forInput = false)) {
            is Grounding.NotFound -> {
                log("TARGET GROUNDED", res.reason, target = grounder.describe(spec, slots), result = "NOT FOUND", reason = "DECISION: RECOVER")
                return StepOutcome.Failed(res.reason, specificQuestion(step, false))
            }
            is Grounding.Found -> {
                log("TARGET GROUNDED", "${grounder.describe(spec, slots)} → \"${res.best.label.take(50)}\"",
                    target = res.best.question, result = "score %.2f".format(res.best.score), reason = res.best.evidence("EXECUTE"))
                res.best
            }
            is Grounding.Ambiguous -> {
                log("STATE CLASSIFIED", "AMBIGUOUS_STATE · ${res.options.size} equally plausible candidates", state = UiState.AMBIGUOUS_STATE.name,
                    reason = res.options.joinToString { "\"${it.question}\" %.2f".format(it.score) } + " \u2192 DECISION: ASK")
                val slotName = spec.slotRef ?: spec.nearSlot
                val options = res.options.map { cleanLabel(if (spec.nearSlot != null) it.context ?: it.label else it.label) }
                val value = slotName?.let { slots[it] }
                val q = if (slotName == "ITEM" && value != null)
                    "I can continue, but I need one more detail.\nWhich ${TextMatch.normalize(value)} would you like?"
                else "${res.options.size} possible ${roleWord(spec.role)} match this action.\nWhich one should I use?"
                when (val a = askUser("WHICH ONE?", q, options, TerminalReason.USER_CLARIFICATION, allowText = slotName != null)) {
                    is Reply.Option -> if (a.index in options.indices) {
                        if (slotName != null) {
                            slots[slotName] = options[a.index]
                            log("PARAMETER RESOLVED", "$slotName = ${options[a.index]} ✓", target = options[a.index], result = "RESOLVED ✓")
                        }
                        res.options[a.index]
                    } else return StepOutcome.Terminal(TerminalReason.USER_CLARIFICATION, "No option chosen.")
                    is Reply.Text -> {
                        // A new value: store it and re-ground the step with it.
                        slots[slotName!!] = a.text
                        log("PARAMETER RESOLVED", "$slotName = ${a.text} ✓", target = a.text, result = "RESOLVED ✓")
                        return StepOutcome.Retry
                    }
                    Reply.TakeControl -> return StepOutcome.Retry
                    Reply.Stop -> return StepOutcome.Terminal(TerminalReason.USER_CLARIFICATION, "You stopped at a clarification question.")
                }
            }
        }
        val action = SafetyGuardian.checkAction(chosen.node)
        if (action.stop) return StepOutcome.Boundary(action)
        return tapAndVerify(chosen.node, cap, step, spec)
    }

    private suspend fun tapAndVerify(node: NodeSnapshot, cap: CapturedScreen, step: SkillStep, spec: TargetSpec?): StepOutcome {
        val q = specificQuestion(step, false)
        val live = cap.live.getOrNull(node.nodeId) ?: return StepOutcome.Failed("The element disappeared before I could tap it.", q)
        attempted++
        AgentBus.setStatus(AgentState.EXECUTING, "Tapping", node.displayLabel.take(60))
        val how = actions.click(live, node.bounds) ?: return StepOutcome.Failed("\"${node.displayLabel.take(40)}\" didn't accept a tap.", q)
        succeeded++
        log("ACTION EXECUTED", "CLICK \"${node.displayLabel.take(50)}\" via $how", action = "CLICK", target = node.displayLabel.take(60), result = "PERFORMED")

        AgentBus.setStatus(AgentState.VERIFYING, "Verifying", expectationFor(step))
        val expected = expectationFor(step)
        val after = awaitChange(cap.snapshot)
        if (spec?.role == SemanticRole.ADD_TO_CART) {
            val now = after ?: capture()
            if (now != null) {
                val item = slots["ITEM"]
                if (item != null && RecoveryEngine.alreadyDone(step, now.snapshot, slots) != null)
                    return StepOutcome.Passed(expected, "quantity control next to \"$item\"")
                if (now.snapshot.nodes.any { it.visible && it.role == SemanticRole.CART } && cap.snapshot.nodes.none { it.visible && it.role == SemanticRole.CART })
                    return StepOutcome.Passed(expected, "cart bar appeared")
                if (after != null) return StepOutcome.Passed(expected, "a new panel opened (${describeScreen(after.snapshot, cap.snapshot)})")
            }
            return StepOutcome.Failed("I tapped \"${node.displayLabel.take(30)}\" but nothing shows the item was added.", q)
        }
        if (after == null) return StepOutcome.Failed("I tapped \"${node.displayLabel.take(40)}\" but the screen didn't change.", q)
        SafetyGuardian.checkScreen(after.snapshot).let { v ->
            if (v.stop) {
                log("STATE VERIFIED", "Expected: $expected · Observed: ${v.kind} screen", action = "CLICK", result = "BOUNDARY")
                return StepOutcome.Boundary(v, afterAction = true)
            }
        }
        val observed = checkExpected(step, cap.snapshot, after.snapshot)
            ?: return StepOutcome.Failed("Verification failed. Expected: $expected · Observed: ${describeScreen(after.snapshot, cap.snapshot)}", q)
        return StepOutcome.Passed(expected, observed)
    }

    private val cartWords = listOf("cart", "checkout", "bill", "order summary", "to pay", "total", "proceed", "subtotal")

    /** Returns what was observed if the step's expected state holds on [after], otherwise null. */
    private fun checkExpected(step: SkillStep, before: ScreenSnapshot, after: ScreenSnapshot): String? {
        val t = step.target
        val texts = after.nodes.filter { it.visible && !it.redacted }.mapNotNull { it.text ?: it.contentDescription ?: it.subtreeLabel }
        fun shown(v: String) = texts.any { TextMatch.similarity(v, it) >= 0.6 }
        return when {
            t == null || step.kind != StepKind.CLICK -> describeScreen(after, before)
            t.role == SemanticRole.SEARCH_INPUT ->
                if (after.nodes.any { it.visible && it.editable }) "a text field is open" else null
            t.role == SemanticRole.CART || t.role == SemanticRole.CHECKOUT ->
                texts.firstOrNull { l -> cartWords.any { l.lowercase().contains(it) } }?.let { "cart content (\"${it.take(30)}\")" }
            t.slotRef == "RESTAURANT" ->
                slots["RESTAURANT"]?.takeIf(::shown)?.let { "\"$it\" shown · ${describeScreen(after, before)}" }
            t.slotRef == "ITEM" -> slots["ITEM"]?.let { v ->
                if (shown(v) || after.nodes.any { it.visible && it.role == SemanticRole.ADD_TO_CART }) "\"$v\" details shown" else null
            }
            else -> describeScreen(after, before)
        }
    }

    private suspend fun doInput(step: SkillStep, cap: CapturedScreen): StepOutcome {
        val value = fill(step.valueTemplate.orEmpty(), slots)
        val q = specificQuestion(step, false)
        val spec = (step.target ?: TargetSpec(SemanticRole.TEXT_INPUT)).copy(anchorLabel = null)
        var screen = cap
        var g = grounder.ground(spec, slots, screen.snapshot, forInput = true)
        if (g is Grounding.NotFound) {
            // Many apps show a search *button* that opens the real field.
            val opener = screen.snapshot.nodes.firstOrNull {
                it.visible && (it.clickable || it.longClickable) && (it.role == SemanticRole.SEARCH_INPUT ||
                    TextMatch.tokens(it.displayLabel).contains("search"))
            } ?: return StepOutcome.Failed("I can't find a text field for \"$value\".", q)
            val live = screen.live.getOrNull(opener.nodeId) ?: return StepOutcome.Failed("The search control vanished.", q)
            actions.click(live, opener.bounds)
            log("ACTION EXECUTED", "Opened search via \"${opener.displayLabel.take(30)}\"", action = "CLICK", target = opener.displayLabel.take(40))
            screen = awaitChange(screen.snapshot) ?: capture() ?: return StepOutcome.Failed("Screen unreadable after opening search.", q)
            g = grounder.ground(spec, slots, screen.snapshot, forInput = true)
        }
        val field = when (g) {
            is Grounding.Found -> g.best.node
            is Grounding.Ambiguous -> g.options.firstOrNull { it.node.role == SemanticRole.SEARCH_INPUT }?.node ?: g.options.first().node
            is Grounding.NotFound -> return StepOutcome.Failed("I can't find a text field for \"$value\".", q)
        }
        val action = SafetyGuardian.checkAction(field)
        if (action.stop) return StepOutcome.Boundary(action)
        log("TARGET GROUNDED", "Text field ${field.role.name}", target = field.displayLabel.take(40))
        val live = screen.live.getOrNull(field.nodeId) ?: return StepOutcome.Failed("The text field vanished.", q)
        attempted++
        AgentBus.setStatus(AgentState.EXECUTING, "Typing", value)
        if (!actions.setText(live, value)) return StepOutcome.Failed("The field refused text input.", q)
        succeeded++
        log("ACTION EXECUTED", "INPUT_TEXT \"$value\"", action = "INPUT_TEXT", target = field.displayLabel.take(40), result = "PERFORMED")
        lastInputField = field.resourceIdName to field.className
        delay(600)
        val check = capture()
        val ok = check?.snapshot?.nodes?.any { it.editable && TextMatch.similarity(value, it.text) >= 0.9 } ?: false
        return if (ok) StepOutcome.Passed("field shows \"$value\"", "field shows \"$value\"")
        else StepOutcome.Failed("I typed \"$value\" but the field doesn't show it.", q)
    }

    private suspend fun doQuantity(step: SkillStep, cap: CapturedScreen): StepOutcome {
        val want = slots["QUANTITY"]?.toIntOrNull() ?: 1
        val q = specificQuestion(step, false)
        var screen = cap
        repeat(12) {
            val s = screen.snapshot
            val item = slots["ITEM"]
            val anchors = s.nodes.filter { it.visible && item != null && TextMatch.similarity(item, it.text ?: it.contentDescription) >= 0.6 }
            val controls = s.nodes.filter { it.visible && it.role == SemanticRole.QUANTITY_CONTROL && (it.clickable || it.longClickable) }
            if (controls.isEmpty()) return StepOutcome.Failed("I can't find a quantity control for \"${item ?: "the item"}\".", q)
            fun near(n: NodeSnapshot) = anchors.minOfOrNull { abs(cy(it) - cy(n)) } ?: 0.0
            val plus = controls.filter { isIncrement(it) }.minByOrNull(::near)
            val minus = controls.filter { isDecrement(it) }.minByOrNull(::near)
            val current = readQuantity(s, plus ?: minus ?: controls.first()) ?: 1
            if (current == want) return StepOutcome.Passed("quantity = $want", "quantity shows $current")
            val btn = (if (current < want) plus else minus)
                ?: return StepOutcome.Failed("I can't find the ${if (current < want) "increase" else "decrease"} button.", q)
            val live = screen.live.getOrNull(btn.nodeId) ?: return StepOutcome.Failed("The quantity control vanished.", q)
            attempted++
            AgentBus.setStatus(AgentState.EXECUTING, "Setting quantity", "$current → $want")
            actions.click(live, btn.bounds) ?: return StepOutcome.Failed("The quantity button didn't respond.", q)
            succeeded++
            log("ACTION EXECUTED", "CLICK quantity ${if (current < want) "+" else "−"} ($current → $want)", action = "CLICK", target = btn.displayLabel)
            screen = awaitChange(s) ?: capture() ?: return StepOutcome.Failed("Screen unreadable after changing quantity.", q)
            SafetyGuardian.checkScreen(screen.snapshot).let { if (it.stop) return StepOutcome.Boundary(it) }
        }
        return StepOutcome.Failed("Quantity didn't reach $want after several taps (a customisation screen may have opened).", q)
    }

    private suspend fun doAddress(step: SkillStep, cap: CapturedScreen): StepOutcome {
        val want = slots["ADDRESS"] ?: return StepOutcome.Passed("no address change", "skipped")
        val q = specificQuestion(step, false)
        val picker = cap.snapshot.nodes.filter { it.visible && (it.clickable || it.longClickable) && it.role == SemanticRole.ADDRESS_SELECTOR }
            .maxByOrNull { it.roleScore } ?: return StepOutcome.Failed("I can't find an address selector on this screen.", q)
        val opened = tapAndVerify(picker, cap, step, null)
        if (opened !is StepOutcome.Passed) return opened
        val screen = capture() ?: return StepOutcome.Failed("Screen unreadable after opening addresses.", q)
        val choice = when (val g = grounder.ground(TargetSpec(SemanticRole.LIST_ITEM, slotRef = "ADDRESS"), slots, screen.snapshot, false)) {
            is Grounding.Found -> g.best.node
            is Grounding.Ambiguous -> {
                val labels = g.options.map { cleanLabel(it.label) }
                val a = askUser("WHICH ADDRESS?", "Several saved addresses match \"$want\".\nWhich one?", labels, TerminalReason.USER_CLARIFICATION)
                if (a is Reply.Option && a.index in labels.indices) g.options[a.index].node
                else return StepOutcome.Terminal(TerminalReason.USER_CLARIFICATION, "Address not chosen.")
            }
            is Grounding.NotFound -> return StepOutcome.Failed("I couldn't find a saved address called \"$want\".", q)
        }
        val r = tapAndVerify(choice, screen, step, null)
        if (r !is StepOutcome.Passed) return r
        val after = capture()
        val shown = after?.snapshot?.nodes?.any { it.visible && TextMatch.similarity(want, it.text ?: it.contentDescription) >= 0.6 } ?: false
        return if (shown) StepOutcome.Passed("address \"$want\" selected", "\"$want\" shown on screen")
        else StepOutcome.Failed("I selected an address but \"$want\" isn't shown afterwards.", q)
    }

    // ---- state-aware recovery (sections 15 and 16) --------------------------------------------

    private suspend fun recover(step: SkillStep, attempt: Int): RecoveryResult {
        val sk = skill!!
        val cap = capture() ?: return RecoveryResult.Ask("UNKNOWN STATE", "I can't read the screen. Please check the phone, then tap Continue.")
        val s = cap.snapshot
        val reading = StateClassifier.classify(s, sk.targetPackage, step, slots)
        log("STATE CLASSIFIED", "${reading.state.name} · ${reading.evidence}", state = reading.state.name, recovery = attempt)

        return when (reading.state) {
            UiState.PAYMENT, UiState.LOGIN, UiState.OTP -> RecoveryResult.Boundary(SafetyGuardian.checkScreen(s))

            UiState.POPUP -> {
                val d = RecoveryEngine.safeDismiss(s)!!
                val live = cap.live.getOrNull(d.nodeId) ?: return RecoveryResult.Retry
                actions.click(live, d.bounds)
                log("RECOVERY", "Dismissed popup via \"${d.displayLabel}\" → re-inspect → re-ground", action = "DISMISS", target = d.displayLabel, result = "OK", recovery = attempt)
                awaitChange(s)
                RecoveryResult.Retry
            }

            UiState.ALREADY_COMPLETED -> {
                log("RECOVERY", "Skipped redundant action: ${reading.evidence}", action = "SKIP", result = "OK", recovery = attempt)
                RecoveryResult.AlreadyDone
            }

            UiState.NETWORK_BLOCK -> {
                val retry = StateClassifier.retryControl(s)
                val live = retry?.let { cap.live.getOrNull(it.nodeId) }
                if (live != null) {
                    actions.click(live, retry.bounds)
                    log("RECOVERY", "Network problem: tapped \"${retry.displayLabel}\" and waiting", action = "RETRY", recovery = attempt)
                } else log("RECOVERY", "Network problem: waiting before re-inspecting", action = "WAIT", recovery = attempt)
                delay(2500)
                RecoveryResult.Retry
            }

            UiState.EMPTY_RESULTS -> RecoveryResult.Ask(
                "NO RESULTS",
                "${sk.targetApp} shows no results for \"${slots["ITEM"] ?: "this search"}\".\nPlease search for something else yourself, then tap Continue, or Stop.",
            )

            UiState.UNKNOWN_STATE -> if (attempt == 1) {
                log("RECOVERY", "Safe inspection: waiting for the screen to settle", action = "WAIT", recovery = attempt)
                delay(1500)
                RecoveryResult.Retry
            } else RecoveryResult.Ask(
                "UNKNOWN STATE",
                "The screen isn't what I expected (${reading.evidence}).\nPlease bring ${sk.targetApp} back to the right screen, then tap Continue.",
            )

            UiState.MISSING_TARGET, UiState.EXPECTED_STATE, UiState.AMBIGUOUS_STATE -> missingTarget(cap, attempt)
        }
    }

    /** Attempt 1: re-inspect (and submit a typed search). 2: scroll + alternate match. 3: back to the top, re-ground. */
    private suspend fun missingTarget(cap: CapturedScreen, attempt: Int): RecoveryResult {
        val s = cap.snapshot
        when (attempt) {
            1 -> {
                val lf = lastInputField
                if (lf != null) {
                    val field = s.nodes.firstOrNull { it.editable && it.visible && it.resourceIdName == lf.first && it.className == lf.second }
                        ?: s.nodes.firstOrNull { it.editable && it.visible && it.role == SemanticRole.SEARCH_INPUT }
                    val live = field?.let { cap.live.getOrNull(it.nodeId) }
                    lastInputField = null
                    if (live != null && actions.imeEnter(live)) {
                        log("RECOVERY", "Submitted the search (keyboard action) → re-inspect", action = "SUBMIT_SEARCH", result = "OK", recovery = attempt)
                        awaitChange(s)
                        return RecoveryResult.Retry
                    }
                }
                log("RECOVERY", "Re-inspecting the UI after a short wait", action = "RE-INSPECT", recovery = attempt)
                delay(1000)
            }
            2 -> {
                val list = RecoveryEngine.largestScrollable(s)
                val live = list?.let { cap.live.getOrNull(it.nodeId) }
                if (live != null && actions.scrollForward(live)) {
                    log("RECOVERY", "Scrolled to look for the target; next grounding uses an alternate semantic match", action = "SCROLL", result = "OK", recovery = attempt)
                    awaitChange(s)
                } else log("RECOVERY", "Nothing to scroll; trying an alternate semantic match", action = "ALTERNATE_MATCH", recovery = attempt)
            }
            else -> {
                val list = RecoveryEngine.largestScrollable(s)
                val live = list?.let { cap.live.getOrNull(it.nodeId) }
                var back = 0
                while (live != null && back < 3 && actions.scrollBackward(live)) { back++; delay(400) }
                log("RECOVERY", "Re-grounding from a fresh capture${if (back > 0) " (scrolled back $back×)" else ""}", action = "RE-GROUND", recovery = attempt)
                delay(600)
            }
        }
        return RecoveryResult.Retry
    }

    // ---- wording ------------------------------------------------------------------------------

    private fun specificQuestion(step: SkillStep, exhausted: Boolean): String {
        val after = if (exhausted) " after ${RecoveryEngine.MAX_ATTEMPTS} recovery attempts" else ""
        val t = step.target
        return when {
            step.kind == StepKind.INPUT ->
                "I couldn't find a text field to type \"${fill(step.valueTemplate.orEmpty(), slots)}\"$after. Please type it yourself, then tap Continue."
            step.kind == StepKind.SET_QUANTITY ->
                "I couldn't set the quantity to ${slots["QUANTITY"]}$after. Please set it yourself, then tap Continue."
            step.kind == StepKind.SELECT_ADDRESS ->
                "I couldn't select the address \"${slots["ADDRESS"]}\"$after. Please choose it yourself, then tap Continue."
            t?.role == SemanticRole.ADD_TO_CART && t.nearSlot != null ->
                "I couldn't find the Add button for ${slots[t.nearSlot] ?: "the item"}$after. Please add it yourself, or tell me how to continue."
            t?.slotRef != null ->
                "I couldn't find \"${slots[t.slotRef]}\"$after. Please select it yourself, then tap Continue."
            t?.ordinal != null ->
                "I couldn't find result #${t.ordinal}$after. Please select it yourself, then tap Continue."
            t != null -> "I couldn't find ${grounder.describe(t, slots)}$after. Please do this step yourself, then tap Continue."
            else -> "I couldn't complete \"${step.title}\"$after. Please do it yourself, then tap Continue."
        }
    }

    /** What should be true after the step (section 17). */
    private fun expectationFor(step: SkillStep): String {
        val t = step.target
        return when {
            step.kind == StepKind.INPUT -> "field shows the typed text"
            step.kind == StepKind.SET_QUANTITY -> "quantity = ${slots["QUANTITY"]}"
            step.kind == StepKind.SELECT_ADDRESS -> "address \"${slots["ADDRESS"]}\" selected"
            t == null -> "screen responds"
            t.role == SemanticRole.ADD_TO_CART -> "\"${slots["ITEM"] ?: "item"}\" added to cart"
            t.role == SemanticRole.SEARCH_INPUT -> "search opens"
            t.role == SemanticRole.CART -> "cart opens"
            t.role == SemanticRole.CHECKOUT -> "checkout opens"
            t.slotRef == "RESTAURANT" -> "\"${slots["RESTAURANT"]}\" page opens"
            t.slotRef == "ITEM" || t.role == SemanticRole.PRODUCT -> "product details open"
            t.ordinal != null -> "result #${t.ordinal} opens"
            else -> "screen responds to \"${t.anchorLabel ?: t.role.name.lowercase()}\""
        }
    }

    private fun describeScreen(after: ScreenSnapshot, before: ScreenSnapshot): String {
        val heads = after.nodes.filter { it.visible && !it.redacted && (it.text?.length ?: 0) in 3..40 }
            .sortedBy { it.bounds.top }.mapNotNull { it.text }.distinct().take(2)
        val where = if (after.packageName != before.packageName) "another app (${after.packageName})" else "new screen"
        return if (heads.isEmpty()) where else "$where: ${heads.joinToString(" / ") { "\"$it\"" }}"
    }

    private fun roleWord(r: SemanticRole) = when (r) {
        SemanticRole.ADD_TO_CART, SemanticRole.BUTTON, SemanticRole.CART, SemanticRole.CHECKOUT -> "buttons"
        SemanticRole.PRODUCT -> "products"
        SemanticRole.LIST_ITEM -> "items"
        else -> "elements"
    }

    private fun cleanLabel(l: String): String =
        l.split(" · ", "\u00B7", "\u20B9", "\n").first().trim().ifBlank { l.trim() }.take(50)

    // ---- helpers ------------------------------------------------------------------------------

    private fun isIncrement(n: NodeSnapshot): Boolean {
        val l = (n.text ?: n.contentDescription ?: "").trim().lowercase()
        return l == "+" || l.contains("increase") || l.contains("increment") || l.contains("add one") || l.contains("plus")
    }

    private fun isDecrement(n: NodeSnapshot): Boolean {
        val l = (n.text ?: n.contentDescription ?: "").trim().lowercase()
        return l == "-" || l == "\u2212" || l == "\u2013" || l.contains("decrease") || l.contains("decrement") || l.contains("minus")
    }

    private fun readQuantity(s: ScreenSnapshot, control: NodeSnapshot): Int? {
        val y = cy(control)
        return s.nodes.filter { it.visible && !it.redacted && it.text?.trim()?.matches(Regex("\\d{1,2}")) == true && abs(cy(it) - y) < 60 }
            .minByOrNull { abs((it.bounds.left + it.bounds.right) / 2 - (control.bounds.left + control.bounds.right) / 2) }
            ?.text?.trim()?.toIntOrNull()
    }

    private suspend fun awaitChange(before: ScreenSnapshot, timeoutMs: Long = 3000): CapturedScreen? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(300)
            val c = capture() ?: continue
            if (c.snapshot.fingerprint != before.fingerprint || c.snapshot.packageName != before.packageName) {
                delay(350)
                return capture() ?: c
            }
        }
        return null
    }

    private fun fill(template: String, slots: Map<String, String>): String =
        Regex("\\{([A-Z_]+)\\}").replace(template) { m -> slots[m.groupValues[1]] ?: m.value }

    private fun cy(n: NodeSnapshot) = (n.bounds.top + n.bounds.bottom) / 2.0
}
