# 折叠屏适配规范 · vivo X Fold3（内外双屏）

> 配套：[01-产品设计说明书](./01-产品设计说明书.md)｜[03-大模型接入](./03-大模型接入与密钥配置.md)
> 适用机型：vivo X Fold3 / X Fold3 Pro（同一套屏幕参数）｜适配框架：Jetpack WindowManager 1.3.0 + Compose Material3
> 文档版本：v1.0

---

## 1. 设备屏幕参数与换算

### 1.1 硬参数

| 项 | 外屏（Folded） | 内屏（Unfolded） |
|---|---|---|
| 尺寸 | 6.53 英寸 | 8.03 英寸 |
| 分辨率（竖持） | 1172 × 2748 px | 2200 × 2480 px |
| 长宽比 | 约 2.34 : 1（细长） | 约 1.13 : 1（近方） |
| 刷新率 | 120Hz LTPO | 120Hz LTPO |
| 折算 PPI（对角） | ≈ 457 | ≈ 413 |
| Android density 档 | 440 dpi → `density = 2.75` | 420 dpi → `density = 2.625` |
| **折算宽 × 高（dp）** | **≈ 426 × 999 dp** | **≈ 838 × 945 dp** |
| WindowWidthSizeClass | Compact（<600dp） | **Medium（600–839dp）或 Expanded（≥840dp）← 见 §3 陷阱** |

> 换算公式：`dp = px / (dpi / 160)`。上表 dp 为理论值，**实际运行值必须以 `LocalDensity` / `WindowMetrics` 实测为准**，代码中禁止出现 `1172`、`2748`、`2200`、`2480` 这类硬编码像素值，也禁止出现 `if (widthDp > 400)` 这种魔法数。

### 1.2 设计基线（UI 稿尺寸）

| 屏 | 画板尺寸 | 安全区 | 设计栅格 |
|---|---|---|---|
| 外屏 | 412 × 915 dp（主流取值，兼容 426dp） | 顶部状态栏 28dp、底部手势条 24dp、左右各 16dp | 4 列，列间距 12dp，边距 16dp |
| 内屏 | 838 × 945 dp | 同上，但左右边距用 24dp | 12 列，列间距 16dp，边距 24dp；中央留 24dp「折痕安全带」 |

**折痕安全带**：内屏中央有一条可见折痕，任何**居中、跨中线的横排内容**（如居中标题、跨栏按钮、分割线）都会被折痕破坏观感。规则：中央 ±12dp 范围内不放文字、不放大按钮、不放横向细线。双栏布局天然规避（中线正好是栏间距）。

---

## 2. 适配架构

### 2.1 双信号输入

布局决策必须同时读两类信号，缺一不可：

| 信号 | API | 回答什么问题 |
|---|---|---|
| 窗口尺寸 | `WindowSizeClass`（`calculateWindowSizeClass`） | 我能用多大空间 |
| 折叠姿态 | `WindowInfoTracker.windowLayoutInfo` → `FoldingFeature` | 物理形态是什么（展开/折叠/悬停/分隔） |

只看尺寸会错（§3 的 838 陷阱），只看姿态也不够（分屏/自由窗口下无 FoldingFeature 但仍是大屏）。

### 2.2 姿态模型

```kotlin
// ui/adaptive/FoldPosture.kt

enum class FoldPosture {
    NO_FOLD,                  // 单屏设备或无法判定
    FLAT,                     // 完全展开，屏幕连续
    HALF_OPEN_VERTICAL,       // 悬停：折痕竖直（像书）
    HALF_OPEN_HORIZONTAL,     // 悬停：折痕水平（像笔记本）
    SEPARATING                // 应用跨两个物理屏，中间有真实间隙（多用于分屏场景）
}

data class FoldState(
    val posture: FoldPosture,
    val hingeBounds: Rect?,   // 折痕/铰链在窗口坐标中的位置，用于避让
    val isSeparating: Boolean,
    val occlusionType: FoldingFeature.OcclusionType
)
```

### 2.3 姿态监听实现

