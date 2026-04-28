package cn.niuyannet.kaochang.android.mqtt

import android.os.Handler
import android.os.Looper
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.DeviceStateText
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * MQTT 业务回调封装。
 */
object VMMqttHelper : VMMqtt() {

    const val topic_by_server: String = "/cl/devices/{device_id}/get"

    private const val DISCONNECT_LOG_WINDOW_MS = 5_000L
    private var lastDisconnectLogAtMs = 0L
    private var lastDisconnectCause = ""
    private var reconnectAttempt = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    @Volatile
    private var lastAvailableAckLogKey: String? = null

    private fun availableDisplayText(available: Long): String {
        return AvailableStateLogFormatter.displayText(available)
    }

    internal fun buildAvailableAckLogKey(
        available: Long,
        applied: Boolean,
        reason: String
    ): String {
        return AvailableStateLogFormatter.buildAckLogKey(
            available = available,
            applied = applied,
            reason = reason
        )
    }

    private fun shouldLogDisconnect(causeText: String): Boolean {
        val now = System.currentTimeMillis()
        val shouldLog =
            causeText != lastDisconnectCause || now - lastDisconnectLogAtMs >= DISCONNECT_LOG_WINDOW_MS
        if (shouldLog) {
            lastDisconnectCause = causeText
            lastDisconnectLogAtMs = now
        }
        return shouldLog
    }

    private fun runtimeAckStateText(
        status: Int,
        onlineStatus: Int,
        supplyStatus: Int,
        isEnable: Int,
        modeType: Int,
        transitionMode: Int,
        moveStatus: Int,
        errorStatus: Int
    ): String {
        return "status（设备启用状态）=${DeviceStateText.serviceStatus(status)}，" +
            "onlineStatus（设备营业状态）=${DeviceStateText.onlineStatus(onlineStatus)}，" +
            "supplyStatus（补货状态）=$supplyStatus，" +
            "isEnable（烤肠算法开关）=${DeviceStateText.isEnable(isEnable)}，" +
            "modeType（并发模式）=${DeviceStateText.modeType(modeType)}，" +
            "transitionMode（并发切换过渡态）=${DeviceStateText.transitionMode(transitionMode)}，" +
            "moveStatus（机械动作状态）=${DeviceStateText.moveStatus(moveStatus)}，" +
            "errorStatus（设备故障状态）=${DeviceStateText.errorStatus(errorStatus)}"
    }

    private val messageListeners = ConcurrentHashMap<String, (TopicMessage) -> Unit>()

    private fun consumeRuntimeStateAckIfNeeded(topicMessage: TopicMessage): Boolean {
        if (topicMessage.action != MqttProtocol.ACTION_SERVICE_STATE) {
            return false
        }
        val content = JSON.parseObject(topicMessage.content ?: "{}")
        if (content.getString("messageType").orEmpty() != MqttProtocol.TYPE_RUNTIME_STATE_ACK) {
            return false
        }
        val runtimeSeq = content.getLongValue("runtimeSeq")
        val applied = content.getBooleanValue("applied")
        val reason = content.getString("reason").orEmpty()
        val status = content.getIntValue("status")
        val onlineStatus = content.getIntValue("onlineStatus")
        val supplyStatus = content.getIntValue("supplyStatus")
        val isEnable = content.getIntValue("isEnable")
        val modeType = content.getIntValue("modeType")
        val transitionMode = content.getIntValue("transitionMode")
        val moveStatus = content.getIntValue("moveStatus")
        val errorStatus = content.getIntValue("errorStatus")
        val ackTs = content.getLongValue("ackTs")
        val ackResult = SendServerHelper.markRuntimeStateAcked(runtimeSeq)
        val payloadLength = (topicMessage.content ?: "").length
        LogUtils.d(
            "【MQTT业务】收到运行时状态回执：" +
                "action=2（设备状态同步），runtimeSeq=$runtimeSeq，payloadLength=$payloadLength"
        )
        if (applied) {
            LogUtils.d(
                "【MQTT运行态】服务端已确认运行时状态：" +
                    "runtimeSeq=$runtimeSeq，freshAck=${ackResult.freshAck}，reason=$reason，" +
                    runtimeAckStateText(
                        status = status,
                        onlineStatus = onlineStatus,
                        supplyStatus = supplyStatus,
                        isEnable = isEnable,
                        modeType = modeType,
                        transitionMode = transitionMode,
                        moveStatus = moveStatus,
                        errorStatus = errorStatus
                    ) +
                    "，ackTs=$ackTs，ackLatencyMs=${ackResult.ackLatencyMs ?: -1L}，" +
                    "source=${ackResult.snapshot?.source ?: "unknown"}"
            )
        } else {
            LogUtils.w(
                "【MQTT运行态】服务端未应用运行时状态：" +
                    "runtimeSeq=$runtimeSeq，freshAck=${ackResult.freshAck}，reason=$reason，" +
                    runtimeAckStateText(
                        status = status,
                        onlineStatus = onlineStatus,
                        supplyStatus = supplyStatus,
                        isEnable = isEnable,
                        modeType = modeType,
                        transitionMode = transitionMode,
                        moveStatus = moveStatus,
                        errorStatus = errorStatus
                    ) +
                    "，ackTs=$ackTs，ackLatencyMs=${ackResult.ackLatencyMs ?: -1L}，" +
                    "source=${ackResult.snapshot?.source ?: "unknown"}"
            )
        }
        return true
    }

