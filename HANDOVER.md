# M7A 项目交接文档

> 版本：2026-11-02
> 读者：接手开发的人 **和他的 AI 助手**
> 如果你是人：从第 1 节顺序读，第 2 节能让你当天跑起来。
> 如果你是 AI：直接跳到第 10 节，那里写了必须遵守的规则和最短的上下文路径。

---

## 1. 这是个什么项目

**March7thAssistant Android Local** —— 《崩坏：星穹铁道》的 Android 端自动化助手。

一句话说清它做的事：**在手机后台建一块 1920×1080 的虚拟屏，把游戏拉进去，然后像人一样看屏幕、点屏幕，按任务链依次完成日常。**

几个关键点，决定了后面所有设计：

| 事实 | 影响 |
|---|---|
| 不 root 也要能跑 | 靠 Shizuku 或 Root 提权，用 `app_process` 起虚拟屏 + 注入输入 |
| 游戏跑在固定 1920×1080 虚拟屏里 | 所有坐标、模板可以按一套分辨率写死，不用做多分辨率适配 |
| 引擎原样保留 MaaFramework 的 API | 任务实现是 Kotlin，但底层识别/截图能力来自 `libMaaFramework.so` |
| 全部识别靠模板匹配 + OCR | 不读游戏内存、不注入进程、不改客户端 —— 也正因如此才有封号风险，见 `LICENSE` 与免责声明 |

### 与上游的关系

从 MaaMeow（明日方舟助手）的 Android 外壳移植而来，现在已经是崩铁专用。代码里仍有方舟时代的残留（`RecruitConfig`、`InfrastConfig` 等参数类保留但不用），看到不要困惑，也不要顺手删 —— 删了会牵连序列化兼容。

### 现在的状态

- 版本 `1.0.0`，versionCode `11`（写死的，不跟 git 提交数走，原因见 `app/build.gradle.kts` 第 23 行的注释）
- 内置任务：启动游戏 / 邮件 / 清体力 / 每日实训 / 忘却之庭 / 兑换码 / 关闭游戏 / 自定义任务
- 自定义任务包体系已完成（`.m7atask` 导入、24 种原语、参数表单）
- 定时策略：按「配置（Profile）」触发整套任务链

---

## 2. 十分钟跑起来

### 2.1 环境清单

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | **17+** | Android Studio 自带的 JBR 就够 |
| Android Studio | 最新稳定版 | 需要支持 AGP 9.2.1 |
| Android SDK | compileSdk **37** | `minSdk 28` / `targetSdk 36` |
| **NDK** | **29.0.14206865** | **必须精确这个版本**，用来编 `libbridge.so` |
| CMake | 3.22.1 | SDK Manager 里装 |
| 内存 | 建议 8 GB 可用 | 少于这个值编 release 的 R8 容易卡死 |
| Python | 3.10+ | 只跑 `scripts/` 下的工具脚本，不参与构建 |

安装 NDK：

```bash
sdkmanager --install "ndk;29.0.14206865"
sdkmanager --install "cmake;3.22.1"
```

### 2.2 拿到代码

两种方式，选一种：

**方式 A（推荐）：从 GitHub 克隆**

```bash
git clone https://github.com/biirekha857-star/March7thAssistantAndroidlocal.git m7a
cd m7a
```

需要仓库权限，找维护者加 collaborator。

**方式 B：直接用这个 zip**

zip 里已经带了 `.git` 历史，解压后就是一个可用仓库。远程地址已经清理过（不带凭据），需要你自己配一个：

```bash
git remote set-url origin https://github.com/biirekha857-star/March7thAssistantAndroidlocal.git
```

### 2.3 配置 `local.properties`

这个文件**不在版本控制里**（zip 里也没带），必须自己建，放在项目根目录：

