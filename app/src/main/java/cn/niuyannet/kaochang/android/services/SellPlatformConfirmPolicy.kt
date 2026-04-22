package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor

object SellPlatformConfirmPolicy {

    enum class InitialConfirmationDecision {
        VISION_UNAVAILABLE_ASSUME_DELIVERED,
        WAIT_FOR_DELIVERY_OR_FAST_TAKE,
        WAIT_FOR_USER_TAKE
    }

    fun evaluateInitialConfirmation(
        presenceResults: List<SauceDetectionProcessor.SausageInfo?>
    ): InitialConfirmationDecision {
        val detectedSausageInQuickRetries = presenceResults.any { it?.code == 2 }
        val allPresenceCapturesFailed = presenceResults.all { it == null }
        return when {
            allPresenceCapturesFailed -> InitialConfirmationDecision.VISION_UNAVAILABLE_ASSUME_DELIVERED
            detectedSausageInQuickRetries -> InitialConfirmationDecision.WAIT_FOR_USER_TAKE
            else -> InitialConfirmationDecision.WAIT_FOR_DELIVERY_OR_FAST_TAKE
        }
    }
}
