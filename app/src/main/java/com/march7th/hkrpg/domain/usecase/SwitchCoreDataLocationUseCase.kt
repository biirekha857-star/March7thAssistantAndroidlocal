package com.march7th.hkrpg.domain.usecase

import android.content.Context
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.CoreDataLocation
import com.march7th.hkrpg.manager.RemoteServiceManager
import com.march7th.hkrpg.utils.Misc

/** 落设置 → 断提权进程 → 重启：CorePathConfig 与 MaaCore 状态都是进程内固定的 */
class SwitchCoreDataLocationUseCase(
    private val context: Context,
    private val appSettings: AppSettingsManager,
) {
    suspend operator fun invoke(target: CoreDataLocation) {
        appSettings.setCoreDataLocation(target)
        RemoteServiceManager.unbind()
        Misc.restartApp(context)
    }
}
