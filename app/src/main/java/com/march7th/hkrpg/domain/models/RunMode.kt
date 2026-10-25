package com.march7th.hkrpg.domain.models

import com.march7th.hkrpg.constant.DisplayMode

/**
 * 运行模式。
 *
 * ## 为什么只剩「后台模式」
 *
 * 原版 MAA-Meow 有两种模式：
 * - `FOREGROUND`（前台）：直接把**主屏**镜像给引擎，坐标按物理分辨率算。
 * - `BACKGROUND`（后台）：另建一块固定分辨率的**虚拟屏**，游戏跑在上面。
 *
 * 移植崩铁时**只保留后台**，原因不是"省事"，而是**方案成立的前提**：
 *
 * 1. **虚拟屏分辨率固定**（[com.march7th.hkrpg.constant.DefaultDisplayConfig]
 *    恒为 1920×1080@240dpi），所以崩铁的 UI 布局在任何设备上**逐像素一致** ——
 *    坐标和模板只需验证一次就全局有效。
 * 2. 物理屏分辨率千差万别（本机 2780×1264、别人可能 2400×1080 / 3120×1440…），
 *    前台模式意味着**每个坐标都要维护两套**，根本不可维护。
 * 3. 崩铁的「左右边距调节」在虚拟屏上会**重置为默认且不可调**，
 *    进一步保证布局稳定；物理屏上这个设置会压缩左右两侧 UI 的位置。
 *
 * 删掉前台模式后，`TaskCompositionService` 里那条
 * `RunMode.FOREGROUND -> Misc.getScreenSize(context)`（把物理屏尺寸喂给引擎、
 * 导致所有模板失配）也随之消失。
 */
enum class RunMode(
    val displayMode: Int
) {
    BACKGROUND(DisplayMode.BACKGROUND)
}