```kotlin
@Composable
fun rememberFoldPosture(): State<FoldState> {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val initial = FoldState(FoldPosture.NO_FOLD, null, false, FoldingFeature.OcclusionType.NONE)

    return produceState(initial, activity) {
        val tracker = WindowInfoTracker.getOrCreate(activity)
        tracker.windowLayoutInfo(activity).collect { info ->
            val fold = info.displayFeatures
                .filterIsInstance<FoldingFeature>()
                .firstOrNull()

            val posture = when {
                fold == null -> FoldPosture.NO_FOLD
                fold.isSeparating -> FoldPosture.SEPARATING
                fold.state == FoldingFeature.State.HALF_OPENED &&
                    fold.orientation == FoldingFeature.Orientation.VERTICAL -> FoldPosture.HALF_OPEN_VERTICAL
                fold.state == FoldingFeature.State.HALF_OPENED &&
                    fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> FoldPosture.HALF_OPEN_HORIZONTAL
                fold.state == FoldingFeature.State.FLAT -> FoldPosture.FLAT
                else -> FoldPosture.NO_FOLD
            }

            value = FoldState(
                posture = posture,
                hingeBounds = fold?.bounds,
                isSeparating = fold?.isSeparating ?: false,
                occlusionType = fold?.occlusionType ?: FoldingFeature.OcclusionType.NONE
            )
        }
    }.let { remember { it } }
}
```

> `FoldingFeature.Orientation.VERTICAL` 表示折痕是**竖的**（左右折叠），此时上下分区不可用、左右双栏可用。命名容易反直觉，务必以「折痕方向」而非「折叠方向」理解。

### 2.4 布局决策函数

```kotlin
// ui/adaptive/Breakpoints.kt

enum class LayoutMode { SINGLE_PANE, TWO_PANE, TABLETOP }

data class LayoutSpec(
    val mode: LayoutMode,
    val listPaneWidthDp: Dp,
    val detailPaneWidthDp: Dp,
    val horizontalPadding: Dp,
    val gutterDp: Dp,
    val captureAsFullSheet: Boolean
)

fun resolveLayout(widthDp: Int, heightDp: Int, fold: FoldState): LayoutSpec {
    // 1) 悬停（笔记本形态）最优先：物理形态决定交互，尺寸判断会失效
    if (fold.posture == FoldPosture.HALF_OPEN_HORIZONTAL) {
        return LayoutSpec(LayoutMode.TABLETOP, 0.dp, 0.dp, 24.dp, 0.dp, captureAsFullSheet = false)
    }
    // 2) 折痕竖直的悬停：左右分区，等价双栏但间距更大
    if (fold.posture == FoldPosture.HALF_OPEN_VERTICAL) {
        return LayoutSpec(LayoutMode.TWO_PANE, 320.dp, 0.dp, 24.dp, 32.dp, captureAsFullSheet = false)
    }
    // 3) 展开平板态：用 FoldingFeature 兜底，绕开 838dp 陷阱
    val isUnfolded = fold.posture == FoldPosture.FLAT || fold.isSeparating
    if (isUnfolded && widthDp >= 700) {
        return LayoutSpec(LayoutMode.TWO_PANE, 340.dp, 0.dp, 24.dp, 1.dp, captureAsFullSheet = false)
    }
    // 4) 仅按尺寸的大屏（分屏 / 自由窗口 / 其他平板）
    if (widthDp >= 840) {
        return LayoutSpec(LayoutMode.TWO_PANE, 340.dp, 0.dp, 24.dp, 1.dp, captureAsFullSheet = false)
    }
    // 5) 默认单栏（外屏、竖持内屏的分屏小窗）
    val pad = if (widthDp >= 600) 24.dp else 16.dp
    return LayoutSpec(LayoutMode.SINGLE_PANE, 0.dp, 0.dp, pad, 0.dp, captureAsFullSheet = true)
}
```

**优先级说明**：姿态 > 尺寸。手写这条优先级的原因是——折叠屏的物理形态是事实，尺寸只是结果；先信事实。

---

## 3. 关键陷阱：838dp 差 2dp

### 3.1 问题

内屏理论宽度 `2200px / 2.625 = 838.09dp`。Material3 的 `WindowWidthSizeClass` 断点是：

```
Compact   : 0   .. 599
Medium    : 600 .. 839
Expanded  : 840 .. ∞
```

