package com.march7th.hkrpg.data.model.update

/**
 * 应用更新提示（启动时弹窗用的完整信息）。
 *
 * 与 [UpdateInfo] 的区别：那个是「有没有新版本」的通用结果，
 * 这个专门喂给启动弹窗 —— 带上更新要点（`changelog`）和是否强制。
 *
 * @property versionName 新版本名，如 `0.5.7beta`。断网兜底时为空串。
 * @property currentVersion 本机版本名，用于标题里展示「从哪升到哪」。
 * @property changelog 更新要点（Markdown，来自站点 `update.md` 的正文）。
 * @property downloadUrl 下载地址（`update.md` 的 `apkUrl`）。
 * @property forced 是否**不允许跳过**。
 */
data class AppUpdatePrompt(
    val versionName: String,
    val currentVersion: String,
    val changelog: String,
    val downloadUrl: String,
    val forced: Boolean,
) {
    companion object {
        /**
         * 断网兜底：只记得「必须更新」，但拿不到版本号与更新要点。
         *
         * 这种情况下弹窗改用静态文案 + 固定的 Release 页地址，
         * 因为本地只留了下限，没有存整份清单 —— 存了反而会和真实清单失配。
         */
        fun forcedOffline(currentVersion: String, downloadUrl: String): AppUpdatePrompt =
            AppUpdatePrompt(
                versionName = "",
                currentVersion = currentVersion,
                changelog = "",
                downloadUrl = downloadUrl,
                forced = true,
            )
    }
}
