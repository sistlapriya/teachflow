package com.teachflow.agent.core

import com.teachflow.agent.accessibility.ObservedAction
import com.teachflow.agent.accessibility.ScreenSnapshot
import com.teachflow.agent.learning.SynthesisResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AgentState(val label: String) {
    READY("READY"),
    LISTENING("LISTENING"),
    LEARNING("LEARNING"),
    MATCHING("MATCHING"),
    EXECUTING("EXECUTING"),
    VERIFYING("VERIFYING"),
    RECOVERING("RECOVERING"),
    WAITING_FOR_USER("WAITING FOR USER"),
    COMPLETED("COMPLETED"),
    BLOCKED("BLOCKED"),
}

/**
 * A question TeachFlow needs answered before it can continue.
 * [allowText]: the user may also type or say a free answer (opens the answer screen).
 */
data class Question(val id: Long, val text: String, val options: List<String>, val allowText: Boolean = false, val headline: String = "")

/** The user's answer to a [Question]. */
sealed interface Reply {
    data class Option(val index: Int) : Reply
    data class Text(val text: String) : Reply
    data object TakeControl : Reply
    data object Stop : Reply
}

data class AgentStatus(
    val state: AgentState = AgentState.READY,
    val headline: String = "Ready",
    val detail: String = "",
    val question: Question? = null,
    /** Shown in the overlay while teaching. */
    val observedCount: Int = 0,
    /** True while the user has taken control and TeachFlow is waiting. */
    val humanInControl: Boolean = false,
    /** Set while a safety boundary is shown, e.g. "PAYMENT SCREEN DETECTED". */
    val boundary: String? = null,
    /** One-line run summary shown when a run ends. */
    val summary: String? = null,
)

/** Requests from the UI (activity or overlay) to the accessibility service. */
sealed interface AgentRequest {
    data class StartTeaching(val command: String, val targetPackage: String, val targetApp: String) : AgentRequest
    data object StopTeaching : AgentRequest
    /**
     * @param overrides slot values the user supplied during clarification (e.g. ITEM = Farmhouse).
     * @param notes (phase, message) trace entries recorded before the run started.
     * @param interventions user interventions already made (clarification answers).
     */
    data class Run(
        val skillId: String,
        val command: String,
        val overrides: Map<String, String> = emptyMap(),
        val notes: List<Pair<String, String>> = emptyList(),
        val interventions: Int = 0,
        /** EXPERIMENTAL cross-app transfer: run the skill against a different app than it was taught in. */
        val transferPackage: String? = null,
        val transferApp: String? = null,
    ) : AgentRequest
    data object Cancel : AgentRequest
    data object TakeControl : AgentRequest
    data object Resume : AgentRequest
}


/**
 * In-process bridge between the accessibility service, the overlay and the activity.
 * They share one process, so plain flows are enough.
 */
object AgentBus {
    private const val MAX_ACTIONS = 300

    val serviceConnected = MutableStateFlow(false)
    val overlayEnabled = MutableStateFlow(true)

    private val _snapshot = MutableStateFlow<ScreenSnapshot?>(null)
    val snapshot: StateFlow<ScreenSnapshot?> = _snapshot.asStateFlow()

    private val _actions = MutableStateFlow<List<ObservedAction>>(emptyList())
    val actions: StateFlow<List<ObservedAction>> = _actions.asStateFlow()

    val status = MutableStateFlow(AgentStatus())

    /** Result of the last teaching session, waiting for the user to review and save. */
    val pendingSynthesis = MutableStateFlow<SynthesisResult?>(null)

    private val _requests = MutableSharedFlow<AgentRequest>(extraBufferCapacity = 16)
    val requests: SharedFlow<AgentRequest> = _requests.asSharedFlow()

    private val _answers = MutableSharedFlow<Pair<Long, Reply>>(extraBufferCapacity = 8)
    val answers: SharedFlow<Pair<Long, Reply>> = _answers.asSharedFlow()

    fun request(r: AgentRequest) { _requests.tryEmit(r) }
    fun answer(questionId: Long, reply: Reply) { _answers.tryEmit(questionId to reply) }

    fun setStatus(state: AgentState, headline: String, detail: String = "") {
        status.update { it.copy(state = state, headline = headline, detail = detail, question = null, boundary = null, summary = null) }
    }

    fun publishSnapshot(s: ScreenSnapshot) {
        val prev = _snapshot.value
        _snapshot.value = s
        if (prev?.fingerprint != s.fingerprint) attachAfterFingerprint(s.fingerprint, s.capturedAt)
    }

    fun recordAction(a: ObservedAction) {
        _actions.update { (listOf(a) + it).take(MAX_ACTIONS) }
    }

    fun replaceLatest(a: ObservedAction) {
        _actions.update { list ->
            if (list.isNotEmpty() && list[0].id == a.id) listOf(a) + list.drop(1) else (listOf(a) + list).take(MAX_ACTIONS)
        }
    }

    fun clearActions() { _actions.value = emptyList() }

    private fun attachAfterFingerprint(fp: String, at: Long) {
        _actions.update { list ->
            val first = list.firstOrNull() ?: return@update list
            if (first.screenAfter == null && first.screenBefore != fp && at - first.timestamp in 0..4000) {
                listOf(first.copy(screenAfter = fp)) + list.drop(1)
            } else list
        }
    }
}
