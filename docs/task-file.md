# 任务文件开发规范

> 适用范围：M7A 自定义任务包（`.m7atask` / `.json`）
> 规范版本：`schemaVersion: 1`（对应 App `versionCode >= 11`）

自定义任务 = **一份 JSON 描述的流程 + 若干模板图**。App 内置执行器按顺序解释这些步骤，
调用真实的手游操作原语（截图 / 模板匹配 / OCR / 点击 / 滑动）。

它不是脚本语言，没有变量作用域、没有函数、不能读文件、不能执行任意代码 ——
只有下面表格里那些动作。这样做的原因很实际：能自由执行代码的任务包，本质就是一个
外挂加载器，账号风险和分发风险都由使用它的普通玩家承担。

---

## 1. 打包格式

### 1.1 两种文件都收

| 扩展名 | 内容 | 什么时候用 |
| --- | --- | --- |
| `.json` | 只有流程，不带图片 | 纯 OCR / 坐标点击的任务 |
| `.m7atask` | ZIP，内含 `task.json` + `assets/*` | 需要模板图（推荐） |

`.m7atask` 的目录结构：

```
my-task.m7atask          ← 就是个 zip，改扩展名即可
├── task.json            ← 必需，必须叫这个名字、必须在根目录
├── assets/
│   ├── entry.png        ← 模板图，命名随意，建议 40~120px 宽
│   └── confirm.png
└── README.md            ← 可选，社区页展示用
```

> 模板图要求：PNG，**从 1920×1080 的画面里裁**，四周留 2~4px 背景，
> 不要裁到贴边 —— 贴边模板在别的手势栏高度下会匹配不上。

### 1.2 导入位置

App：**设置 → 数据 → 导入任务包**。点击后会先弹一页免责声明，**倒计时 8 秒后
「我已阅读并同意」才可点** —— 这一步不能跳过，因为任务文件确实会代替用户在屏幕上
点击。同意后才会打开文件选择器。

导入后出现在「已安装任务包」列表里，可删除。

### 1.3 一个包 = 一个板块

任务列表点「添加任务」，**每装一个包就多一格，格子标题就是包名**，点哪格加哪张卡。

一个任务链里可以同时挂多个包，各自有开关、各自拖顺序、互不影响 —— 这是 1.0.0
前后的重要区别：早期版本所有包挤在一个「自定义任务」节点里，只能二选一，
想做「早上跑日常包、晚上跑周包」根本拆不开。

现在要分时段跑不同包，有两种做法：

| 想要的效果 | 做法 |
| --- | --- |
| 一次跑完 A 和 B（按顺序） | 同一个配置里把两张卡都勾上，拖成你要的顺序 |
| 早上跑 A、晚上跑 B | 建两个「配置」，各自只勾一个包；两条定时策略分别指向它们 |

> ⚠️ **链里是按 `id` 区分板块的**（不是按包名）。两个不同来源的包如果撞了 `id`，
> 会被当成同一个包 —— 后导入的顶掉先导入的，链里也只会剩一张卡。
> 所以 `id` 一定要起成不会撞的（见 2.1）。

---

## 2. task.json 结构

```json
{
  "schemaVersion": 1,
  "id": "com.example.echo",
  "name": "历战余响 · 一键挑战",
  "author": "你的名字",
  "version": "1.0.0",
  "description": "从大厅进入历战余响并挑战一次",
  "minAppVersionCode": 11,
  "params": [
    { "key": "times", "label": "挑战次数", "type": "int", "default": 1, "min": 1, "max": 3 }
  ],
  "steps": [
    { "op": "log", "text": "开始" },
    { "op": "tap", "x": 0.5, "y": 0.9 },
    { "op": "wait", "ms": 1200 }
  ]
}
```