**838 < 840**。如果代码写：

```kotlin
if (windowSize.widthSizeClass == WindowWidthSizeClass.Expanded) { TwoPane() } else { SinglePane() }
```

那么 vivo X Fold3 展开后**永远进不了双栏**——用户买了 8 英寸大屏，看到的却和外面 6.5 英寸一模一样。这是折叠屏适配最典型的翻车点。

### 3.2 三种解法（按推荐度排序）

| 方案 | 做法 | 评价 |
|---|---|---|
| **A. 姿态优先**（推荐） | `FoldingFeature.state == FLAT && widthDp >= 700` → 双栏 | 语义正确，不受 dpi 抖动影响 |
| B. 自定义断点 | 用 700dp 替代 840dp 作为双栏阈值 | 简单，但对 700–839dp 的小平板也会双栏（通常可接受） |
| C. 双条件或 | `Expanded \|\| (isUnfolded && width >= 700)` | 兼容性好，代码稍啰嗦 |

采用 A + B 组合（见 §2.4 第 3、4 条），并在此处留一条单元测试：

```kotlin
@Test
fun `838dp unfolded device must be two pane`() {
    val spec = resolveLayout(widthDp = 838, heightDp = 945,
        fold = FoldState(FoldPosture.FLAT, Rect(0,1099,2200,1121), false, FoldingFeature.OcclusionType.NONE))
    assertEquals(LayoutMode.TWO_PANE, spec.mode)
}

@Test
fun `417dp outer screen must be single pane`() {
    val spec = resolveLayout(widthDp = 417, heightDp = 915, fold = FoldState(FoldPosture.NO_FOLD, null, false, FoldingFeature.OcclusionType.NONE))
    assertEquals(LayoutMode.SINGLE_PANE, spec.mode)
}
```

### 3.3 其他机型的兜底

设备实际 density 可能被厂商微调（例如报 440dpi 则内屏为 800dp，报 400dpi 则为 880dp）。因此**绝不能只写 838 一个判断**，必须同时满足「姿态为展开」这一条。8192 种组合里唯一可靠的锚点是 `FoldingFeature`。

---

## 4. 外屏布局规范（Compact · 单栏）

### 4.1 场景定位

外屏是**单手、碎片时间、站立走动**的场景。核心动作只有一个：**说一句话把它记下来**。任何需要双手或需要阅读长文的内容都不该出现在外屏。

### 4.2 尺寸与结构

```
可用区域：426 × 999 dp（设计基线 412 × 915 dp）
├─ 状态栏（系统）：28 dp
├─ 顶栏：56 dp ─── 左"随记" 20sp/Medium，右"全部|待办|灵感" 13sp，最右齿轮 24dp
├─ 内容区：flex ─── LazyColumn，item 间距 8dp，左右 padding 16dp
├─ 录音 FAB：64 dp 圆，右下，right=20dp / bottom=24dp（叠加在内容之上）
└─ 手势条（系统）：24 dp
```

### 4.3 布局规则

| 元素 | 外屏规格 |
|---|---|
| 卡片 | 宽 = 屏宽 - 32dp，高 ≥64dp，圆角 12dp，内边距 16dp |
| 卡片标题 | 16sp，`fontWeight 500`，最多 2 行，`ellipsis` |
| 卡片副信息 | 12sp，`onSurfaceVariant` 60% alpha，单行 |
| 分组标题 | 12sp，`letterSpacing 0.5sp`，上间距 20dp 下间距 8dp |
| FAB | 64dp（不是标准 56dp，外屏单手拇指可达性更重要） |
| 顶栏标题 | 20sp，只在滚动到顶时显示；下滚渐隐为 0 |
| 列表密度 | 一屏约 8–9 张卡；单卡信息量严格控制在「标题 + 一行时间/标记」 |

### 4.4 外屏交互差异

