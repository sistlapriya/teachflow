package com.teachflow.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teachflow.agent.evaluation.JudgeResults
import com.teachflow.agent.evaluation.METRICS
import com.teachflow.agent.evaluation.NOT_TESTED
import com.teachflow.agent.evaluation.TESTS
import com.teachflow.agent.evaluation.TestResult
import com.teachflow.agent.execution.RunReportStore
import com.teachflow.agent.ui.theme.TF

/**
 * T1–T14 evaluation console. Every test starts NOT YET TESTED. The tester runs it on the device,
 * attaches the real run report as evidence, and records PASS or FAIL. Metrics are computed only
 * from those recorded results.
 */
@Composable
fun TestsScreen() {
    val ctx = LocalContext.current
    val store = remember { RunReportStore.get(ctx) }
    val runs by store.runs.collectAsStateWithLifecycle()
    val results = remember { mutableStateMapOf<String, TestResult>().apply { putAll(JudgeResults.load(ctx)) } }
    var openTrace by remember { mutableStateOf<String?>(null) }
    fun update(id: String, f: (TestResult) -> TestResult) { results[id] = f(results[id] ?: TestResult()); JudgeResults.save(ctx, results) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Label("Judge mode")
            Title("T1–T14 evaluation")
            Text("Run each test on this device, attach its run report as evidence, then record PASS or FAIL. Nothing is marked PASS automatically.",
                color = TF.Muted, fontSize = 13.sp)
            TextButton(onClick = {
                share(ctx, "TeachFlow judge results", JudgeResults.export(results, runs, com.teachflow.agent.BuildConfig.VERSION_NAME))
            }) { Text("Export results JSON (import it on the website)") }
        }
        item {
            Panel {
                Label("Metrics (from recorded results)")
                METRICS.forEach { (k, ids) ->
                    val v = JudgeResults.metric(ids, results)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(k, color = TF.Text, fontSize = 13.sp, modifier = Modifier.width(170.dp))
                        Text(v?.let { "$it passed" } ?: NOT_TESTED, color = if (v == null) TF.Dim else TF.Green, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("  (${ids.joinToString()})", color = TF.Dim, fontSize = 11.sp)
                    }
                }
            }
        }
        items(TESTS, key = { it.id }) { t ->
            val r = results[t.id] ?: TestResult()
            val run = r.runId?.let { id -> runs.firstOrNull { it.id == id } }
            Panel(border = when (r.status) { "PASS" -> TF.Green; "FAIL" -> TF.Red; else -> TF.Line }.copy(alpha = if (r.status == NOT_TESTED) 1f else 0.5f)) {
                Text("${t.id} · ${t.name}", color = TF.Text, fontSize = 16.sp)
                Text(t.description, color = TF.Muted, fontSize = 13.sp)
                Text("Expected: ${t.expected}", color = TF.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf(NOT_TESTED to TF.Muted, "PASS" to TF.Green, "FAIL" to TF.Red).forEach { (st, c) ->
                        TextButton(onClick = { update(t.id) { it.copy(status = st) } }) {
                            Text(if (r.status == st) "● $st" else st, color = if (r.status == st) c else TF.Dim, fontSize = 12.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = r.observation, onValueChange = { v -> update(t.id) { it.copy(observation = v) } },
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("Observation: what actually happened") }, textStyle = TextStyle(fontSize = 13.sp),
                )
                Label("Last run")
                if (run == null) Text("No run attached.", color = TF.Dim, fontSize = 13.sp)
                else {
                    Text("\"${run.command}\" · ${run.finalStatus}", color = statusColor(run), fontSize = 13.sp)
                    Text("${dateTime(run.startedAt)} · stop reason ${run.terminalReason.display}", color = TF.Muted, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    runs.firstOrNull()?.let { latest ->
                        TextButton(onClick = { update(t.id) { it.copy(runId = latest.id) } }) { Text("Attach latest run") }
                    }
                    if (run != null) TextButton(onClick = { openTrace = if (openTrace == t.id) null else t.id }) {
                        Text(if (openTrace == t.id) "Hide trace" else "Trace")
                    }
                }
                if (run != null && openTrace == t.id) { RunReportSummary(run); TraceList(run.trace) }
            }
        }
        item { Text("") }
    }
}
