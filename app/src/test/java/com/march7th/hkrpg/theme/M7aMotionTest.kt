package com.march7th.hkrpg.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaaMotionTest {

    @Test
    fun duration_reduceMotionSnapsToZero() {
        assertEquals(0, M7aMotion.duration(reduceMotion = true, millis = 220))
        assertEquals(220, M7aMotion.duration(reduceMotion = false, millis = 220))
    }

    @Test
    fun pagerDuration_growsWithDistanceThenSnaps() {
        assertEquals(M7aMotion.Medium, M7aMotion.pagerDuration(1, reduceMotion = false))
        assertEquals(M7aMotion.Medium + M7aMotion.Fast, M7aMotion.pagerDuration(2, reduceMotion = false))
        assertEquals(0, M7aMotion.pagerDuration(3, reduceMotion = true))
    }

    @Test
    fun expandTransitions_noneWhenReduced() {
        assertEquals(
            androidx.compose.animation.EnterTransition.None,
            M7aMotion.expandIn(reduceMotion = true),
        )
        assertEquals(
            androidx.compose.animation.ExitTransition.None,
            M7aMotion.expandOut(reduceMotion = true),
        )
        assertTrue(M7aMotion.expandIn(reduceMotion = false) != androidx.compose.animation.EnterTransition.None)
    }
}
