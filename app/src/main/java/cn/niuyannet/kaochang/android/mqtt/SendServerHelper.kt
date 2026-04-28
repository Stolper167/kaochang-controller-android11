package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.DeviceStateText
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import cn.niuyannet.kaochang.android.init.AppConfigBean

object SendServerHelper {
    private const val RUNTIME_STATE_ACK_TIMEOUT_MS = 8_000L
    private const val AVAILABLE_STATE_ACK_TIMEOUT_MS = 8_000L

    private val runtimeStateSeq = AtomicLong(System.currentTimeMillis())
    private val runtimeStateAckHandler = Handler(Looper.getMainLooper())
    private val pendingRuntimeStateAcks = ConcurrentHashMap<Long, RuntimeStatePublishSnapshot>()
    private val availableStateSeq = AtomicLong(System.currentTimeMillis())
    private val availableStateAckHandler = Handler(Looper.getMainLooper())
    private val pendingAvailableStateAcks = ConcurrentHashMap<Long, AvailableStatePublishSnapshot>()
    @Volatile
    private var lastPublishedRuntimeStateSeq: Long = -1L
    @Volatile
    private var lastAckedRuntimeStateSeq: Long = -1L
    @Volatile
    private var lastPublishedAvailableStateSeq: Long = -1L
    @Volatile
    private var lastAckedAvailableStateSeq: Long = -1L
    @Volatile
    private var activeRuntimeStateSignature: String? = null
    @Volatile
    private var activeRuntimeStateSeq: Long = -1L
    @Volatile
    private var lastAvailablePublishLogKey: String? = null
    @Volatile
    private var lastAvailableAckTimeoutLogKey: String? = null

    private fun availableDisplayText(available: Long): String {
        return AvailableStateLogFormatter.displayText(available)
    }

    private fun availableSourceText(source: String): String {
        return source.substringBefore(':').ifBlank { source }
    }

    private fun runtimeStateText(
        status: Int,
        onlineStatus: Int,
        supplyStatus: Int,
        scanBlockStatus: Int,
        inspectionMode: Int,
        isEnable: Int,
        modeType: Int,
        transitionMode: Int,
        moveStatus: Int,
        errorStatus: Int,
        restStatusSource: String?
    ): String {
        return "status（设备启用状态）=${DeviceStateText.serviceStatus(status)}，" +
            "onlineStatus（设备营业状态）=${DeviceStateText.onlineStatus(onlineStatus)}，" +
            "supplyStatus（补货状态）=$supplyStatus，" +
            "scanBlockStatus（前台屏蔽状态）=$scanBlockStatus，" +
            "inspectionMode（检修模式）=$inspectionMode，" +
            "isEnable（烤肠算法开关）=${DeviceStateText.isEnable(isEnable)}，" +
            "modeType（并发模式）=${DeviceStateText.modeType(modeType)}，" +
            "transitionMode（并发切换过渡态）=${DeviceStateText.transitionMode(transitionMode)}，" +
            "moveStatus（机械动作状态）=${DeviceStateText.moveStatus(moveStatus)}，" +
            "errorStatus（设备故障状态）=${DeviceStateText.errorStatus(errorStatus)}，" +
            "restStatusSource（休息中来源）=${DeviceStateText.restStatusSource(restStatusSource)}"
    }

    internal fun buildAvailableStateLogKey(available: Long): String {
        return AvailableStateLogFormatter.buildStateLogKey(available)
    }

    data class RuntimeControlAckPayload(
        val controlSeq: Long,
        val applied: Boolean,
        val reason: String,
        val onlineStatus: Int,
        val supplyStatus: Int,
        val scanBlockStatus: Int,
        val inspectionMode: Int,
        val isEnable: Int,
        val restStatusSource: String?
    )

    data class ConcurrencyControlAckPayload(
        val controlSeq: Long,
        val applied: Boolean,
        val changed: Boolean,
        val reason: String,
        val modeType: Int,
        val transitionMode: Int
    )

    data class RuntimeStatePublishSnapshot(
        val runtimeSeq: Long,
        val source: String,
        val status: Int,
        val onlineStatus: Int,
        val supplyStatus: Int,
        val scanBlockStatus: Int,
        val inspectionMode: Int,
        val isEnable: Int,
        val modeType: Int,
        val transitionMode: Int,
        val moveStatus: Int,
        val errorStatus: Int,
        val restStatusSource: String?,
        val stateSignature: String,
        val publishedAt: Long
    )

