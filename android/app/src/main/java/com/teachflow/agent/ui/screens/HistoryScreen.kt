package com.teachflow.agent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teachflow.agent.execution.RunReportStore
import com.teachflow.agent.ui.theme.TF

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val store = remember { RunReportStore.get(ctx) }
    val runs by store.runs.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Title("Run history")
            Text("Every run and every safe no-op, with the reason it ended.", color = TF.Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { share(ctx, "TeachFlow run reports", store.exportJson()) }) { Text("Export JSON") }
                TextButton(onClick = { store.clear() }) { Text("Clear", color = TF.Red) }
            }
        }
        if (runs.isEmpty()) item { Panel { Text("No runs yet.", color = TF.Muted) } }
        items(runs, key = { it.id }) { r ->
            Panel(border = statusColor(r).copy(alpha = 0.35f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(r.terminalReason.name, statusColor(r)); Chip(dateTime(r.startedAt))
                }
                Row(Modifier.padding(top = 8.dp)) {}
                RunReportSummary(r)
                if (r.parameters.isNotEmpty()) Text("Parameters: " + r.parameters.entries.joinToString { "${it.key}=${it.value}" },
                    color = TF.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                if (open == r.id) {
                    Row(Modifier.padding(top = 10.dp)) {}
                    TraceList(r.trace)
                    TextButton(onClick = { open = null }) { Text("Hide trace") }
                } else Text("Show execution trace (${r.trace.size} entries)", color = TF.Cyan, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp).clickable { open = r.id })
            }
        }
        item { Text("") }
    }
}
