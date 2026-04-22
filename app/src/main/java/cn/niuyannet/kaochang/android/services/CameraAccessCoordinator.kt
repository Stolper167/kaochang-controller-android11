package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.utils.LogUtils
import java.util.concurrent.atomic.AtomicReference

object CameraAccessCoordinator {
    private val currentOwner = AtomicReference<String?>(null)

    @JvmStatic
    fun tryAcquire(owner: String): Boolean {
        while (true) {
            val holder = currentOwner.get()
            if (holder == null) {
                if (currentOwner.compareAndSet(null, owner)) {
                    LogUtils.d("【相机协调】$owner 获取相机所有权")
                    return true
                }
                continue
            }
            if (holder == owner) {
                return true
            }
            LogUtils.w("【相机协调】$owner 获取相机所有权失败，当前占用者=$holder")
            return false
        }
    }

    @JvmStatic
    fun release(owner: String) {
        if (currentOwner.compareAndSet(owner, null)) {
            LogUtils.d("【相机协调】$owner 释放相机所有权")
        }
    }

    @JvmStatic
    fun currentOwner(): String? = currentOwner.get()
}
