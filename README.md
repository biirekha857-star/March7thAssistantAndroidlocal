<div align="center">
  <h1 align="center">
    <img src="./docs/icon.png" width="160">
    <br/>
    March7thAssistant<br/>
    <sub>Android Local</sub>
  </h1>
</div>

<div align="center">

🌟 点一下右上角的 Star，GitHub 主页就能收到软件更新通知了哦~

</div>

<br/>

<div align="center">

**简体中文** ｜ [English](./README_EN.md) ｜ [使用教程](#-快速上手) ｜ [常见问题](#已知问题) ｜ [问题反馈](https://github.com/biirekha857-star/March7thAssistantAndroidlocal/issues)

Android 8.0+ · arm64-v8a · Shizuku / Root

</div>

<br/>

> ⚠️ 本项目基于虚拟屏与图像识别实现，不修改游戏文件、不注入游戏进程。**使用自动化工具存在账号被处罚的风险，请自行评估后使用**，完整条款见文末[免责声明](#-免责声明)。

## 功能简介

- **后台运行**：游戏跑在独立虚拟显示器上，你可以同时刷视频、聊天、看文档，互不打断
- **日常**：启动游戏、领取月卡、邮件、清体力、每日实训、关闭游戏
- **清体力**：拟造花萼（金/赤）、凝滞虚影、侵蚀隧洞、饰品提取、历战余响，6 种副本全覆盖
- **每日实训**：勾选式执行，不做不可靠的任务列表 OCR，可指定要做哪几项
- **任务链**：卡片式任务列表，支持拖拽排序、就地调参、定时执行
- **实时预览**：App 内等比放大查看虚拟屏画面，长屏设备自动做黑边适配
- **日志与诊断**：时间线式任务日志，一键导出日志包用于排查
- **触控自愈**：注入失败自动清理残留手势并降级重试，避免「画面正常但点不动」

## 界面展示

<p align="center">
  <img src="./docs/screenshots/home.png" width="200" />
  <img src="./docs/screenshots/schedule.png" width="200" />
  <img src="./docs/screenshots/settings.png" width="200" />
  <img src="./docs/screenshots/settings-detail.png" width="200" />
</p>

从左到右：任务页（预检 + 卡片流） · 定时任务 · 设置分类 · 设置详情

## 注意事项

- 遇到错误请先看 [已知问题](#已知问题)，再在 [Issue](https://github.com/biirekha857-star/March7thAssistantAndroidlocal/issues) 反馈，附上「设置 → 日志与诊断 → 导出日志」
- 欢迎 [PR](https://github.com/biirekha857-star/March7thAssistantAndroidlocal/pulls)，提交前请先阅读 [任务开发规范](docs/TASK_DEVELOPMENT.md)
- **请勿在米游社等官方社区讨论、宣传或分享本项目**
- 本项目完全免费、开源，任何付费售卖渠道都与作者无关

## 下载安装

前往 [Releases](https://github.com/biirekha857-star/March7thAssistantAndroidlocal/releases/latest) 下载 APK 安装即可。

> 仅提供 **arm64-v8a**，Android 8.0 以下无法安装。

## 🚀 快速上手

```
① 安装 APK
② 授予 Shizuku 或 Root 权限
③ 首次启动会引导完成：
    · 用户协议确认（含风险提示）
    · 权限后端选择（Shizuku / Root）
    · 权限授予
④ 进入「首页」，在任务卡片里勾选今天要做的项目
⑤ 点「开始任务」
```

首次运行前建议：

- 关闭游戏的**省电优化**（系统设置 → 电池 → 本应用 → 不限制）
- 确保游戏已登录，并且停在大厅
- 若使用 Shizuku，保持 Shizuku 服务在运行

## 📱 系统要求

| 项 | 要求 |
|---|---|
| Android | **8.0 (API 26)** 及以上，推荐 **12+** |
| 架构 | **arm64-v8a** |
| 权限 | Root（KernelSU / Magisk）或 **Shizuku** |
| 存储 | 约 500 MB 可用空间 |
| 屏幕 | 支持任意比例，长屏设备自动加黑边适配 |

### 为什么需要特权服务

采集虚拟屏画面、向虚拟屏注入触摸事件，都需要 **shell / root 级权限**。本项目提供两种后端：

- **Shizuku**（推荐）：无需 Root，通过 ADB 授权即可
- **Root**：KernelSU / Magisk，直接以 shell 身份运行

## 📋 开发进度

### 已实现且可用

| 任务 | 状态 | 说明 |
|---|---|---|
| **启动游戏** | ✅ 稳定 | 拉起游戏、点「点击进入」、领取列车补给月卡、处理奖励确认、确认进入大厅 |
| **邮件** | ✅ 稳定 | 一键领取邮件附件 |
| **清体力** | ✅ 基本可用 | 支持全部 6 种副本类型，见下表 |
| **每日实训** | ✅ 可用（勾选式） | 委托、拍照、万能合成机、秘技、消耗体力、领奖 |
| **关闭游戏** | ✅ 稳定 | 结束游戏进程 |

### 清体力支持的副本

| 副本 | 单次消耗 | 单次连打上限 | 状态 |
|---|---|---|---|
| 拟造花萼（金） | 10 | 24 | ✅ |
| 拟造花萼（赤） | 10 | 24 | ✅ |
| 凝滞虚影 | 30 | 8 | ✅ |
| 侵蚀隧洞 | 40 | 6 | ✅ |
| 饰品提取（差分宇宙） | 40 | 6 | ✅ 含自由移动场景 |
| 历战余响（周本） | 30 | 3 | ✅ |

> 「饰品提取」是差分宇宙玩法，进入后有自由移动场景，程序会自动向前走并点攻击键触发战斗。

### 每日实训的实现方式

**不做任务列表 OCR 识别，改为用户勾选。**

原因：崩铁每个号每天的实训任务是随机抽取的。早期尝试复刻 PC 版的「OCR 扫卡片 → 算分 → 补到 500」逻辑，但手游下极不可靠（OCR 拆字跨卡片、「宇宙」认成「字画」、卡片布局随文字长度浮动）。

现在的做法：用户在配置面板勾选今天要做哪几项，程序按顺序执行，每项做完回大厅确认，最后统一领奖。

### 开发中 / 规划中

| 项目 | 状态 | 说明 |
|---|---|---|
| 差分宇宙完整流程 | 🚧 规划 | 目前只有「走一步 → 攻击」，完整流程需处理祝福选择、层数推进 |
| 历战余响选择具体周本 | 🚧 规划 | 目前取列表第一个 |
| 锄大地 / 模拟宇宙 | 📋 未开始 | 优先级较低 |
| 成就、忘却之庭 | 📋 未开始 | — |

### 已知问题

| 问题 | 影响 | 状态 |
|---|---|---|
| 部分角色攻击键位置不同 | 中 | 使用固定坐标，若失效需调整 |
| 周本次数动态上限 | 低 | 已跳过次数验证 |
| 触摸注入偶发失效 | 中 | 🔧 已加自愈与降级重试，待更多样本验证 |

## 🛠 源码运行与构建

### 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17+ |
| Android SDK | compileSdk **37** |
| **NDK** | **29.0.14206865**（必需，编译 `libbridge.so`） |
| CMake | 3.22.1 |
| 内存 | **建议 8 GB 可用**，低于此值 R8 易卡死 |

### 构建

```bash
# 1. 安装 NDK（必需）
sdkmanager --install "ndk;29.0.14206865"

# 2. 配置签名（可选，不配则 release 不签名）
# 在 local.properties 里写：
#   KEYSTORE_PATH=/path/to/your.jks
#   KEYSTORE_PASSWORD=***
#   KEY_ALIAS=***
#   KEY_PASSWORD=***

# 3. 构建
./gradlew :app:assembleDebug      # 调试版
./gradlew :app:assembleRelease    # 发布版
```

### R8 强度可配

在 `local.properties` 里设置：

```properties
# none  = 不混淆（开发调试，最快）
# light = 只混淆代码不压资源（开源发布，默认）
# full  = 全量混淆+资源压缩（上架商店，需 16GB 内存）
maa.r8=light
```

> ⚠️ **`libbridge.so` 的 JNI 类名不能被混淆**，`proguard-rules.pro` 里已用
> `-keep class ... { *; }` 锁定。改成 `{ native <methods>; }` 会导致
> `UnsatisfiedLinkError: JNI_ERR returned from JNI_OnLoad`。

## 🤝 参与开发

想新增任务或修改识别逻辑？请先阅读 [任务开发规范](docs/TASK_DEVELOPMENT.md)。

### 快速规范摘要

1. **优先区域识别**，不要全屏扫（性能差 5~20 倍）
2. **能用模板匹配就别用 OCR**，OCR 有拆字/误识风险
3. **按钮位置固定时直接写坐标**，比识别更可靠
4. **BACK 键只作最后手段**：先点 ✕ / 弹窗自带的取消，按完必须检测「是否退出游戏」并点取消
5. **每个动作后确认界面状态**，不要连续盲操作
6. **兜底点击只用安全空白位**，绝不碰返回键、头像、右侧功能列、底部主按钮

## 相关项目

March7thAssistant Android Local 离不开以下开源项目的帮助，感谢所有维护者与贡献者：

- [March7thAssistant](https://github.com/moesnow/March7thAssistant) ：PC 版崩铁自动化工具，本项目的**任务逻辑参考来源**
- [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) ：在 Android 上运行 MAA 的框架，本项目的**技术底座**（虚拟屏、特权服务、UI 框架）
- [RapidOCR](https://github.com/RapidAI/RapidOCR) ：OCR 模型来源
- [OpenCV](https://opencv.org/) ：模板匹配与图像处理

上游 MAA-Meow 是「在 Android 上跑**明日方舟**的 MAA」，本项目把内核**换成崩铁**：保留工程结构、虚拟屏采集、Shizuku / KernelSU 特权服务、Compose UI 与任务链框架；重写识别引擎、导航层与全部崩铁任务实现。

**没有这两个项目就没有本项目，请优先给上游点 Star。**

## 📄 免责声明

1. 本项目仅供**学习与技术交流**，所有功能均基于虚拟屏、图像识别与 Shizuku / Root 提权实现，不修改游戏文件、不注入游戏进程、不修改游戏内存。
2. **使用自动化工具可能违反游戏用户协议，存在账号被封禁的风险**，该风险由使用者自行承担，项目作者及贡献者不承担任何责任。
3. **请勿在米游社等官方社区讨论、宣传或分享本项目**，这既会提高被检测的概率，也可能导致项目下架。
4. 本项目完全免费开源，若你是通过付费渠道获得，说明你被骗了，请立即退款并举报卖家。
5. 不对任何数据丢失、账号损失、设备损坏承担责任。
6. 使用本项目即表示你已阅读、理解并同意以上全部条款。

## 许可证

本项目基于上游 [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) 二次开发，上游采用 **AGPL-3.0**，AGPL 要求衍生作品沿用同一许可证，因此本项目同样以 [**AGPL-3.0**](LICENSE) 发布。

| 场景 | 要求 |
|---|---|
| 自己用、自己改 | ✅ 随便用，不公开也行 |
| 分发修改版 | 必须开源，且同样用 AGPL |
| 作为网络服务提供 | 必须向用户提供完整源码 |

---

## ☕ 赞赏支持

本项目**完全免费、开源**，没有任何付费功能或内购。如果它确实帮到了你，欢迎请作者喝杯奶茶 —— **纯属自愿，不赞赏也完全不影响使用**。

<div align="center">

<img src="docs/sponsor.png" width="260" alt="赞赏码" />

<sub>扫码赞赏（微信 / 支付宝）</sub>

</div>

> ⚠️ 本项目不会因为赞赏而提供任何额外功能、优先支持或特殊待遇。

## Contributors

<a href="https://github.com/biirekha857-star/March7thAssistantAndroidlocal/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=biirekha857-star/March7thAssistantAndroidlocal" />
</a>

## Stargazers over time

<a href="https://star-history.com/#biirekha857-star/March7thAssistantAndroidlocal&Date">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=biirekha857-star/March7thAssistantAndroidlocal&type=Date&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=biirekha857-star/March7thAssistantAndroidlocal&type=Date" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=biirekha857-star/March7thAssistantAndroidlocal&type=Date" />
 </picture>
</a>
