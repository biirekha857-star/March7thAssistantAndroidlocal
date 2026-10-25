package com.march7th.hkrpg.utils.i18n

import android.content.Context
import com.march7th.hkrpg.R
import com.march7th.hkrpg.domain.models.OverlayControlMode
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.domain.models.RunMode
import com.march7th.hkrpg.domain.service.CoreResourceLoader

fun Context.runModeDisplayName(mode: RunMode): String {
    return when (mode) {
        RunMode.BACKGROUND -> getString(R.string.home_run_mode_background)
    }
}

fun Context.overlayControlModeDisplayName(mode: OverlayControlMode): String {
    return when (mode) {
        OverlayControlMode.ACCESSIBILITY -> getString(R.string.home_overlay_mode_accessibility)
        OverlayControlMode.FLOAT_BALL -> getString(R.string.home_overlay_mode_float_ball)
    }
}

fun wakeUpClientTypeLabel(clientType: String): UiText {
    return when (clientType) {
        "Official" -> uiTextOf(R.string.panel_wakeup_client_official)
        "Bilibili" -> uiTextOf(R.string.panel_wakeup_client_bilibili)
        "YoStarEN" -> uiTextOf(R.string.panel_wakeup_client_yostar_en)
        "YoStarJP" -> uiTextOf(R.string.panel_wakeup_client_yostar_jp)
        "YoStarKR" -> uiTextOf(R.string.panel_wakeup_client_yostar_kr)
        "txwy" -> uiTextOf(R.string.panel_wakeup_client_txwy)
        else -> uiTextDynamic(clientType)
    }
}

fun Context.wakeUpClientTypeDisplayName(clientType: String): String {
    return wakeUpClientTypeLabel(clientType).resolve(this)
}

fun Context.remoteBackendPermissionLabel(backend: RemoteBackend): String {
    return getString(R.string.remote_backend_permission_label, backend.display)
}

fun Context.resourceLoaderMessage(state: CoreResourceLoader.State): String {
    return when (state) {
        is CoreResourceLoader.State.Loading -> {
            state.message.ifBlank { getString(R.string.resource_loader_loading_message) }
        }

        is CoreResourceLoader.State.Reloading -> {
            state.message.ifBlank { getString(R.string.resource_loader_reloading_message) }
        }

        else -> ""
    }
}
