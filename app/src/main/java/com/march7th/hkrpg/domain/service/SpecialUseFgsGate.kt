package com.march7th.hkrpg.domain.service

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.LogLevel
import timber.log.Timber

/** specialUse FGS 预检：appop 被 ROM 或管控工具拒绝时 startForeground 必抛 SecurityException */
object SpecialUseFgsGate {

    // 替代的 checkOpRawNoThrow 是新加的 flagged API，14/15 上没有
    @Suppress("DEPRECATION")
    fun isDenied(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val permission = Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE
        val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val mode = AppOpsManager.permissionToOp(permission)?.let { op ->
            try {
                context.getSystemService(AppOpsManager::class.java)
                    .unsafeCheckOpRawNoThrow(op, Process.myUid(), context.packageName)
            } catch (e: RuntimeException) {
                Timber.w(e, "SpecialUseFgsGate: read appop failed")
                null
            }
        }
        val denied = isDenied(mode, granted)
        if (denied) Timber.w("SpecialUseFgsGate: denied, mode=%s granted=%s", mode, granted)
        return denied
    }

    /** 对齐 AOSP checkAppOpPermission；FOREGROUND 等放行，交给 startForeground 的 catch 兜底 */
    internal fun isDenied(mode: Int?, permissionGranted: Boolean): Boolean = when (mode) {
        AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> true
        AppOpsManager.MODE_DEFAULT, null -> !permissionGranted
        else -> false
    }

    /** 以 specialUse 类型拉起 FGS；appop 被拒时抛 SecurityException，善后策略由调用方定 */
    fun startForeground(service: Service, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(id, notification)
        }
    }

    /** 本轮无 FGS 时写进任务日志，tooltip 给出手动放开的命令 */
    fun appendDeniedLog(context: Context, logger: CoreSessionLogger) {
        // 命令不参与翻译，拼好整条再填进文案
        val command = "appops set --uid ${context.packageName} FOREGROUND_SERVICE_SPECIAL_USE allow"
        logger.append(
            context.getString(R.string.runlog_fgs_special_use_denied),
            LogLevel.WARNING,
            context.getString(R.string.runlog_fgs_special_use_denied_tip, command),
        )
    }
}
