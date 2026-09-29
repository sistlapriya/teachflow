package com.teachflow.agent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.execution.RunReportStore
import com.teachflow.agent.skills.SkillRepository
import com.teachflow.agent.ui.theme.TF

@Composable
fun DiagnosticsScreen(onOpenAccessibility: () -> Unit) {
    val ctx = LocalContext.current
    val snap by AgentBus.snapshot.collectAsStateWithLifecycle()
    val actions by AgentBus.actions.collectAsStateWithLifecycle()
    val overlayOn by AgentBus.overlayEnabled.collectAsStateWithLifecycle()
    val connected by AgentBus.serviceConnected.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Int?>(null) }
    val candidates = snap?.semanticCandidates.orEmpty().take(80)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Title("Diagnostics")
            Text("Live accessibility data from the last app you used. Switch to an app, then come back.", color = TF.Muted, fontSize = 13.sp)
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Floating panel", color = TF.Text, modifier = Modifier.weight(1f))
                    Switch(checked = overlayOn, onCheckedChange = { AgentBus.overlayEnabled.value = it })
                }
                Text("Service: ${if (connected) "connected" else "not connected"}", color = if (connected) TF.Green else TF.Amber, fontSize = 13.sp)
                Text("Current package: ${snap?.packageName ?: "—"}", color = TF.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Accessibility nodes: ${snap?.nodes?.size ?: 0}${if (snap?.truncated == true) " (truncated)" else ""} · candidates ${snap?.semanticCandidates?.size ?: 0}",
                    color = TF.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("Fingerprint: ${snap?.fingerprint ?: "—"}", color = TF.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenAccessibility) { Text("Accessibility settings") }
                    TextButton(onClick = { SkillRepository.get(ctx).clear() }) { Text("Reset skills", color = TF.Red) }
                    TextButton(onClick = { RunReportStore.get(ctx).clear() }) { Text("Clear runs", color = TF.Red) }
                }
            }
        }
        item { Label("Semantic candidates") }
        items(candidates, key = { it.nodeId }) { n ->
            val color = when { n.role.sensitive || n.role.safetyBoundary -> TF.Amber; else -> TF.Cyan }
            Panel(modifier = Modifier.clickable { open = if (open == n.nodeId) null else n.nodeId }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip(n.role.name, color); Chip("score %.2f".format(n.roleScore)) }
                Text(n.displayLabel.take(80), color = TF.Text, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "${n.shortClass}${if (n.clickable) " · clickable" else ""}${if (n.editable) " · editable" else ""}${n.resourceIdName?.let { " · #$it" } ?: ""}",
                    color = TF.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
                if (open == n.nodeId) n.roleEvidence.forEach { Text("· $it", color = TF.Muted, fontSize = 12.sp) }
            }
        }
        item { Label("Observed actions (${actions.size})") }
        items(actions.take(60), key = { it.id }) { a ->
            Panel {
                Text("${time(a.timestamp)} ${a.type} · ${a.target?.role ?: "?"}", color = TF.Cyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(a.displayLabel.take(80), color = TF.Text, fontSize = 14.sp)
                a.inputValue?.let { Text("value: $it", color = if (a.redacted) TF.Amber else TF.Muted, fontSize = 12.sp) }
                a.scrollDirection?.let { Text("direction: $it", color = TF.Muted, fontSize = 12.sp) }
                Text("${a.packageName}${if (a.screenAfter != null) " · screen changed" else ""}", color = TF.Dim, fontSize = 11.sp)
            }
        }
        item { Text("") }
    }
}
