package com.suiji.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// 设计基调（对齐《随记 SuiJi App UI》设计稿）
//
//  - 底色纯白，容器只用一层极浅的灰（#F0F3F6）撑层次，不画重阴影；
//  - 只有一个主色 #2562EB，所有强调都从它派生；
//  - 圆角克制：卡片 14、按钮 12、标签药丸、面板 20；
//  - 字重只用到 Medium/SemiBold，靠字号与灰度拉层级，不靠加粗。
//
// 这套令牌是这次改版的地基：以前颜色写死、字号从 11 到 24 随手取、
// 圆角 4~16 混用，所以"看着简陋"——问题不在审美，在没有约束。
// ============================================================

object SuiPalette {
    val Primary = Color(0xFF2562EB)
    val PrimaryPressed = Color(0xFF1C4FD0)
    val PrimarySoft = Color(0xFFE8EEFD)
    val PrimarySoft2 = Color(0xFFF2F6FE)

    val Bg = Color(0xFFFFFFFF)
    val SoftBg = Color(0xFFF0F3F6)
    val SoftBg2 = Color(0xFFF6F8FA)
    val Line = Color(0xFFE5E8EB)

    val TextPrimary = Color(0xFF1A1C23)
    val TextSecondary = Color(0xFF99A2AF)

    val Idea = Color(0xFF6F5BD6)
    val IdeaSoft = Color(0xFFEFECFC)
    val Danger = Color(0xFFE5484D)
    val DangerSoft = Color(0xFFFDECEC)
    val Success = Color(0xFF12A150)
}

private val Light = lightColorScheme(
    primary = SuiPalette.Primary,
    onPrimary = Color.White,
    primaryContainer = SuiPalette.PrimarySoft,
    onPrimaryContainer = Color(0xFF10306E),
    secondary = SuiPalette.Idea,
    onSecondary = Color.White,
    secondaryContainer = SuiPalette.IdeaSoft,
    onSecondaryContainer = Color(0xFF2A1E63),
    tertiary = SuiPalette.Danger,
    onTertiary = Color.White,
    background = SuiPalette.Bg,
    onBackground = SuiPalette.TextPrimary,
    surface = SuiPalette.Bg,
    onSurface = SuiPalette.TextPrimary,
    surfaceVariant = SuiPalette.SoftBg,
    onSurfaceVariant = SuiPalette.TextSecondary,
    surfaceContainer = SuiPalette.SoftBg2,
    outline = Color(0xFFC7CCD4),
    outlineVariant = SuiPalette.Line,
    error = SuiPalette.Danger,
    onError = Color.White,
    scrim = Color(0x66000000)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5B8AF7),
    onPrimary = Color(0xFF06173A),
    primaryContainer = Color(0xFF1A2744),
    onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = Color(0xFF9B8BF0),
    onSecondary = Color(0xFF1B1140),
    secondaryContainer = Color(0xFF2A2350),
    onSecondaryContainer = Color(0xFFE3DDFF),
    tertiary = Color(0xFFFF8A8D),
    onTertiary = Color(0xFF3B0A0B),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFEAECF0),
    surface = Color(0xFF14171D),
    onSurface = Color(0xFFEAECF0),
    surfaceVariant = Color(0xFF1C2027),
    onSurfaceVariant = Color(0xFF8A93A2),
    surfaceContainer = Color(0xFF191D24),
    outline = Color(0xFF3A414C),
    outlineVariant = Color(0xFF232830),
    error = Color(0xFFFF8A8D),
    onError = Color(0xFF3B0A0B),
    scrim = Color(0x99000000)
)

/** Material3 没有的语义色，随主题切换 */
data class SuiExtra(
    val todo: Color,
    val todoSoft: Color,
    val idea: Color,
    val ideaSoft: Color,
    val success: Color,
    val danger: Color,
    val dangerSoft: Color,
    val line: Color,
    val softBg: Color,
    val textPrimary: Color,
    val textSecondary: Color
)

private val LightExtra = SuiExtra(
    todo = SuiPalette.Primary,
    todoSoft = SuiPalette.PrimarySoft,
    idea = SuiPalette.Idea,
    ideaSoft = SuiPalette.IdeaSoft,
    success = SuiPalette.Success,
    danger = SuiPalette.Danger,
    dangerSoft = SuiPalette.DangerSoft,
    line = SuiPalette.Line,
    softBg = SuiPalette.SoftBg,
    textPrimary = SuiPalette.TextPrimary,
    textSecondary = SuiPalette.TextSecondary
)

private val DarkExtra = SuiExtra(
    todo = Color(0xFF5B8AF7),
    todoSoft = Color(0xFF1A2744),
    idea = Color(0xFF9B8BF0),
    ideaSoft = Color(0xFF2A2350),
    success = Color(0xFF3DD68C),
    danger = Color(0xFFFF8A8D),
    dangerSoft = Color(0xFF3A1B1D),
    line = Color(0xFF232830),
    softBg = Color(0xFF1C2027),
    textPrimary = Color(0xFFEAECF0),
    textSecondary = Color(0xFF8A93A2)
)

val LocalSuiExtra = staticCompositionLocalOf { LightExtra }

private val SuiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

private val SuiTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 30.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun SuiJiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSuiExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = SuiTypography,
            shapes = SuiShapes,
            content = content
        )
    }
}

/** 便捷取语义色 */
val suijiExtra: SuiExtra
    @Composable get() = LocalSuiExtra.current

/** 页面统一水平内边距 */
val PagePad = 20.dp