```properties
# 你的 Android SDK 路径（Windows 下反斜杠要转义成 \\）
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk

# 签名配置（只在要打 release 包时才需要，见第 7 节）
# KEYSTORE_PATH=C\:\\path\\to\\your.jks
# KEYSTORE_PASSWORD=***
# KEY_ALIAS=***
# KEY_PASSWORD=***

# R8 混淆强度：none（最快，日常开发用这个）/ light / full
maa.r8=none
```

> **注意**：`sdk.dir` 不写也能用 —— 用 Android Studio 打开项目时它会提示你选 SDK 目录，然后自动生成这一行。

### 2.4 构建

```bash
./gradlew :app:assembleDebug      # 调试版，约 3~7 分钟
./gradlew :app:assembleRelease    # 发布版，约 25~35 分钟（R8 全量）
./gradlew :app:compileDebugKotlin # 只编译不打包，约 1~5 分钟，改代码时用它验证
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

### 2.5 首次构建要下载什么（以及为什么没放进仓库）

仓库里**已经**包含了全部源码和 Gradle Wrapper（`gradle/wrapper/gradle-wrapper.jar` 是入库的，
所以 `./gradlew` 直接能用）。下面三样不适合入库，首次构建时才会下载：

| 东西 | 体积 | 从哪来 | 说明 |
|---|---|---|---|
| Gradle 发行版 9.4.1 | ~150 MB | 已配腾讯镜像 | 只下一次，缓存在 `~/.gradle/wrapper` |
| Maven 依赖 | 首次 ~800 MB | 已配阿里云 + 腾讯镜像 | `settings.gradle.kts` 里四条镜像源 |
| NDK 29.0.14206865 | ~1 GB | Android SDK Manager | 编 `libbridge.so` 必需 |

**为什么不放进仓库**：Gitee 单文件限制 100 MB、仓库建议控制在 500 MB 以内；
这几样加起来 2 GB 且都是"可有可无的缓存"，塞进去会让 clone 变慢、仓库变死。

**网络不好怎么办**（三条，按成本从低到高）：

1. 先确认镜像生效 —— 项目已经写好阿里云/腾讯源，正常情况不用改任何配置
2. NDK 单独下载：Google 官方 URL 换成国内镜像，或者让同事直接把
   `<SDK>/ndk/29.0.14206865` 整个目录拷过来放进自己的 SDK 目录
3. 依赖缓存拷贝：把同事的 `~/.gradle/caches` 打包拷过来解压，能省掉首次几百 MB 下载
   （约 1.5 GB，走网盘，不要走 git）

### 2.6 第一次构建大概率会踩的三个坑

**① `NDK not configured` / `CMake not found`**
NDK 版本必须正好是 `29.0.14206865`。装完执行一次 `./gradlew --stop` 再编。

**② 依赖下载卡住 / 超时**
项目用了腾讯镜像（`gradle-wrapper.properties` 里那个 `mirrors.cloud.tencent.com`）。如果镜像抽风，把那一行换回 `services.gradle.org`，或者保证代理可用。

**③ 离线模式编不过**
`--offline` 只有在依赖已经全部缓存过之后才有效。第一次构建别加这个参数。

---

## 3. 代码地图

### 3.1 目录职责

```
m7a/
├── app/                       主 APK，几乎全部代码在这
│   ├── src/main/java/com/march7th/hkrpg/
│   │   ├── starrail/          ★ 崩铁业务的全部实现（引擎、任务、导航）
│   │   ├── core/              内核桥接层（给 MaaFramework 传参）
│   │   ├── data/              数据层：模型、DataStore、资源、网络
│   │   ├── domain/            用例、服务（编排、守护、日志）
│   │   ├── presentation/      UI（Compose）
│   │   ├── remote/            与 Shizuku/Root 服务通信
│   │   └── manager/           权限、Shizuku 就绪检测
│   ├── src/main/assets/
│   │   ├── starrail/images/   ★ 所有模板图（按目录分层）
│   │   └── announcement/      公告兜底（离线时显示）
│   └── src/main/res/          资源（strings.xml 中英各一份）
├── hidden-api/                反射用的隐藏 API 桩
├── annotation-api/            自定义注解
├── ksp-processor/             注解处理器（生成模板索引）
├── build-logic/               约定插件（统一编译配置）
├── scripts/                   Python 工具脚本（图标、OCR 模型转换、签名任务包索引…）
├── site/                      官网（Cloudflare Pages 静态站）
├── docs/                      开发规范、发版说明、任务包格式
└── examples/                  示例任务包
```

### 3.2 必须认识的 12 个类

| 类 | 位置 | 一句话职责 |
|---|---|---|
| `StarRailTasks` | `starrail/` | **每个内置任务的实现**。`startUp()`、`mail()`、`power()`… 一个函数一个任务 |
| `ScreenNavigator` | `starrail/engine/` | **界面判态与导航**。这是全项目最核心也最脆的类，2600+ 行 |
| `ShellAutomation` | `starrail/engine/` | 截帧、点击、滑动、注入。所有"手"的操作都在这 |
| `ImageUtils` | `starrail/engine/` | 模板匹配、帧归一化 |
| `OcrEngine` | `starrail/engine/` | PP-OCR 文字识别 |
| `ScriptedTaskRunner` | `starrail/engine/` | 解释执行自定义任务包（`task.json`） |
| `PackSandbox` | `starrail/engine/` | 任务包的运行时边界与参数夹取 |
| `CustomTaskPackStore` | `data/resource/` | 任务包的安装、校验、列表 |
| `TaskChainState` | `data/preferences/` | **任务链**的持久化（DataStore），含自愈逻辑 |
| `TaskCompositionService` | `domain/service/` | 把任务链变成一次真实执行（起服务、下发任务） |
| `TaskTypeInfo` | `data/model/` | 任务类型枚举，决定 UI 上显示什么 |
| `ActivationWizard` | `presentation/activation/` | 首次激活向导（提权 → 授权） |

### 3.3 三条主线（按这个顺序读代码）

**主线 A：一次任务是怎么跑起来的**

```
用户点「开始任务」
  ↓