    data class RuntimeStateAckResult(
        val freshAck: Boolean,
        val snapshot: RuntimeStatePublishSnapshot?,
        val ackLatencyMs: Long?
    )

    data class AvailableStateAckPayload(
        val availableSeq: Long,
        val applied: Boolean,
        val reason: String,
        val available: Long,
        val availableCountdownLatched: Int
    )

    data class AvailableStatePublishSnapshot(
        val availableSeq: Long,
        val source: String,
        val available: Long,
        val availableCountdownLatched: Int,
        val publishedAt: Long
    )

    data class AvailableStateAckResult(
        val freshAck: Boolean,
        val snapshot: AvailableStatePublishSnapshot?,
        val ackLatencyMs: Long?
    )

    /**
     * 通过 MQTT 上报订单动作事件（action=1）。
     * `status（订单事件状态）` 当前约定：
     * - 1 = 开始夹取
     * - 2 = 单次履约完成，顾客已取走
     * - 3 = 已出餐但顾客未取，系统已丢弃
     * - 5 = 单次履约失败
     */
    fun publishServiceUpdateOrder(msgObj: JSONObject): Boolean {
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_ORDER_EVENT
                content = JSON.toJSONString(msgObj)
            }
        )
    }

    /**
     * 通过 MQTT 上报设备运行时状态（action=2）。
     * 当前第一批收口的主字段：
     * - onlineStatus（设备营业状态）
     * - isEnable（烤肠算法开关）
     *
     * 仍保留必要运行态字段，便于服务端继续复用现有消费逻辑。
     */
    fun publishServiceUpdateStatus(source: String = "runtime_state:auto"): Boolean {
        val config = AppConfig.getAppConfig()
        val now = System.currentTimeMillis()
        val stateSignature = buildRuntimeStateSignature(config)
        val (seq, reusedSeq) = resolveRuntimeStateSeq(stateSignature)
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_RUNTIME_STATE
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["runtimeSeq"] = seq
        payload["status"] = config.status
        payload["onlineStatus"] = config.onlineStatus
        payload["supplyStatus"] = config.supplyStatus
        payload["scanBlockStatus"] = config.scanBlockStatus
        payload["inspectionMode"] = config.inspectionMode
        payload["isEnable"] = AppConfig.getAlgorithmEnableFlag()
        payload["modeType"] = config.modeType
        payload["transitionMode"] = config.transitionMode
        payload["moveStatus"] = config.moveStatus
        payload["errorStatus"] = config.errorStatus
        payload["restStatusSource"] = config.restStatusSource
        payload["source"] = source
        payload["reportedAt"] = now
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
        if (ok) {
            lastPublishedRuntimeStateSeq = seq
            val snapshot = RuntimeStatePublishSnapshot(
                runtimeSeq = seq,
                source = source,
                status = config.status,
                onlineStatus = config.onlineStatus,
                supplyStatus = config.supplyStatus,
                scanBlockStatus = config.scanBlockStatus,
                inspectionMode = config.inspectionMode,
                isEnable = AppConfig.getAlgorithmEnableFlag(),
                modeType = config.modeType,
                transitionMode = config.transitionMode,
                moveStatus = config.moveStatus,
                errorStatus = config.errorStatus,
                restStatusSource = config.restStatusSource,
                stateSignature = stateSignature,
                publishedAt = now
            )
            pendingRuntimeStateAcks[seq] = snapshot
            scheduleRuntimeStateAckTimeout(snapshot)
            LogUtils.d(
                "【MQTT运行态】已上报运行时状态：" +
                    "runtimeSeq=$seq，reusedSeq=$reusedSeq，source=$source，" +
                    runtimeStateText(
                        status = config.status,
                        onlineStatus = config.onlineStatus,
                        supplyStatus = config.supplyStatus,
                        scanBlockStatus = config.scanBlockStatus,
                        inspectionMode = config.inspectionMode,
                        isEnable = AppConfig.getAlgorithmEnableFlag(),
                        modeType = config.modeType,
                        transitionMode = config.transitionMode,
                        moveStatus = config.moveStatus,
                        errorStatus = config.errorStatus,
                        restStatusSource = config.restStatusSource
                    )
            )
        } else {
            LogUtils.w(
                "【MQTT运行态】运行时状态上报失败：" +
                    "runtimeSeq=$seq，reusedSeq=$reusedSeq，source=$source，" +
                    runtimeStateText(
                        status = config.status,
                        onlineStatus = config.onlineStatus,
                        supplyStatus = config.supplyStatus,
                        scanBlockStatus = config.scanBlockStatus,
                        inspectionMode = config.inspectionMode,
                        isEnable = AppConfig.getAlgorithmEnableFlag(),
                        modeType = config.modeType,
                        transitionMode = config.transitionMode,
                        moveStatus = config.moveStatus,
                        errorStatus = config.errorStatus,
                        restStatusSource = config.restStatusSource
                    )
            )
        }
        return ok
    }

    /**
     * 通过 MQTT 上报首页展示相关状态（action=2）。
     * 当前只收口：
     * - available（二维码页预计时间）
     * - availableCountdownLatched（二维码页预计时间锁存标记）
     */
    fun publishAvailableState(source: String): Boolean {
        val config = AppConfig.getAppConfig()
        val seq = availableStateSeq.incrementAndGet()
        val now = System.currentTimeMillis()
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_AVAILABLE_STATE
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["availableSeq"] = seq
        payload["available"] = config.available
        payload["availableCountdownLatched"] = config.availableCountdownLatched
        payload["onlineStatus"] = config.onlineStatus
        payload["status"] = config.status
        payload["source"] = source
        payload["reportedAt"] = now
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
        if (ok) {
            lastPublishedAvailableStateSeq = seq
            val snapshot = AvailableStatePublishSnapshot(
                availableSeq = seq,
                source = source,
                available = config.available,
                availableCountdownLatched = config.availableCountdownLatched,
                publishedAt = now
            )
            pendingAvailableStateAcks[seq] = snapshot
            scheduleAvailableStateAckTimeout(snapshot)
            val logKey = buildAvailableStateLogKey(config.available)
            if (lastAvailablePublishLogKey != logKey) {
                lastAvailablePublishLogKey = logKey
                LogUtils.d(
                    "【MQTT展示态】已上报首页展示态：seq=$seq，文案=${availableDisplayText(config.available)}，来源=${availableSourceText(source)}"
                )
            }
        } else {
            LogUtils.w(
                "【MQTT展示态】首页展示态上报失败：文案=${availableDisplayText(config.available)}，来源=${availableSourceText(source)}"
            )
        }
        return ok
    }

    /**
     * 回执远端运行时控制（action=2）。
     * ack 仅确认“是否应用了这条 controlSeq”，不承担设备状态同步职责。
     */
    fun publishRuntimeControlAck(ack: RuntimeControlAckPayload): Boolean {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_RUNTIME_CONTROL_ACK
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["controlSeq"] = ack.controlSeq
        payload["applied"] = ack.applied
        payload["reason"] = ack.reason
        payload["onlineStatus"] = ack.onlineStatus
        payload["supplyStatus"] = ack.supplyStatus
        payload["scanBlockStatus"] = ack.scanBlockStatus
        payload["inspectionMode"] = ack.inspectionMode
        payload["isEnable"] = ack.isEnable
        payload["restStatusSource"] = ack.restStatusSource
        payload["ackTs"] = System.currentTimeMillis()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
    }

    fun publishConcurrencyControlAck(ack: ConcurrencyControlAckPayload): Boolean {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_CONCURRENCY_CONTROL_ACK
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["controlSeq"] = ack.controlSeq
        payload["applied"] = ack.applied
        payload["changed"] = ack.changed
        payload["reason"] = ack.reason
        payload["modeType"] = ack.modeType
        payload["transitionMode"] = ack.transitionMode
        payload["ackTs"] = System.currentTimeMillis()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
    }

    private fun scheduleRuntimeStateAckTimeout(snapshot: RuntimeStatePublishSnapshot) {
        runtimeStateAckHandler.postDelayed({
            val pending = pendingRuntimeStateAcks[snapshot.runtimeSeq] ?: return@postDelayed
            if (pending.publishedAt != snapshot.publishedAt) {
                return@postDelayed
            }
            if (pending.runtimeSeq <= lastAckedRuntimeStateSeq) {
                pendingRuntimeStateAcks.remove(snapshot.runtimeSeq)
                return@postDelayed
            }
            val ageMs = System.currentTimeMillis() - pending.publishedAt
            LogUtils.w(
                "【MQTT运行态】运行时状态上报后仍未收到服务端确认：" +
                    "runtimeSeq=${pending.runtimeSeq}，source=${pending.source}，" +
                    runtimeStateText(
                        status = pending.status,
                        onlineStatus = pending.onlineStatus,
                        supplyStatus = pending.supplyStatus,
                        scanBlockStatus = pending.scanBlockStatus,
                        inspectionMode = pending.inspectionMode,
                        isEnable = pending.isEnable,
                        modeType = pending.modeType,
                        transitionMode = pending.transitionMode,
                        moveStatus = pending.moveStatus,
                        errorStatus = pending.errorStatus,
                        restStatusSource = pending.restStatusSource
                    ) +
                    "，ageMs=$ageMs"
            )
        }, RUNTIME_STATE_ACK_TIMEOUT_MS)
    }

    private fun scheduleAvailableStateAckTimeout(snapshot: AvailableStatePublishSnapshot) {
        availableStateAckHandler.postDelayed({
            val pending = pendingAvailableStateAcks[snapshot.availableSeq] ?: return@postDelayed
            if (pending.availableSeq <= lastAckedAvailableStateSeq) {
                pendingAvailableStateAcks.remove(snapshot.availableSeq)
                return@postDelayed
            }
            val ageMs = System.currentTimeMillis() - pending.publishedAt
            val logKey = buildAvailableStateLogKey(pending.available)
            if (lastAvailableAckTimeoutLogKey != logKey) {
                lastAvailableAckTimeoutLogKey = logKey
                LogUtils.w(
                    "【MQTT展示态】首页展示态上报后仍未收到服务端确认：" +
                        "seq=${pending.availableSeq}，文案=${availableDisplayText(pending.available)}，等待=${ageMs / 1000L}s"
                )
            }
        }, AVAILABLE_STATE_ACK_TIMEOUT_MS)
    }

    fun markAvailableStateAcked(availableSeq: Long): AvailableStateAckResult {
        if (availableSeq <= 0L) {
            return AvailableStateAckResult(
                freshAck = false,
                snapshot = null,
                ackLatencyMs = null
            )
        }
        val snapshot = pendingAvailableStateAcks.remove(availableSeq)
        val ackLatencyMs = snapshot?.let { System.currentTimeMillis() - it.publishedAt }
        snapshot?.let {
            if (lastAvailableAckTimeoutLogKey == buildAvailableStateLogKey(it.available)) {
                lastAvailableAckTimeoutLogKey = null
            }
        }
        val freshAck = if (availableSeq > lastAckedAvailableStateSeq) {
            lastAckedAvailableStateSeq = availableSeq
            true
        } else {
            false
        }
        return AvailableStateAckResult(
            freshAck = freshAck,
            snapshot = snapshot,
            ackLatencyMs = ackLatencyMs
        )
    }

    fun markRuntimeStateAcked(runtimeSeq: Long): RuntimeStateAckResult {
        if (runtimeSeq <= 0L) {
            return RuntimeStateAckResult(
                freshAck = false,
                snapshot = null,
                ackLatencyMs = null
            )
        }
        val snapshot = pendingRuntimeStateAcks.remove(runtimeSeq)
        val ackLatencyMs = snapshot?.let { System.currentTimeMillis() - it.publishedAt }
        val freshAck = if (runtimeSeq > lastAckedRuntimeStateSeq) {
            lastAckedRuntimeStateSeq = runtimeSeq
            true
        } else {
            false
        }
        return RuntimeStateAckResult(
            freshAck = freshAck,
            snapshot = snapshot,
            ackLatencyMs = ackLatencyMs
        )
    }

    private fun buildRuntimeStateSignature(config: AppConfigBean): String {
        return buildString {
            append(config.status).append('|')
            append(config.onlineStatus).append('|')
            append(config.supplyStatus).append('|')
            append(config.scanBlockStatus).append('|')
            append(config.inspectionMode).append('|')
            append(AppConfig.getAlgorithmEnableFlag()).append('|')
            append(config.modeType).append('|')
            append(config.transitionMode).append('|')
            append(config.moveStatus).append('|')
            append(config.errorStatus).append('|')
            append(config.restStatusSource ?: "")
        }
    }

    private fun resolveRuntimeStateSeq(stateSignature: String): Pair<Long, Boolean> {
        synchronized(pendingRuntimeStateAcks) {
            if (activeRuntimeStateSeq > 0L && stateSignature == activeRuntimeStateSignature) {
                return activeRuntimeStateSeq to true
            }
            val nextSeq = runtimeStateSeq.incrementAndGet()
            activeRuntimeStateSignature = stateSignature
            activeRuntimeStateSeq = nextSeq
            return nextSeq to false
        }
    }

    fun lastPublishedRuntimeStateSeq(): Long = lastPublishedRuntimeStateSeq
    fun lastPublishedAvailableStateSeq(): Long = lastPublishedAvailableStateSeq
}
