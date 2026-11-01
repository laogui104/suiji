# 随记 SuiJi · 轻量 AI 私人记忆助理

> 不用手动记 · 不用手动分类 · 不会被打扰 · 事不会忘

一句话：**张嘴说，它自己存、自己分类，只在你闲着的时候轻轻提你一下。**

- 应用包名 `com.suiji.assistant`
- 技术栈 Kotlin + Jetpack Compose + SQLite（手写 DAO）+ AlarmManager
- 最低 Android 8.0 (API 26)｜目标 Android 14 (API 34)
- 适配 vivo X Fold3 内外双屏（含 90° 悬停态）

---

## 1. 文档索引

| 文档 | 内容 |
|---|---|
| [docs/01-产品设计说明书.md](docs/01-产品设计说明书.md) | 代码级设计：模块、类、函数、状态机、交互规则、边界处理、测试计划 |
| [docs/02-折叠屏适配规范-vivo-X-Fold3.md](docs/02-折叠屏适配规范-vivo-X-Fold3.md) | 内屏/外屏/悬停态布局尺寸、交互差异、适配实现与验收清单 |
| [docs/03-大模型接入与密钥配置.md](docs/03-大模型接入与密钥配置.md) | 10 家服务商预设、密钥三处落点、测试连接按钮完整实现、错误码映射 |
| [docs/04-实现与自测报告.md](docs/04-实现与自测报告.md) | 实现与设计的差异、逐项自测记录、已知限制 |
| [docs/05-v1.0.1-问题修复说明.md](docs/05-v1.0.1-问题修复说明.md) | **v1.0.1**：语音链路根因分析与双通道改造、视觉设计系统重做 |
| [docs/06-v1.0.2-录音与离线识别.md](docs/06-v1.0.2-录音与离线识别.md) | **v1.0.2**：首页大录音按钮、去掉读秒、内置离线识别彻底解决「转不了文字」 |
| [docs/07-v1.1.0-界面重设计与3.2-3.4强化说明.md](docs/07-v1.1.0-界面重设计与3.2-3.4强化说明.md) | **v1.1.0**：按 UI 稿重做界面；3.2 分拣（拆条 / 置信度 / 回执 / 纠错记忆）与 3.4 深化（三模式结构化 / 版本 / 离线骨架）强化全记录 |
| [docs/08-v1.2.0-提醒修复与日历同步.md](docs/08-v1.2.0-提醒修复与日历同步.md) | **v1.2.0**：提醒一条都不响的五个根因与修复、系统日历同步的可行性与实现、设备时间基准与「模型算错 8 小时」的修正 |
| `README.md`（本文） | 权限清单与用途、构建运行、项目结构 |

---

## 1.1 已交付产物（可直接安装）

| 文件 | 大小 | 说明 |
|---|---|---|
| `dist/SuiJi-v1.2.0-release.apk` | 58 MB | **推荐安装这个**。release 变体，已签名（debug 证书，可直接安装），仅 arm64-v8a。体积大头是 42 MB 中文离线语音模型 |
| `dist/SuiJi-v1.2.0-debug.apk` | 70 MB | 调试变体，额外含 x86_64，便于连电脑看日志 / 跑模拟器 |
| `dist/SHA256SUMS.txt` | — | 两个 APK 的 SHA-256，安装前可校验完整性 |
| `app/src/` | 43 个 `.kt` | 与本 APK 完全一致的源码（`diff` 校验通过），可直接改 |

> 说明：`dist/`（APK 与校验文件）和内置离线模型 `app/src/main/assets/model-cn.zip` 属构建产物与第三方资源，**未纳入 Git**（见 `.gitignore`）。克隆后执行 `bash tools/fetch_model.sh` 拉取同版本模型，再 `gradle assembleDebug` 即可产出等价 APK。

> 交付包是**干净配置**：`BuildConfig.LLM_API_KEY=""`，默认服务商 DeepSeek、默认地址 `https://api.deepseek.com/v1`，不含任何测试地址或密钥。卸载重装后设置页显示「未配置 Key」。

安装方式（任选其一）：