### 2.1 顶层字段

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | int | ✅ | 固定 `1`。将来加原语会升到 2，旧包仍可运行 |
| `id` | string | ✅ | 全局唯一，反向域名或 `作者.任务名`。**同一个 id 再导入 = 覆盖升级**；任务链里也按它区分板块，撞 id 等于两个包共用一个位置 |
| `name` | string | ✅ | 显示名，≤ 24 字 |
| `author` | string | ✅ | 作者署名，≤ 32 字。留空会被拒 |
| `version` | string | ✅ | 任务自己的版本，如 `1.0.0`，用于覆盖时提示 |
| `description` | string | ❌ | 一句话说明，≤ 120 字 |
| `minAppVersionCode` | int | ❌ | 低于它的 App 拒绝导入，默认 `11` |
| `params` | array | ❌ | 用户可调参数，见 2.2 |
| `steps` | array | ✅ | 步骤列表，1 ~ 200 步，见第 3 节 |

### 2.2 params（可选）

声明后 App 会在任务卡片上给一个表单，值通过 `{key}` 在步骤里引用。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `key` | string | 字母/数字/下划线，步骤里写 `"{key}"` |
| `label` | string | 表单上显示的名字 |
| `type` | `int` / `bool` / `string` | 三种 |
| `default` | 与 type 一致 | 默认值 |
| `min` / `max` | int | 仅 `int` 有效 |

引用方式：任何字符串字段里写 `{times}`，执行前替换成用户填的值。

---

## 3. 步骤（steps）

每个步骤是一个对象，`op` 决定它是哪种动作。所有步骤都支持两个公共字段：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `op` | — | 动作名，见下表 |
| `optional` | `false` | `true` 时该步失败**不中断**流程，只记一条警告 |
| `note` | — | 写给人看的注释，日志里会带出来 |

### 3.1 坐标怎么写

一律用 **0 ~ 1 的归一化坐标**（相对 1920×1080 的左上角），不要写像素：

```json
{ "op": "tap", "x": 0.5, "y": 0.916 }     // 屏幕正中偏下 → 1920×1080 下是 (960, 989)
```

App 会按实际帧尺寸换算，所以 21:9、挖孔屏都能对得上。**写像素坐标的包会在别的机型上点空**。

### 3.2 动作表

#### 等待与日志

| op | 字段 | 说明 |
| --- | --- | --- |
| `log` | `text` | 往日志写一行，调试期的进度标记 |
| `wait` | `ms` | 死等，上限 60000。**能不用就不用**，优先用 `waitTemplate` / `waitText` |

#### 点击与滑动

| op | 字段 | 说明 |
| --- | --- | --- |
| `tap` | `x`, `y`, `durationMs?` | 点一下，默认 60ms |
| `longPress` | `x`, `y`, `durationMs?` | 长按，默认 800ms |
| `swipe` | `fromX`, `fromY`, `toX`, `toY`, `durationMs?` | 滑动，默认 320ms |

#### 模板图

| op | 字段 | 说明 |
| --- | --- | --- |
| `waitTemplate` | `image`, `timeoutMs?`, `threshold?`, `region?`, `stable?` | 等到模板出现，默认超时 10000、阈值 0.80 |
| `tapTemplate` | `image`, `timeoutMs?`, `threshold?`, `region?`, `offsetX?`, `offsetY?` | 等到模板出现并点它中心（可加偏移） |
| `assertTemplate` | `image`, `threshold?`, `region?` | 必须在场，不在就判失败（用于流程起点自检） |

- `region`：`[x, y, w, h]`，四元组同样是 0~1，用来缩小搜索面积（**强烈建议填**，
  全屏匹配慢 20 倍以上，而且容易被画面里相同按钮误命中）。
- `threshold`：0.6 ~ 0.95。实机光照差异大，超过 0.90 基本只有像素级一致的模板才过。
- `stable`：连续几帧都命中才算出现，默认 1；动效多的界面设 2 更稳。

#### 文字（OCR）

| op | 字段 | 说明 |
| --- | --- | --- |
| `waitText` | `text`, `timeoutMs?`, `region?` | 等文字出现（包含匹配） |
| `tapText` | `text`, `timeoutMs?`, `region?` | 等文字出现并点它 |
| `assertText` | `text`, `region?` | 必须在场 |

