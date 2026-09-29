package com.teachflow.agent.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teachflow.agent.core.AgentState
import com.teachflow.agent.ui.theme.TF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun stateColor(s: AgentState): Color = when (s) {
    AgentState.READY, AgentState.COMPLETED -> TF.Green
    AgentState.WAITING_FOR_USER -> TF.Amber
    AgentState.BLOCKED -> TF.Red
    AgentState.RECOVERING -> TF.Violet
    else -> TF.Cyan
}

@Composable
fun Chip(text: String, color: Color = TF.Muted) {
    Text(
        text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun Label(text: String) = Text(text.uppercase(), color = TF.Dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)

@Composable
fun Panel(modifier: Modifier = Modifier, border: Color = TF.Line, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .background(TF.Surface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Title(text: String) = Text(text, color = TF.Text, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)

fun time(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t))
fun dateTime(t: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(t))

fun share(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, subject))
}
