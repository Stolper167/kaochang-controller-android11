package cn.niuyannet.kaochang.android.fragments

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualAlgorithmEnableSyncRetryPolicyTest {

    @Test
    fun decide_shouldRetryAfterFirstFailure() {
        assertEquals(
            ManualAlgorithmEnableSyncRetryPolicy.Decision.RETRY,
            ManualAlgorithmEnableSyncRetryPolicy.decide(attempt = 1, success = false)
        )
    }

    @Test
    fun decide_shouldProceedWhenRetrySucceeds() {
        assertEquals(
            ManualAlgorithmEnableSyncRetryPolicy.Decision.PROCEED,
            ManualAlgorithmEnableSyncRetryPolicy.decide(attempt = 2, success = true)
        )
    }

    @Test
    fun decide_shouldRejectAfterSecondFailure() {
        assertEquals(
            ManualAlgorithmEnableSyncRetryPolicy.Decision.REJECT,
            ManualAlgorithmEnableSyncRetryPolicy.decide(attempt = 2, success = false)
        )
    }
}
