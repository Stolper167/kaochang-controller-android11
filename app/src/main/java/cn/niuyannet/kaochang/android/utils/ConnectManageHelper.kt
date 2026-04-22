package cn.niuyannet.kaochang.android.utils

import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object ConnectManageHelper {
    private var connectLun = true

    @OptIn(DelicateCoroutinesApi::class)
    fun lunStatus(callback: () -> Unit) {
        connectLun = true
        GlobalScope.launch(Dispatchers.IO) {
            // 初始连接：MQTT + 不调 Modbus
            VMMqttHelper.connect { }
            GlobalScope.launch(Dispatchers.Main) {
                callback.invoke()
            }

            // 轮询目前只负责 Modbus 层的重连维护，同时增加对 MQTT 状态的定时巡检作为兜底
            while (connectLun) {
                delay(30 * 1000L) // 每 30 秒检查一次（降低频率，减少与闭环重试的冲突）
                if (!connectLun) {
                    break
                }
                
                // 【新增巡检】如果发现 MQTT 没连上，主动触发一次辅助重连
                if (!VMMqttHelper.connectStatus()) {
                    LogUtils.w("【MQTT巡检】定时巡检发现连接已断开，正在尝试发起兜底重连...")
                    VMMqttHelper.triggerManualReconnect("30秒巡检兜底")
                }

                VMModbusHelper.connectModbus { _ -> }
            }
        }
    }

    fun disConnect() {
        connectLun = false
        VMMqttHelper.unAllSubscribe()
        VMModbusHelper.isLunXun = false
        VMMqttHelper.disconnect()
        VMModbusHelper.disconnectModbusMaster()
        
        // 【新增生命周期管理】释放所有待执行的 MQTT 重连任务，防止 App 状态残留
        VMMqttHelper.cleanupReconnectTasks()
    }
}
