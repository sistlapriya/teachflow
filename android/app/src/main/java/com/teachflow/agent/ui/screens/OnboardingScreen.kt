package com.teachflow.agent.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teachflow.agent.ui.theme.TF

@Composable
fun OnboardingScreen(enabled: Boolean, onOpenAccessibility: () -> Unit, onOpenAppInfo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Chip("LIVE PROTOTYPE · ANDROID", TF.Green)
        Text("Welcome to TeachFlow", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = TF.Text)
        Text("Teach Android tasks once. Reuse them through natural language.", color = TF.Muted, fontSize = 17.sp)

        Panel {
            Label("Accessibility access")
            Text(
                "TeachFlow needs Accessibility access to read the buttons, fields and labels of the app on screen, " +
                    "to learn from your demonstration, and to tap and type for you when you replay a skill.",
                color = TF.Text, modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Settings → Accessibility → Installed apps (or Downloaded apps) → TeachFlow → On.",
                color = TF.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onOpenAccessibility, modifier = Modifier.padding(top = 12.dp)) {
                Text(if (enabled) "Accessibility enabled ✓" else "Enable")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Text(
                    "Toggle greyed out as \"Restricted setting\"? Android 13+ blocks this for sideloaded apps. " +
                        "Open App info → ⋮ menu → Allow restricted settings, then try again.",
                    color = TF.Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.padding(top = 8.dp)) { Text("Open App info") }
            }
        }

        Panel(border = TF.Amber.copy(alpha = 0.4f)) {
            Label("Safety")
            Text("TeachFlow never automates:", color = TF.Text, modifier = Modifier.padding(top = 8.dp))
            Text("Password · OTP · PIN · CVV · Card numbers · Payment", color = TF.Amber, modifier = Modifier.padding(top = 4.dp))
            Text(
                "These fields are never recorded, stored or typed. Automation stops at payment and sign-in and hands control back to you.",
                color = TF.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }

        Panel {
            Label("How it works")
            listOf(
                "Say or type a task.",
                "Demonstrate it once in the app.",
                "TeachFlow learns the workflow.",
                "Repeat it naturally later, with new items, quantities or addresses.",
                "TeachFlow adapts to popups and asks when it isn't sure.",
                "It stops when human input is required.",
            ).forEachIndexed { i, s -> Text("${i + 1}. $s", color = TF.Text, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
