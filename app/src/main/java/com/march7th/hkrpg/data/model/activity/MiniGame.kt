package com.march7th.hkrpg.data.model.activity

import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.resource.CoreVersion
import com.march7th.hkrpg.data.resource.MiniGameTextRegistry
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.uiTextOf

/**
 * 小游戏（UI 使用）
 */
data class MiniGame(
    val display: UiText,                 // 显示名称
    val value: String,                   // 任务代码
    val utcStartTime: Long,              // UTC 开始时间
    val utcExpireTime: Long,             // UTC 结束时间
    val tip: UiText = UiText.Empty,      // 提示信息（版本过低时显示警告）
    val tipKey: String? = null,          // 提示本地化键
    val isUnsupported: Boolean = false,  // 版本不支持标记
    val activity: String? = null,        // 所属活动名，关卡提示里挂在同名活动下
    val category: UiText                 // 列表分组标题
) {
    val isOpen: Boolean
        get() {
            if (utcStartTime == 0L && utcExpireTime == Long.MAX_VALUE) return true
            val now = System.currentTimeMillis()
            return now in utcStartTime until utcExpireTime
        }

    companion object {
        /**
         * 从 API 入口创建（带版本检查 + Display/Tip 解析）
         * 迁移自 WPF ParseMiniGameEntry + ParseMiniGameEntries
         */
        fun fromEntry(entry: MiniGameEntry): MiniGame {
            val finalDisplay =
                MiniGameTextRegistry.resolveDisplay(entry.display, entry.displayKey, entry.value)
            // 任务代码必须保持原始字符串，不能用本地化文案兜底
            val finalValue = entry.value ?: entry.display ?: entry.displayKey ?: ""

            val minReq = entry.minimumRequired
            val unsupported = !minReq.isNullOrBlank() &&
                    !CoreVersion.meetsMinimumRequired(minReq)

            // 版本过低时覆盖 tip/tipKey (WPF: ParseMiniGameEntries)
            val tip: UiText
            val tipKey: String?
            if (unsupported) {
                tip = uiTextOf(R.string.mini_game_low_version_tip, minReq, CoreVersion.current)
                tipKey = null
            } else {
                tip = MiniGameTextRegistry.resolveTip(
                    tip = entry.tip,
                    tipKey = entry.tipKey,
                    display = finalDisplay,
                    displayKey = entry.displayKey,
                    value = finalValue
                )
                tipKey = entry.tipKey
            }

            return MiniGame(
                display = finalDisplay,
                value = finalValue,
                utcStartTime = StageActivityInfo.parseToUtcMillis(
                    entry.utcStartTime,
                    entry.timeZone
                ),
                utcExpireTime = StageActivityInfo.parseToUtcMillis(
                    entry.utcExpireTime,
                    entry.timeZone
                ),
                tip = tip,
                tipKey = tipKey,
                isUnsupported = unsupported,
                activity = entry.activity?.takeIf { it.isNotBlank() },
                category = MiniGameTextRegistry.resolveCategory(entry.category, entry.categoryKey)
            )
        }
    }
}
