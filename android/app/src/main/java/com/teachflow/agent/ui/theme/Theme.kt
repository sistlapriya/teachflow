package com.teachflow.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object TF {
    val Bg = Color(0xFF05070B)
    val Surface = Color(0xFF0B1018)
    val Surface2 = Color(0xFF111824)
    val Line = Color(0xFF1E2A3C)
    val Text = Color(0xFFF4F7FB)
    val Muted = Color(0xFF8A98B0)
    val Dim = Color(0xFF5A6780)
    val Blue = Color(0xFF3B82F6)
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFF43F5E)
}

private val Scheme = darkColorScheme(
    primary = TF.Blue, onPrimary = Color.White,
    secondary = TF.Violet, tertiary = TF.Cyan,
    background = TF.Bg, onBackground = TF.Text,
    surface = TF.Surface, onSurface = TF.Text,
    surfaceVariant = TF.Surface2, onSurfaceVariant = TF.Muted,
    outline = TF.Line, error = TF.Red,
)

@Composable
fun TeachFlowTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)