> OCR 只在中文/英文界面可靠。数字识别容易把 `0` 认成 `O`，执行器已做 `O→0`、`l→1` 容错，
> 但**不要用 OCR 读小字号数字做关键判断**（比如层数），宁可数图形。

#### 流程控制

| op | 字段 | 说明 |
| --- | --- | --- |
| `repeat` | `times`, `steps` | 子步骤重复 N 次，N ≤ 20，可嵌套 1 层 |
| `ifTemplate` | `image`, `threshold?`, `region?`, `then`, `else?` | 模板在 → 执行 `then`，否则执行 `else` |
| `ifText` | `text`, `region?`, `then`, `else?` | 同上，条件换成 OCR |

#### 结束

| op | 字段 | 说明 |
| --- | --- | --- |
| `stop` | `ok?` | 结束任务，`ok` 默认 `true`。用于「本层已满星，跳过」这类提前收工 |

#### 颜色与计数

模板匹配对**尺度**敏感：17px 的星和 40px 的星，同一个模板分数会趋近 0。
要数图标（几颗星、几条进度、几个红点），用颜色连通块，尺度无关。

| op | 字段 | 说明 |
| --- | --- | --- |
| `ifCount` | `region`, `color`, `value`, `compare?`, `tolerance?`, `minArea?`, `maxArea?`, `then`, `else?` | 数区域内的颜色块，按条件走分支 |
| `assertCount` | 同上（无 `then`/`else`） | 数量不符合预期就判失败 |
| `waitColor` | `color`, `region?`, `ratio?`, `tolerance?`, `timeoutMs?` | 等区域颜色占比达到 `ratio`（默认 0.02） |

#### 滑到出现

| op | 字段 | 说明 |
| --- | --- | --- |
| `swipeUntil` | `image` 或 `text`, `fromX/fromY/toX/toY`, `maxTries?`, `intervalMs?`, `region?` | 反复滑，直到目标出现 |

- `color`：`#RRGGBB`、`RRGGBB`，或 `R,G,B`（**RGB 顺序**，0~255）。大小写不敏感。
- `tolerance`：单通道容差 0~128，默认 32。数值越大越"宽容"，超过 60 容易把背景也算进去。
- `minArea` / `maxArea`：连通块面积（按 1920×1080 算的像素），默认 80 ~ 4000。
  只想要小图标就把 `maxArea` 调小，想数大色块就调大。
- `compare`：`>=`（默认）`>` `<=` `<` `==` `!=`。
- `waitColor` 的 `ratio`：目标色占区域的面积比。一整块纯色按钮约 0.6~0.9，
  "有一抹高亮"约 0.02~0.05。
- `swipeUntil` 的 `maxTries` 默认 8、上限 30。**有上限是刻意的** —— 无上限的
  "滑到找到为止"在识别失败时会滑满整个 20 分钟。

```json
// 层号下方那片金星，数到 3 颗就认为满星
{
  "op": "ifCount",
  "region": [0.05, 0.29, 0.13, 0.06],
  "color": "#FFC870",
  "minArea": 80,
  "maxArea": 4000,
  "compare": ">=",
  "value": 3,
  "then": [{ "op": "log", "text": "满星，跳过" }, { "op": "stop", "ok": true }]
}
```

#### 变量与表达式

| op | 字段 | 说明 |
| --- | --- | --- |
| `setVar` | `to`, `expr` | 算一个值存进变量 |
| `ifExpr` | `expr`, `then`, `else?` | 表达式为真走 `then` |
| `whileExpr` | `expr`, `steps` | 表达式为真就循环，单次最多 200 轮 |
| `readNumber` | `region`, `to`, `timeoutMs?` | OCR 读区域里的第一个整数，存进变量 |
| `readText` | `region`, `to` | OCR 读区域全部文字（拼成一行），存进变量 |

变量名规则：字母或下划线开头，只能含字母数字下划线，最长 32。

在 `text`、`image` 字段里用 `{变量名}` 取值；先查 `params` 再查运行期变量，
都没命中就保持原样。**坐标字段（`x`/`y`/`region`）不接受占位符**，只写数字。

### 3.3 表达式

