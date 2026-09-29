package com.teachflow.agent.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teachflow.agent.accessibility.TeachFlowAccessibilityService
import com.teachflow.agent.core.AgentBus
import com.teachflow.agent.core.AgentState
import com.teachflow.agent.nlu.AppResolver
import com.teachflow.agent.nlu.CommandParser
import com.teachflow.agent.skills.SkillRepository
import com.teachflow.agent.ui.screens.DiagnosticsScreen
import com.teachflow.agent.ui.screens.HistoryScreen
import com.teachflow.agent.ui.screens.HomeScreen
import com.teachflow.agent.ui.screens.OnboardingScreen
import com.teachflow.agent.ui.screens.ReviewScreen
import com.teachflow.agent.ui.screens.SkillsScreen
import com.teachflow.agent.ui.screens.TestsScreen
import com.teachflow.agent.ui.theme.TF
import com.teachflow.agent.ui.theme.TeachFlowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TeachFlowTheme { Root() } }
    }
}

fun isServiceEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
        it.resolveInfo.serviceInfo.packageName == ctx.packageName &&
            it.resolveInfo.serviceInfo.name == TeachFlowAccessibilityService::class.java.name
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(isServiceEnabled(ctx)) }
    LifecycleResumeEffect(Unit) {
        enabled = isServiceEnabled(ctx)
        onPauseOrDispose { }
    }
    val connected by AgentBus.serviceConnected.collectAsStateWithLifecycle()
    val pending by AgentBus.pendingSynthesis.collectAsStateWithLifecycle()
    val apps = remember { AppResolver(ctx) }
    val parser = remember { CommandParser { apps.apps().map { it.label } } }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val openAccessibility = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    val openAppInfo = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) }

    Box(Modifier.fillMaxSize().background(TF.Bg).safeDrawingPadding()) {
        when {
            !enabled -> OnboardingScreen(enabled, openAccessibility, openAppInfo)
            pending != null -> ReviewScreen(
                r = pending!!,
                onSave = { name ->
                    SkillRepository.get(ctx).save(pending!!.skill.copy(name = name))
                    AgentBus.pendingSynthesis.value = null
                    AgentBus.setStatus(AgentState.READY, "Skill saved")
                    tab = 1
                },
                onDiscard = { AgentBus.pendingSynthesis.value = null; AgentBus.setStatus(AgentState.READY, "Ready") },
            )
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> HomeScreen(apps, parser, connected)
                        1 -> SkillsScreen(apps)
                        2 -> HistoryScreen()
                        3 -> DiagnosticsScreen(openAccessibility)
                        else -> TestsScreen()
                    }
                }
                NavigationBar(containerColor = TF.Surface) {
                    listOf("Home", "Skills", "History", "Diagnostics", "Tests").forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("◉", "▤", "↺", "⌁", "✓")[i]) }, label = { Text(label) })
                    }
                }
            }
        }
    }
}
