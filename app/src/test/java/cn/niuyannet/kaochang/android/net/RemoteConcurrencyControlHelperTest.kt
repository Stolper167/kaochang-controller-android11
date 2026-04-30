package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConcurrencyControlHelperTest {

    @Test
    fun resolveTarget_shouldInferLowToHighTransitionWhenBackendOnlySwitchesMode() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 0,
            requestedModeType = 2,
            requestedTransitionMode = null
        )

        assertEquals(2, resolved.modeType)
        assertEquals(1, resolved.transitionMode)
        assertEquals(false, resolved.overrodeConflictingTransition)
    }

    @Test
    fun resolveTarget_shouldInferHighToLowTransitionWhenBackendOnlySwitchesMode() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 2,
            currentTransitionMode = 0,
            requestedModeType = 1,
            requestedTransitionMode = null
        )

        assertEquals(2, resolved.modeType)
        assertEquals(2, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldAllowExplicitManualClearToLowStable() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 2,
            requestedModeType = 1,
            requestedTransitionMode = 0
        )

        assertEquals(1, resolved.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldClearInvalidLowHighToLowWhenHighAreaIsIdle() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 2,
            requestedModeType = 1,
            requestedTransitionMode = 2,
            highConcurrencyAreaActive = false
        )

        assertEquals(1, resolved.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldKeepHighToLowTransitionWhenHighAreaHasSausage() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 2,
            requestedModeType = 1,
            requestedTransitionMode = 2,
            highConcurrencyAreaActive = true
        )

        assertEquals(2, resolved.modeType)
        assertEquals(2, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldUseBackendLatestTargetWhenHighToLowNeedsOverride() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 2,
            requestedModeType = 2,
            requestedTransitionMode = 1
        )

        assertEquals(2, resolved.modeType)
        assertEquals(1, resolved.transitionMode)
        assertTrue(resolved.overrodeConflictingTransition)
    }
}
