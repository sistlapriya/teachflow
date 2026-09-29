package com.teachflow.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teachflow.agent.learning.Decision
import com.teachflow.agent.learning.SynthesisResult
import com.teachflow.agent.ui.theme.TF

@Composable
fun ReviewScreen(r: SynthesisResult, onSave: (String) -> Unit, onDiscard: () -> Unit) {
    val skill = r.skill
    var name by rememberSaveable { mutableStateOf(skill.name) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Chip("LEARNED", TF.Green)
        Title("LEARNED \u2713")
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Skill name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("${skill.targetApp} · intent ${skill.intent} · learned from 1 demonstration", color = TF.Muted)

        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column { Text("${r.observed}", fontSize = 26.sp, color = TF.Text); Label("Observed") }
                Column { Text("${r.relevant}", fontSize = 26.sp, color = TF.Green); Label("Relevant") }
                Column { Text("${r.ignored}", fontSize = 26.sp, color = TF.Amber); Label("Ignored") }
            }
            r.stopReason?.let { Text(it, color = TF.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        }

        Panel {
            Label("Parameters")
            skill.slots.forEach { s ->
                Text("${s.name} (${s.type.name.lowercase()}) = ${s.defaultValue ?: "—"}", color = TF.Text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Panel {
            Label("Semantic actions (${skill.steps.size})")
            skill.steps.forEachIndexed { i, st ->
                Text("${i + 1}. ${st.title}${if (st.optional) " · optional" else ""}", color = if (st.kind.name == "STOP_AT_BOUNDARY") TF.Amber else TF.Text,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Text(st.evidence, color = TF.Muted, fontSize = 12.sp)
            }
            Text("Safety boundary: ${skill.safetyBoundary}", color = TF.Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }

        Panel {
            Label("Teaching trace")
            r.judged.forEach { j ->
                val color = when {
                    j.decision == Decision.DISCARD -> TF.Amber
                    j.relevance.name == "UNCERTAIN" -> TF.Violet
                    j.decision == Decision.FOLD -> TF.Cyan
                    else -> TF.Green
                }
                Text("${j.decision} · ${j.rec.action.type} \"${j.rec.action.displayLabel.take(40)}\"", color = color, fontSize = 13.sp,
                    textDecoration = if (j.decision == Decision.DISCARD) TextDecoration.LineThrough else null, modifier = Modifier.padding(top = 8.dp))
                Text("RELEVANCE ${j.relevance} · DECISION ${j.decision}", color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(j.reason, color = TF.Muted, fontSize = 12.sp)
            }
        }

        if (r.warnings.isNotEmpty()) Panel(border = TF.Amber.copy(alpha = 0.5f)) {
            Label("Check before saving")
            r.warnings.forEach { Text("· $it", color = TF.Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSave(name.trim().ifBlank { skill.name }) }) { Text("SAVE SKILL") }
            OutlinedButton(onClick = onDiscard) { Text("Discard") }
        }
    }
}