TaskChainState.chain            取出任务链（List<TaskChainNode>）
  ↓
AnalyzeTaskChainUseCase         过滤 enabled、排序、做可行性检查
  ↓
TaskCompositionService          起 Shizuku 服务、建虚拟屏、把任务下发给内核
  ↓
StarRailTasks.run(type, params) 按类型分发到具体实现
  ↓
ScreenNavigator + ShellAutomation + ImageUtils + OcrEngine   看屏幕、点屏幕
```

**主线 B：怎么和屏幕交互**

- `ShellAutomation.frame()` 截一帧（虚拟屏 1920×1080）
- `ImageUtils.findInRegion(模板, 阈值, 区域)` 找图
- `OcrEngine.run(mat, region)` 认字
- `nav.isAt(Screen.MAIN)` 判当前在哪个界面
- `nav.closeCurrent()` 关掉当前界面回大厅

判态是整条链最容易出错的地方。`ScreenNavigator` 里每个判据都有注释解释"为什么用这个锚点、实测数据是多少"，**改之前先读那段注释**，那些数字是真机量出来的。

**主线 C：自定义任务包**

```
用户导入 .m7atask
  ↓
CustomTaskPackStore.install()   解压 + 逐条校验（字段、op 白名单、坐标范围、模板路径）
  ↓
SettingsViewModel               导入成功 → TaskChainState.addCustomNode() 自动加一张卡
  ↓
用户在卡片里填参数                CustomTaskConfig.values
  ↓
ScriptedTaskRunner.run(packId, overrides)
  ↓