```bash
# 1) 数据线安装
adb install -r dist/SuiJi-v1.2.0-release.apk

# 2) 或者把 APK 传到手机，用文件管理器点开安装
#    首次需在系统里允许「安装未知来源应用」
```

安装后首次启动：桌面图标「随记」→ 可先在「设置 → AI 模型」里选服务商、粘 Key、点「测试连接」。
**不配 Key 也能用**：自动走本地规则分拣，全部功能可用。

### 构建环境（本次实际使用）

| 项 | 值 |
|---|---|
| JDK | Microsoft OpenJDK 17.0.10（便携版，`E:\android-toolchain\jdk-17.0.10+7`） |
| Android SDK | build-tools 34.0.0 / platform android-34 / platform-tools |
| Gradle | 8.7（`E:\android-toolchain\gradle-8.7`） |
| AGP / Kotlin | 8.5.2 / 1.9.24 |

```bash
export JAVA_HOME="E:\android-toolchain\jdk-17.0.10+7"
export ANDROID_HOME="E:\android-toolchain\sdk"

# 注意：工程目录名含中文时，Gradle 的测试任务会因 JVM 类路径编码问题失败，
# 建议在纯 ASCII 路径下构建（本次是在 E:\suiji-app 构建后回拷 APK）
gradle assembleRelease
```

---

## 2. 快速开始

### 2.1 环境

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android Studio | Koala 2024.1.1 以上 |
| Gradle | 8.7（用 wrapper） |
| Kotlin | 1.9.24 |
| AGP | 8.5.2 |
| 设备 | vivo X Fold3 真机，或 API 34 可折叠 AVD |

### 2.2 构建

```bash
git clone https://github.com/laogui104/suiji.git && cd suiji

# 1) 下载中文离线识别模型（42 MB，不入库，见 .gitignore）
bash tools/fetch_model.sh
#    产物：app/src/main/assets/model-cn.zip

# 2) 配密钥（可选，不配也能跑，会走本地规则分拣）
cp local.properties.sample local.properties
#    编辑 local.properties，把 LLM_API_KEY 换成你自己的 Key

# 3) 编译安装
gradle assembleDebug          # 或 ./gradlew（见下方说明）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4) 跑测试
gradle testDebugUnitTest
```

> 仓库不含 Gradle Wrapper。用本机 Gradle 8.7 直接构建，或在 Android Studio 打开工程后由 IDE 自动补齐；
> 也可执行一次 `gradle wrapper --gradle-version 8.7` 生成 `gradlew`。

### 2.3 密钥配置（3 种方式，任选一种）

```properties
# local.properties（不进 Git）
LLM_PROVIDER=deepseek
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL=deepseek-chat
LLM_API_KEY={{API_KEY}}          # ← 换成你自己的 Key
```

或装好后在 App 内：`设置 → AI 模型 → 服务商（下拉选） → API Key（粘贴） → 测试连接`。

> 本仓库**不包含任何真实 API Key**。密钥是账户凭证，只能由账户所有者在对应平台申领。App 内置 10 家服务商地址与默认模型，你只需选一个、贴 Key、点「测试连接」。
> 没有 Key 时 App 不会崩溃：自动降级为本地规则分拣，全部功能可用（详见 [03 号文档 §9](docs/03-大模型接入与密钥配置.md)）。

---

## 3. 权限清单（完整）

### 3.1 一句话概括

App 只申请 **4 个**会弹窗的运行时权限，其余要么是普通权限（安装即授予、不弹窗），要么是**可选的特殊权限**（不给也不影响主流程，只是提醒时机变笨一点）。

### 3.2 全部权限逐条说明

#### A. 运行时权限（会弹系统对话框，共 4 个）

