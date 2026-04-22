package cn.niuyannet.kaochang.android.utils

import android.content.Context
import android.content.SharedPreferences
import cn.niuyannet.kaochang.android.MyApp
import com.alibaba.fastjson.JSON
import java.util.concurrent.ConcurrentHashMap

/**
 * 独立的订单防重复原子状态机。
 * 解决 MQTT QoS1 或弱网带来的重发导致重复出餐问题。
 */
object OrderStateManager {

    // 状态定义
    const val STATE_RECEIVED_BUT_BUSY = 0    // 机器忙，仅收到消息。不作为消费依据，允许重试。
    const val STATE_ACCEPTED_PROCESSING = 1  // 已确定放入出餐链路，绝不允许同 orderNo 中途二次插入。
    const val STATE_FINISHED = 2             // 出餐结束终态 (持久化)
    const val STATE_FAILED = 3               // 完全失败且无法通过简单重投恢复的终态 (持久化)

    private const val SP_NAME = "order_states_sp"
    private const val TTL_MS = 24 * 60 * 60 * 1000L // 终态数据的存储 TTL：24小时

    data class OrderState(
        var orderNo: String = "",
        var state: Int = STATE_RECEIVED_BUT_BUSY,
        var timestamp: Long = 0L
    )

    // 纯内存状态记录 (包含 PROCESSING 和 BUSY)
    private val memoryStates = ConcurrentHashMap<String, OrderState>()

    /**
     * 只有机器允许入场（非忙状态）时才调用此方法进行原子核验与准入。
     *
     * @return true = 允许接单出餐; false = 已被拦截（属于重复执行请求或已经处于终态）
     */
    @Synchronized
    fun checkAndAcceptOrder(orderNo: String): Boolean {
        if (orderNo.isBlank()) {
            LogUtils.e("【订单防重】拦截极高危异常报文：orderNo 缺失，立刻拒绝业务执行防 Phantom 出餐漏洞！")
            return false 
        }

        cleanExpiredStatesInMemory()

        val existState = getOrderState(orderNo)
        when (existState?.state) {
            STATE_ACCEPTED_PROCESSING -> {
                LogUtils.w("【订单状态机】防重复拦截：该订单已经在下位机处理队列中：orderNo=$orderNo")
                return false
            }
            STATE_FINISHED, STATE_FAILED -> {
                LogUtils.w("【订单状态机】防重复拦截：该订单已处于历史终态机制内：orderNo=$orderNo, 终态=${existState.state}")
                return false
            }
            STATE_RECEIVED_BUT_BUSY -> {
                LogUtils.d("【订单状态机】合法补发单入场允许！该单上次因设备忙碌被暂拒，现允许执行出餐阶段：orderNo=$orderNo")
            }
            else -> {
                // 新单子，符合首次接收链路
            }
        }

        // 统一原子切换为主处理态
        val stateObj = OrderState(orderNo, STATE_ACCEPTED_PROCESSING, System.currentTimeMillis())
        memoryStates[orderNo] = stateObj
        LogUtils.w("【订单状态机】状态锁定期：订单进入执行闭环态 (ACCEPTED_PROCESSING)，其他同号消息将遭拦截：orderNo=$orderNo")
        return true
    }

    /**
     * 当设备正忙无法接单时使用。
     * 本状态纯用作日志追溯和状态过渡标记，不代表已消费锁定。
     */
    @Synchronized
    fun recordBusyIgnored(orderNo: String) {
        if (orderNo.isBlank()) return
        val existState = getOrderState(orderNo)
        // 阶梯式门禁：已经是主处理或终态的情况下，不得倒退状态
        if (existState?.state == STATE_ACCEPTED_PROCESSING || existState?.state == STATE_FINISHED || existState?.state == STATE_FAILED) {
            return
        }
        memoryStates[orderNo] = OrderState(orderNo, STATE_RECEIVED_BUT_BUSY, System.currentTimeMillis())
        LogUtils.w("【订单状态机】拒止分流降阶 (RECEIVED_BUT_BUSY)：orderNo=$orderNo")
    }

