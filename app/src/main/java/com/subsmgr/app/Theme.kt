package com.subsmgr.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Ok = Color(0xFF16A34A)
val Warn = Color(0xFFD97706)
val Bad = Color(0xFFDC2626)
val Muted = Color(0xFF64748B)

private val Light = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF4F46E5), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF), onSecondaryContainer = Color(0xFF1E1B4B),
    background = Color(0xFFF4F7FA), onBackground = Color(0xFF0F172A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEAF0F5), onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1), outlineVariant = Color(0xFFE2E8F0),
    error = Bad, errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59), onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF818CF8), onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF3730A3), onSecondaryContainer = Color(0xFFE0E7FF),
    background = Color(0xFF0B1220), onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF131C2E), onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1C2740), onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155), outlineVariant = Color(0xFF1E293B),
    error = Color(0xFFF87171), errorContainer = Color(0xFF7F1D1D), onErrorContainer = Color(0xFFFEE2E2),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

/** تفضيل المظهر: system (تلقائي حسب الهاتف) / light (نهاري) / dark (ليلي). يُحفظ على الهاتف ويُطبَّق فوراً. */
object ThemePref {
    var mode by mutableStateOf("system")
        private set

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("voltnet_ui", Context.MODE_PRIVATE)

    fun load(ctx: Context) { mode = sp(ctx).getString("theme", "system") ?: "system" }

    fun set(ctx: Context, m: String) {
        mode = m
        sp(ctx).edit().putString("theme", m).apply()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (ThemePref.mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, content = content)
}
