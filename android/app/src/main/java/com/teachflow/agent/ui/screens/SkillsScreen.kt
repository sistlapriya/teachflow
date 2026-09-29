package com.teachflow.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.core.AgentRequest
import com.teachflow.agent.nlu.AppResolver
import com.teachflow.agent.skills.Skill
import com.teachflow.agent.skills.SkillJson
import com.teachflow.agent.skills.SkillRepository
import com.teachflow.agent.skills.StepKind
import com.teachflow.agent.ui.theme.TF

@Composable
fun SkillsScreen(apps: AppResolver) {
    val ctx = LocalContext.current
    val repo = remember { SkillRepository.get(ctx) }
    val skills by repo.skills.collectAsStateWithLifecycle()
    val runs by remember { com.teachflow.agent.execution.RunReportStore.get(ctx) }.runs.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Skill?>(null) }
    var transferFor by remember { mutableStateOf<Skill?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Label("Skill memory")
            Title("My skills")
            Text("Saved on this device and kept across app restarts. Run counts come from real run reports only.", color = TF.Muted, fontSize = 13.sp)
        }
        if (skills.isEmpty()) item { Panel { Text("No skills yet. Teach one from Home.", color = TF.Muted) } }
        items(skills, key = { it.id }) { s ->
            Panel {
                Chip(s.intent, TF.Violet)
                Text(s.name, color = TF.Text, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Learned from ${s.demonstrations} demonstration${if (s.demonstrations == 1) "" else "s"} · ${dateTime(s.createdAt)}", color = TF.Muted, fontSize = 13.sp)
                Text("Source app: ${s.targetApp} · Status: ${s.status}", color = TF.Muted, fontSize = 13.sp)
                val last = runs.firstOrNull { it.skillId == s.id }
                Text("Last run: ${last?.let { "${it.finalStatus} · ${dateTime(it.startedAt)}" } ?: "not run yet"}", color = last?.let { statusColor(it) } ?: TF.Dim, fontSize = 13.sp)
                Label("Parameters")
                s.slots.forEach { Text("${it.name}${it.defaultValue?.let { d -> "  (taught: $d)" } ?: ""}", color = TF.Text, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                val actions = s.steps.count { it.kind != StepKind.STOP_AT_BOUNDARY }
                Text("Actions $actions", color = TF.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Safety boundary: ${s.safetyBoundary}", color = TF.Amber, fontSize = 13.sp)
                val runs = s.successCount + s.failureCount + s.handoffCount
                Text(
                    if (runs == 0) "Runs: not run yet" else "Runs $runs · reached payment ${s.successCount} · handed over ${s.handoffCount} · failed ${s.failureCount}",
                    color = TF.Muted, fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedButton(onClick = { expanded = if (expanded == s.id) null else s.id }) { Text(if (expanded == s.id) "Hide" else "Inspect") }
                    OutlinedButton(onClick = {
                        AgentBus.request(AgentRequest.Run(s.id, s.exampleCommands.firstOrNull() ?: s.name))
                        apps.launchFresh(s.targetPackage)
                    }) { Text("Run") }
                    TextButton(onClick = { confirmDelete = s }) { Text("Delete", color = TF.Red) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { transferFor = s }) { Text("Try in another app") }
                    Chip("EXPERIMENTAL", TF.Violet)
                }
                if (expanded == s.id) {
                    s.steps.forEachIndexed { i, st ->
                        Text("${i + 1}. ${st.title}${if (st.optional) " · optional" else ""}", color = TF.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(st.evidence, color = TF.Muted, fontSize = 12.sp)
                    }
                    if (s.discarded.isNotEmpty()) {
                        Label("Discarded during teaching")
                        s.discarded.forEach { Text("✕ ${it.label} · ${it.reason}", color = TF.Amber, fontSize = 12.sp) }
                    }
                    TextButton(onClick = { share(ctx, "TeachFlow skill ${s.name}", SkillJson.toJson(s).toString(2)) }) { Text("Export JSON") }
                }
            }
        }
        item { Text("") }
    }

    transferFor?.let { s ->
        AlertDialog(
            onDismissRequest = { transferFor = null },
            title = { Text("Try \"${s.name}\" in another app") },
            text = {
                Column {
                    Text(
                        "EXPERIMENTAL: TeachFlow grounds the same semantic steps against the other app's screens. " +
                            "It isn't validated, it asks or stops when unsure, and the run doesn't count in this skill's statistics.",
                        color = TF.Muted, fontSize = 13.sp,
                    )
                    LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                        items(apps.apps().filter { it.packageName != s.targetPackage }) { a ->
                            Text(a.label, color = TF.Text, modifier = Modifier.fillMaxWidth().clickable {
                                transferFor = null
                                AgentBus.request(AgentRequest.Run(s.id, s.exampleCommands.firstOrNull() ?: s.name,
                                    transferPackage = a.packageName, transferApp = a.label))
                                apps.launchFresh(a.packageName)
                            }.padding(12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { transferFor = null }) { Text("Cancel") } },
        )
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete skill?") },
            text = { Text(s.name) },
            confirmButton = { TextButton(onClick = { repo.delete(s.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