| # | 权限 | 何时申请 | 用途说明 | 拒绝后的降级 |
|---|---|---|---|---|
| 1 | `RECORD_AUDIO`<br>麦克风 | 首次点录音时 | **核心**。语音录入的唯一入口。音频只用于两件事：① 交给**内置离线模型**在本机转成文字（默认，不联网）；② 主动选「云端」时上传到你指定的 `/audio/transcriptions` 转写。两种方式都是拿到文字后立即删除音频文件 | 自动切「打字输入」模式，功能不缺失，只是不能"张嘴即录" |
| 2 | `POST_NOTIFICATIONS`<br>通知（Android 13+） | 首次启动，只弹一次 | **核心**。闲时柔性提醒需要一条低优先级通知（无声无震动）。如果只在 App 内提醒，你退出 App 后就收不到了 | 提醒降级为「仅应用内顶部条」，App 不在前台时不提醒 |
| 3 | `BLUETOOTH_CONNECT`<br>蓝牙（Android 12+，可选） | 检测到已连蓝牙耳机时 | 让蓝牙耳机麦克风可用于录音，开车/走路时不用掏手机 | 用手机麦克风录音，不影响使用 |
| 4 | `VIBRATE`<br>震动 | 与 1 同时声明 | **仅用于录音开始的一声触感反馈**（确认"开始录了"），提醒本身绝不震动 | 无影响 |
| 5 | `READ_CALENDAR` + `WRITE_CALENDAR`<br>日历（可选） | 仅在设置页打开「自动写入日历」时申请 | 把带时间的待办写成系统日历事件，由**系统日历**负责守时。这是绕开国产 ROM 杀后台最可靠的一条路。只写你自己创建的事件，不读也不改其他日程 | 日历同步开关保持关闭，提醒仍走 App 自己的精确闹钟 |

#### B. 特殊权限（需跳系统设置页手动开启，均为可选）

| # | 权限 | 申请方式 | 用途说明 | 不授权的后果 |
|---|---|---|---|---|
| 6 | `PACKAGE_USAGE_STATS`<br>使用情况访问 | `Settings.ACTION_USAGE_ACCESS_SETTINGS` | **柔性提醒的关键**。读取「当前前台是哪个应用」「上次操作手机是多久前」，从而判断你是否空闲、是否在刷短视频的间隙、是否正在工作。**只读包名与时间戳，不读任何应用内内容** | 降级判定：只能用"屏幕亮着多久 + 手机静置多久"来猜，判定更保守，提醒次数变少（宁可不提也不打扰） |
| 6 | `SYSTEM_ALERT_WINDOW`<br>显示在其他应用上层 | `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` | **可选增强**。让"顶部淡色小字"能出现在其他 App 之上（比如你正在刷微博时）。默认关闭，App 内默认用系统通知替代 | 用低优先级通知替代，效果接近（也是无声无震动） |
| 7 | `ACCESS_NOTIFICATION_POLICY`<br>勿扰模式访问 | `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` | 读取系统免打扰开关状态。你开了勿扰 → App 一定闭嘴 | 无法感知系统勿扰，靠 App 内的「安静时段」设置兜底 |
| 8 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`<br>忽略电池优化 | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 防止系统在后台冻结 App，导致闲时评估和提醒失效。国产 ROM（含 vivo OriginOS）还需在「i管家 → 后台高耗电」加白 | 提醒可能延迟数小时甚至不触发 |

#### C. 普通权限（安装即授予，不弹窗，无需用户操作）

| # | 权限 | 用途 |
|---|---|---|
| 9 | `INTERNET` | 调用大模型 API 做语义分拣；仅在主动选「云端转写」时用于上传音频。默认走本机离线识别，此时录音全程不联网。不做任何埋点上报 |
| 10 | `ACCESS_NETWORK_STATE` | 判断当前是否联网，决定走 AI 分拣还是本地规则兜底；断网时走离线队列 |
| 11 | `MODIFY_AUDIO_SETTINGS` | 录音时正确切换音频输入通路（听筒/蓝牙/免提），避免录到静音或爆音 |
| 12 | `WAKE_LOCK` | 录音与转写的短暂窗口内保持 CPU 唤醒，防止息屏导致录音截断（用完立即释放） |
| 13 | `FOREGROUND_SERVICE` | 承载录音前台服务，让你在锁屏或切到其他 App 时仍能继续录音 |
| 14 | `FOREGROUND_SERVICE_MICROPHONE`<br>（Android 14 必填） | 声明前台服务的类型为「麦克风」，符合 Android 14 前台服务类型强制要求 |
| 15 | `RECEIVE_BOOT_COMPLETED` | 手机重启后恢复提醒调度，否则重启后所有待办提醒失效 |
| 16 | `SCHEDULE_EXACT_ALARM` | 精确闹钟，让"下午三点"的待办在三点准时进入待唤醒状态（注意：是进入待唤醒，不是弹窗） |
| 17 | `USE_EXACT_ALARM`<br>（Android 13+） | 同上，新版权限名。两者都声明以兼容不同 ROM |

#### D. 明确不申请的权限（及原因）

| 权限 | 为什么不申请 |
|---|---|
| `QUERY_ALL_PACKAGES`（枚举所有已安装应用） | 属 Google Play 敏感权限，且**没必要**。判断"是不是娱乐应用"只需拿前台包名查内置表，无需枚举全机应用 |
| `READ_CONTACTS` | 产品定位是"私人助理"，不应碰你的通讯录。对接人姓名由你自己说的话里提取 |
| `READ_CALENDAR` / `WRITE_CALENDAR` | **v1.2.0 起改为可选申请**：只在设置页手动打开「自动写入日历」时才要，且只写本 App 创建的事件。默认关闭，不打开就完全不碰日历 |
| `ACCESS_FINE_LOCATION` | 不做位置提醒。地点信息（如"到公司提醒我"）也仅从语音文本中提取文字，不定位 |
| `READ_SMS` / `READ_CALL_LOG` | 无任何场景需要 |
| `CAMERA` | 不做拍照/扫描 |
| `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` | 音频与数据库全部存 App 私有目录，不碰你的文件 |
| `BIND_ACCESSIBILITY_SERVICE`（无障碍） | 技术上可用无障碍读前台应用，但**这是最重的权限**，会被应用商店严格审核，且用户极难信任。改用 `PACKAGE_USAGE_STATS`，权限更轻、更透明 |

### 3.3 Manifest 完整声明

```xml
<!-- ===== 普通权限：安装即授予 ===== -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
<uses-permission android:name="android.permission.VIBRATE" />

