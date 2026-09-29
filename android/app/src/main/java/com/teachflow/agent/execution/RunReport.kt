package com.teachflow.agent.execution

import android.content.Context
import com.teachflow.agent.core.TfLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Coarse outcome, used for skill statistics. */
enum class RunStatus { SUCCESS, HUMAN_HANDOFF, BLOCKED, FAILED, CANCELLED, NO_OP }

/** Why the run ended. Every run finishes with exactly one of these. */
enum class TerminalReason(val display: String) {
    SUCCESS("SUCCESS"),
    PAYMENT_BOUNDARY("PAYMENT_BOUNDARY"),
    AUTHENTICATION_BOUNDARY("AUTHENTICATION_BOUNDARY"),
    USER_CLARIFICATION("USER_CLARIFICATION"),
    RECOVERY_EXHAUSTED("NO SAFE MATCH AFTER 3 RECOVERY ATTEMPTS"),
    UNKNOWN_INTENT("UNKNOWN_INTENT"),
    AMBIGUOUS_INTENT("AMBIGUOUS_INTENT"),
    USER_HANDOFF("USER_HANDOFF"),
    ERROR("ERROR"),
}

/** One structured trace entry. Every field except phase and message is optional detail. */
data class TraceEvent(
    val t: Long,
    val phase: String,
    val message: String,
    val step: Int? = null,
    val action: String? = null,
    val target: String? = null,
    val state: String? = null,
    val result: String? = null,
    val recoveryCount: Int? = null,
    val reason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("t", t); put("phase", phase); put("message", message)
        putOpt("step", step); putOpt("action", action); putOpt("target", target); putOpt("state", state)
        putOpt("result", result); putOpt("recoveryCount", recoveryCount); putOpt("reason", reason)
    }

    companion object {
        fun fromJson(o: JSONObject) = TraceEvent(
            t = o.getLong("t"), phase = o.getString("phase"), message = o.getString("message"),
            step = o.optIntOrNull("step"), action = o.optStringOrNull("action"), target = o.optStringOrNull("target"),
            state = o.optStringOrNull("state"), result = o.optStringOrNull("result"),
            recoveryCount = o.optIntOrNull("recoveryCount"), reason = o.optStringOrNull("reason"),
        )
    }
}

internal fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
internal fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null