PackSandbox.assetRel()          模板路径必须在包自己的 assets/ 内
```

格式规范在 `docs/task-file.md`，**改任务包能力前必读**，末尾还有一节"大白话版"。

---

## 4. 开发约定

### 4.1 注释风格（重要）

这个项目的注释写得比代码多，而且是**刻意的**：

- 中文注释
- 不写"这里做了什么"（看代码就知道），写"**为什么这么做**、**不这么做会怎样**、**实测数据是多少**"
- 修 bug 时把现象和排查过程留在注释里，例如：

```kotlin
// 11-02 10:59 实测：点完「退出关卡」后游戏进入加载，遮罩盖着整屏 ——
// 判态认不出大厅，也永远找不到可点的 ✕。旧代码就在这个状态下按 BACK
// 去"自救"，每次都把「是否退出游戏」叠上来，连按 6 轮全部落空。
```

**接手后请延续这个风格。** 这个项目最大的维护成本不是写新功能，是"三个月后忘了当初为什么这么写"。

### 4.2 提交信息

格式：`type(scope): 一句话`

```
feat(taskpack): 任务包参数表单；去掉步数与时长上限
fix(nav): 退出框认「继续游戏」、加载遮罩先等、点菜单失败重试
docs: 任务包规范补「一个包一个板块」
refactor(nav): 删掉 BACK 键与「退出确认框」自救
```

正文写"为什么"和"影响了什么"，不写"改了哪几行"（diff 里有）。

### 4.3 分支

目前是 **main 直推**。两个人协作后建议改成：

```
main         随时可发版
├── dev      日常开发
└── feat/xxx 大功能单独开分支
```

改动涉及 `ScreenNavigator` 时务必单独分支 + 实机验证，这个类改坏了整个 App 就废了。

### 4.4 测试

项目里有 `app/src/test` 和 `app/src/androidTest`，但**覆盖率很低**，实际验证主要靠：

1. `./gradlew :app:compileDebugKotlin` 必须过（这是底线）
2. 真机跑一遍对应任务，看日志

没有模拟器方案 —— 这套东西依赖 Shizuku/虚拟屏，模拟器跑不起来。

---

## 5. 发版流程

### 5.1 版本号

在 `app/build.gradle.kts` 里**写死**：

```kotlin
val gitVersionCode: Int by lazy { 11 }      // 每次发版 +1
val gitVersionName: String by lazy { "1.0.0" }
```

故意不跟 git 提交数走（原因见文件里的注释）。

### 5.2 出包

```properties
# local.properties
maa.r8=none    # 自己测试用，最快
maa.r8=light   # 开源发布，默认
maa.r8=full    # 上架商店，需 16GB 内存
```

```bash
./gradlew :app:assembleRelease
```

### 5.3 发布清单

1. 改 `versionCode` / `versionName`
2. 写 `docs/release/<版本>.md`
3. 更新 `site/update.md`（客户端更新检查读这个文件）和 `site/update.json`
4. 更新 `site/announcement_zh.md` / `announcement_en.md`（App 内公告）
5. `site/` 目录部署到 Cloudflare Pages
6. 编 release APK、签名、发 GitHub Release

> `site/update.md` 里有 `forceUpdate` 字段，设为 `true` 会让旧版本直接弹强制更新。**慎用**。

---

## 6. 已知的坑（都是踩过的）

| # | 坑 | 现象 / 对策 |
|---|---|---|
| 1 | NDK 版本不对 | `libbridge.so` 编译失败。必须 `29.0.14206865` |
| 2 | 编 release 极慢 | R8 全量要 25~35 分钟，别以为卡死了 |
| 3 | debug 包不能覆盖 release | 签名不同，装之前必须先卸载旧版（会丢 App 内设置） |
| 4 | `local.properties` 里有明文密码 | **不要提交、不要外传**，见第 8 节 |
| 5 | Android 资源里的转义 | `<string>` 里写 `\'` 或 `\"` 会报 `Invalid unicode escape sequence`，而且报错行号会指到上一条，很难查 |
| 6 | Compose 的 `ITextField` | `label` 参数类型是 `String?`，不是 `@Composable () -> Unit`，别按 Material 的写法传 lambda |
| 7 | 任务链自愈会删重复 | `TaskChainState.healChain()` 同类型只留第一个。**自定义任务是唯一例外**（按 packId 去重） |
| 8 | 模板图必须从 1920×1080 截 | 贴边裁会匹配不上，四周留 2~4px |
| 9 | 游戏内按 BACK 没有任何用 | 只会弹「是否退出游戏」，不会返回。**代码里已经全面禁用** |
| 10 | 退出确认框的按钮叫「继续游戏」 | 不叫「取消」。认「取消」的代码永远匹配不到 |
| 11 | 编译产物巨大 | `app/build` 能到 20+ GB，清一次 `./gradlew clean` 要重编 5 分钟，别随便清 |
| 12 | 引擎日志位置 | 真机 `/sdcard/Android/data/com.march7th.hkrpg/files/logs/`，App 内「设置 → 日志」可导出 zip |