| 交互 | 外屏做法 | 原因 |
|---|---|---|
| 录音入口 | FAB 单击 → 全屏 sheet；支持长按直接开始（不松手模式） | 单手拇指可达右下角；全屏避免误触列表 |
| 录音界面 | 全屏 sheet，`skipPartiallyExpanded`，高度 100% | 426dp 宽塞不下"列表 + 录音"双区 |
| 详情查看 | 整页跳转（`NavHost` push） | 单栏无并置空间 |
| 灵感深化 | 底部 sheet，90% 高度，可下拉关闭 | 保持上下文可见 |
| 返回 | 系统返回 / 左上箭头；sheet 内为下拉手势 | — |
| 键盘 | 文字输入时 sheet 顶到键盘上沿，内容区 `imePadding()` | — |
| 双击顶栏 | 滚到顶部 | 长列表快速归位 |

---

## 5. 内屏布局规范（Expanded · 双栏）

### 5.1 场景定位

内屏是**坐下来、双手、有 2 分钟以上**的场景。此时用户愿意阅读、愿意整理、愿意让 AI 深化。信息密度可以翻倍，但**入口依然只有一个**。

### 5.2 尺寸与结构

```
可用区域：838 × 945 dp
├─ 状态栏：28 dp
├─ 顶栏：64 dp（比外屏高 8dp，大屏留白更从容）
├─ 内容区（Row）：
│   ├─ 左栏 340 dp ── 时间流列表（与首页一致，只是卡片内边距 20dp）
│   ├─ 分割线 1 dp ── 仅在 hover/focus 时着色
│   └─ 右栏 flex（≈473 dp）── 详情 / 录音面板 / 灵感深化 / 设置
└─ 手势条：24 dp
```

### 5.3 右栏四种形态

| 触发 | 右栏内容 | 左栏状态 |
|---|---|---|
| 未选中任何项 | 空态：居中一行灰字「选一条看看，或者右下角说一句」+ 录音按钮 | 正常，首项不高亮 |
| 点列表某项 | 详情：标题 22sp、原文 15sp、AI 分拣结果（待办时间/对接人/标签 chips） | 选中项左侧 3dp 竖条 + 8% 背景 |
| 点录音 | 录音面板：波形在上、实时转写居中、底部大按钮；完成后原地变成「分拣结果预览」，可编辑再保存 | 保持可见、可滚动 |
| 点灵感「深化」 | 详情 + 深化结果同屏：上半原文、下半 AI 输出，中间 1dp 分割 | 保持 |

### 5.4 内屏专属增强（外屏没有的）

1. **分拣结果可视化**：录音完成后，右栏把 `ClassifyResult` 渲染成结构化的 4 行（类型 / 时间 / 对接人 / 标签），每行可点改。这是大屏才有的「可校对」体验——外屏只有一句 Toast。
2. **灵感对比视图**（阶段二）：原文与 AI 深化左右并排（各 ~230dp）。
3. **批量整理**：左栏支持长按多选，右栏变为「批量操作」（批量归类 / 批量删除）。外屏不做多选。
4. **搜索**：顶栏中间出现搜索框（宽 320dp），边输边筛左栏，右栏保持当前详情。外屏搜索是下拉覆盖。

### 5.5 双栏交互差异表

| 交互 | 外屏 | 内屏 |
|---|---|---|
| 选中项 | 跳页 | 原地右栏渲染，列表位置不变 |
| 返回键 | 退出当前页 | 先清空右栏选中态，再退出 |
| 录音 | 全屏 sheet | 右栏内嵌面板，左栏不遮挡 |
| 提示条位置 | 顶部通栏 | 顶栏下方通栏（右栏顶部对齐） |
| 滚动 | 单列表 | 双栏独立滚动，滚左栏不影响右栏 |
| 删除 | Snackbar 撤销 | Snackbar 显示在右栏底部（不横跨全宽） |
| 空态 | 居中插画文字 | 右栏空态 + 左栏正常 |

---

## 6. 悬停态（Tabletop）布局

### 6.1 形态

内屏折到 90° 左右横放，形如笔记本：**上半屏朝上、下半屏朝下（做支架）**。

`FoldingFeature.orientation == HORIZONTAL && state == HALF_OPENED`。

### 6.2 分区规则

