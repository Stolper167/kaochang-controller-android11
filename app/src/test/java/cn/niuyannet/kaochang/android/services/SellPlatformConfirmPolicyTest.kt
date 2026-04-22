package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class SellPlatformConfirmPolicyTest {

    private fun sausageInfo(code: Int): SauceDetectionProcessor.SausageInfo {
        return SauceDetectionProcessor.SausageInfo().apply {
            this.code = code
        }
    }

    @Test
    fun evaluateInitialConfirmation_shouldFallbackWhenAllCapturesFail() {
        val results = listOf<SauceDetectionProcessor.SausageInfo?>(null, null, null, null)

        val decision = SellPlatformConfirmPolicy.evaluateInitialConfirmation(results)

        assertEquals(
            SellPlatformConfirmPolicy.InitialConfirmationDecision.VISION_UNAVAILABLE_ASSUME_DELIVERED,
            decision
        )
    }

    @Test
    fun evaluateInitialConfirmation_shouldWaitForUserTakeWhenSausageDetected() {
        val sausage = sausageInfo(2)
        val results = listOf<SauceDetectionProcessor.SausageInfo?>(null, sausage)

        val decision = SellPlatformConfirmPolicy.evaluateInitialConfirmation(results)

        assertEquals(
            SellPlatformConfirmPolicy.InitialConfirmationDecision.WAIT_FOR_USER_TAKE,
            decision
        )
    }

    @Test
    fun evaluateInitialConfirmation_shouldWaitForDeliveryWhenOnlyEmptyFramesDetected() {
        val empty = sausageInfo(1)
        val results = listOf<SauceDetectionProcessor.SausageInfo?>(empty, empty)

        val decision = SellPlatformConfirmPolicy.evaluateInitialConfirmation(results)

        assertEquals(
            SellPlatformConfirmPolicy.InitialConfirmationDecision.WAIT_FOR_DELIVERY_OR_FAST_TAKE,
            decision
        )
    }
}