---

## 7. 当前进度与未完成项

### 2026-11-02 这一批改动（已提交，未推送）

| 提交 | 内容 |
|---|---|
| `b33a310` | 删掉 BACK 键与「退出确认框」自救 —— 游戏内 BACK 无返回作用 |
| `e09550b` | 退出框认「继续游戏」、加载遮罩先等、点菜单失败重试 |
| `f59b9f3` | 关不掉界面时点左上角「返回」，替代 BACK |
| `e3f687b` | 自定义任务支持「一个包一个板块」（链里可并存多个包） |
| `1921108`/`cf971c0` | 文档：一个包一个板块、倒计时 6→8 秒订正 |
| `52f2b74` | `docs/task-file.md` 追加「大白话版」附录 |
| `5b37a37` | 导入任务包后自动出现板块；内置公告换掉上游方舟内容 |
| `ba8f35e` | 启动客户端四选一（官服 / B 服 / 国际服 / 云游戏），向导去掉重复选择 |
| `69d0c2d` | 任务包参数表单；去掉步数与时长上限 |
| `1a30abe` | int 参数在执行期按 min/max 夹取 |
| `a1b7381`/`bb5bee2` | Shizuku 安装教程入口（向导 + 设置页） |

**这些提交已推送到 Gitee 私有仓库**（remote 名 `gitee`）。GitHub 作为公开发布渠道，
发版前再同步一次。两个 remote 的用法见第 9 节。

### 待验证 / 待办

- [ ] 清体力结束后能否自动回大厅（左上返回的坐标 `(58,58)` 是否准确）
- [ ] B 服 / 国际服的实际启动（包名是否与本机安装的一致）
- [ ] 自定义任务参数表单在真机上的交互
- [ ] 老的任务包节点不会自动补板块（升级前导入的包需要手动在「添加任务」里加一次）
- [ ] 站点 `site/` 的文案已改但**尚未部署**

---

## 8. 密钥与凭据（重要）

### 8.1 现状风险

当前 `local.properties` 里明文写着 release 签名的两个密码（`KEYSTORE_PASSWORD` /
`KEY_PASSWORD`）。**这是真实凭据，不能外传，也不要提交。**

> 已经处理掉的：git remote URL 里原本内嵌着 GitHub / Gitee 的访问令牌，
> 现在两个 remote 都是干净地址，凭据统一存在用户目录的 `~/.git-credentials` 里
> （`git config credential.helper store`）。项目目录里不再有任何令牌，
> 即使整个文件夹被拷走也不会带走凭据。
>
> 仍然存在风险的：`local.properties` 的签名密码是明文。长期建议按 8.3 节挪到
> `~/.gradle/gradle.properties`。

### 8.2 三类密钥怎么办

**① release 签名密钥（`m7a.jks`）**

它是「同签名覆盖安装」的唯一凭据 —— 谁拿到它，谁就能发一个能覆盖安装到用户手机上的假更新。

建议：**不要放进交接包。** 三种方案按信任程度选：

