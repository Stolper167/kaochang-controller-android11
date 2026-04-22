package cn.niuyannet.kaochang.android.utils

import java.util.concurrent.atomic.AtomicReference

object MaintenanceUiRefreshBridge {

    private val refresherRef = AtomicReference<((String) -> Unit)?>(null)

    fun register(refresher: (String) -> Unit) {
        refresherRef.set(refresher)
    }

    fun unregister(refresher: ((String) -> Unit)? = null) {
        if (refresher == null) {
            refresherRef.set(null)
            return
        }
        refresherRef.compareAndSet(refresher, null)
    }

    fun requestRefresh(reason: String) {
        refresherRef.get()?.invoke(reason)
    }
}
