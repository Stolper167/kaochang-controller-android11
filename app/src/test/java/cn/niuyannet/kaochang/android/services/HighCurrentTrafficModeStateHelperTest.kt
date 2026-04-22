package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighCurrentTrafficModeStateHelperTest {

    @Test
    fun resolveLowToHighTransitionIdleState_shouldKeepHighModeWhenSchedulingIsDisabled() {
        val resolved = resolveLowToHighTransitionIdleState(
            currentModeType = 2,
            schedulingEnabled = false,
            boxSupplyAvailable = true,
            hasActiveHeatingIn13_15 = false,
            hasStartedHighHeatSupplyNow = false
        )

        assertNotNull(resolved)
        assertEquals(2, resolved!!.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveLowToHighTransitionIdleState_shouldReturnNullWhenStillWaitingForOldOrNewBatch() {
        val resolved = resolveLowToHighTransitionIdleState(
            currentModeType = 2,
            schedulingEnabled = false,
            boxSupplyAvailable = true,
            hasActiveHeatingIn13_15 = true,
            hasStartedHighHeatSupplyNow = false
        )

        assertEquals(null, resolved)
    }

    @Test
    fun recordSupplementLaunch_shouldRefreshTrackingWhenTransitionStartsNewBatch() {
        val previous = HighTrafficSupplementTrackingState(
            pitCount = 0,
            lastFillPitTime = 1_000L
        )

        val updated = recordHighTrafficSupplementLaunch(
            previous = previous,
            filledCount = 9,
            now = 20_000L
        )

        assertEquals(9, updated.pitCount)
        assertEquals(20_000L, updated.lastFillPitTime)
    }

    @Test
    fun recordSupplementLaunch_shouldKeepPreviousTrackingWhenNoSausageWasMoved() {
        val previous = HighTrafficSupplementTrackingState(
            pitCount = 3,
            lastFillPitTime = 12_345L
        )

        val updated = recordHighTrafficSupplementLaunch(
            previous = previous,
            filledCount = 0,
            now = 99_999L
        )

        assertEquals(previous, updated)
    }

    @Test
    fun shouldSwitchHighToLowByTimeout_shouldReturnFalseWhenThresholdIsNonPositive() {
        assertFalse(
            shouldSwitchHighToLowByTimeout(
                lastFillPitTime = 10_000L,
                now = 20_000L,
                timeThresholdLowMinutes = 0
            )
        )
    }

    @Test
    fun shouldSwitchHighToLowByTimeout_shouldReturnTrueWhenElapsedTimeExceedsThreshold() {
        assertTrue(
            shouldSwitchHighToLowByTimeout(
                lastFillPitTime = 10_000L,
                now = 80_001L,
                timeThresholdLowMinutes = 1
            )
        )
    }
}