| 方案 | 适用 | 做法 |
|---|---|---|
| A. 不让对方签 release（推荐） | 一般协作 | 对方只做 `assembleDebug` 自己测；需要正式包时由维护者签 |
| B. 单独安全传递 | 高度互信 | 用密码管理器（Bitwarden/1Password）或当面拷贝，**不要走微信/QQ/邮件** |
| C. 各自一把钥匙 | 长期独立发布 | 换签名意味着老用户必须卸载重装，代价大，不建议 |

**② GitHub 个人访问令牌**

- 现在 URL 里那个令牌**必须立刻吊销**：GitHub → Settings → Developer settings → Personal access tokens → Revoke
- 然后在本地改成不带令牌的地址，用 Git Credential Manager 或 SSH 认证：

```bash
git remote set-url origin https://github.com/biirekha857-star/March7thAssistantAndroidlocal.git
```

- 对方用自己的账号申请令牌，**不要共用**。多人共用一个令牌，出事时无法定位是谁。

**③ Cloudflare Pages（站点部署）**

`site/` 部署需要 `wrangler` 凭据。建议：

- 只给需要发版的人
- 或者各自建一个 Pages 项目做预览，正式域名只由维护者部署

### 8.3 密钥管理建议（长期）

把签名配置从 `local.properties` 挪到 `~/.gradle/gradle.properties`（用户级，不在项目里）：

```properties
# ~/.gradle/gradle.properties
M7A_KEYSTORE_PATH=C:\\keys\\m7a.jks
M7A_KEYSTORE_PASSWORD=***
M7A_KEY_ALIAS=m7a
M7A_KEY_PASSWORD=***
```

`app/build.gradle.kts` 里改成读这些变量。这样项目目录里再也不会有明文密码，也不会被误提交。

---

## 9. 协作方式

### 9.1 两个远端仓库

| remote | 地址 | 用途 |
|---|---|---|
| `origin` | `github.com/biirekha857-star/March7thAssistantAndroidlocal` | 公开仓库，对外发布、Issue、Release |
| `gitee` | `gitee.com/miguan443/march7th-assistant-android-local` | **私有**，两个人日常同步用（国内推拉快） |

日常流程：

```bash
# 改完代码，先推私有的
git push gitee main

# 准备发版时，再同步到公开的
git push origin main
```

两边**不要各改各的**：以 `gitee` 为唯一真相源，`origin` 只做单向同步（gitee → github）。

### 9.2 代码协作

1. 维护者在 Gitee 仓库「管理 → 仓库成员」里邀请对方（私有仓库必须邀请，否则看不到）
2. 双方各自 `git clone` 后 `git remote add gitee ...`，各自用各自的凭据
3. 约定分支策略（见 4.3），改动大就开 PR

> Gitee 的邀请链接形如 `gitee.com/<user>/<repo>/team_members/invite_link`，
> 有效期有限，过期重新生成。

### 9.2 沟通

- 任务包格式问题：看 `docs/task-file.md`（含大白话版）
- 源码层改动：看 `docs/TASK_DEVELOPMENT.md`
- 卡住时最有效的做法：**导出日志 zip 发给对方**（App 内「设置 → 日志 → 导出」），里面包含引擎日志 + 截图 + 设备信息，比口头描述有用十倍

---

## 10. 给 AI 助手的规则

> 这一节是写给接手方 AI 的。人类可以略过，但建议扫一眼，知道你的 AI 被要求做什么。

### 10.1 最短上下文路径

按这个顺序读，不要一上来就全仓库搜索：

1. `docs/TASK_DEVELOPMENT.md` —— 源码任务的开发规范（含"绝对不要做的事"）
2. `docs/task-file.md` —— 自定义任务包格式
3. `app/src/main/java/.../starrail/StarRailTasks.kt` —— 任务实现总入口
4. `app/src/main/java/.../starrail/engine/ScreenNavigator.kt` —— 界面判态（这个文件最长，先读类注释和 `Screen` 枚举）

### 10.2 必须遵守