表达式只做**整数运算 + 比较 + 布尔**，不是一门通用语言。

| 类别 | 支持 |
| --- | --- |
| 数字 | 十进制整数。没有小数、没有科学计数法 |
| 字符串 | `"..."` 或 `'...'`，只用于 `==` / `!=` 和 `+` 拼接 |
| 运算符 | `+ - * / %`、`> >= < <= == !=`、`&& \|\| !`、括号 |
| 函数 | `min(a,b)`、`max(a,b)`、`abs(a)`、`clamp(v,lo,hi)`、`len(s)` |
| 变量 | 直接写名字（不加花括号） |

```json
{ "op": "setVar", "to": "left", "expr": "total - used" }
{ "op": "ifExpr", "expr": "left > 0 && tries < 10", "then": [ ... ] }
{ "op": "setVar", "to": "safe", "expr": "clamp(left, 0, 6)" }
{ "op": "whileExpr", "expr": "left > 0", "steps": [ { "op": "tap", "x": 0.5, "y": 0.9 }, { "op": "setVar", "to": "left", "expr": "left - 1" } ] }
```

硬限制（不是可调参数）：

- 表达式最长 512 字符、最多 400 个记号、括号嵌套最多 24 层；
- 整数溢出按 Long 回绕，不会抛异常；除以 0 直接判这一步失败；
- **没有对象、没有数组、没有自定义函数、没有 `eval`**。写 `foo.bar()` 只会得到
  「表达式算不出来」，不会执行任何东西。

### 3.4 能力边界（沙箱）

这一节是**承诺**，不是"目前还没实现"。任务文件能做和不能做的事，由执行器的动作
白名单决定，改不了：

| 能力 | 有吗 | 说明 |
| --- | --- | --- |
| 看屏幕（模板 / OCR / 颜色） | ✅ | 只能读画面，读不到游戏内存或包体 |
| 点击、长按、滑动、等待 | ✅ | 归一化坐标，注入的是真实触摸事件 |
| 算术、比较、分支、循环 | ✅ | 见 3.3，循环次数有上限 |
| 读本包的 `assets/` 图片 | ✅ | 路径必须在本包内 |
| 执行任意代码 / 脚本 | ❌ | 没有这种动作，任务文件只是数据 |
| 启动或唤起其它 App | ❌ | 执行器里没有任何 App 启动入口 |
| 执行 shell 命令 | ❌ | 同上 |
| 访问网络 | ❌ | 执行器不持有网络客户端 |
| 读写包外文件、剪贴板、账号信息 | ❌ | 没有对应动作 |

运行期还有三重强制约束（即使绕过导入校验也生效）：

1. **动作白名单**：`op` 不在表里立刻失败；
2. **路径闸**：模板路径必须落在本包 `assets/` 内，`..`/符号链接/绝对路径全部拒绝；
3. **预算闸**：单次执行最多 5000 步、最多 20 分钟，超过强制中止；
   `whileExpr` 单次最多 200 轮。

如果你需要的能力不在这张表里，正确做法是**提需求加原语**，而不是找"绕过"的写法 ——
没有这条路。

### 3.5 失败语义

- 默认：**任何一步失败 = 整个任务失败**，回调报错并带上失败步骤的 `op` 与序号。
- `optional: true`：失败只记 WARN 继续。
- 每一步都有超时上限（默认 10s，可到 120s），不会无限挂住。
- 用户点「停止」时，正在执行的等待会被立刻打断。

---

## 4. 一个完整例子

「从大厅进历战余响、打一次、领奖」：

