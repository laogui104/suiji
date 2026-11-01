package com.suiji.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全部图标 Canvas 现画：不引 material-icons-extended（白给 3MB），
 * 线宽、圆角随尺寸自适应，颜色统一走主题。
 *
 * 线宽统一取 min(尺寸) 的 8%～9%，保证 18dp 与 26dp 看起来是同一套线。
 */

@Composable
private fun iconTint(tint: Color) =
    if (tint == Color.Unspecified) LocalContentColor.current else tint

@Composable
fun MicIcon(modifier: Modifier = Modifier.size(24.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        drawRoundRect(
            color = c,
            topLeft = Offset(w * 0.34f, h * 0.08f),
            size = Size(w * 0.32f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.16f),
            style = Stroke(sw, cap = StrokeCap.Round)
        )
        drawArc(
            color = c,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.26f),
            size = Size(w * 0.60f, h * 0.46f),
            style = Stroke(sw, cap = StrokeCap.Round)
        )
        drawLine(c, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.87f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.34f, h * 0.87f), Offset(w * 0.66f, h * 0.87f), sw, StrokeCap.Round)
    }
}

@Composable
fun SearchIcon(modifier: Modifier = Modifier.size(18.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.1f
        drawCircle(c, radius = w * 0.30f, center = Offset(w * 0.43f, h * 0.43f), style = Stroke(sw))
        drawLine(c, Offset(w * 0.65f, h * 0.65f), Offset(w * 0.88f, h * 0.88f), sw, StrokeCap.Round)
    }
}