1. **改完必须 `./gradlew :app:compileDebugKotlin`**，通过才算完成。不要用"应该能编译"结案。
2. **不要改 `proguard-rules.pro` 里的 `-keep class ... { *; }`**。改成 `{ native <methods>; }` 会让 `libbridge.so` 的 JNI 注册失败，App 一启动就崩。
3. **不要碰 `local.properties`、`*.jks`、任何含密码的文件**。这些不在版本控制里，属于维护者的本机配置。
4. **不要为了让编译通过而删功能、注释掉报错代码**。这个项目很多 `@Suppress` 和"看起来多余"的判断都有实机依据。
5. **注释用中文，写"为什么"**，和现有风格一致。
6. **大改动先在 commit message 里说清影响面**，尤其是动 `ScreenNavigator` 时。

### 10.3 验证清单（按改动类型）

| 改了什么 | 至少验证 |
|---|---|
| UI / Compose | `compileDebugKotlin` + 真机截图 |
| 任务实现 / 导航 | 真机跑对应任务，看 `engine.log` |
| 任务包格式 / 校验 | 用 `examples/demo-screen-scan` 走一遍导入 → 运行 |
| 资源字符串 | 中英各一份都要加，否则英文用户看到中文 |
| 数据模型（可序列化） | 老配置能不能反序列化 —— **加字段要给默认值**，改枚举名会让老数据失效 |

### 10.4 日志分析速查

日志在 `engine/engine.log`（导出 zip 里），先看结论再看细节：

```bash
# 1. 这次跑了什么、成没成
grep "结果=" engine.log

# 2. 导航层细节（判态、点击、关闭）
grep "ScreenNavigator" engine.log

# 3. 关键失败信号
grep -E "超时|失败|放弃|未找到|没找到" engine.log
```

常见关键词 → 含义：

| 日志里出现 | 说明 |
|---|---|
| `区域最佳相似度 0.42（阈值 0.80）` | 模板图没匹配上 —— 模板裁得不对，或界面变了 |
| `画面在加载（画面恢复中…）` | 命中了加载遮罩，正在等它结束（正常行为） |
| `点左上角「返回」` | 找不到 ✕，走了通用返回（正常行为） |
| `等待界面 main 超时` | 判态认为还没回大厅，通常是界面停在别的页 |
| `打开手机菜单失败` | 点左上角手机图标没打开菜单（云游戏刚进大厅时偶发，已加重试） |
| `[custom]` 开头 | 自定义任务包执行过程，出错时最后一行是原因 |

### 10.5 不要做的事

- ❌ 不要按 BACK 键（游戏内只会弹退出确认框，见 `docs/TASK_DEVELOPMENT.md`）
- ❌ 不要在任务包里加"能执行代码 / 启 App / 联网"的原语 —— 那会把整个项目变成外挂加载器
- ❌ 不要在全屏范围做模板匹配（性能差 20 倍，且容易误命中），必须给 `region`
- ❌ 不要假设任务链里同类型只有一份（自定义任务可以有多个）
- ❌ 不要清 `app/build` 除非确认要重编 5 分钟以上

---

## 11. 快速自检：你接手的这套东西是好的吗

```bash
# 1. 能编译
./gradlew :app:compileDebugKotlin

# 2. 能出包
./gradlew :app:assembleDebug

# 3. 能装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. 打开 App，走完激活向导（需要 Shizuku 已安装并启动）

# 5. 只勾「邮件」，点开始任务，看能不能跑通
```

五步都过，说明环境没问题，可以开始改代码了。

---

## 12. 最后

这个项目最大的特点是：**所有"看起来多余"的代码，背后都有一台真机上的失败**。

`ScreenNavigator` 里那些"实测 0.55（阈值 0.8）"、"点了 5 次才成功"、"云游戏刚进大厅第一次点击会被吞"的注释，全部是踩出来的。改之前先读，改之后补注释 —— 这是对后来接手的人（包括三个月后的你自己）唯一的交代。

有问题先看 `docs/`，那里比代码更容易读。
