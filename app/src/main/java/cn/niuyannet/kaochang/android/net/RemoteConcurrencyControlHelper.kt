package cn.niuyannet.kaochang.android.net

object RemoteConcurrencyControlHelper {
    private const val MODE_LOW = 1
    private const val MODE_HIGH = 2
    private const val TRANSITION_NONE = 0
    private const val TRANSITION_LOW_TO_HIGH = 1
    private const val TRANSITION_HIGH_TO_LOW = 2

    data class ResolvedConcurrencyTarget(
        val modeType: Int,
        val transitionMode: Int,
        val overrodeConflictingTransition: Boolean
    )

    fun resolveTarget(
        currentModeType: Int,
        currentTransitionMode: Int,
        requestedModeType: Int,
        requestedTransitionMode: Int?,
        highConcurrencyAreaActive: Boolean = false
    ): ResolvedConcurrencyTarget {
        val requestedMode = requestedModeType.takeIf { it == MODE_LOW || it == MODE_HIGH }
            ?: currentModeType.takeIf { it == MODE_LOW || it == MODE_HIGH }
            ?: MODE_LOW
        val resolvedTransitionMode = when {
            requestedTransitionMode != null -> requestedTransitionMode
            currentModeType != requestedMode && requestedMode == MODE_HIGH -> TRANSITION_LOW_TO_HIGH
            currentModeType != requestedMode && requestedMode == MODE_LOW -> TRANSITION_HIGH_TO_LOW
            else -> currentTransitionMode
        }.takeIf { it in TRANSITION_NONE..TRANSITION_HIGH_TO_LOW } ?: TRANSITION_NONE

        val backendOnlySwitchesHighToLow =
            requestedTransitionMode == null &&
                currentModeType != requestedMode &&
                requestedMode == MODE_LOW

        val normalized = when {
            requestedMode == MODE_LOW && resolvedTransitionMode == TRANSITION_NONE ->
                MODE_LOW to TRANSITION_NONE
            requestedMode == MODE_LOW && resolvedTransitionMode == TRANSITION_LOW_TO_HIGH ->
                MODE_HIGH to TRANSITION_LOW_TO_HIGH
            requestedMode == MODE_LOW && resolvedTransitionMode == TRANSITION_HIGH_TO_LOW ->
                if (backendOnlySwitchesHighToLow || highConcurrencyAreaActive) {
                    MODE_HIGH to TRANSITION_HIGH_TO_LOW
                } else {
                    MODE_LOW to TRANSITION_NONE
                }
            requestedMode == MODE_HIGH && resolvedTransitionMode == TRANSITION_LOW_TO_HIGH ->
                MODE_HIGH to TRANSITION_LOW_TO_HIGH
            requestedMode == MODE_HIGH && resolvedTransitionMode == TRANSITION_HIGH_TO_LOW ->
                MODE_HIGH to TRANSITION_HIGH_TO_LOW
            requestedMode == MODE_HIGH ->
                MODE_HIGH to TRANSITION_NONE
            else ->
                MODE_LOW to TRANSITION_NONE
        }
        val overrodeConflictingTransition =
            currentTransitionMode == TRANSITION_HIGH_TO_LOW &&
                requestedMode == MODE_HIGH &&
                normalized.second != TRANSITION_HIGH_TO_LOW
        return ResolvedConcurrencyTarget(
            modeType = normalized.first,
            transitionMode = normalized.second,
            overrodeConflictingTransition = overrodeConflictingTransition
        )
    }
}