/** 设置：齿轮（外齿 + 内圈），比三条滑杆更像"设置" */
@Composable
fun GearIcon(modifier: Modifier = Modifier.size(22.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val cx = w / 2f
        val cy = h / 2f
        drawCircle(c, radius = w * 0.20f, center = Offset(cx, cy), style = Stroke(sw))
        val teeth = 8
        repeat(teeth) { i ->
            val a = Math.toRadians((i * 360.0 / teeth) - 90.0)
            val r1 = w * 0.30f
            val r2 = w * 0.42f
            drawLine(
                color = c,
                start = Offset(cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat()),
                end = Offset(cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat()),
                strokeWidth = sw * 1.35f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun CheckIcon(modifier: Modifier = Modifier.size(16.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.16f
        drawLine(c, Offset(w * 0.16f, h * 0.52f), Offset(w * 0.40f, h * 0.76f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.40f, h * 0.76f), Offset(w * 0.84f, h * 0.22f), sw, StrokeCap.Round)
    }
}

@Composable
fun ClockIcon(modifier: Modifier = Modifier.size(14.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.1f
        drawCircle(c, radius = w * 0.40f, center = Offset(w / 2f, h / 2f), style = Stroke(sw))
        drawLine(c, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.28f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.68f, h * 0.58f), sw, StrokeCap.Round)
    }
}

@Composable
fun SparkIcon(modifier: Modifier = Modifier.size(14.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.1f
        val cx = w / 2f
        val cy = h / 2f
        drawLine(c, Offset(cx, h * 0.06f), Offset(cx, h * 0.94f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.06f, cy), Offset(w * 0.94f, cy), sw, StrokeCap.Round)
        val d = w * 0.19f
        drawLine(c, Offset(cx - d, cy - d), Offset(cx + d, cy + d), sw * 0.75f, StrokeCap.Round)
        drawLine(c, Offset(cx - d, cy + d), Offset(cx + d, cy - d), sw * 0.75f, StrokeCap.Round)
    }
}

@Composable
fun TrashIcon(modifier: Modifier = Modifier.size(16.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.1f
        drawLine(c, Offset(w * 0.16f, h * 0.26f), Offset(w * 0.84f, h * 0.26f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.40f, h * 0.26f), Offset(w * 0.40f, h * 0.15f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.60f, h * 0.26f), Offset(w * 0.60f, h * 0.15f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.40f, h * 0.15f), Offset(w * 0.60f, h * 0.15f), sw, StrokeCap.Round)
        val path = Path().apply {
            moveTo(w * 0.27f, h * 0.28f)
            lineTo(w * 0.33f, h * 0.88f)
            lineTo(w * 0.67f, h * 0.88f)
            lineTo(w * 0.73f, h * 0.28f)
        }
        drawPath(path, c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(c, Offset(w * 0.44f, h * 0.42f), Offset(w * 0.44f, h * 0.74f), sw * 0.8f, StrokeCap.Round)
        drawLine(c, Offset(w * 0.56f, h * 0.42f), Offset(w * 0.56f, h * 0.74f), sw * 0.8f, StrokeCap.Round)
    }
}

@Composable
fun BackIcon(modifier: Modifier = Modifier.size(20.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        drawLine(c, Offset(w * 0.62f, h * 0.16f), Offset(w * 0.30f, h * 0.5f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.30f, h * 0.5f), Offset(w * 0.62f, h * 0.84f), sw, StrokeCap.Round)
    }
}

@Composable
fun CloseIcon(modifier: Modifier = Modifier.size(18.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        drawLine(c, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), sw, StrokeCap.Round)
    }
}

/** 停止：设计稿是白色圆角方块（不用三角） */
@Composable
fun StopIcon(modifier: Modifier = Modifier.size(22.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = c,
            topLeft = Offset(w * 0.28f, h * 0.28f),
            size = Size(w * 0.44f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.12f)
        )
    }
}

@Composable
fun ChevronIcon(modifier: Modifier = Modifier.size(14.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.14f
        drawLine(c, Offset(w * 0.38f, h * 0.20f), Offset(w * 0.62f, h * 0.5f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.62f, h * 0.5f), Offset(w * 0.38f, h * 0.80f), sw, StrokeCap.Round)
    }
}

/** 键盘：表示"改用打字" */
@Composable
fun KeyboardIcon(modifier: Modifier = Modifier.size(20.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.075f
        val left = w * 0.08f
        val right = w * 0.92f
        val top = h * 0.26f
        val bottom = h * 0.74f
        drawRoundRect(
            color = c,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(sw)
        )
        val rows = listOf(0.40f, 0.52f, 0.64f)
        rows.forEachIndexed { ri, y ->
            val count = if (ri == 2) 3 else 4
            val from = if (ri == 2) 1.5f else 1f
            repeat(count) { i ->
                val x = left + (right - left) * ((from + i).toFloat() / (if (ri == 2) 4f else 5f))
                drawCircle(c, radius = w * 0.026f, center = Offset(x, h * y))
            }
        }
    }
}

// ============================================================
// 底部导航：首页 / 灵感 / 我
// ============================================================

@Composable
fun HomeNavIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    filled: Boolean = false
) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val roof = Path().apply {
            moveTo(w * 0.12f, h * 0.46f)
            lineTo(w * 0.5f, h * 0.14f)
            lineTo(w * 0.88f, h * 0.46f)
        }
        drawPath(
            roof, c,
            style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        if (filled) {
            val body = Path().apply {
                moveTo(w * 0.20f, h * 0.44f)
                lineTo(w * 0.80f, h * 0.44f)
                lineTo(w * 0.80f, h * 0.86f)
                lineTo(w * 0.20f, h * 0.86f)
                close()
            }
            drawPath(body, c)
        } else {
            val body = Path().apply {
                moveTo(w * 0.22f, h * 0.42f)
                lineTo(w * 0.22f, h * 0.86f)
                lineTo(w * 0.78f, h * 0.86f)
                lineTo(w * 0.78f, h * 0.42f)
            }
            drawPath(body, c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun BulbIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    filled: Boolean = false
) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        drawCircle(
            color = if (filled) c else Color.Transparent,
            radius = w * 0.26f,
            center = Offset(w * 0.5f, h * 0.40f)
        )
        drawCircle(
            c, radius = w * 0.26f, center = Offset(w * 0.5f, h * 0.40f), style = Stroke(sw)
        )
        // 灯座
        drawLine(c, Offset(w * 0.40f, h * 0.68f), Offset(w * 0.60f, h * 0.68f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.42f, h * 0.78f), Offset(w * 0.58f, h * 0.78f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.46f, h * 0.87f), Offset(w * 0.54f, h * 0.87f), sw, StrokeCap.Round)
        // 两道光
        drawLine(c, Offset(w * 0.50f, h * 0.06f), Offset(w * 0.50f, h * 0.12f), sw * 0.8f, StrokeCap.Round)
        drawLine(c, Offset(w * 0.14f, h * 0.30f), Offset(w * 0.20f, h * 0.36f), sw * 0.8f, StrokeCap.Round)
        drawLine(c, Offset(w * 0.86f, h * 0.30f), Offset(w * 0.80f, h * 0.36f), sw * 0.8f, StrokeCap.Round)
    }
}

@Composable
fun PersonIcon(
    modifier: Modifier = Modifier.size(24.dp),
    tint: Color = Color.Unspecified,
    filled: Boolean = false
) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        drawCircle(
            color = if (filled) c else Color.Transparent,
            radius = w * 0.17f,
            center = Offset(w * 0.5f, h * 0.31f)
        )
        drawCircle(c, radius = w * 0.17f, center = Offset(w * 0.5f, h * 0.31f), style = Stroke(sw))
        val body = Path().apply {
            moveTo(w * 0.18f, h * 0.88f)
            cubicTo(w * 0.20f, h * 0.64f, w * 0.80f, h * 0.64f, w * 0.82f, h * 0.88f)
        }
        drawPath(body, c, style = Stroke(sw, cap = StrokeCap.Round))
        if (filled) {
            val fill = Path().apply {
                moveTo(w * 0.18f, h * 0.86f)
                cubicTo(w * 0.20f, h * 0.66f, w * 0.80f, h * 0.66f, w * 0.82f, h * 0.86f)
                close()
            }
            drawPath(fill, c)
        }
    }
}