    /**
     * 对处理中途临时失败由于未到终态引发的废弃，提供安全退出的内存钩子。
     */
    @Synchronized
    fun releaseProcessing(orderNo: String) {
        if (orderNo.isBlank()) return
        val existState = memoryStates[orderNo]
        if (existState?.state == STATE_ACCEPTED_PROCESSING) {
            memoryStates.remove(orderNo)
            LogUtils.w("【订单状态机】释放单次执行处理锁 (PROCESSING 解锁)，允许未来合法流转与重投进入：orderNo=$orderNo")
        }
    }

    /**
     * 标记交易最终完成并向缓存持久化，以抵抗系统杀死重启导致的防重新丢。
     */
    @Synchronized
    fun markAsFinished(orderNo: String) {
        if (orderNo.isBlank()) return
        LogUtils.d("【订单状态机】终态锁定 (FINISHED)：orderNo=$orderNo")
        val stateObj = OrderState(orderNo, STATE_FINISHED, System.currentTimeMillis())
        memoryStates.remove(orderNo)
        saveTerminalState(stateObj)
    }

    /**
     * 标记交易报废。
     */
    @Synchronized
    fun markAsFailed(orderNo: String) {
        if (orderNo.isBlank()) return
        LogUtils.e("【订单状态机】报废锁定 (FAILED)：orderNo=$orderNo")
        val stateObj = OrderState(orderNo, STATE_FAILED, System.currentTimeMillis())
        memoryStates.remove(orderNo)
        saveTerminalState(stateObj)
    }

    // ============================================
    // =              内部持久化和清理            =
    // ============================================

    private fun getOrderState(orderNo: String): OrderState? {
        val memory = memoryStates[orderNo]
        if (memory != null) return memory

        val sp = getSp() ?: return null
        val jsonStr = sp.getString(orderNo, null) ?: return null
        try {
            val stateObj = JSON.parseObject(jsonStr, OrderState::class.java)
            if (System.currentTimeMillis() - stateObj.timestamp > TTL_MS) {
                sp.edit().remove(orderNo).apply()
                return null
            }
            return stateObj
        } catch (_: Exception) {}
        return null
    }

    private fun saveTerminalState(stateObj: OrderState) {
        val sp = getSp() ?: return
        try {
            sp.edit().putString(stateObj.orderNo, JSON.toJSONString(stateObj)).apply()
        } catch (_: Exception) {}
    }

    /**
     * 每收到请求或需要时进行轻度惰性清理，避免 HashMap 无限膨胀
     */
    private fun cleanExpiredStatesInMemory() {
        val now = System.currentTimeMillis()
        val memKeys = memoryStates.keys().toList()
        for (key in memKeys) {
            memoryStates[key]?.let {
                // 如果内存锁态（PROCESSING 或 BUSY）维持超过 3 小时没进入终态，强制解除锁以防止意外永久死锁
                if (now - it.timestamp > 3 * 3600_000L) {
                    memoryStates.remove(key)
                    LogUtils.w("【订单状态机】自动释放孤儿长生命周期内存订单锁：orderNo=$key")
                }
            }
        }
    }

    /**
     * 可在恰当时刻执行的全量缓存清理（例如每日夜间或应用启动时）
     */
    fun performFullSpCleanup() {
        val sp = getSp() ?: return
        val now = System.currentTimeMillis()
        val all = sp.all
        val editor = sp.edit()
        var cleaned = 0
        all.forEach { (key, value) ->
            if (value is String) {
                try {
                    val stateObj = JSON.parseObject(value, OrderState::class.java)
                    if (now - stateObj.timestamp > TTL_MS) {
                        editor.remove(key)
                        cleaned++
                    }
                } catch (_: Exception) {
                    editor.remove(key)
                }
            }
        }
        editor.apply()
        if (cleaned > 0) {
            LogUtils.d("【订单状态机】SP全量清理执行完毕，共释放 $cleaned 条过期长存历史订单。")
        }
    }

    private fun getSp(): SharedPreferences? {
        return try {
            MyApp.instance().applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }
}
