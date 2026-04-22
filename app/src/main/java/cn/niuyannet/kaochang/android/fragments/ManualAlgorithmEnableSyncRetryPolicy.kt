package cn.niuyannet.kaochang.android.fragments

internal object ManualAlgorithmEnableSyncRetryPolicy {
    const val MAX_ATTEMPTS = 2
    const val RETRY_DELAY_MS = 800L

    enum class Decision {
        PROCEED,
        RETRY,
        REJECT
    }

    fun decide(attempt: Int, success: Boolean): Decision = when {
        success -> Decision.PROCEED
        attempt < MAX_ATTEMPTS -> Decision.RETRY
        else -> Decision.REJECT
    }
}
