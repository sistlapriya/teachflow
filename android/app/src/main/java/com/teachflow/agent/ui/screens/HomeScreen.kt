package com.teachflow.agent.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.core.AgentRequest
import com.teachflow.agent.execution.RunReport
import com.teachflow.agent.execution.RunReportStore
import com.teachflow.agent.execution.TerminalReason
import com.teachflow.agent.execution.TraceEvent
import com.teachflow.agent.nlu.AppResolver
import com.teachflow.agent.nlu.CommandParser
import com.teachflow.agent.nlu.LaunchableApp
import com.teachflow.agent.nlu.ParsedCommand
import com.teachflow.agent.skills.MatchOutcome
import com.teachflow.agent.skills.MatchResult
import com.teachflow.agent.skills.SkillMatcher
import com.teachflow.agent.skills.SkillRepository
import com.teachflow.agent.ui.theme.TF

private sealed interface HomeMessage {
    data class Info(val text: String) : HomeMessage
    data class Unknown(val command: String) : HomeMessage
    data class ChooseWorkflow(val command: String, val parsed: ParsedCommand, val options: List<MatchResult>) : HomeMessage
    data class Started(val result: MatchResult) : HomeMessage
}

@Composable
fun HomeScreen(apps: AppResolver, parser: CommandParser, serviceConnected: Boolean) {
    val ctx = LocalContext.current
    val repo = remember { SkillRepository.get(ctx) }
    val runStore = remember { RunReportStore.get(ctx) }
    val skills by repo.skills.collectAsStateWithLifecycle()
    val runs by runStore.runs.collectAsStateWithLifecycle()
    val status by AgentBus.status.collectAsStateWithLifecycle()
    var command by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<HomeMessage?>(null) }
    var pickAppFor by remember { mutableStateOf<String?>(null) }
    val matcher = remember { SkillMatcher() }

    fun speechIntent(prompt: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { command = it }
    }

    fun noOp(cmd: String, reason: TerminalReason, msg: String, extra: List<Pair<String, String>> = emptyList()) {
        val now = System.currentTimeMillis()
        val parsed = parser.parse(cmd)
        val trace = listOf(
            TraceEvent(now, "VOICE RECEIVED", "\"$cmd\""),
            TraceEvent(now, "INTENT PARSED", "${parsed.intentName} · slots ${parsed.slots}"),
        ) + extra.map { (p, m) -> TraceEvent(now, p, m) }
        runStore.add(RunReport.noOp(cmd, reason, msg, trace))
    }

    fun launch(r: MatchResult, cmd: String, overrides: Map<String, String>, notes: List<Pair<String, String>>, interventions: Int) {
        val skill = r.skill
        AgentBus.request(AgentRequest.Run(skill.id, cmd, overrides, notes, interventions))
        message = if (!apps.launchFresh(skill.targetPackage)) HomeMessage.Info("${skill.targetApp} isn't installed or can't be opened.")
        else HomeMessage.Started(r)
    }

    /** Missing or too-general details are asked for mid-run by the executor (Part J), not here. */
    fun proceed(r: MatchResult, cmd: String, notes: List<Pair<String, String>>, interventions: Int) =
        launch(r, cmd, emptyMap(), notes, interventions)

    fun startTeaching(cmd: String, app: LaunchableApp) {
        AgentBus.request(AgentRequest.StartTeaching(cmd, app.packageName, app.label))
        if (!apps.launchFresh(app.packageName)) message = HomeMessage.Info("${app.label} can't be opened.")
    }

    fun onRun() {
        val cmd = command.trim()
        if (cmd.isEmpty()) { message = HomeMessage.Info("Say or type a command first."); return }
        if (!serviceConnected) { message = HomeMessage.Info("The accessibility service isn't connected yet."); return }
        val parsed = parser.parse(cmd)
        when (val out = matcher.match(parsed, skills)) {
            is MatchOutcome.Matched -> proceed(out.result, cmd, emptyList(), 0)
            is MatchOutcome.Ambiguous -> message = HomeMessage.ChooseWorkflow(cmd, parsed, out.options)
            is MatchOutcome.NoMatch -> {
                noOp(cmd, TerminalReason.UNKNOWN_INTENT, "No learned workflow matches this request. Nothing was executed.",
                    listOf("SKILL SEARCH" to "No skill above the match threshold (best %.2f)".format(out.best?.score ?: 0.0)))
                message = HomeMessage.Unknown(cmd)
            }
        }
    }

    fun onTeach(cmdIn: String = command) {
        val cmd = cmdIn.trim()
        if (cmd.isEmpty()) { message = HomeMessage.Info("First say or type the task you're about to demonstrate."); return }
        if (!serviceConnected) { message = HomeMessage.Info("The accessibility service isn't connected yet."); return }
        val app = apps.byLabel(parser.parse(cmd).appLabel)
        if (app == null) pickAppFor = cmd else startTeaching(cmd, app)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("LIVE PROTOTYPE", TF.Green)
            Chip("● ${status.state.label}", stateColor(status.state))
        }
        Title("What would you like to do?")
        OutlinedTextField(
            value = command, onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Order a Margherita pizza from Domino's on Zomato") },
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                try { speech.launch(speechIntent("What should TeachFlow do?")) } catch (e: ActivityNotFoundException) {
                    message = HomeMessage.Info("Speech recognition isn't available on this device. Type the command instead.")
                }
            }) { Text("🎙 Speak") }
            Button(onClick = { onRun() }) { Text("Run") }
            OutlinedButton(onClick = { onTeach() }) { Text("Teach new skill") }
        }

        when (val m = message) {
            is HomeMessage.Info -> Panel { Text(m.text, color = TF.Text) }

            is HomeMessage.Unknown -> Panel(border = TF.Amber.copy(alpha = 0.5f)) {
                Chip("UNKNOWN INTENT", TF.Amber)
                Text("I haven't learned a workflow for this request yet.", color = TF.Text, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
                Text("Nothing was executed (safe no-op).", color = TF.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Button(onClick = { onTeach(m.command) }) { Text("Teach me") }
                    OutlinedButton(onClick = { message = null }) { Text("Cancel") }
                }
            }

            is HomeMessage.ChooseWorkflow -> Panel(border = TF.Cyan.copy(alpha = 0.5f)) {
                Chip("WHICH WORKFLOW?", TF.Cyan)
                val byRestaurant = m.options.all { it.skill.slot("RESTAURANT")?.defaultValue != null }
                Text(if (byRestaurant) "Which restaurant workflow should I use?" else "More than one learned workflow fits. Which one should I use?",
                    color = TF.Text, modifier = Modifier.padding(top = 8.dp))
                m.options.forEach { o ->
                    val distinct = o.skill.slot("RESTAURANT")?.defaultValue ?: o.skill.targetApp
                    OutlinedButton(onClick = {
                        proceed(o, m.command, listOf("AMBIGUOUS INTENT" to "${m.options.size} workflows matched → you chose \"$distinct\""), 1)
                    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column {
                            Text(distinct, fontWeight = FontWeight.SemiBold)
                            Text("${o.skill.name} · ${o.skill.targetApp}", fontSize = 12.sp, color = TF.Muted)
                        }
                    }
                }
                TextButton(onClick = {
                    noOp(m.command, TerminalReason.AMBIGUOUS_INTENT, "Several workflows matched and none was chosen. Nothing was executed.")
                    message = null
                }) { Text("Cancel") }
            }

            is HomeMessage.Started -> Panel {
                Label("Matched skill")
                Text(m.result.skill.name, color = TF.Text, modifier = Modifier.padding(top = 6.dp))
                Text("Match score %.2f (heuristic, not a probability)".format(m.result.score), color = TF.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                m.result.reasons.forEach { Text("· $it", color = TF.Muted, fontSize = 13.sp) }
                Text("Follow progress in the floating TeachFlow panel.", color = TF.Cyan, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            null -> Unit
        }

        if (status.state.name != "READY") {
            Panel(border = (if (status.boundary != null) TF.Amber else stateColor(status.state)).copy(alpha = 0.5f)) {
                Label(if (status.boundary != null) "🛡 Safety boundary" else "Agent")
                Text(status.headline, color = if (status.boundary != null) TF.Amber else TF.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                if (status.detail.isNotBlank()) Text(status.detail, color = TF.Muted, fontSize = 14.sp)
                status.summary?.let { Text(it, color = TF.Dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        }

        runs.firstOrNull()?.let { last -> Panel(border = statusColor(last).copy(alpha = 0.35f)) { RunReportSummary(last) } }

        Panel {
            Label("Skill memory: ${skills.size}")
            if (skills.isEmpty()) Text("None yet. Type a task above and tap Teach new skill.", color = TF.Muted, modifier = Modifier.padding(top = 6.dp))
            skills.take(4).forEach { s ->
                Text("${s.name} · ${s.targetApp}", color = TF.Text, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clickable { command = s.exampleCommands.firstOrNull() ?: s.name }.padding(top = 8.dp))
            }
        }
    }

    pickAppFor?.let { cmd ->
        AlertDialog(
            onDismissRequest = { pickAppFor = null },
            title = { Text("Which app will you demonstrate in?") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps.apps()) { a ->
                        Text(a.label, color = TF.Text, modifier = Modifier.fillMaxWidth().clickable { pickAppFor = null; startTeaching(cmd, a) }.padding(12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickAppFor = null }) { Text("Cancel") } },
        )
    }
}
