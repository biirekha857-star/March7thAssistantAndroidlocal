# 任务开发规范

本文档说明**如何在本项目中新增一个崩铁自动化任务**。

面向想给项目加任务的开发者。所有规则都来自**实机踩坑经验**，不是凭空规定 ——
每条后面都附了真实失败案例，建议读完再动手。

---

## 目录

1. [整体架构](#1-整体架构)
2. [新增任务的完整流程](#2-新增任务的完整流程)
3. [识别策略：优先级与取舍](#3-识别策略优先级与取舍)
4. [禁用事项（血泪教训）](#4-禁用事项血泪教训)
5. [性能要求](#5-性能要求)
6. [错误处理与日志](#6-错误处理与日志)
7. [调试方法](#7-调试方法)
8. [提交前检查清单](#8-提交前检查清单)

---

## 1. 整体架构

### 分层

```
┌─────────────────────────────────────────────┐
│  外壳（App 主进程）                          │
│  Compose UI / 任务链 / 配置面板 / 定时        │
└──────────────────┬──────────────────────────┘
                   │ AIDL (M7aCoreService)
┌──────────────────▼──────────────────────────┐
│  特权服务（:shizuku_service 进程）            │
│  StarRailLocalEngine                         │
│    ├─ StarRailTasks   ← 任务分发            │
│    ├─ ScreenNavigator ← 界面导航 + 识别      │
│    ├─ ShellAutomation ← 截帧 + 触摸注入      │
│    └─ 各具体任务（PowerTask / DailyTask …）  │
└──────────────────┬──────────────────────────┘
                   │ JNI
┌──────────────────▼──────────────────────────┐
│  libbridge.so（C++）                         │
│  虚拟屏采集 / 触摸事件注入                    │
└─────────────────────────────────────────────┘
```

### 关键类

| 类 | 职责 | 位置 |
|---|---|---|
| `StarRailTaskRegistry` | 任务注册表（声明有哪些任务） | `starrail/StarRailTaskRegistry.kt` |
| `StarRailTasks` | 任务分发 + 通用流程 | `starrail/StarRailTasks.kt` |
| `ScreenNavigator` | 界面导航、模板匹配、OCR 封装 | `starrail/engine/ScreenNavigator.kt` |
| `ShellAutomation` | 截帧、点击、滑动、长按 | `starrail/engine/ShellAutomation.kt` |
| `OcrEngine` | PP-OCRv6 文字识别 | `starrail/engine/OcrEngine.kt` |
| `ImageUtils` | 模板匹配、帧归一化 | `starrail/engine/ImageUtils.kt` |

---

## 2. 新增任务的完整流程

假设要新增一个叫 `MyTask` 的任务，需要改 **5 个地方**：

### ① 任务类型枚举

`core/task/CoreTaskType.kt`：

```kotlin
enum class CoreTaskType(val value: String) {
    ...
    MY_TASK("MyTask"),   // ← 加这里
}
```

> ⚠️ **必须加**。外壳是拿这个枚举的字符串调 `AppendTask(type, params)` 的，
> 漏了会导致任务**静默地加不进任务链**，UI 上勾了也白勾。

### ② 任务参数（如果需要配置）

`data/model/MyTaskConfig.kt`：

```kotlin
@Serializable
data class MyTaskConfig(
    @SerialName("taskType")
    val taskType: String = "MyTask",

    /** 配置字段 */
    val someOption: Boolean = true,
) : TaskParamProvider {
    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        val coreType = CoreTaskType.fromValue(taskType)
            ?: run {
                ctx.appendLog(UiText.Dynamic("未知任务类型"), LogLevel.ERROR)
                return emptyList()
            }
        val json = """{"someOption":$someOption}"""
        return listOf(CoreTaskParams(coreType, json))
    }
}
```

> ⚠️ 字段名**不能叫 `type`** —— `TaskParamProvider` 是多态序列化基类，
> kotlinx.serialization 会用 `type` 当判别器。

如果任务不需要配置，直接用 `StarRailTaskConfig("MyTask")`。

### ③ 注册到 TaskTypeInfo

`data/model/TaskTypeInfo.kt`：

```kotlin
MY_TASK(
    taskType = "MyTask",
    nameRes = R.string.task_type_my_task,
    defaultConfig = { MyTaskConfig() },   // 或 StarRailTaskConfig("MyTask")
),
```

同时加字符串资源：

```xml
<!-- res/values/strings.xml -->
<string name="task_type_my_task">我的任务</string>
<string name="panel_task_type_my_task">我的任务</string>

<!-- res/values-en/strings.xml（必须同步，否则 i18n 校验失败） -->
<string name="task_type_my_task">My Task</string>
<string name="panel_task_type_my_task">My Task</string>
```

### ④ 注册到任务注册表

`starrail/StarRailTaskRegistry.kt`：

```kotlin
TaskDef(
    type = "MyTask",
    label = "我的任务",
    source = "tasks/xxx/my_task.py",   // 对应的 PC 版源码路径
    verified = false,                   // 实机验证过再改 true
    needOcr = true,                     // 是否依赖 OCR
),
```

### ⑤ 实现任务 + 挂到分发器

新建 `starrail/MyTask.kt`：

```kotlin
internal class MyTask(
    private val auto: ShellAutomation,
    private val nav: ScreenNavigator,
    private val params: MyTaskParams,
) {
    fun run(): Result {
        // 实现见下文
    }
}
```

在 `StarRailTasks.run()` 的分发器里加分支：

```kotlin
fun run(type: String, params: String = "{}"): TaskResult {
    return when (type) {
        ...
        "MyTask" -> myTask(params)   // ← 加这里
        else -> TaskResult(type, false, "尚未实现")
    }
}
```

并实现 `myTask()`，包一层异常处理：

```kotlin
private fun myTask(params: String): TaskResult {
    val nav = navigator ?: return TaskResult("MyTask", false, "导航器未就绪")

    // 起点统一：先确保在大厅
    if (!ensureMainMenu(nav)) {
        return TaskResult("MyTask", false, "无法回到大厅，任务链中止")
    }

    val parsed = parseMyTaskParams(params)
    return try {
        val r = MyTask(auto, nav, parsed).run()
        TaskResult("MyTask", r.ok, r.detail)
    } catch (e: ScreenNavigator.StopRequested) {
        TaskResult("MyTask", false, "任务已被用户停止")
    } catch (e: Exception) {
        Ln.e("$TAG: MyTask 异常: ${e.message}")
        TaskResult("MyTask", false, "异常: ${e.message}")
    }
}
```

### ⑥ UI 配置面板（如果自定义了 Config）

`presentation/view/panel/ConfigurationPanel.kt`：

```kotlin
// 分发处
is MyTaskConfig -> MyTaskConfigPanel(config = cfg, onConfigChange = onConfigChange)

// 标签处
TaskTypeInfo.MY_TASK -> stringResource(R.string.panel_task_type_my_task)
```

---

## 3. 识别策略：优先级与取舍

**按可靠性从高到低**，能用上面的就别用下面的：

| 优先级 | 方式 | 适用场景 | 可靠性 |
|---|---|---|---|
| **1** | **固定坐标** | 按钮位置固定、外观会变（如不同角色的攻击键） | ⭐⭐⭐⭐⭐ |
| **2** | **区域模板匹配** | 按钮/图标有稳定外观，位置可变 | ⭐⭐⭐⭐ |
| **3** | **区域 OCR** | 需要按文字定位 | ⭐⭐⭐ |
| **4** | 全屏 OCR | 只在没有其他办法时 | ⭐⭐ |

### 为什么固定坐标排第一

有些 UI **位置固定但外观会变**，这时识别反而是负担。

真实案例：**攻击键**

> 崩铁每个角色的攻击键图标都不同（拳头 / 剑 / 法杖…）。
> 模板匹配和 OCR 全部失效。
> 但**位置是所有角色统一的** —— 直接写坐标 `(1594, 820)` 就完事了。
>
> ```kotlin
> // 实测 (1920×1080)：圆形按钮 x 1512~1676、y 750~890
> private const val ATK_BTN_X = 1594
> private const val ATK_BTN_Y = 820
> ```

### 为什么模板匹配优于 OCR

OCR 有两个固有的坑：

**坑 1：拆字**

> 实测日志：
> ```
> "累计消耗120点开拓"@727 | "力"@608
> ```
> 「累计消耗120点开拓力」被拆成两段，第二段还跑到了别的 X 位置。
> 用它做卡片分栏时直接错乱。

**坑 2：误识**

> 实测：「差分宇宙」被认成「**差分字画**」。
> 用户看着有这一项，程序永远匹配不上。

**模板匹配没有这些问题** —— 它比的是像素，不是字符。

### 区域识别必须做

**全屏扫描比区域慢 5~20 倍。**

```kotlin
// ❌ 反例：全屏找手机图标
clickImage("screen/main.png", threshold)      // 约 1.5 秒/次

// ✅ 正例：限定左上角
findInRegion("screen/main.png", threshold, CROP_MENU_ICON)   // 几十毫秒
```

> 真实收益：打开手机菜单这一步，从 **6.5 秒**降到 **0.1 秒**。

**区域怎么定**：从实机截图量。用 `python + PIL` 裁图确认边界，
不要凭感觉估。

---

## 4. 禁用事项（血泪教训）

### ❌ 绝对不要按 BACK 键

```kotlin
// 绝对禁止
auto.pressKey(KeyEvent.KEYCODE_BACK)
```

崩铁在部分界面按 BACK 会弹出**「是否退出游戏」**对话框，
这是最难恢复的状态 —— 点错就会把游戏关掉，前面的进度全丢。

**替代方案**：找界面上的 ✕ 关闭按钮，或用 `nav.closeCurrent()`（它内部已经避开了 BACK）。

### ❌ 不要在错误的位置继续点击

如果一步失败了，不要“继续试试下一个按钮” —— 那会在错误的界面上乱点。

```kotlin
// ❌ 反例
if (!openPageA()) {
    tapSomewhereElse()   // 可能在主界面乱点，触发未知行为
}

// ✅ 正例
if (!openPageA()) {
    note("打不开 A 页面，本项跳过")
    returnToMain()       // 回大厅，给下一步一个干净起点
}
```

### ❌ 不要连续盲操作多个弹层

```kotlin
// ❌ 反例：委托页 → 直接点合成页
claimDispatch()          // 可能弹了「获得物品」还没关
useSynthesizer()         // 点击落在「获得物品」弹窗上 → 全部错位

// ✅ 正例：每步之间回大厅确认
claimDispatch()
returnToMain()
useSynthesizer()
```

> 这是用户明确要求的规则：「每项做完回大厅确认再继续，不然很容易驾崩」。

### ❌ 不要在刚打开菜单时立刻操作

菜单是**展开动画**，判定“出现了”不代表“画完了”。

```kotlin
// ✅ 判到菜单后还要等它稳
if (isAt(Screen.MENU)) {
    sleepInterruptible(MENU_SETTLE_MS)   // 1200ms
    return true
}
```

> 真实案例：菜单刚出现就点「指南」，手机一卡顿点空，
> 整个任务 7 秒后报“打不开每日实训页面”。

### ❌ 不要用 `firstOrNull` 取“最上面那个”

**OCR 返回的框顺序不保证按坐标排列。**

```kotlin
// ❌ 反例
enterCandidates.firstOrNull { ... }        // 可能取到列表中间的

// ✅ 正例：显式按 y 排序
enterCandidates.minByOrNull { it.cy }
```

> 真实案例：饰品提取页要选第一行的「进入」，结果取到了 y=540 的，
> 点错副本 → 传送失败 → 卡死 25 秒。

---

## 5. 性能要求

### 分秒必争的地方

**战斗中/过场动画期间的等待是免费的**，不用优化。
**真正要优化的是「干等」和「重复识别」。**

| 反模式 | 影响 | 改进 |
|---|---|---|
| 全屏模板匹配 | 1.5s/次 | 限定区域 → 50ms |
| 固定 sleep 猜动画时长 | 等太久或等不够 | 轮询判定 + 早退 |
| 同一帧重复 OCR | 白白跑多次 | 一次 OCR 拿全部结果再判断 |
| 每步都回大厅 | 5~7s/次 | **但这是必要的**，见上文 |

### 优化示例：全屏 OCR 改一次拿全部

```kotlin
// ❌ 反例：每个区域每个关键词各跑一次
for (region in regions) {
    for (kw in keywords) {
        clickTextAndVerify(kw, region)   // 每次完整 OCR ≈ 1.5s
    }
}
// 3 区域 × 2 关键词 = 6 次 OCR ≈ 9 秒

// ✅ 正例：一次 OCR 拿全部，离线判断
val all = nav.withFrameOcr { OcrEngine.run(it) }
val hit = all.firstOrNull { it.normalized().contains(keyword) }
```

### 优化示例：轮询代替死等

```kotlin
// ❌ 反例：固定等 3 秒
Thread.sleep(3000)
if (!isReady()) fail()

// ✅ 正例：每 150ms 探一次，看到就走
val deadline = SystemClock.uptimeMillis() + 3000
while (SystemClock.uptimeMillis() < deadline) {
    if (isReady()) return true
    sleepInterruptible(150)
}
```

---

## 6. 错误处理与日志

### 可中断的等待

**所有超过 200ms 的等待都必须可中断**，否则用户点「停止任务」没反应。

```kotlin
// ❌ 反例
Thread.sleep(5000)

// ✅ 正例
sleepInterruptible(5000)   // 内部每 200ms 检查一次取消信号
```

### 日志规范

**用 `Ln.i()` 而不是 `println` / `Log.d`**，引擎日志会被转发到 UI 面板（调试模式下）。

```kotlin
private fun note(msg: String) {
    Ln.i("$TAG: $msg")
    notes += msg          // 同时累积到任务结果
}
```

### 失败要说清楚原因

```kotlin
// ❌ 反例：用户看到"失败"完全不知道发生了什么
RoundOutcome(RoundStatus.FAILED)

// ✅ 正例：说清卡在哪一步
RoundOutcome(RoundStatus.FAILED, detail = "没找到副本类型「侵蚀隧洞」")
```

### 诊断日志

**出错时要留下能定位问题的信息**，不要只报“失败了”。

```kotlin
// 扫描失败时，把 OCR 原文和坐标全打出来
Ln.i("$TAG: 扫描 OCR ${texts.size} 条: " +
    texts.joinToString(" | ") { "\"${it.text}\"@${it.cx}" })

// 超时时导出画面
val path = nav.exportFrame("error")
Ln.e("$TAG: 已导出超时画面 -> $path")
```

---

## 7. 调试方法

### 抓引擎日志

引擎日志写在 logcat，tag 是 `MaaMeow`：

```bash
# 实时监听（推荐：跑任务前先开着）
adb logcat -c
adb logcat -v time | grep -E "MaaMeow|YourTask"

# 导出全部
adb logcat -d > full.log
```

**注意**：`Ln.i()` 只在**调试模式**下转发到 UI 面板，
否则只写 logcat。

### 导出当前画面

```kotlin
val path = nav.exportFrame("debug")   // 不受 MAX_DUMPS 限制
```

文件落在 `/data/local/tmp/maameow/debug/screenshots/`，
用 root 或 adb 取出来看。

### 量坐标

从**实机截图**量，不要猜。用 Python：

```python
from PIL import Image
img = Image.open("screenshot.png").convert("RGB")
crop = img.crop((x1, y1, x2, y2))
crop.resize((crop.width * 4, crop.height * 4), Image.NEAREST).save("zoom.png")
```

放大后目视确认边界，再换算成归一化坐标。

### 截模板

```python
# 只裁按钮本体，不要带背景
img.crop((left, top, right, bottom)).save("assets/.../btn.png")
```

**模板要求**：
- **只含按钮本身**，边框外留 1~2 px 即可
- 不要包含会变化的部分（数字、血条、动画）
- 尺寸建议 40~150 px，太小误匹配、太大怕背景变化

---

## 8. 提交前检查清单

### 功能

- [ ] 实机验证通过（至少跑通一次完整流程）
- [ ] 中途点「停止任务」能正确中止
- [ ] 某项失败时不影响后续任务（不崩整个链）
- [ ] 从任意界面开始都能正确导航

### 性能

- [ ] 没有全屏模板匹配（除非确实必要）
- [ ] 所有等待可中断
- [ ] 没有重复 OCR 同一帧

### 代码

- [ ] `CoreTaskType` 加了枚举（漏了任务加不进链）
- [ ] `TaskTypeInfo` 加了条目
- [ ] `StarRailTaskRegistry` 加了 TaskDef
- [ ] `StarRailTasks` 分发器加了分支
- [ ] 中英字符串都加了（否则 i18n 校验失败）
- [ ] 没用 BACK 键
- [ ] 关键步骤有 `note()` 日志

### 文档

- [ ] README 的「开发进度」表格更新
- [ ] 如果新增了依赖，更新构建章节

---

## 附：常见坑速查

| 现象 | 可能原因 |
|---|---|
| UI 上勾了任务但不执行 | `CoreTaskType` 漏加枚举 |
| 任务日志显示“尚未实现” | `StarRailTasks` 分发器漏加分支 |
| 模板匹配全部失败 | 区域坐标算错（注意归一化 vs 像素） |
| OCR 认不出明明存在的字 | 画面被拉伸 / 文字太小 / 区域裁错 |
| 点击没反应 | 坐标不在按钮热区 / 连续点击被合并成拖动 |
| 卡在某个界面不动 | 判定条件写错，或者没等加载完成 |
| 点了停在错误的界面 | 上一步的弹窗没关掉 |

---

*本规范随项目演进持续更新。有疑问请提 Issue。*
