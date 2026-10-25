package com.march7th.hkrpg.schedule.service

import android.content.Context
import android.os.PowerManager

/** 广播与服务分别持锁，限时覆盖冷启动和解锁 */
internal object ScheduleWakeLock {
    fun acquire(context: Context, timeoutMs: Long): PowerManager.WakeLock {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MaaMeow:schedule").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }

    fun release(wakeLock: PowerManager.WakeLock) {
        if (wakeLock.isHeld) wakeLock.release()
    }
}
