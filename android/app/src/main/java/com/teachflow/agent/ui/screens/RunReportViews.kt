package com.teachflow.agent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teachflow.agent.execution.RunReport
import com.teachflow.agent.execution.RunStatus
import com.teachflow.agent.execution.TerminalReason
import com.teachflow.agent.execution.TraceEvent
import com.teachflow.agent.ui.theme.TF

fun statusColor(r: RunReport): Color = when {
    r.status == RunStatus.SUCCESS -> TF.Green
    r.status == RunStatus.NO_OP -> TF.Muted
    r.terminalReason == TerminalReason.AUTHENTICATION_BOUNDARY || r.status == RunStatus.HUMAN_HANDOFF -> TF.Amber
    r.status == RunStatus.CANCELLED -> TF.Muted
    else -> TF.Red
}

@Composable
private fun Row2(k: String, v: String, color: Color = TF.Text) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(k, color = TF.Dim, fontSize = 13.sp, modifier = Modifier.width(140.dp))
        Text(v, color = color, fontSize = 13.sp)
    }
}

/** Section 24 run summary. Every number comes from the recorded execution. */
@Composable
fun RunReportSummary(r: RunReport) {
    Label("Run report")
    Row2("Command", "\"${r.command}\"")
    Row2("Skill", r.skillName ?: "—")
    Row2("Parameters", if (r.parameters.isEmpty()) "—" else r.parameters.entries.joinToString { "${it.key} = ${it.value}" })
    Row2("Steps", "${r.stepsTotal}")
    Row2("Successful", "${r.stepsSucceeded}")
    Row2("Recovered", "${r.stepsRecovered}")
    Row2("User interventions", "${r.userInterventions}")
    Row2("Safety boundary", r.safetyBoundaryTriggered?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—")
    Row2("Final state", r.finalStatus, statusColor(r))
    Row2("Stop reason", r.terminalReason.display, statusColor(r))
    if (r.terminalMessage.isNotBlank()) Text(r.terminalMessage, color = TF.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    if (r.status != RunStatus.SUCCESS) {
        r.stoppedAtStep?.let { Text("Stopped at step $it${r.stoppedAtStepTitle?.let { t -> " · $t" } ?: ""}", color = TF.Muted, fontSize = 12.sp) }
    }
}

private fun rel(t: Long, t0: Long): String {
    val s = ((t - t0) / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun phaseColor(e: TraceEvent): Color = when {
    e.phase.contains("SAFETY") || e.phase.contains("PAYMENT") || e.phase.contains("AUTHENTICATION") || e.phase == "YOUR TURN" -> TF.Amber
    e.phase.contains("EXHAUSTED") || e.result?.startsWith("FAILED") == true || e.phase == "RUN STOPPED" -> TF.Red
    e.phase.startsWith("RECOVERY") || e.phase == "STATE CLASSIFIED" -> TF.Violet
    e.result?.contains("✓") == true -> TF.Green
    else -> TF.Cyan
}

/** Section 25: structured trace; tap an entry for ACTION / TARGET / STATE / RESULT / RECOVERY COUNT / REASON. */
@Composable
fun TraceList(trace: List<TraceEvent>) {
    var open by remember { mutableStateOf<Int?>(null) }
    val t0 = trace.firstOrNull()?.t ?: 0L
    Label("Execution trace (tap an entry)")
    trace.forEachIndexed { i, e ->
        Column(Modifier.fillMaxWidth().clickable { open = if (open == i) null else i }.padding(vertical = 5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rel(e.t, t0), color = TF.Dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(e.phase, color = phaseColor(e), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                e.step?.let { Text("step $it", color = TF.Dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
            Text(e.message, color = TF.Text, fontSize = 12.sp)
            if (open == i) {
                Column(Modifier.padding(start = 10.dp, top = 4.dp)) {
                    listOf(
                        "ACTION" to e.action, "TARGET" to e.target, "STATE" to e.state,
                        "RESULT" to e.result, "RECOVERY COUNT" to e.recoveryCount?.toString(), "REASON" to e.reason,
                    ).forEach { (k, v) -> Row2(k, v ?: "—", if (v == null) TF.Dim else TF.Text) }
                }
            }
        }
    }
}
