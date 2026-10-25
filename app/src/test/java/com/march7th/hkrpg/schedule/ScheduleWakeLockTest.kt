package com.march7th.hkrpg.schedule

import android.content.Context
import android.os.PowerManager
import com.march7th.hkrpg.schedule.service.ScheduleWakeLock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test

class ScheduleWakeLockTest {
    @Test
    fun lockIsBounded_andCleanupHandlesAlreadyExpiredLock() {
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        val power = mockk<PowerManager> {
            every { newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, any()) } returns lock
        }
        val context = mockk<Context> {
            every { getSystemService(Context.POWER_SERVICE) } returns power
        }
        val acquired = ScheduleWakeLock.acquire(context, 10_000L)
        every { lock.isHeld } returns true
        ScheduleWakeLock.release(acquired)
        every { lock.isHeld } returns false
        ScheduleWakeLock.release(acquired)
        verifyOrder {
            lock.setReferenceCounted(false)
            lock.acquire(10_000L)
            lock.release()
        }
        verify(exactly = 1) { lock.release() }
    }
}