```
┌────────────────────────────────────┐
│  上半屏（可视线）≈ 470 dp           │
│  ── 内容预览区 ──                   │
│  · 录音时：波形 + 实时转写           │
│  · 浏览时：当前选中卡片详情          │
│  内容垂直居中，水平 padding 32dp     │
├════════════════════════════════════┤  ← 折痕，高约 22dp，不放任何内容
│  下半屏（操作区）≈ 450 dp           │
│  ── 控制区 ──                       │
│  · 录音时：超大结束按钮（120dp 圆）  │
│  · 浏览时：列表（可滚）或快捷操作     │
│  底部 padding 32dp                  │
└────────────────────────────────────┘
```

### 6.3 实现要点

- 用 `fold.hingeBounds` 精确切分，而不是 `height / 2`。铰链 bounds 在窗口坐标系里，`isSeparating = false`（半开时屏幕连续）但仍占据视觉空间。
- 上半区 `Modifier.weight(1f)`，下半区 `weight(1f)`，中间插一个 `Spacer(height = hingeHeight)`；若 `hingeBounds == null` 则退化为 `height * 0.5` 切分。
- 下半区**不做滚动内容**（横放时滑动体验差），只放按钮。列表浏览在悬停态降级为「上半屏显示详情，下半屏显示 3 条最近」。
- 键盘弹出时，悬停态直接禁止文字输入（提示「请展开到平面再输入」），避免键盘占满下半屏。

### 6.4 悬停态的产品价值

这是「张嘴即记」的最佳物理形态：手机立在桌上，上半屏显示你正在说的话，下半屏按住/点按结束——**像对着一个录音笔说话**。建议在首次进入悬停录音时给一次性的极简提示：「把手机折成 L 形，说话更顺手（只提示一次）」。

---

## 7. vivo / OriginOS 专有事项

### 7.1 必须处理的系统行为

| 事项 | 说明 | 处理 |
|---|---|---|
| Activity 重建 | 展开/折叠触发 `onConfigurationChanged`，未声明 `configChanges` 会重建 | Manifest 已声明 6 项（见 01 文档 §2.3） |
| 兼容模式黑边 | `resizeableActivity="false"` 时内屏以 16:9 居中显示，两侧黑边 | 必须为 `true`（已声明） |
| 悬停自动分屏 | OriginOS 对部分应用强制「上半显示、下半控制」 | 我们的实现与之一致，不冲突；但需在 `onConfigurationChanged` 后重新读一次 `WindowLayoutInfo`，避免收到过期 bounds |
| 折痕下方手势条 | 内屏底部有一条拖拽条，会与 FAB 抢触摸 | FAB `bottom` 边距至少 24dp，且用 `navigationBarsPadding()` |
| 后台高耗电限制 | OriginOS 默认对第三方 App 做「智能省电」，WorkManager 周期任务可能被延迟数小时 | 设置页「后台保活」项引导用户去「i管家 → 应用管理 → 自启动/后台高耗电」加白；**不做弹窗强求**，只在检测到连续 2 天任务未执行时于列表顶部出一条建议 |
| 应用分身 / 平行视界 | vivo 无平行视界；分身后数据库隔离，无需特殊处理 | `applicationId` 不变即可 |
| 深色模式 | OriginOS 强制深色对未适配应用会反色 | 提供完整 `darkColorScheme`，`android:forceDarkAllowed="false"` |

### 7.2 性能注意

- 内屏 2200×2480 @120Hz，像素量是外屏的 1.8 倍。滚动列表必须 `LazyColumn` + 稳定 key，禁止在 item 内做 `blur`、`shadow`、`Brush.linearGradient` 动画。
- 录音波形 7 根柱用 `animateFloatAsState` 已足够，**不要**用 `Canvas` 每帧重绘 60 个点。
- 折叠瞬间（`onConfigurationChanged`）不要立即做重计算，加 100ms 防抖，否则会掉帧。

---

## 8. 自适应 Scaffold 完整实现

