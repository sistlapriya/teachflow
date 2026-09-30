package com.teachflow.agent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.core.AgentRequest
import com.teachflow.agent.core.AgentState
import com.teachflow.agent.core.Reply
import com.teachflow.agent.execution.RunReport
import com.teachflow.agent.execution.RunReportStore
import com.teachflow.agent.execution.TerminalReason
import com.teachflow.agent.execution.TraceEvent
import com.teachflow.agent.nlu.AppResolver
import com.teachflow.agent.nlu.CommandParser
import com.teachflow.agent.skills.MatchOutcome
import com.teachflow.agent.skills.SkillMatcher
import com.teachflow.agent.skills.SkillRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * DEBUG BUILDS ONLY. Test hook for the emulator end-to-end tests: the same actions a person performs
 * by tapping TeachFlow's UI (start / stop teaching, save, run a command, answer a question, resume).
 * It does not bypass anything: teaching still observes real taps, and runs still go through the
 * real matcher, executor and safety guardian.
 *
 *   adb shell am broadcast -n com.teachflow.agent/.debug.DebugCommandReceiver --es cmd status
 * String arguments (command, text) are base64 so shell quoting can't mangle them. The reply is
 * base64 JSON in the broadcast result data.
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        fun arg(k: String): String? = intent.getStringExtra(k)?.let { String(Base64.decode(it, Base64.DEFAULT)) }
        val out = JSONObject()
        try {
            when (intent.getStringExtra("cmd")) {
                "reset" -> {
                    SkillRepository.get(app).clear()
                    RunReportStore.get(app).clear()
                    AgentBus.pendingSynthesis.value = null
                    AgentBus.setStatus(AgentState.READY, "Ready")
                }
                "overlay" -> AgentBus.overlayEnabled.value = intent.getBooleanExtra("on", true)
                "teach" -> AgentBus.request(AgentRequest.StartTeaching(arg("command")!!, intent.getStringExtra("pkg")!!, intent.getStringExtra("label")!!))
                "stop" -> AgentBus.request(AgentRequest.StopTeaching)
                "save" -> {
                    val p = AgentBus.pendingSynthesis.value
                    if (p == null) out.put("saved", JSONObject.NULL) else {
                        SkillRepository.get(app).save(p.skill)
                        AgentBus.pendingSynthesis.value = null
                        AgentBus.setStatus(AgentState.READY, "Skill saved")
                        out.put("saved", p.skill.name)
                        out.put("observed", p.observed); out.put("relevant", p.relevant); out.put("ignored", p.ignored)
                        out.put("slots", JSONArray(p.skill.slots.map { "${it.name}=${it.defaultValue ?: "—"}" }))
                        out.put("steps", JSONArray(p.skill.steps.map { it.title + if (it.optional) " (optional)" else "" }))
                        out.put("judged", JSONArray(p.judged.map {
                            "${it.relevance} · ${it.decision} · ${it.rec.action.type} \"${it.rec.action.displayLabel.take(40)}\" [${it.rec.action.packageName}] — ${it.reason}"
                        }))
                        out.put("warnings", JSONArray(p.warnings))
                    }
                }
                "run" -> {
                    val command = arg("command")!!
                    val apps = AppResolver(app)
                    val parsed = CommandParser { apps.apps().map { it.label } }.parse(command)
                    when (val m = SkillMatcher().match(parsed, SkillRepository.get(app).skills.value)) {
                        is MatchOutcome.Matched -> {
                            AgentBus.request(AgentRequest.Run(m.result.skill.id, command))
                            out.put("match", m.result.skill.name); out.put("score", m.result.score)
                            out.put("reasons", JSONArray(m.result.reasons))
                        }
                        is MatchOutcome.Ambiguous -> {
                            out.put("match", JSONObject.NULL); out.put("ambiguous", JSONArray(m.options.map { it.skill.name }))
                        }
                        is MatchOutcome.NoMatch -> {
                            val now = System.currentTimeMillis()
                            RunReportStore.get(app).add(RunReport.noOp(command, TerminalReason.UNKNOWN_INTENT,
                                "No learned workflow matches this request. Nothing was executed.",
                                listOf(TraceEvent(now, "VOICE RECEIVED", "\"$command\""),
                                    TraceEvent(now, "INTENT PARSED", "${parsed.intentName} · slots ${parsed.slots}"),
                                    TraceEvent(now, "SKILL SEARCH", "No skill above the match threshold (best %.2f)".format(m.best?.score ?: 0.0)))))
                            out.put("match", JSONObject.NULL); out.put("unknown", true)
                        }
                    }
                }
                "answer" -> {
                    val q = AgentBus.status.value.question
                    if (q == null) out.put("answered", JSONObject.NULL) else {
                        val reply = when {
                            intent.hasExtra("option") -> Reply.Option(intent.getIntExtra("option", 0))
                            intent.hasExtra("text") -> Reply.Text(arg("text")!!)
                            else -> Reply.Stop
                        }
                        AgentBus.answer(q.id, reply)
                        out.put("answered", q.headline)
                    }
                }
                "resume" -> AgentBus.request(AgentRequest.Resume)
                "done" -> AgentBus.setStatus(AgentState.READY, "Ready")
                else -> Unit // "status"
            }
            val st = AgentBus.status.value
            out.put("service", AgentBus.serviceConnected.value)
            out.put("state", st.state.name); out.put("headline", st.headline); out.put("detail", st.detail)
            out.put("boundary", st.boundary ?: JSONObject.NULL); out.put("human", st.humanInControl)
            st.question?.let { q ->
                out.put("question", JSONObject().put("id", q.id).put("headline", q.headline).put("text", q.text)
                    .put("options", JSONArray(q.options)).put("allowText", q.allowText))
            }
            out.put("runs", RunReportStore.get(app).runs.value.size)
            out.put("skills", SkillRepository.get(app).skills.value.size)
            out.put("pending", AgentBus.pendingSynthesis.value != null)
        } catch (e: Exception) {
            out.put("error", e.toString())
        }
        resultData = Base64.encodeToString(out.toString().toByteArray(), Base64.NO_WRAP)
    }
}