    private fun consumeAvailableStateAckIfNeeded(topicMessage: TopicMessage): Boolean {
        if (topicMessage.action != MqttProtocol.ACTION_SERVICE_STATE) {
            return false
        }
        val content = JSON.parseObject(topicMessage.content ?: "{}")
        if (content.getString("messageType").orEmpty() != MqttProtocol.TYPE_AVAILABLE_STATE_ACK) {
            return false
        }
        val availableSeq = content.getLongValue("availableSeq")
        val applied = content.getBooleanValue("applied")
        val reason = content.getString("reason").orEmpty()
        val available = content.getLongValue("available")
        val ackResult = SendServerHelper.markAvailableStateAcked(availableSeq)
        if (applied) {
            val logKey = buildAvailableAckLogKey(available = available, applied = true, reason = reason)
            if (lastAvailableAckLogKey != logKey) {
                lastAvailableAckLogKey = logKey
                LogUtils.d(
                    "【MQTT展示态】服务端已确认首页展示态：" +
                        "seq=$availableSeq，结果=已应用，文案=${availableDisplayText(available)}，" +
                        "耗时=${ackResult.ackLatencyMs ?: -1L}ms"
                )
            }
        } else {
            LogUtils.w(
                "【MQTT展示态】服务端未应用首页展示态：" +
                    "seq=$availableSeq，原因=$reason，文案=${availableDisplayText(available)}，" +
                    "耗时=${ackResult.ackLatencyMs ?: -1L}ms"
            )
        }
        return true
    }

    private fun summarizeServiceStateMessage(payload: String): String {
        val topicMessage = JSON.parseObject(payload, TopicMessage::class.java)
        val content = JSON.parseObject(topicMessage.content ?: "{}")
        val messageType = content.getString("messageType").orEmpty()
        val controlSeq = content.getLongValue("controlSeq")
        val onlineStatus = content.getString("onlineStatus").orEmpty()
        val isEnable = if (content.containsKey("isEnable")) content.getString("isEnable") else ""
        val modeType = if (content.containsKey("modeType")) content.getString("modeType") else ""
        val transitionMode = if (content.containsKey("transitionMode")) content.getString("transitionMode") else ""
        val refreshRemoteConfig =
            if (content.containsKey("refreshRemoteConfig")) content.getString("refreshRemoteConfig") else ""
        return when (messageType) {
            MqttProtocol.TYPE_RUNTIME_CONTROL ->
                "收到运行时控制：action=2（设备状态同步），controlSeq=$controlSeq, " +
                    "onlineStatus（设备营业状态）=${onlineStatus.ifBlank { "未携带" }}, " +
                    "isEnable（烤肠算法开关）=${isEnable.ifBlank { "未携带" }}, refreshRemoteConfig=$refreshRemoteConfig"
            MqttProtocol.TYPE_CONCURRENCY_CONTROL ->
                "收到并发控制：action=2（设备状态同步），controlSeq=$controlSeq, " +
                    "modeType（并发模式）=${modeType.ifBlank { "未携带" }}, " +
                    "transitionMode（并发切换过渡态）=${transitionMode.ifBlank { "未携带" }}"
            MqttProtocol.TYPE_RUNTIME_CONTROL_ACK ->
                "收到运行时控制回执：action=2（设备状态同步），controlSeq=$controlSeq, payloadLength=${payload.length}"
            MqttProtocol.TYPE_RUNTIME_STATE_ACK -> {
                val runtimeSeq = content.getLongValue("runtimeSeq")
                "收到运行时状态回执：action=2（设备状态同步），runtimeSeq=$runtimeSeq, payloadLength=${payload.length}"
            }
            MqttProtocol.TYPE_AVAILABLE_STATE_ACK -> {
                val availableSeq = content.getLongValue("availableSeq")
                "收到首页展示态回执：seq=$availableSeq"
            }
            MqttProtocol.TYPE_AVAILABLE_STATE ->
                "收到首页展示态消息：messageType=$messageType"
            MqttProtocol.TYPE_RUNTIME_STATE ->
                "收到运行时状态消息：action=2（设备状态同步），messageType=$messageType, payloadLength=${payload.length}"
            else ->
                "收到设备状态下发：action=2（设备状态同步），messageType=${messageType.ifBlank { "unknown" }}, payloadLength=${payload.length}"
        }
    }