// ============================================================
// 状态类小图标
// ============================================================

@Composable
fun BellIcon(modifier: Modifier = Modifier.size(18.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.1f
        val body = Path().apply {
            moveTo(w * 0.22f, h * 0.72f)
            lineTo(w * 0.26f, h * 0.46f)
            cubicTo(w * 0.26f, h * 0.20f, w * 0.74f, h * 0.20f, w * 0.74f, h * 0.46f)
            lineTo(w * 0.78f, h * 0.72f)
            close()
        }
        drawPath(body, c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(c, Offset(w * 0.44f, h * 0.86f), Offset(w * 0.56f, h * 0.86f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.5f, h * 0.10f), Offset(w * 0.5f, h * 0.18f), sw, StrokeCap.Round)
    }
}

@Composable
fun RefreshIcon(modifier: Modifier = Modifier.size(16.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        drawArc(
            color = c,
            startAngle = 40f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.16f),
            size = Size(w * 0.68f, h * 0.68f),
            style = Stroke(sw, cap = StrokeCap.Round)
        )
        // 箭头
        val p = Path().apply {
            moveTo(w * 0.80f, h * 0.16f)
            lineTo(w * 0.86f, h * 0.34f)
            lineTo(w * 0.68f, h * 0.32f)
        }
        drawPath(p, c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun AlertIcon(modifier: Modifier = Modifier.size(16.dp), tint: Color = Color.Unspecified) {
    val c = iconTint(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.1f
        val tri = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.94f, h * 0.86f)
            lineTo(w * 0.06f, h * 0.86f)
            close()
        }
        drawPath(tri, c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(c, Offset(w * 0.5f, h * 0.40f), Offset(w * 0.5f, h * 0.60f), sw, StrokeCap.Round)
        drawCircle(c, radius = sw * 0.6f, center = Offset(w * 0.5f, h * 0.72f))
    }
}

/** 空状态：浅蓝圆 + 麦克风（对应设计稿第 7 屏） */
@Composable
fun EmptyMicArt(size: Dp = 132.dp) {
    val extra = suijiExtra
    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(size)
            .background(extra.todoSoft, CircleShape),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        MicIcon(Modifier.size(size * 0.40f), primary)
    }
}