```json
{
  "schemaVersion": 1,
  "id": "com.example.echo",
  "name": "历战余响 · 一键",
  "author": "example",
  "version": "1.0.0",
  "minAppVersionCode": 11,
  "params": [
    { "key": "times", "label": "挑战次数", "type": "int", "default": 1, "min": 1, "max": 3 }
  ],
  "steps": [
    { "op": "assertTemplate", "image": "assets/lobby.png", "threshold": 0.75,
      "note": "先确认在大厅，不在大厅直接失败，别乱点" },

    { "op": "tapTemplate", "image": "assets/phone.png", "timeoutMs": 5000 },
    { "op": "waitTemplate", "image": "assets/guide.png", "timeoutMs": 8000,
      "region": [0, 0, 1, 0.14] },

    { "op": "tapText", "text": "生存索引", "timeoutMs": 5000, "region": [0, 0.1, 1, 0.2] },
    { "op": "wait", "ms": 900 },

    { "op": "tapTemplate", "image": "assets/echo_entry.png", "timeoutMs": 8000 },
    { "op": "waitTemplate", "image": "assets/echo_title.png", "timeoutMs": 10000 },

    { "op": "repeat", "times": "{times}", "steps": [
        { "op": "tapTemplate", "image": "assets/challenge.png", "timeoutMs": 8000 },
        { "op": "waitTemplate", "image": "assets/auto_battle.png", "timeoutMs": 30000,
          "optional": true, "note": "自动战斗按钮不是每期都有" },
        { "op": "waitText", "text": "返回", "timeoutMs": 240000,
          "note": "结算出现「返回」就算打完，最多等 4 分钟" },
        { "op": "tapText", "text": "返回", "timeoutMs": 8000, "optional": true }
    ] },

    { "op": "tapTemplate", "image": "assets/reward.png", "timeoutMs": 8000, "optional": true },
    { "op": "stop", "ok": true }
  ]
}
```

### 4.1 能直接跑起来的示例

上面的例子需要你自己截图做模板，跑不通很正常。仓库里另有一份**开箱可跑**的：

```
examples/demo-screen-scan/          源文件（task.json + assets/phone.png）
examples/demo-screen-scan-1.0.0.m7atask   打包好的成品，直接导入
```

「示例·画面体检」把原语挨个用一遍：模板识别、数金色块、变量与表达式、`repeat`、
`whileExpr`、OCR 读文字、一个**故意写成必失败**的 `optional` 步骤（用来看跳过日志长什么样），
最后点一下左上角手机图标。整个流程只有那一次点击会动到游戏，其余都是只读。

跑完对照日志看有没有 `❌`：

```
=== 自定义任务链路自检开始 ===
变量 k = 0
变量 total = 20
✅ ③ 表达式引擎正常：(2+3)*4 == 20、len("abc") == 3、clamp(99,0,6) == 6
⚠️ #15 assertCount 跳过：数量不符合预期：数到 0 个，要求 >=50
=== 自检结束，如果没有 ❌ 就说明整条链路正常 ===
```

---

## 5. 校验规则（导入时会被拦下的情况）

导入时 App 会逐条校验，**任何一条不过都不会写入**，并直接告诉你第几步哪里错了：

1. `schemaVersion` 不是 1；
2. `id` / `name` / `author` / `version` 缺失或超长；
3. `minAppVersionCode` 大于当前 App；
4. 步骤数 > 200、`repeat.times` > 20、嵌套超过 4 层；
5. `op` 不在白名单里（含拼写错误，比如 `tapTemplates`）；
6. 坐标不在 `0 ~ 1` 范围；
7. `timeoutMs` > 120000；
8. 引用了 `assets/` 下不存在的图片；
9. 引用了 `params` 里没声明的 `{key}`；
10. 同 `id` 的包已存在 → 提示「升级 / 取消」，不会静默覆盖。

针对识别与表达式新增的检查：

11. `color` 不是 `#RRGGBB` / `RRGGBB` / `R,G,B`，或分量不在 0~255；
12. `compare` 不是 `>= > <= < == !=` 之一；
13. `ifCount` / `assertCount` 缺 `region` 或 `value`；
14. `readNumber` / `readText` 缺 `region`，或 `to` 不是合法变量名；
15. `setVar` 缺 `to`，或 `expr` 语法错（会告诉你错在第几个记号）；
16. `swipeUntil` 既没给 `image` 也没给 `text`，或 `maxTries` 不在 1~30；
17. `whileExpr` 缺 `steps`。

校验器一次最多报 12 条，按出现顺序。

---

## 6. 分发与署名

### 6.1 直接分享文件

