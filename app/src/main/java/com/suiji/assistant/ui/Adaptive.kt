package com.suiji.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.catch

/**
 * 折叠姿态。命名以「折痕方向」为准：
 * VERTICAL 表示折痕是竖的（左右折叠）。
 */
enum class FoldPosture { NO_FOLD, FLAT, HALF_OPEN_VERTICAL, HALF_OPEN_HORIZONTAL, SEPARATING }

data class FoldState(
    val posture: FoldPosture = FoldPosture.NO_FOLD,
    val hingeTopPx: Int = 0,
    val hingeBottomPx: Int = 0,
    val hingeWidthPx: Int = 0,
    val hingeHeightPx: Int = 0
)

enum class LayoutMode { SINGLE_PANE, TWO_PANE, TABLETOP }

data class LayoutSpec(
    val mode: LayoutMode,
    val listWidth: Dp,
    val pad: Dp,
    val fold: FoldState
)

fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun rememberFoldState(): FoldState {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() } ?: return FoldState()
    val flow = remember(activity) {
        runCatching {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .catch { emit(WindowLayoutInfo(emptyList())) }
        }.getOrNull()
    } ?: return FoldState()

    val info by flow.collectAsState(initial = null)
    return remember(info) {
        val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
        if (fold == null) {
            FoldState()
        } else {
            val posture = when {
                fold.isSeparating -> FoldPosture.SEPARATING
                fold.state == FoldingFeature.State.HALF_OPENED &&
                        fold.orientation == FoldingFeature.Orientation.VERTICAL -> FoldPosture.HALF_OPEN_VERTICAL
                fold.state == FoldingFeature.State.HALF_OPENED &&
                        fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> FoldPosture.HALF_OPEN_HORIZONTAL
                fold.state == FoldingFeature.State.FLAT -> FoldPosture.FLAT
                else -> FoldPosture.NO_FOLD
            }
            FoldState(
                posture = posture,
                hingeTopPx = fold.bounds.top,
                hingeBottomPx = fold.bounds.bottom,
                hingeWidthPx = fold.bounds.width(),
                hingeHeightPx = fold.bounds.height()
            )
        }
    }
}

/**
 * 布局决策：姿态优先于尺寸。
 * 关键点——vivo X Fold3 内屏折算 838dp，低于 Material3 的 840dp 断点，
 * 只判 Expanded 会导致展开后仍是单栏，因此这里用 700dp + FoldingFeature 兜底。
 */
fun resolveLayout(widthDp: Int, heightDp: Int, fold: FoldState): LayoutSpec {
    if (fold.posture == FoldPosture.HALF_OPEN_HORIZONTAL) {
        return LayoutSpec(LayoutMode.TABLETOP, 0.dp, 24.dp, fold)
    }
    if (fold.posture == FoldPosture.HALF_OPEN_VERTICAL) {
        return LayoutSpec(LayoutMode.TWO_PANE, 320.dp, 24.dp, fold)
    }
    val isUnfolded = fold.posture == FoldPosture.FLAT || fold.posture == FoldPosture.SEPARATING
    // 700dp：X Fold3 内屏为 838dp，远低于 Material3 的 840dp 断点，必须单独兜底
    // 720dp：无 FoldingFeature 上报的大屏（分屏/平板/模拟器）也走双栏
    if ((isUnfolded && widthDp >= 700) || widthDp >= 720) {
        return LayoutSpec(LayoutMode.TWO_PANE, 340.dp, 24.dp, fold)
    }
    val pad = if (widthDp >= 600) 24.dp else 16.dp
    return LayoutSpec(LayoutMode.SINGLE_PANE, 0.dp, pad, fold)
}

@Composable
fun currentLayoutSpec(): LayoutSpec {
    val cfg = LocalConfiguration.current
    val fold = rememberFoldState()
    return remember(cfg.screenWidthDp, cfg.screenHeightDp, fold) {
        resolveLayout(cfg.screenWidthDp, cfg.screenHeightDp, fold)
    }
}
