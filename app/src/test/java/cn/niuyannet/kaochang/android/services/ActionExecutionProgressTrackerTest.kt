package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionExecutionProgressTrackerTest {

    @Test
    fun onStatus_shouldWaitForRunningBeforeSuccessWhenRequired() {
        val tracker = ActionExecutionProgressTracker(requireObservedRunningBeforeSuccess = true)

        assertEquals(
            ActionExecutionProgressTracker.ProgressDecision.WAIT_FOR_START,
            tracker.onStatus(ActionExecution201State.IDLE)
        )
    }

    @Test
    fun onStatus_shouldReturnSuccessAfterObservedRunningAndThenIdle() {
        val tracker = ActionExecutionProgressTracker(requireObservedRunningBeforeSuccess = true)

        assertEquals(
            ActionExecutionProgressTracker.ProgressDecision.WAIT,
            tracker.onStatus(ActionExecution201State.RUNNING)
        )
        assertEquals(
            ActionExecutionProgressTracker.ProgressDecision.SUCCESS,
            tracker.onStatus(ActionExecution201State.IDLE)
        )
    }

    @Test
    fun onStatus_shouldAllowIdleImmediateSuccessWhenRunningNotRequired() {
        val tracker = ActionExecutionProgressTracker(requireObservedRunningBeforeSuccess = false)

        assertEquals(
            ActionExecutionProgressTracker.ProgressDecision.SUCCESS,
            tracker.onStatus(ActionExecution201State.IDLE)
        )
    }
}
