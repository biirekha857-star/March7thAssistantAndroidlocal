package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 云游戏启动任务的参数。
 *
 * # 为什么单独做一个任务
 *
 * 《崩坏：星穹铁道》有两种客户端：
 *
 * | 版本 | 包名 | 特点 |
 * |---|---|---|
 * | **本地客户端** | `com.miHoYo.hkrpg` | 下载安装，跑在手机上 |
 * | **云游戏** | `com.miHoYo.cloudgames.hkrpg` | 云端串流，**启动流程完全不同** |
 *
 * 两者的开机动画、登录页、加载界面都不一样，所以不能共用
 * `StartUp` 那一套「等点击进入 → 进大厅」的逻辑。
 *
 * 拆成独立任务后：
 * - 用户可以在任务链里**自己选择**用哪个
 * - 两者可以并列存在，按需启用
 *
 * # 当前状态：**框架已就绪，启动步骤待实现**
 *
 * 云游戏的启动流程（启动器 → 云实例分配 → 串流连入 → 进入游戏）
 * 需要实测确认后再填。此处先留出参数位和调用点。
 */
@Serializable
data class CloudGameTaskConfig(
    /** 固定 `"CloudGameStartUp"`，与 [TaskTypeInfo.CLOUD_GAME_START_UP] 对齐 */
    @SerialName("taskType")
    val taskType: String = "CloudGameStartUp",

    /**
     * 云游戏包名。
     *
     * 默认国服 `com.miHoYo.cloudgames.hkrpg`；其它渠道可改。
     */
    val packageName: String = DEFAULT_PACKAGE,

    /**
     * 启动后最长等待多久进入游戏（秒）。
     *
     * 云游戏要**分配实例 + 串流初始化**，比本地客户端慢得多，
     * 所以默认给到 300 秒（本地是 180 秒）。
     */
    val startupTimeoutSec: Int = 300,
) : TaskParamProvider {

    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        val coreType = CoreTaskType.fromValue(taskType)
        if (coreType == null) {
            ctx.appendLog(UiText.Dynamic("未知的崩铁任务类型：$taskType"), LogLevel.ERROR)
            return emptyList()
        }
        val json = buildString {
            append("""{"packageName":""")
            append('"')
                .append(packageName.replace("\\", "\\\\").replace("\"", "\\\""))
                .append('"')
            append(""","startupTimeoutSec":$startupTimeoutSec""")
            append("}")
        }
        return listOf(CoreTaskParams(coreType, json))
    }

    companion object {
        /** 国服云游戏默认包名。 */
        const val DEFAULT_PACKAGE = "com.miHoYo.cloudgames.hkrpg"
    }
}
