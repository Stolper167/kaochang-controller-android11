package cn.niuyannet.kaochang.android.net

object RemoteConcurrencyControlHelper {

    data class ResolvedConcurrencyTarget(
        val modeType: Int,
        val transitionMode: Int,
        val overrodeConflictingTransition: Boolean
    )

    fun resolveTarget(
        currentModeType: Int,
        currentTransitionMode: Int,
        requestedModeType: Int,
        requestedTransitionMode: Int?
    ): ResolvedConcurrencyTarget {
        val resolvedTransitionMode = when {
            requestedTransitionMode != null -> requestedTransitionMode
            currentModeType != requestedModeType && requestedModeType == 2 -> 1
            currentModeType != requestedModeType && requestedModeType == 1 -> 2
            else -> currentTransitionMode
        }
        val overrodeConflictingTransition =
            currentTransitionMode == 2 && requestedModeType == 2 && resolvedTransitionMode != 2
        return ResolvedConcurrencyTarget(
            modeType = requestedModeType,
            transitionMode = resolvedTransitionMode,
            overrodeConflictingTransition = overrodeConflictingTransition
        )
    }
}