```kotlin
// ui/adaptive/SuiJiScaffold.kt

@Composable
fun SuiJiScaffold(
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCapture: () -> Unit,
    list: @Composable (Modifier) -> Unit,
    detail: @Composable (Modifier) -> Unit,
    empty: @Composable (Modifier) -> Unit
) {
    val activity = LocalContext.current.findActivity()
    val size = calculateWindowSizeClass(activity)
    val fold by rememberFoldPosture()
    val spec = remember(size.widthSizeClass, size.heightSizeClass, fold) {
        resolveLayout(
            widthDp = size.widthSizeClass.widthDpApprox(),
            heightDp = size.heightSizeClass.heightDpApprox(),
            fold = fold
        )
    }

    when (spec.mode) {
        LayoutMode.SINGLE_PANE -> {
            Scaffold(
                topBar = { SuiJiTopBar(compact = true) },
                floatingActionButton = { CaptureFab(onCapture, size = 64.dp) }
            ) { pad ->
                Box(Modifier.padding(pad)) { list(Modifier.fillMaxSize()) }
            }
            if (selectedId != null) FullScreenDetail(selectedId)
            if (captureVisible) FullScreenCaptureSheet()
        }

        LayoutMode.TWO_PANE -> {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                SuiJiTopBar(compact = false)
                Row(Modifier.weight(1f).padding(horizontal = spec.horizontalPadding)) {
                    Box(Modifier.width(spec.listPaneWidthDp)) { list(Modifier.fillMaxSize()) }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    Box(Modifier.weight(1f).padding(start = 16.dp)) {
                        if (selectedId != null) detail(Modifier.fillMaxSize())
                        else empty(Modifier.fillMaxSize())
                    }
                }
            }
        }

        LayoutMode.TABLETOP -> {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    detail(Modifier.fillMaxWidth().padding(horizontal = 32.dp))
                }
                Spacer(Modifier.height(hingeHeightDp(fold)))
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    TabletopControls()
                }
            }
        }
    }
}

/** WindowSizeClass 只暴露档位，这里用 WindowMetrics 取真实 dp */
@Composable
private fun realWidthDp(): Int {
    val ctx = LocalContext.current
    return with(LocalDensity.current) {
        WindowMetricsCalculator.getOrCreate()
            .computeCurrentWindowMetrics(ctx).bounds.width().toDp().value.toInt()
    }
}
```

> 上例中 `widthSizeClass.widthDpApprox()` 是示意。**实际实现必须用 `WindowMetricsCalculator` 取 `bounds.width()` 再 `.toDp()`**，因为 `WindowSizeClass` 只给档位（Compact/Medium/Expanded）不给具体 dp，而我们恰恰需要具体 dp 来绕开 838 陷阱。

### 8.1 尺寸变化时的状态保持

```kotlin
// 单一 ViewModel，Activity 作用域，不随配置变化重建
val vm: HomeViewModel = hiltViewModel(LocalContext.current.findActivity() as ViewModelStoreOwner)

// 滚动位置用 rememberSaveable + LazyListState 的 saver
val listState = rememberLazyListState()
LaunchedEffect(Unit) { listState.scrollToItem(savedIndex) }
```

折叠时**不重建** `LazyListState`，用户展开后仍在原来位置。这是"连续性"的核心体验指标。

---

## 9. 交互差异汇总（一页看完）

| 维度 | 外屏 426dp | 内屏 838dp | 悬停 90° |
|---|---|---|---|
| 布局 | 单栏 | 双栏 340 + flex | 上下 1:1 |
| 顶栏高 | 56dp | 64dp | 无（内容顶到边） |
| 边距 | 16dp | 24dp | 32dp |
| 录音形态 | 全屏 sheet | 右栏面板 | 上半预览 + 下半按钮 |
| 列表卡片内边距 | 16dp | 20dp | 不显示列表 |
| 录音按钮 | 64dp FAB | 右栏底部通栏 88dp | 120dp 圆 |
| 详情 | 整页 | 右栏 | 上半屏 |
| 提示条 | 顶部通栏 | 顶栏下通栏 | 上半屏顶部 |
| 多选 | 不支持 | 支持 | 不支持 |
| 搜索 | 下拉覆盖 | 顶栏内嵌 | 不支持 |
| 键盘 | 支持 | 支持 | 禁止（提示展开） |
| 单手可达 | 是 | 否（双手） | 桌面放置 |

---

## 10. 测试方法

### 10.1 模拟器

在 Android Studio 里创建自定义 AVD，或直接改分辨率：

