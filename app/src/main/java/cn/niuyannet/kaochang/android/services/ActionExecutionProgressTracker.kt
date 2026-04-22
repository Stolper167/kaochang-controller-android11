package cn.niuyannet.kaochang.android.services

internal enum class ActionExecution201State {
    IDLE,
    RUNNING,
    BUSY,
    DISCONNECTED
}

internal class ActionExecutionProgressTracker(
    private val requireObservedRunningBeforeSuccess: Boolean
) {

    enum class ProgressDecision {
        WAIT,
        WAIT_FOR_START,
        SUCCESS
    }

    private var observedRunning = false

    fun onStatus(status: ActionExecution201State): ProgressDecision {
        return when (status) {
            ActionExecution201State.RUNNING -> {
                observedRunning = true
                ProgressDecision.WAIT
            }

            ActionExecution201State.IDLE -> {
                if (!requireObservedRunningBeforeSuccess || observedRunning) {
                    ProgressDecision.SUCCESS
                } else {
                    ProgressDecision.WAIT_FOR_START
                }
            }

            ActionExecution201State.BUSY,
            ActionExecution201State.DISCONNECTED -> ProgressDecision.WAIT
        }
    }
}