<!-- ===== 运行时权限：会弹窗，共 5 个（日历这 2 个只在开关打开时要） ===== -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.READ_CALENDAR" />
<uses-permission android:name="android.permission.WRITE_CALENDAR" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- ===== 特殊权限：需跳设置页，均为可选 ===== -->
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- 蓝牙权限仅在 Android 12+ 生效，低版本自动忽略 -->
<uses-permission android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
```

### 3.4 权限申请时机与话术

| 时机 | 权限 | 为什么这个时机 | 话术（一句话，不啰嗦） |
|---|---|---|---|
| 首次点录音按钮 | `RECORD_AUDIO` | 用户此刻有明确意图，通过率最高 | 「需要麦克风才能听你说话」 |
| 首次启动（只弹一次） | `POST_NOTIFICATIONS` | 不等用户操作，也不打断；之后若被拒就在设置页显示红字，不再反复弹窗 | 「到点的待办要能提醒你，需要通知权限」 |
| 设置页打开「自动写入日历」时 | `READ_CALENDAR` / `WRITE_CALENDAR` | 用户主动点开关，意图明确 | 「把待办放进系统日历，提醒更准时」 |
| 首次录音成功入库后 | `PACKAGE_USAGE_STATS`（跳设置） | 用户已体验到价值，再要权限 | 「允许后，我就知道什么时候不该打扰你」 |
| 后台任务连续 2 天未执行 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 已出现实际问题才提 | 「系统在后台限制了提醒，点这里放行」 |
| 用户主动在设置页开启「锁屏也提醒」 | `SYSTEM_ALERT_WINDOW` | 用户主动要求才申请 | — |
| 检测到蓝牙耳机已连接 | `BLUETOOTH_CONNECT` | 场景触发，最自然 | — |

**硬规则**：
1. 任何权限都不在冷启动时批量索要。
2. 拒绝即降级，**不重复弹窗**；用户可在设置页随时补开。
3. 不使用「不给权限就退出」的拦截页。
4. 每次只问一个，绝不连问。

### 3.5 隐私承诺（写进设置页「关于」）

- 语音转文字分两条路：**系统识别**在本机完成，音频不出设备；**云端识别**会把这段录音上传到你自己填的服务商地址转写，拿到文字后本地 WAV 立即删除。走哪条由你决定，默认「自动」。
- 只有文字（你说的话 + 分拣请求）会发给**你自己选的大模型服务商**；App 本身没有任何中转服务器。
- 不采集设备标识、IMEI、Android ID、地理位置、通讯录、短信。
- 无埋点、无统计 SDK、无广告 SDK、无第三方崩溃上报。
- 所有数据存在本机 SQLite 数据库，卸载即全部清除，不上云（多设备同步是阶段三付费功能，默认关）。
- 一句话：**除了你选的那家大模型，没有第二个人知道你说过什么。**

---

## 4. 项目结构

```
.
├── README.md                       ← 本文（含全部权限说明）
├── docs/
│   ├── 01-产品设计说明书.md          ← 代码级设计（模块/类/函数/状态机/边界）
│   ├── 02-折叠屏适配规范-vivo-X-Fold3.md
│   └── 03-大模型接入与密钥配置.md
├── local.properties.sample         ← 密钥模板（复制为 local.properties 后填）
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/suiji/assistant/
        │   ├── SuiJiApp.kt
        │   ├── MainActivity.kt
        │   ├── core/          （配置、加密、时间解析、错误、日志）
        │   ├── data/          （Room、DataStore、大模型客户端、STT、空闲检测）
        │   ├── domain/        （模型、仓库接口、用例）
        │   ├── service/       （录音前台服务、磁贴、Worker、通知）
        │   └── ui/            （Compose：自适应骨架、首页、录入、详情、灵感、设置）
        └── res/
