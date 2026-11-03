package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「启动游戏」任务的参数 —— **选择启动哪个客户端**。
 *
 * # 为什么要在任务内部选，而不是做成两个任务
 *
 * 手机同一时刻**只能跑一个游戏实例**，两个独立的「启动」任务并存没有意义，
 * 用户还可能同时勾选导致冲突。
 *
 * 所以做成**一个任务 + 客户端选择器**，交互上像清体力选副本那样：
 *
 * ```
 * 启动游戏
 *   ○ 官服          com.miHoYo.hkrpg
 *   ○ B 服          com.miHoYo.hkrpg.bilibili
 *   ○ 国际服        com.HoYoverse.hkrpgoversea
 *   ● 云游戏        com.miHoYo.cloudgames.hkrpg
 * ```
 *
 * 四个选项**平铺**成一列，不套二级菜单 —— 一共就四种，藏起来只会让人多点两下。
 *
 * 默认选本地客户端，行为与以前完全一致。
 */
@Serializable
data class StartUpTaskConfig(
    /** 固定 `"StartUp"` */
    @SerialName("taskType")
    val taskType: String = "StartUp",

    /** 用哪个客户端启动。 */
    val client: GameClient = GameClient.LOCAL,

    /**
     * 启动超时（秒）。
     *
     * 本地客户端默认 180 秒；云游戏要分配云端实例 + 建流，
     * 默认 300 秒。用 [effectiveTimeoutSec] 取实际值。
     */
    @SerialName("timeoutSec")
    val timeoutSec: Int? = null,
) : TaskParamProvider {

    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        val coreType = CoreTaskType.fromValue(taskType)
        if (coreType == null) {
            ctx.appendLog(UiText.Dynamic("未知的崩铁任务类型：$taskType"), LogLevel.ERROR)
            return emptyList()
        }
        val timeout = effectiveTimeoutSec
        val json = buildString {
            append("""{"client":""")
            append('"').append(client.wireName).append('"')
            append(""","packageName":""")
            append('"').append(client.packageName).append('"')
            append(""","timeoutSec":$timeout""")
            append("}")
        }
        return listOf(CoreTaskParams(coreType, json))
    }

    /** 实际生效的超时秒数（用户没显式设置时用该客户端的默认值）。 */
    val effectiveTimeoutSec: Int
        get() = timeoutSec ?: client.defaultTimeoutSec

    /**
     * 可选的游戏客户端。**四种平铺**，不做二级选择。
     *
     * @param wireName 传给内核的标识（写进任务 params，改它会让旧配置失配）
     * @param clientKey 对应的 [com.march7th.hkrpg.constant.Packages] key
     * @param packageName 应用包名
     * @param defaultTimeoutSec 默认启动超时
     */
    enum class GameClient(
        val wireName: String,
        val clientKey: String,
        val packageName: String,
        val defaultTimeoutSec: Int,
    ) {
        /**
         * 官服（国服）：直接安装运行，启动快。
         *
         * ⚠️ 枚举名保持 `LOCAL`：它会被 kotlinx.serialization 按名字写进
         * DataStore，改成 OFFICIAL 会让老用户的配置反序列化失败。
         * 只改显示文案（「本地客户端」→「官服」）不影响存量数据。
         */
        LOCAL(
            wireName = "local",
            clientKey = "Official",
            packageName = "com.miHoYo.hkrpg",
            defaultTimeoutSec = 180,
        ),

        /** B 服（渠道服）。启动流程与官服完全一致，只是包名不同。 */
        BILIBILI(
            wireName = "bilibili",
            clientKey = "Bilibili",
            packageName = "com.miHoYo.hkrpg.bilibili",
            defaultTimeoutSec = 180,
        ),

        /** 国际服。 */
        OVERSEA(
            wireName = "oversea",
            clientKey = "YoStarEN",
            packageName = "com.HoYoverse.hkrpgoversea",
            defaultTimeoutSec = 180,
        ),

        /**
         * 云游戏客户端：云端串流。
         *
         * 启动要先**分配云端实例**（可能排队）再建流，所以超时给得更宽。
         */
        CLOUD(
            wireName = "cloud",
            // 云游戏有独立的包名，不能复用官服的 —— 静音、AppOps、进程监控
            // 全都要按这个 key 去查 Packages，复用会让它们去操作一个没装的包
            clientKey = "Cloud",
            packageName = "com.miHoYo.cloudgames.hkrpg",
            defaultTimeoutSec = 300,
        );

        companion object {
            fun fromWire(name: String?): GameClient =
                entries.firstOrNull { it.wireName == name } ?: LOCAL
        }
    }
}