- 发布到社区时**只发 `.m7atask` / `.json`**，不要带 `device_id`、账号截图之类的东西。
- 作者署名写进 `author` 字段；App 的「已安装任务包」列表会展示它。
- 不要打包引用游戏本体的资源（角色立绘、剧情截图），只放自己截的按钮模板。
- 任务包**不含任何可执行代码**，因此不涉及 GPL/AGPL 传染；但它调用的 App 本体是
  GPL-3.0，打包分发时请一并遵守。

### 6.2 发布到官方索引（App 内「在线任务包」）

用户在 App 里直接浏览安装的包，来自站点上的一个**索引文件**：

```
https://m7a.pages.dev/taskpacks/index.json          索引（几 KB，人维护）
https://m7a.pages.dev/taskpacks/index.json.sig      索引签名（Base64）
https://m7a.pages.dev/taskpacks/<id>-<ver>.m7atask  包本体
```

索引结构：

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-11-01",
  "packs": [
    {
      "id": "daily-lite",
      "name": "轻量日常",
      "author": "你的名字",
      "version": "1.0.0",
      "description": "只做每日实训 + 清体力，5 分钟跑完",
      "stepCount": 24,
      "url": "https://m7a.pages.dev/taskpacks/daily-lite-1.0.0.m7atask",
      "urlFallback": "https://github.com/<owner>/<repo>/releases/download/taskpacks/daily-lite-1.0.0.m7atask",
      "sha256": "9f2c…（64 位小写十六进制）",
      "size": 284510,
      "minAppVersionCode": 11
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 必须和包内 `task.json` 的 `id` **完全一致**，否则装上了也对不上配置 |
| `name` / `author` / `version` | 是 | 展示用。列表以索引为准，不回读包里的 |
| `url` | 是 | 主下载地址，必须 `https` |
| `urlFallback` | 否 | 主源失败时自动切换（用来兜 Cloudflare 挂掉） |
| `sha256` | **是** | 下载后逐字节校验，对不上拒绝安装 |
| `size` | 建议 | 提前挡掉超大文件；填了就会核对 |
| `minAppVersionCode` | 否 | 低于它的 App 不显示、也不允许装 |

**为什么索引必须签名**

索引决定了「用户手机去下载哪个文件」。它要是没被保护，改一行 `url` 就能让所有人的
App 装上攻击者准备好的包 —— 那是远程接管，不是分发。

所以 App 内置一把公钥（ECDSA P-256），拉列表时：

1. 取 `index.json` 的**原始字节**和 `index.json.sig`；
2. 用内置公钥验 `SHA256withECDSA`；
3. **验签通过才解析**；不通过就整份作废，一个包都不显示。

这里没有「忽略警告继续」的开关 —— 有那个开关等于没做。

注意 `sha256` 字段单独并不防篡改：它写在索引里，索引被改 hash 也会跟着改。
它解决的是「下载过程损坏」和「主备源被换」，真正防篡改的是索引签名，两者都要。

**签名怎么做**

私钥在维护者机器上（`m7a-keystore/taskpack-signing-key.pem`，**绝不入库**）。
改完索引跑一次：

```bash
python scripts/sign_taskpack_index.py
```

脚本会算 sha256、核对 `id` 与包内 `task.json` 是否一致、写 `index.json.sig`。
手动等价命令：

```bash
openssl dgst -sha256 -sign m7a-keystore/taskpack-signing-key.pem \
  -out /tmp/index.der site/taskpacks/index.json
base64 -w0 /tmp/index.der > site/taskpacks/index.json.sig
```

**完整发布流程**

```bash
# 1. 打包（task.json 放根目录，模板图放 assets/）
zip -r daily-lite-1.0.0.m7atask task.json assets/

# 2. 放进站点目录
cp daily-lite-1.0.0.m7atask site/taskpacks/

# 3. 在 index.json 的 packs 里加一条（sha256 可以先空着，脚本会算）
python scripts/sign_taskpack_index.py

# 4. 部署
wrangler pages deploy site
```

脚本会就地补全 `sha256` / `size`，所以**手写索引时不用自己算 hash**。

**社区怎么贡献**

`author` 写贡献者名字，但**签名只用维护者私钥**。流程是：

- 别人做好包 → 加入 [官方 QQ 群](https://qm.qq.com/q/i6heXbm134) 提交 `.m7atask` 和一段 JSON 条目 → 维护者复核、跑脚本、部署；
- 这不是为了垄断，而是「谁签名谁负责」—— 用户装的每个包，责任在签名的人身上。

要放开成「谁都能发」，得让 App 内置多把公钥（或换一套信任模型），
那是另一个设计，当前没有做。

**主备源**

| 用途 | 地址 |
| --- | --- |
| 主 | `https://m7a.pages.dev/taskpacks/…`（Cloudflare Pages） |
| 备 | `https://raw.githubusercontent.com/<owner>/<repo>/main/site/taskpacks/…` |

两边都会验签，备用源不等于可信源。`raw.githubusercontent.com` 在国内经常连不上，
所以它只是兜底，包本体的备用地址建议指 GitHub Release。

---

## 7. 调试建议

1. **先在「设置 → 日志」打开详细日志**，每一步的 `op`、耗时、匹配分数都会打出来。
2. 模板匹配失败时，日志里会给出 `区域最佳相似度`：
   - `0.85+` 却不过阈值 → 阈值写高了；
   - `0.5 ~ 0.8` → 模板尺寸或画面版本不对，重新裁图；
   - `< 0.5` → `region` 没框到按钮。
3. 不确定坐标时，先用纯 `tap` + `wait` 写最小流程跑通路径，再把 `tap` 逐步换成
   `tapTemplate` —— 先能跑，再稳。
4. 不要依赖固定 `wait` 等转场。转场时长在低端机上能差 3 倍，一律用 `waitTemplate`。

---

## 8. 版本与兼容

| App 版本 | 自定义任务 | 在线任务包 |
| --- | --- | --- |
| < 1.0.0（`versionCode < 11`） | 不支持 | 不支持 |
| ≥ 1.0.0（`versionCode ≥ 11`） | `schemaVersion: 1` 全量 | 索引验签 + sha256 校验 |

新增原语只会**追加**，不会改已有语义；确有破坏性变更时才升 `schemaVersion`，
届时旧包仍按 v1 解释执行。

**换签名密钥的代价**：公钥硬编码在 APK 里，换私钥必须同时改
`M7aApi.TASKPACK_INDEX_PUBKEY` 并发新版。老版本 App 会一直验签失败
（表现为「在线任务包」列表空 + 一条签名错误），所以**别轻易换**，且私钥要备份。

---

## 9. 投稿与审核

想把自己写的任务包放进 App 内「在线任务包」列表（也就是 6.2 的官方索引），
**加入官方 QQ 群提交**：

> **https://qm.qq.com/q/i6heXbm134**

提交时请一并给出：

| 内容 | 说明 |
| --- | --- |
| `.m7atask` 文件 | 自己先跑通，能完整跑完、不留残局 |
| `id` / `name` / `author` / `version` / `description` | 直接贴 `task.json` 里那几行就行 |
| 适用场景与前置条件 | 从哪个界面开始跑、需要什么设置（分辨率、游戏语言、是否已通关某层） |
| 一张流程截图或录屏 | 方便复核，也能直接用在列表描述里 |

审核看两件事：**能不能稳定跑完**、**会不会在别人手机上乱点**。不评价玩法好坏。
常见会被退回的情况：

- 坐标只在自己那台手机上成立（没做分辨率归一化，或写死了 1080p 像素值）
- 用固定 `wait` 等转场，换台机器就点空
- 越过 3.4 的能力边界（试图读写包外文件、起别的应用）
- 包里塞了和任务无关的素材（立绘、剧情截图、别人的模板图）

没过会说明是哪一条，改完可以再提。

**审核通过不等于安全担保。** 签名只证明「这份包是维护者部署进索引的」，
不代表它对每个账号都无害 —— 上架的包同样适用导入时的免责声明，
风险由使用者自行判断和承担。