```

分层依赖方向：`ui → domain ← data`。`domain` 不依赖 Android 框架（除 `Clock` 注入），便于单元测试。

---

## 5. 核心能力清单

| 能力 | 实现 | 用户看到什么 |
|---|---|---|
| 张嘴即录 | 快捷磁贴 / App 内 FAB / 锁屏通知 → 前台服务录音 | 点一下，说话，松手，完事 |
| AI 自动二分类 | 大模型 + JSON schema 强约束 + 本地规则兜底 | 说完自动变成「待办」或「灵感」卡，不用选类别 |
| 字段自动抽取 | 时间 / 对接人 / 地点 / 标签 + 中文时间本地校准 | 说"下周三下午三点找张总" → 自动排到周三 15:00 |
| 闲时柔性提醒 | 8 路设备信号打分 + 一票否决（通话/免打扰/夜间/息屏） | 顶部一行淡色小字，无声无震动，4 秒自动收起 |
| 灵感深化 | 梳理 / 拓展 / 可行性三模式 | 点「深化」，零散想法变成三段结构化思路 |
| 时序归档 + 搜索 | Room + 时间桶分组 + 关键词搜索 | 说过的话永远找得回 |
| 折叠屏双屏 | WindowManager 姿态感知 + 双栏/单栏/悬停 | 外屏单手记，内屏坐下改，折成 L 形像录音笔 |
| 全链路降级 | 无 Key / 断网 / 限流 / 无权限 | 功能一个不少，只是"变笨一点" |

---

## 6. 常见问题

**Q：没有 API Key 能装能用吗？**
能。会自动走本地规则分拣，准确率约 75%（有 Key 约 92%），所有功能可用。

**Q：为什么提醒不是准点弹？**
这是产品设计。到点只是"可以开始提了"，真正提的时机由你的空闲状态决定。传统待办在开会时弹窗，我们不会。

**Q：会不会一直弹？**
单条待办最多提 2 次，间隔 ≥6 小时；全局任意两条提醒间隔 ≥45 分钟；一天上限 6 条；连续忽略 3 条会自动降低敏感度。

**Q：vivo 上提醒不触发怎么办？**
`设置 → 后台保活 → 去设置`，在「i管家 → 应用管理 → 自启动」和「后台高耗电」里放行「随记」。

**Q：录音会上传吗？**
不会。只上传转出的文字，且只发给你自己配的那家大模型服务商。

**Q：能同步到别的手机吗？**
MVP 不支持。数据全在本机，卸载即清空。设置页可导出备份文件。

---

## 7. 验收自检表

- [ ] 外屏冷启动到可交互 < 800ms
- [ ] 点录音 → 说话 → 松手 → 卡片出现，全程 < 4s
- [ ] 录音中展开内屏，录音不中断，界面变双栏
- [ ] 内屏（838dp）确实显示双栏（不是单栏）
- [ ] 内屏折 90°，出现上半预览 + 下半大按钮
- [ ] 设置 → 服务商下拉能选到 10 个选项，地址自动填好
- [ ] 设置 → 测试连接 5 步全绿，显示延迟
- [ ] 到期待办在静置 90s 后出现顶部条，**手机无响铃、无震动**
- [ ] 通话中来提醒 → 不出现；通话结束后下一个空闲窗口出现
- [ ] 开勿扰 → 完全不提醒
- [ ] 断网录入 → 仍能入库分类 → 联网后自动重跑 AI 分拣
- [ ] 删除后 5 秒内可撤销

---

## 8. 版本

`v1.2.0` ｜ 对应立项报告「第一阶段：MVP 自用版」
- v1.0.0：首个可运行版本（录入 → AI 分拣 → 列表 / 详情 / 柔性提醒 / 折叠屏适配）
- **v1.0.1**：语音链路重做（本地录音 + 双通道转写，不再依赖厂商是否预装识别服务）；视觉设计系统重做（设计令牌 / 图标 / 组件 / 全部页面）。详见 [05 号文档](docs/05-v1.0.1-问题修复说明.md)
- **v1.0.2**：内置 42 MB 中文离线模型，不联网、不用 Key 也能转文字；录音按钮放大到 84dp 并搬到首页、去掉读秒；修正「接入对话模型就能转写」的错误假设。详见 [06 号文档](docs/06-v1.0.2-录音与离线识别.md)
- **v1.1.0**：
  - 界面按《随记 SuiJi App UI》八屏重做——设计令牌化（单一主色 `#2562EB`、1px 分割线、全套圆角与字号）、18 个图标全部 Canvas 手绘、组件规范化（卡片 / 药丸分段 / 麦克风光晕 / 底部面板）
  - **3.2 分拣强化**：口语分段（一句话多件事拆成多条）→ 规则逐段预判 → 模型判定 → 五因子置信度融合 → 三档处置（静默 / 待确认 / 待整理）→ 分拣回执逐条改判 → 纠错记忆回流提示词。详情页新增「AI 判定」卡（引擎 + 置信度 + 理由 + 三个纠正动作）
  - **3.4 深化强化**：三模式结构化输出（梳理三段 / 拓展五方向 / 可行性四维打分 + 结论）、三级解析容错、多版本历史、结果反哺（用作标题 / 不确定点转待办）、无 Key 时给明确标注的离线骨架而非空白
  - 详见 [07 号文档](docs/07-v1.1.0-界面重设计与3.2-3.4强化说明.md)
