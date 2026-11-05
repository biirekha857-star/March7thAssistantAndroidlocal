---
versionCode: 15
versionName: 1.0.4
apkUrl: https://github.com/biirekha857-star/March7thAssistantAndroidlocal/releases/download/v1.0.4/March7thAssistant-1.0.4.apk
forceUpdate: false
minSupportedVersionCode: 11
publishedAt: 2026-11-05
---

**1.0.4**：取坐标能横屏了，滑动和框选也能直接量

**新增 · 取坐标支持横屏 / 滑动 / 框选**

- 打开后自动横屏
- 顶部三个模式：
  - 点：和以前一样，点一下得到 x、y
  - 滑动：拖一条线，得到 fromX / fromY / toX / toY，直接对应 swipe 参数
  - 框选：拖一个矩形，得到 [x, y, w, h]，直接对应 region 参数
- 输出格式与任务文件里的参数名一致，复制就能粘

**新增 · 更新提示可以忽略某个版本**

- 弹窗底部加了「忽略此版本」
- 点了之后这个版本不再提示
- 强制更新不显示这个入口
