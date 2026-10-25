package com.march7th.hkrpg.data.model

import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.resource.ServerTimezone
import com.march7th.hkrpg.domain.models.PlanSideTask
import com.march7th.hkrpg.domain.models.UserDataUpdateTriggerInterval
import com.march7th.hkrpg.domain.models.isUserDataUpdateDue
import com.march7th.hkrpg.core.task.CoreTaskParams
import com.march7th.hkrpg.core.task.CoreTaskType
import com.march7th.hkrpg.utils.i18n.uiTextOf
import kotlinx.serialization.Serializable

/** 更新数据：按间隔展开为 0~2 个识别任务。 */
@Serializable
data class UserDataUpdateConfig(
    val updateOperBox: Boolean = true,
    val updateDepot: Boolean = true,
    val triggerInterval: UserDataUpdateTriggerInterval = UserDataUpdateTriggerInterval.EVERY_TIME,
) : TaskParamProvider {

    override fun toTaskParams(ctx: TaskParamContext): List<CoreTaskParams> {
        if (!updateOperBox && !updateDepot) {
            return emptyList()
        }

        val yjToday = ServerTimezone.getYjDate(ctx.clientType)
        val yjZone = ServerTimezone.getServerZone(ctx.clientType)
        val operDue = updateOperBox && isUserDataUpdateDue(
            lastSyncMillis = ctx.operBoxRepository.snapshot.value.syncTimeMillis,
            interval = triggerInterval,
            yjToday = yjToday,
            yjZone = yjZone,
        )
        val depotDue = updateDepot && isUserDataUpdateDue(
            lastSyncMillis = ctx.depotRepository.snapshot.value.syncTimeMillis,
            interval = triggerInterval,
            yjToday = yjToday,
            yjZone = yjZone,
        )
        if (!operDue && !depotDue) {
            return emptyList()
        }

        // 干员识别改走一图流：不占任务位，启动时并行拉取
        val operViaYituliu = operDue && ctx.operBoxUseYituliuApi
        if (operViaYituliu) {
            ctx.registerSideTask(PlanSideTask.OPER_BOX_YITULIU)
        }

        // 对齐上游：先干员后仓库（串行）。
        return buildList {
            if (operDue && !operViaYituliu) add(
                CoreTaskParams(
                    CoreTaskType.OPER_BOX,
                    "{}",
                    logName = uiTextOf(R.string.runlog_task_with_detail, ctx.node.name, uiTextOf(R.string.maa_oper_box)),
                )
            )
            if (depotDue) add(
                CoreTaskParams(
                    CoreTaskType.DEPOT,
                    "{}",
                    logName = uiTextOf(R.string.runlog_task_with_detail, ctx.node.name, uiTextOf(R.string.maa_depot)),
                )
            )
        }
    }
}