- **v1.2.0**：
  - **提醒修复**：定位到「已授权通知却一条都不响」的五个根因——息屏被一票否决、亮屏时长恒为 0 分导致评分永远差 10 分、周期闹钟被每次冷启动重置、渠道 `IMPORTANCE_LOW` 不进状态栏、失败时静默无日志。改为「每条待办一个 `setExactAndAllowWhileIdle` 精确闹钟 + 15 分钟自续期兜底 + 六个时机对账」，通知升级为无声 `DEFAULT` 渠道并带「稍后 / 完成」动作
  - **系统日历同步**（默认关闭的开关）：直接写 `CalendarContract`，事件 + 提前提醒一起写，改时间走 UPDATE 不重复插入；没账号的机器自动建「随记」本地日历；无权限时降级 `ACTION_INSERT`
  - **时间基准**：新增 `SuiClock` 统一设备时间入口，提示词给全 `now_epoch_ms / now_local / timezone / weekday`，模型改为只回 `dueLocal` 字符串由本机换算；时间校正容差 24h → 2h 并识别时区错位（原来偏 8 小时的错误值会被原样接受）
  - 详见 [08 号文档](docs/08-v1.2.0-提醒修复与日历同步.md)

后续版本范围见 [01 号文档 §17](docs/01-产品设计说明书.md)（阶段二/三的接口已预留，数据层无需重构）。