```bash
# 外屏
adb shell wm size 1172x2748
adb shell wm density 440

# 内屏（展开）
adb shell wm size 2200x2480
adb shell wm density 420

# 验证当前 dp
adb shell wm size
adb shell wm density

# 查看折叠姿态（需 API 30+ 的可折叠 AVD）
adb shell cmd device_state print
```

### 10.2 用 Compose Testing 模拟折叠

```kotlin
@get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

@Test
fun foldSwitch_keepsCaptureRecording() {
    // 外屏录音
    composeRule.onNodeWithTag("capture_fab").performClick()
    composeRule.onNodeWithTag("record_btn").performClick()
    composeRule.onNodeWithText("正在聆听").assertExists()

    // 模拟切到内屏
    composeRule.activityRule.scenario.onActivity {
        WindowLayoutInfoPublisherRule().override { info ->
            info.copy(displayFeatures = listOf(fakeFoldingFeature(FLAT, VERTICAL)))
        }
    }

    // 断言：录音未中断，右栏出现
    composeRule.onNodeWithText("正在聆听").assertExists()
    composeRule.onNodeWithTag("detail_pane").assertExists()
}
```

### 10.3 真机验收清单（vivo X Fold3）

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 外屏冷启动 | 首屏可交互 ≤800ms，单栏，FAB 在右下不被手势条遮挡 |
| 2 | 外屏录音 → 展开内屏 | 录音不中断；面板从全屏 sheet 变为右栏；已转写文字保留 |
| 3 | 内屏浏览 → 折叠外屏 | 右栏详情收起；列表回到跳转前位置 |
| 4 | 内屏 → 折 90° 悬停 | 上下分区；上半预览、下半大按钮；折痕处无内容被切断 |
| 5 | 悬停态弹出键盘 | 不弹键盘，出现「请展开后输入」提示 |
| 6 | 内屏 838dp | 必须是双栏（回归 840 陷阱） |
| 7 | 内屏中央 | 无文字/按钮被折痕穿过 |
| 8 | 深色模式 | 全界面正确反色，无白块 |
| 9 | 分屏（左随记 + 右微信） | 宽度 ≈419dp → 降级单栏，无溢出 |
| 10 | 旋转（悬停态转横） | 骨架不闪白，1 帧内完成布局切换 |

---

## 11. 常见翻车点速查

| 现象 | 根因 | 修法 |
|---|---|---|
| 内屏展开后仍单栏 | 838dp < 840dp 断点 | §3 姿态优先判定 |
| 展开瞬间录音丢失 | Activity 重建，录音在 UI 层 | 录音放前台 Service；Manifest 加 `configChanges` |
| 内屏两侧黑边 | `resizeableActivity=false` | 改 `true` |
| 折痕压在标题上 | 居中跨中线内容 | 中央 ±12dp 安全带；标题左对齐 |
| FAB 点不到 | 与折痕下方拖拽条重叠 | `navigationBarsPadding()` + bottom ≥24dp |
| 悬停时下半屏被键盘顶掉 | 未禁输入 | 悬停态禁键盘 |
| 展开后列表跳回顶部 | `LazyListState` 随 Composition 重建 | `rememberSaveable` + Activity 作用域 ViewModel |
| 内屏掉帧 | 卡片内有阴影/渐变/模糊 | 去掉 elevation 与 blur，用描边代替 |
| 分屏时布局溢出 | 只判了 Expanded | 加 Compact 兜底与 `widthIn(max=)` |
| 国产 ROM 提醒不触发 | 后台被省电策略限制 | 引导后台白名单（不弹窗） |

---

## 12. 与产品承诺的关系

折叠屏适配不是「加分项」，而是这款产品的**成本项**——每多一套布局，就多一份「打扰用户」的风险。因此适配原则只有一条：

> **屏越大，越克制。**

- 外屏：只做「记」和「扫一眼」，绝不堆信息。
- 内屏：多给的是「校对」和「整理」，不是新功能。
- 悬停：把物理形态变成最自然的录音姿势。

任何为了"利用大屏"而新增的面板、图表、指标卡，都先过 §01 文档 §15.4 的第 6 条禁令：能不能用 sheet 或面板解决？不能再说。