    private fun summarizeInboundMessage(action: Int, payload: String): String {
        return try {
            when (action) {
                MqttProtocol.ACTION_ORDER_EVENT -> {
                    val topicMessage = JSON.parseObject(payload, TopicMessage::class.java)
                    val content = JSON.parseObject(topicMessage.content ?: "{}")
                    val orderNo = content.getString("order_no").orEmpty()
                    val goodsItems = content.getJSONArray("goods_items")
                    val itemCount = goodsItems?.size ?: 0
                    "收到下发订单：action=1（订单指令），orderNo=${orderNo.ifBlank { "未知" }}, itemCount=$itemCount"
                }
                MqttProtocol.ACTION_SERVICE_STATE -> summarizeServiceStateMessage(payload)
                MqttProtocol.ACTION_DEVICE_LOG ->
                    "收到设备动作日志消息：action=3，payloadLength=${payload.length}"
                else -> "收到下发消息：action=$action，payloadLength=${payload.length}"
            }
        } catch (_: Exception) {
            "收到下发消息：action=$action，payloadLength=${payload.length}"
        }
    }

    /**
     * 对上层暴露生命周期安全的监听接口，必须使用稳定的 ownerTag 进行注册。
     */
    fun registerMessageListener(ownerTag: String, listener: (TopicMessage) -> Unit) {
        messageListeners[ownerTag] = listener
        LogUtils.d("【MQTT业务】注册业务消息监听器成功：ownerTag=$ownerTag, 当前监听器数量=${messageListeners.size}")
    }

    /**
     * 在组件销毁时，精确按 ownerTag 注销，防止 Activity 和闭包引用泄漏。
     */
    fun unregisterMessageListener(ownerTag: String) {
        val removed = messageListeners.remove(ownerTag)
        if (removed != null) {
            LogUtils.d("【MQTT业务】注销业务消息监听器成功：ownerTag=$ownerTag, 当前监听器数量=${messageListeners.size}")
        }
    }