/** All counts are taken from the actual execution; nothing is estimated. */
data class RunReport(
    val id: String,
    val skillId: String?,
    val skillName: String?,
    val command: String,
    val parameters: Map<String, String>,
    val startedAt: Long,
    val completedAt: Long,
    val status: RunStatus,
    val terminalReason: TerminalReason,
    val terminalMessage: String,
    val stoppedAtStep: Int?,
    val stoppedAtStepTitle: String?,
    val stepsTotal: Int,
    val stepsSucceeded: Int,
    val stepsRecovered: Int,
    val recoveryAttempts: Int,
    val userInterventions: Int,
    val actionsAttempted: Int,
    val actionsSucceeded: Int,
    val safetyBoundaryTriggered: String?,
    val trace: List<TraceEvent>,
) {
    val finalStatus: String
        get() = when (terminalReason) {
            TerminalReason.SUCCESS -> "SUCCESS"
            TerminalReason.PAYMENT_BOUNDARY -> if (status == RunStatus.SUCCESS) "SUCCESS — STOPPED AT PAYMENT" else "STOPPED — PAYMENT BOUNDARY"
            TerminalReason.AUTHENTICATION_BOUNDARY -> "STOPPED — AUTHENTICATION REQUIRED"
            TerminalReason.USER_CLARIFICATION -> "STOPPED — CLARIFICATION NOT RESOLVED"
            TerminalReason.RECOVERY_EXHAUSTED -> "STOPPED — NO SAFE MATCH AFTER 3 RECOVERY ATTEMPTS"
            TerminalReason.UNKNOWN_INTENT -> "SAFE NO-OP — UNKNOWN INTENT"
            TerminalReason.AMBIGUOUS_INTENT -> "SAFE NO-OP — AMBIGUOUS INTENT"
            TerminalReason.USER_HANDOFF -> "STOPPED — HANDED TO YOU"
            TerminalReason.ERROR -> "ERROR"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); putOpt("skillId", skillId); putOpt("skillName", skillName); put("command", command)
        put("parameters", JSONObject(parameters as Map<*, *>))
        put("startedAt", startedAt); put("completedAt", completedAt); put("status", status.name)
        put("terminalReason", terminalReason.name); put("terminalMessage", terminalMessage); put("finalStatus", finalStatus)
        putOpt("stoppedAtStep", stoppedAtStep); putOpt("stoppedAtStepTitle", stoppedAtStepTitle)
        put("stepsTotal", stepsTotal); put("stepsSucceeded", stepsSucceeded); put("stepsRecovered", stepsRecovered)
        put("recoveryAttempts", recoveryAttempts); put("userInterventions", userInterventions)
        put("actionsAttempted", actionsAttempted); put("actionsSucceeded", actionsSucceeded)
        putOpt("safetyBoundaryTriggered", safetyBoundaryTriggered)
        put("trace", JSONArray(trace.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JSONObject): RunReport {
            val p = o.optJSONObject("parameters")
            val params = LinkedHashMap<String, String>()
            p?.keys()?.forEach { k -> params[k] = p.getString(k) }
            val tr = o.optJSONArray("trace")
            return RunReport(
                id = o.getString("id"), skillId = o.optStringOrNull("skillId"), skillName = o.optStringOrNull("skillName"),
                command = o.optString("command"), parameters = params,
                startedAt = o.optLong("startedAt"), completedAt = o.optLong("completedAt"),
                status = runCatching { RunStatus.valueOf(o.getString("status")) }.getOrDefault(RunStatus.FAILED),
                terminalReason = runCatching { TerminalReason.valueOf(o.getString("terminalReason")) }.getOrDefault(TerminalReason.ERROR),
                terminalMessage = o.optString("terminalMessage"),
                stoppedAtStep = o.optIntOrNull("stoppedAtStep"), stoppedAtStepTitle = o.optStringOrNull("stoppedAtStepTitle"),
                stepsTotal = o.optInt("stepsTotal"), stepsSucceeded = o.optInt("stepsSucceeded"), stepsRecovered = o.optInt("stepsRecovered"),
                recoveryAttempts = o.optInt("recoveryAttempts"), userInterventions = o.optInt("userInterventions"),
                actionsAttempted = o.optInt("actionsAttempted"), actionsSucceeded = o.optInt("actionsSucceeded"),
                safetyBoundaryTriggered = o.optStringOrNull("safetyBoundaryTriggered"),
                trace = if (tr == null) emptyList() else (0 until tr.length()).map { TraceEvent.fromJson(tr.getJSONObject(it)) },
            )
        }

        /** A request that ended safely without running anything (unknown or unresolved ambiguous intent). */
        fun noOp(command: String, reason: TerminalReason, message: String, trace: List<TraceEvent>): RunReport {
            val now = System.currentTimeMillis()
            return RunReport(
                id = "run_$now", skillId = null, skillName = null, command = command, parameters = emptyMap(),
                startedAt = trace.firstOrNull()?.t ?: now, completedAt = now, status = RunStatus.NO_OP,
                terminalReason = reason, terminalMessage = message, stoppedAtStep = null, stoppedAtStepTitle = null,
                stepsTotal = 0, stepsSucceeded = 0, stepsRecovered = 0, recoveryAttempts = 0, userInterventions = 0,
                actionsAttempted = 0, actionsSucceeded = 0, safetyBoundaryTriggered = null,
                trace = trace + TraceEvent(now, "SAFE NO-OP", message, result = "NO ACTION TAKEN", reason = reason.display),
            )
        }
    }
}

class RunReportStore private constructor(context: Context) {
    private val file = File(context.filesDir, "runs.json")
    private val _runs = MutableStateFlow(load())
    val runs: StateFlow<List<RunReport>> = _runs.asStateFlow()

    @Synchronized
    fun add(r: RunReport) {
        _runs.value = (listOf(r) + _runs.value).take(100)
        persist()
    }

    @Synchronized
    fun clear() { _runs.value = emptyList(); persist() }

    fun exportJson(): String = JSONArray(_runs.value.map { it.toJson() }).toString(2)

    private fun load(): List<RunReport> = try {
        if (!file.exists()) emptyList()
        else JSONArray(file.readText()).let { a -> (0 until a.length()).map { RunReport.fromJson(a.getJSONObject(it)) } }
    } catch (e: Exception) {
        TfLog.w("REPORT", "Could not read runs.json", e); emptyList()
    }

    private fun persist() {
        val tmp = File(file.parentFile, "runs.json.tmp")
        tmp.writeText(exportJson())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        @Volatile private var instance: RunReportStore? = null
        fun get(context: Context): RunReportStore =
            instance ?: synchronized(this) { instance ?: RunReportStore(context.applicationContext).also { instance = it } }
    }
}
