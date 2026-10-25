package com.march7th.hkrpg.domain.service

import com.march7th.hkrpg.R
import com.march7th.hkrpg.utils.i18n.UiText
import com.march7th.hkrpg.utils.i18n.uiTextOf

/** 将 [TaskCompositionService.StartResult] 映射为可读的 i18n 文案；Success 返回 null */
fun resolveStartResultMessage(result: TaskCompositionService.StartResult): UiText? =
    when (result) {
        is TaskCompositionService.StartResult.Success -> null
        is TaskCompositionService.StartResult.ResourceError ->
            uiTextOf(R.string.task_start_error_resource_load_failed)

        is TaskCompositionService.StartResult.InitializationError -> when (result.phase) {
            TaskCompositionService.StartResult.InitializationError.InitPhase.CREATE_INSTANCE ->
                uiTextOf(R.string.task_start_error_maa_create_instance)

            TaskCompositionService.StartResult.InitializationError.InitPhase.SET_TOUCH_MODE ->
                uiTextOf(R.string.task_start_error_set_touch_mode)
        }

        is TaskCompositionService.StartResult.PortraitOrientationError ->
            uiTextOf(R.string.task_start_error_portrait_orientation)

        is TaskCompositionService.StartResult.InvalidAspectRatioError ->
            uiTextOf(R.string.task_start_error_invalid_aspect_ratio)

        is TaskCompositionService.StartResult.ConnectionError -> when (result.phase) {
            TaskCompositionService.StartResult.ConnectionError.ConnectPhase.DISPLAY_MODE ->
                uiTextOf(R.string.task_start_error_display_mode)

            TaskCompositionService.StartResult.ConnectionError.ConnectPhase.VIRTUAL_DISPLAY ->
                if (result.shizukuAsRoot) {
                    uiTextOf(R.string.task_start_error_virtual_display_shizuku_as_root)
                } else {
                    uiTextOf(R.string.task_start_error_virtual_display)
                }

            TaskCompositionService.StartResult.ConnectionError.ConnectPhase.MAA_CONNECT ->
                uiTextOf(R.string.task_start_error_connect_timeout)
        }

        is TaskCompositionService.StartResult.StartError ->
            uiTextOf(R.string.task_start_error_start_failed)

        is TaskCompositionService.StartResult.ServiceConnecting ->
            uiTextOf(R.string.task_start_error_service_connecting)

        is TaskCompositionService.StartResult.RemoteAccessUnavailable ->
            uiTextOf(R.string.task_start_error_backend_unavailable, result.backend.display)

        is TaskCompositionService.StartResult.AlreadyRunning ->
            uiTextOf(R.string.task_start_error_already_running)
    }