    /**
     * 重写底层绑定钩子：每次新建底层 Client 连接成功后，接管其回调监听，并严格核验代次 generation。
     */
    override fun bindCallbackAndSubscribe(client: MqttAsyncClient, generation: Long, callback: (Boolean) -> Unit) {
        LogUtils.d("【MQTT业务】初始化当前代次回调引擎：generation=$generation")
        client.setCallback(object : MqttCallback {
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (generation != connectionGeneration) {
                    LogUtils.w("【MQTT业务】拦截陈旧连接残留的消息：topic=$topic, hookGen=$generation, curGen=$connectionGeneration")
                    return
                }
                
                val expectedTopic = AppConfig.getTopicDeviceIdUrl(topic_by_server)
                if (expectedTopic == topic) {
                    try {
                        val payload = String(message?.payload ?: ByteArray(0))
                        val bean = JSON.parseObject(payload, TopicMessage::class.java)
                        if (consumeRuntimeStateAckIfNeeded(bean)) {
                            return
                        }
                        if (consumeAvailableStateAckIfNeeded(bean)) {
                            return
                        }
                        LogUtils.d(
                            "【MQTT业务】${summarizeInboundMessage(bean.action, payload)}，" +
                                "generation=$generation，topic=$topic"
                        )
                        
                        // 分发给所有安全注册的消费者
                        messageListeners.values.forEach { it.invoke(bean) }
                        
                    } catch (e: Exception) {
                        LogUtils.e("【MQTT业务】下发消息解析失败：topic=$topic, error=${e.message}")
                    }
                }
            }

            override fun connectionLost(cause: Throwable?) {
                if (generation != connectionGeneration) {
                    LogUtils.w("【MQTT连接】拦截陈旧连接残留的断开事件：hookGen=$generation, curGen=$connectionGeneration")
                    return
                }

                val causeText = cause?.toString() ?: "未知原因"
                if (shouldLogDisconnect(causeText)) {
                    LogUtils.w(
                        "【MQTT连接】监听到底层连接丢失 (deviceId=${AppConfig.getDeviceId()}) | 代次=$generation | 原因=$causeText"
                    )
                }

                // 连接已断，释放本代次 WakeLock；重连流程会获取新的
                releaseWakeLock(generation)

                scheduleReconnect("断连回调触发")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                if (generation == connectionGeneration) {
                    LogUtils.d("【MQTT发布】投递完成 | 代次=$generation")
                }
            }
        })
        
        // 最后执行父类的全量订阅（真正下发 callback）
        super.bindCallbackAndSubscribe(client, generation, callback)
    }

    /**
     * 统一重连调度器：负责计算延迟并执行闭环重连（屡败屡战）
     * @param reason 触发重连的场景说明
     */
    private fun scheduleReconnect(reason: String) {
        // 【并发防冲突】如果已经在连接中，不启动新的调度
        synchronized(this) {
            if (isConnecting) {
                LogUtils.w(
                    "【MQTT连接】重复重连请求已跳过：reason=$reason，" +
                        "isConnecting（MQTT连接握手中标记）=1（正在连接）"
                )
                return
            }
            if (reconnectRunnable != null) {
                LogUtils.d("【MQTT连接】安全重载：放弃本次调度，重连队列中已有待办任务 | 触发原因=$reason，当前 attempt=$reconnectAttempt")
                return
            }
        }

        // 计算延迟：最小2秒，每次增加2秒，最大30秒
        reconnectAttempt++
        val delayMs = minOf(2000L * reconnectAttempt, 30000L)
        
        LogUtils.w("【MQTT连接】计划在 ${delayMs}ms 后发起第 ${reconnectAttempt} 次重连尝试 | 触发原因=$reason")

        reconnectRunnable = Runnable {
            // 执行之前先将占用标记清理，允许递归调度产生新的动作
            synchronized(this) { reconnectRunnable = null }
            
            // 最终执行连接前再检查一次，防止已连接
            if (!connectStatus()) {
                LogUtils.w("【MQTT连接】开始执行第 ${reconnectAttempt} 次手动连接任务...")
                connect { success ->
                    if (success) {
                        LogUtils.d("【MQTT连接】恭喜，手动重连成功！已重置尝试计数器")
                        reconnectAttempt = 0
                    } else {
                        LogUtils.e("【MQTT连接】本次连接尝试失败，将自动进入下一轮闭环调配...")
                        // 【核心优化】只要没连上，就递归调用自己进行下一次调度
                        scheduleReconnect("闭环失败重试")
                    }
                }
            } else {
                LogUtils.d("【MQTT连接】同步检查发现已是在线状态，终止重连队列")
                reconnectAttempt = 0
            }
        }
        reconnectHandler.postDelayed(reconnectRunnable!!, delayMs)
    }

    /**
     * 外部手动触发重连的入口 (如巡检发现掉线时调用)
     */
    fun triggerManualReconnect(reason: String) {
        if (!connectStatus()) {
            scheduleReconnect(reason)
        }
    }

    /**
     * 清理所有累积的重连任务 (用于 App 退出或主动断连场景)
     */
    fun cleanupReconnectTasks() {
        synchronized(this) {
            reconnectRunnable?.let {
                reconnectHandler.removeCallbacks(it)
                LogUtils.d("【MQTT清理】已成功注销所有待执行的异步重连任务")
            }
            reconnectRunnable = null
            reconnectAttempt = 0
        }
    }
}
