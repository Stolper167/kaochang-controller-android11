package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor

class QueueData(
    val roiFlag: Int,
    val delay: Long,
    val timeoutMs: Long,
    val retry: Int,
    val callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
)
