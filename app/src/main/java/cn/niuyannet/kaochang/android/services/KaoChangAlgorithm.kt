package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 智能烤肠算法
 * 检测烤盘状态、库存并自动管理烤肠烤制流程
 */
object KaoChangAlgorithm {
    private data class HeatBatch(
        val name: String,
        val pans: List<KaoPan>
    )

    data class SupplyRuntimeContext(
        val hasSupplyBoxesAvailable: Boolean,
        val hasSellableSausage: Boolean,
        val hasActiveBatch: Boolean
    )

    private val TAG = "KaoChangAlgorithm"
    private const val LOG_ALGORITHM = "算法轮询"
    private const val LOG_DEVICE = "设备状态"
    private const val LOG_COMM = "通信状态"
    private const val SUPPLY_RECOVER_SOURCE = "runtime_state:supply_status_recovered"
    private const val SUPPLY_SOLD_OUT_SOURCE = "runtime_state:supply_status_sold_out"
    private const val MODBUS_MONITOR_INTERVAL_MS = 1_000L
    private const val MODBUS_DISCONNECT_REPORT_DELAY_MS = 5_000L
    private const val MODBUS_RECONNECT_STABLE_DELAY_MS = 5_000L
    private const val MODBUS_RECONNECT_ATTEMPT_INTERVAL_MS = 5_000L
    /**
     * 算法对象内部的后台作用域。
     *
     * 只用于承接算法轮询和 Modbus 状态监控这类应用级后台任务，
     * 避免继续使用 GlobalScope 造成难以控制的悬挂协程。
     */
    private val algorithmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false
    private var modbusMonitorStarted = false
    private var serviceStartTime: Long = 0
    private var modbusDisconnectSinceMs: Long? = null
    private var modbusReconnectSinceMs: Long? = null
    private var lastReconnectAttemptMs: Long = 0L
    private var modbusReportedUnavailable = false
    private var lastRecoverableOnlineStatus = 1
    private var lastAvailableDecisionLogKey = ""
    private var lastAvailableUpdateLogKey = ""
    private var lastBusinessStartRecoveryLogKey = ""
    private var loggedManualRecoveryRequiredOnDisconnect = false
    private var loggedManualRecoveryRequiredAfterReconnect = false
    private var inspectionDisconnectSuppressedLogged = false
    private var lastPollStateSnapshot: String? = null
    var isEnabled = false

    private fun logD(category: String, message: String) = LogUtils.d(TAG, "[$category] $message")
    private fun logW(category: String, message: String) = LogUtils.w(TAG, "[$category] $message")
    private fun logI(category: String, message: String) = LogUtils.i(TAG, "[$category] $message")
    private fun logE(category: String, message: String) = LogUtils.e(TAG, "[$category] $message")

    private fun modeText(value: Int): String = when (value) {
        1 -> "1(低并发)"
        2 -> "2(高并发)"
        else -> "$value(未知)"
    }

    private fun transitionText(value: Int): String = when (value) {
        0 -> "0(无过渡态)"
        1 -> "1(低转高过渡)"
        2 -> "2(高转低过渡)"
        else -> "$value(未知)"
    }

    private fun onlineStatusText(value: Int): String = when (value) {
        0 -> "0(未启用/离线)"
        1 -> "1(运营中)"
        2 -> "2(休息中)"
        3 -> "3(维护中)"
        4 -> "4(工作中/正在出餐)"
        else -> "$value(未知)"
    }

    private fun moveStatusText(value: Int): String = when (value) {
        0 -> "0(机械臂空闲)"
        1 -> "1(机械臂运动中)"
        else -> "$value(未知)"
    }

    private fun errorStatusText(value: Int): String = when (value) {
        0 -> "0(正常)"
        1 -> "1(识别/流程错误)"
        2 -> "2(通信/硬件级故障)"
        else -> "$value(未知)"
    }

    private fun restStatusSourceText(value: String?): String = AppConfig.restStatusSourceText(value)

    private fun supplyStatusText(value: Int): String = when (value) {
        AppConfigBean.SUPPLY_STATUS_NORMAL -> "0(正常)"
        AppConfigBean.SUPPLY_STATUS_SOLD_OUT -> "1(售罄/待补货)"
        else -> "$value(未知)"
    }

    private fun isEnableText(value: Int): String = when (value) {
        0 -> "0（关闭）"
        1 -> "1（开启）"
        else -> "$value（未知）"
    }

    private fun logBusinessStartSkipOnce(source: String, reason: String, config: AppConfigBean) {
        val logKey = listOf(
            source,
            reason,
            config.onlineStatus,
            config.errorStatus,
            config.moveStatus,
            config.isEnable,
            AppConfig.normalizeRestStatusSource(config.restStatusSource)
        ).joinToString("|")
        if (lastBusinessStartRecoveryLogKey == logKey) {
            return
        }
        lastBusinessStartRecoveryLogKey = logKey
        logD(
            LOG_DEVICE,
            "跳过营业开始自动恢复：source=$source，原因=$reason，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}，" +
                "restStatusSource（休息中来源）=${restStatusSourceText(config.restStatusSource)}，" +
                "moveStatus（机械动作状态）=${moveStatusText(config.moveStatus)}，" +
                "errorStatus（设备故障状态）=${errorStatusText(config.errorStatus)}"
        )
    }

    fun recoverBusinessStartIfNeeded(source: String): Boolean {
        val config = AppConfig.getAppConfig()
        val businessTime = config.businessTime ?: return false
        if (!isWithinBusinessHours(getCurrentTime(), businessTime)) {
            lastBusinessStartRecoveryLogKey = ""
            return false
        }
        return when (config.onlineStatus) {
            2 -> {
                if (AppConfig.normalizeRestStatusSource(config.restStatusSource) != AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS) {
                    logBusinessStartSkipOnce(source, "休息中来源不是系统自动营业结束", config)
                    return false
                }
                val previousOnlineStatus = config.onlineStatus
                val previousIsEnable = config.isEnable
                KaoChangOperate.startBusinessHours(config, source)
                val currentConfig = AppConfig.getAppConfig()
                val changed = previousOnlineStatus != currentConfig.onlineStatus || previousIsEnable != currentConfig.isEnable
                if (changed) {
                    lastBusinessStartRecoveryLogKey = ""
                }
                changed
            }
            1 -> {
                lastBusinessStartRecoveryLogKey = ""
                false
            }
            0 -> {
                logBusinessStartSkipOnce(source, "未启用", config)
                false
            }
            3 -> {
                logBusinessStartSkipOnce(source, "维护中", config)
                false
            }
            4 -> {
                logBusinessStartSkipOnce(source, "正在出餐", config)
                false
            }
            else -> {
                logBusinessStartSkipOnce(source, "未知状态", config)
                false
            }
        }
    }

    fun restoreRoastAlgorithmState() {
        isEnabled = AppConfig.getAppConfig().isEnable == 1
    }

    /**
     * 启动烤肠算法主循环。
     * 当前只保留状态变化日志，避免每轮轮询刷屏。
     */
    fun runServer() {
        if (isRunning) return
        restoreRoastAlgorithmState()
        isRunning = true
        val config = AppConfig.getAppConfig()
        rememberRecoverableOnlineStatus(config.onlineStatus)
        if (config.onlineStatus == 3 && config.errorStatus == 2 && !VMModbusHelper.connectStatus()) {
            modbusReportedUnavailable = true
        }
        logD(LOG_ALGORITHM, "算法准备0：moveStatus=${config.moveStatus}")
        config.moveStatus = 0
        AppConfig.saveAppConfig(config)
        logD(LOG_ALGORITHM, "算法准备1：moveStatus=${config.moveStatus}")
        serviceStartTime = System.currentTimeMillis()
        logD(LOG_ALGORITHM, "智能烤肠算法服务启动")
        startModbusStateMonitor()
        algorithmScope.launch {
            while (isRunning) {
                pollKaoPanStatus()
                if (!isRunning) break
                // 每轮同步一次烤盘状态和烤肠箱库存到服务端，确保小程序看到的数据实时
                DataManagementAPI.uploadDataToServer()
                delay(10 * 1000)
            }
        }
    }

    private fun updateAvailableState(
        config: AppConfigBean,
        newAvailable: Long,
        newLatched: Int,
        reason: String
    ) {
        if (config.available == newAvailable && config.availableCountdownLatched == newLatched) {
            return
        }
        val oldDisplayText = buildAvailableDisplayText(config.available)
        val newDisplayText = buildAvailableDisplayText(newAvailable)
        val logKey = listOf(reason, newLatched, newDisplayText).joinToString("|")
        if (lastAvailableUpdateLogKey != logKey) {
            lastAvailableUpdateLogKey = logKey
            logD(
                LOG_ALGORITHM,
                "更新二维码预计时间：reason=$reason，" +
                    "available=${config.available}->$newAvailable，" +
                    "availableCountdownLatched=${config.availableCountdownLatched}->$newLatched，" +
                    "displayText=$oldDisplayText->$newDisplayText"
            )
        }
        config.available = newAvailable
        config.availableCountdownLatched = newLatched
        AppConfig.saveAppConfig(config)
        HomeUiRefreshBridge.requestRefresh("available_state_changed")
        val mqttOk = SendServerHelper.publishAvailableState("algorithm:$reason")
        if (!mqttOk) {
            logW(
                LOG_ALGORITHM,
                "二维码预计时间 MQTT 主上报失败，回退 HTTP 补偿：" +
                    "reason=$reason，available=$newAvailable，availableCountdownLatched=$newLatched"
            )
            NetApi.updateDeviceInfo()
        }
    }

    private fun buildAvailableDisplayText(available: Long): String {
        if (available <= 0L) {
            return "扫码下单 纯肉烤肠"
        }
        return if (available >= 60_000L) {
            "预计烤制 ${kotlin.math.ceil(available / 60000.0).toInt()} 分钟"
        } else {
            "预计烤制 ${kotlin.math.ceil(available / 1000.0).toInt()} 秒"
        }
    }

    private fun isHeatingPan(kaoPan: KaoPan): Boolean {
        return kaoPan.isHasSausage && kaoPan.startTime > 0L && kaoPan.holdingTime <= 0L
    }

    private fun isHoldingPan(kaoPan: KaoPan): Boolean {
        return kaoPan.isHasSausage && kaoPan.holdingTime > 0L
    }

    private fun calcPanRemainHeatTime(kaoPan: KaoPan, now: Long): Long? {
        if (!isHeatingPan(kaoPan)) {
            return null
        }
        val heatTime = now - kaoPan.startTime
        val remain = kaoPan.bakingTime - heatTime
        return remain.takeIf { it > 0L }
    }

    private fun calcBatchRemainHeatTime(list: List<KaoPan>, now: Long): Long? {
        return list
            .asSequence()
            .mapNotNull { calcPanRemainHeatTime(it, now) }
            .maxOrNull()
    }

    private fun buildSupplementHeatBatches(
        isLowOrTransition: Boolean,
        heatArea: List<KaoPan>
    ): List<HeatBatch> {
        return if (isLowOrTransition) {
            listOf(
                HeatBatch("13~15补肠批次", heatArea.filter { it.positionSn in 13..15 }),
                HeatBatch("16~18补肠批次", heatArea.filter { it.positionSn in 16..18 })
            )
        } else {
            listOf(HeatBatch("25~33补肠批次", heatArea))
        }
    }

    private fun buildAvailableDecisionContext(
        config: AppConfigBean,
        sellArea: List<KaoPan>,
        heatArea: List<KaoPan>
    ): String {
        val frontOccupiedCount = sellArea.count { it.isHasSausage }
        val frontHoldingCount = sellArea.count { isHoldingPan(it) }
        val frontHeatingCount = sellArea.count { isHeatingPan(it) }
        val heatAreaHeatingCount = heatArea.count { isHeatingPan(it) }
        return "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
            "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}，" +
            "modeType（并发模式）=${modeText(config.modeType)}，" +
            "transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}，" +
            "frontOccupiedCount（前区有肠数）=$frontOccupiedCount，" +
            "frontHoldingCount（前区保温数）=$frontHoldingCount，" +
            "frontHeatingCount（前区加热数）=$frontHeatingCount，" +
            "heatAreaHeatingCount（补热区加热数）=$heatAreaHeatingCount，" +
            "available（二维码页预计时间）=${config.available}，" +
            "availableCountdownLatched（二维码页预计时间锁存标记）=${config.availableCountdownLatched}"
    }

    private fun logAvailableDecision(
        reason: String,
        config: AppConfigBean,
        sellArea: List<KaoPan>,
        heatArea: List<KaoPan>
    ) {
        val frontOccupiedCount = sellArea.count { it.isHasSausage }
        val frontHoldingCount = sellArea.count { isHoldingPan(it) }
        val frontHeatingCount = sellArea.count { isHeatingPan(it) }
        val heatAreaHeatingCount = heatArea.count { isHeatingPan(it) }
        val logKey =
            listOf(
                reason,
                config.onlineStatus,
                config.isEnable,
                config.modeType,
                config.transitionMode,
                frontOccupiedCount,
                frontHoldingCount,
                frontHeatingCount,
                heatAreaHeatingCount,
                config.availableCountdownLatched
            ).joinToString("|")
        if (lastAvailableDecisionLogKey == logKey) {
            return
        }
        lastAvailableDecisionLogKey = logKey
        logD(
            LOG_ALGORITHM,
            "二维码预计时间判定：reason=$reason，${buildAvailableDecisionContext(config, sellArea, heatArea)}"
        )
    }
    /**
     * 计算二维码页显示的“预计烤制时间”。
     * 展示规则：
     * 1. 前区还有保温烤肠时，不展示预计烤制时间；
     * 2. 前区没有可售烤肠时：
     *    - 若当前开盘批次仍在前区加热，则显示“最早能恢复可售”的开盘批次剩余时间；
     *    - 若只剩补肠批次在补热区加热，则显示“最早能恢复可售”的那一批剩余时间；
     * 3. 当前批次全部进入保温后才一次性开放；
     * 4. 小程序可售开关仍通过 availableCountdownLatched 控制。
     */
    private fun checkAvailable(config: AppConfigBean, kaoPanList: List<KaoPan>) {
        val list_1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        val list_1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList)
        val list_13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val list_25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, kaoPanList)

        val isLowOrTransition = config.modeType == 1 || config.transitionMode == 1
        val sellArea = if (isLowOrTransition) list_1_9 else list_1_21
        val heatArea = if (isLowOrTransition) list_13_18 else list_25_33
        val trackedArea = sellArea + heatArea
        val trackedPans = trackedArea.filter { it.isHasSausage }
        val sellableHoldingPans = sellArea.filter { isHoldingPan(it) }
        val now = System.currentTimeMillis()

        if (trackedPans.isEmpty()) {
            logAvailableDecision("当前批次相关烤盘已全部清空", config, sellArea, heatArea)
            updateAvailableState(config, 0L, 0, "当前批次相关烤盘已全部清空")
            return
        }

        if (sellableHoldingPans.isNotEmpty()) {
            logAvailableDecision("前区仍有保温烤肠，不展示预计时间", config, sellArea, heatArea)
            updateAvailableState(config, 0L, 1, "前区仍有保温烤肠，不展示预计时间")
            return
        }

        val nextOpeningBatch = findNextOpeningSellBatch(
            isLowOrTransition = isLowOrTransition,
            sellArea = sellArea,
            now = now
        )
        if (nextOpeningBatch != null) {
            logAvailableDecision(
                "前区暂无可售烤肠，显示${nextOpeningBatch.name}恢复可售前的预计时间：remainingMs=${nextOpeningBatch.remainHeatTimeMs}",
                config,
                sellArea,
                heatArea
            )
            updateAvailableState(
                config,
                nextOpeningBatch.remainHeatTimeMs,
                0,
                "前区暂无可售烤肠，显示${nextOpeningBatch.name}恢复可售前的预计时间"
            )
            return
        }

        val nextSupplementBatch = buildSupplementHeatBatches(isLowOrTransition, heatArea)
            .mapNotNull { batch ->
                calcBatchRemainHeatTime(batch.pans, now)?.let { remain ->
                    batch.name to remain
                }
            }
            .minByOrNull { it.second }

        if (nextSupplementBatch != null) {
            logAvailableDecision(
                "前区已售空，显示${nextSupplementBatch.first}恢复可售前的预计时间：remainingMs=${nextSupplementBatch.second}",
                config,
                sellArea,
                heatArea
            )
            updateAvailableState(
                config,
                nextSupplementBatch.second,
                0,
                "前区已售空，显示${nextSupplementBatch.first}恢复可售前的预计时间"
            )
            return
        }

        logAvailableDecision("前区无保温烤肠，当前也没有正在加热的待开放批次", config, sellArea, heatArea)
        updateAvailableState(config, 0L, 0, "前区无保温烤肠，当前也没有正在加热的待开放批次")
    }

    private fun getSellArea(config: AppConfigBean, kaoPanList: List<KaoPan>): List<KaoPan> {
        return if (config.modeType == 1 || config.transitionMode == 1) {
            TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        } else {
            TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList)
        }
    }

    private fun getTrackedArea(config: AppConfigBean, kaoPanList: List<KaoPan>): List<KaoPan> {
        return if (config.modeType == 1 || config.transitionMode == 1) {
            getSellArea(config, kaoPanList) + TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        } else {
            getSellArea(config, kaoPanList) + TrafficModeHelper.getTrafficModePositions(25, 33, kaoPanList)
        }
    }

    private fun evaluateSupplyRuntimeContext(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>,
        kaoPanBoxList: List<cn.niuyannet.kaochang.android.model.bean.KaoPanBox>
    ): SupplyRuntimeContext {
        val sellArea = getSellArea(config, kaoPanList)
        val trackedArea = getTrackedArea(config, kaoPanList)
        val hasSupplyBoxesAvailable = kaoPanBoxList.any { it.num > 0 }
        val hasSellableSausage = sellArea.any { isHoldingPan(it) }
        val hasActiveBatch =
            trackedArea.any { it.isHasGrilling } ||
                trackedArea.any { it.isHasSausage && !isHoldingPan(it) } ||
                config.moveStatus != 0 ||
                config.onlineStatus == 4 ||
                config.transitionMode != 0 ||
                config.zone3CleanPending == 1
        return SupplyRuntimeContext(
            hasSupplyBoxesAvailable = hasSupplyBoxesAvailable,
            hasSellableSausage = hasSellableSausage,
            hasActiveBatch = hasActiveBatch
        )
    }

    private fun publishRuntimeStateOrFallback(source: String) {
        val mqttOk = SendServerHelper.publishServiceUpdateStatus(source)
        if (!mqttOk) {
            logW(LOG_DEVICE, "运行态主上报失败，回退 HTTP 补偿：source=$source")
            NetApi.updateDeviceInfo()
        }
    }

    private fun stopHeatingForSoldOutState(config: AppConfigBean) {
        KaoChangOperate.stopHeating(1)
        if (config.modeType == 1 || config.transitionMode == 1) {
            KaoChangOperate.stopHeating(2)
            return
        }
        KaoChangOperate.stopHeating(2)
        KaoChangOperate.stopHeating(3)
    }

    private fun refreshSupplyStatus(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>,
        kaoPanBoxList: List<cn.niuyannet.kaochang.android.model.bean.KaoPanBox>
    ): SupplyRuntimeContext {
        val context = evaluateSupplyRuntimeContext(config, kaoPanList, kaoPanBoxList)
        val targetSupplyStatus =
            if (!context.hasSupplyBoxesAvailable && !context.hasSellableSausage && !context.hasActiveBatch) {
                AppConfigBean.SUPPLY_STATUS_SOLD_OUT
            } else {
                AppConfigBean.SUPPLY_STATUS_NORMAL
            }
        if (config.supplyStatus == targetSupplyStatus) {
            return context
        }

        val previousSupplyStatus = config.supplyStatus
        config.supplyStatus = targetSupplyStatus
        AppConfig.saveAppConfig(config)
        if (targetSupplyStatus == AppConfigBean.SUPPLY_STATUS_SOLD_OUT) {
            stopHeatingForSoldOutState(config)
            logW(
                LOG_DEVICE,
                "补货状态已切换为售罄：supplyStatus（补货状态）=${supplyStatusText(previousSupplyStatus)}->${supplyStatusText(config.supplyStatus)}，" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                    "原因=烤肠箱已空且当前无可售烤肠、无活动批次"
            )
            publishRuntimeStateOrFallback(SUPPLY_SOLD_OUT_SOURCE)
        } else {
            logI(
                LOG_DEVICE,
                "补货状态已恢复正常：supplyStatus（补货状态）=${supplyStatusText(previousSupplyStatus)}->${supplyStatusText(config.supplyStatus)}，" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                    "原因=检测到烤肠箱已补货或仍有可继续售卖/处理的批次"
            )
            publishRuntimeStateOrFallback(SUPPLY_RECOVER_SOURCE)
        }
        HomeUiRefreshBridge.requestRefresh("supply_status_changed")
        return context
    }

    fun hasBlockingActiveBatchForAlgorithmDisable(): Boolean {
        val config = AppConfig.getAppConfig()
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val context = evaluateSupplyRuntimeContext(config, kaoPanList, KaoPanHelper.getKaoPanBoxList())
        return context.hasActiveBatch
    }

    private fun canEnableAlgorithm(config: AppConfigBean, onlineStatus: Int = config.onlineStatus): Boolean {
        return onlineStatus in listOf(1, 4) &&
            config.inspectionMode == AppConfigBean.INSPECTION_MODE_NORMAL &&
            config.errorStatus == AppConfigBean.ERROR_STATUS_NORMAL
    }

    private fun shouldFreezeByInspection(config: AppConfigBean): Boolean {
        return config.inspectionMode == AppConfigBean.INSPECTION_MODE_ACTIVE
    }

    /**
     * 轮询烤盘和设备状态，决定当前调度模式。
     */
    private suspend fun pollKaoPanStatus() {
        val config = AppConfig.getAppConfig()
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val kaoPanBoxList = KaoPanHelper.getKaoPanBoxList()
        var onlineStatus = config.onlineStatus
        if (config.businessTime == null) {
            logW(LOG_ALGORITHM, "【营业时间】营业时间配置为空（businessTime=null），算法按非营业时间处理，设备将不会运营。请检查后台是否已配置营业时间")
        }
        val isWithinBusinessHours = isWithinBusinessHours(getCurrentTime(), config.businessTime)
        if (!isWithinBusinessHours) {
            // 只有运营中（1）才需要执行停业收尾切换到休息中（2）；
            // 维护中/未启用等后台管理员设置的状态，不触发自动切换
            if (onlineStatus == 1) {
                logD(LOG_ALGORITHM, "当前不在营业时间内，设备运营中，执行停业收尾")
                KaoChangOperate.pauseBusiness()
                KaoChangOperate.endBusinessHours(config)
            } else {
                logD(LOG_ALGORITHM, "当前不在营业时间内，设备状态=${onlineStatusText(onlineStatus)}，跳过停业收尾")
            }
            return
        }

        if (onlineStatus == 2) {
            recoverBusinessStartIfNeeded("pollKaoPanStatus")
            onlineStatus = AppConfig.getAppConfig().onlineStatus
        }

        if (!(onlineStatus == 1 || onlineStatus == 4)) {
            logD(LOG_DEVICE, "设备当前处于${onlineStatusText(onlineStatus)}，跳过算法调度")
            return
        }
        if (shouldFreezeByInspection(config)) {
            logD(
                LOG_DEVICE,
                "设备当前处于检修模式，已冻结自动调度：" +
                    "inspectionMode=${config.inspectionMode}，onlineStatus=${onlineStatusText(onlineStatus)}，" +
                    "isEnable=${isEnableText(config.isEnable)}"
            )
            return
        }
        if (!VMModbusHelper.connectStatus()) {
            logD(LOG_COMM, "modbus 串口未连接，跳过算法调度")
            return
        }

        val supplyContext = refreshSupplyStatus(config, kaoPanList, kaoPanBoxList)
        val schedulingEnabled = isEnabled && config.isEnable == 1 && canEnableAlgorithm(config, onlineStatus)

        val pollStateSnapshot =
            "mode=${config.modeType},transition=${config.transitionMode},online=$onlineStatus,move=${config.moveStatus},error=${config.errorStatus},supply=${config.supplyStatus},scanBlock=${config.scanBlockStatus},inspection=${config.inspectionMode},enable=$schedulingEnabled"
        if (pollStateSnapshot != lastPollStateSnapshot) {
            logD(
                LOG_ALGORITHM,
                "算法状态变化：modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}，onlineStatus=${onlineStatusText(onlineStatus)}，supplyStatus=${supplyStatusText(config.supplyStatus)}，scanBlockStatus=${config.scanBlockStatus}，inspectionMode=${config.inspectionMode}，moveStatus=${moveStatusText(config.moveStatus)}，errorStatus=${errorStatusText(config.errorStatus)}，schedulingEnabled=$schedulingEnabled"
            )
            lastPollStateSnapshot = pollStateSnapshot
        }

        checkAvailable(config, kaoPanList)
        if (config.supplyStatus == AppConfigBean.SUPPLY_STATUS_SOLD_OUT) {
            return
        }

        when {
            config.transitionMode != 0 -> {
                HighCurrentTrafficMode.highTrafficMode(
                    config,
                    list = kaoPanList,
                    listBox = kaoPanBoxList,
                    boxSupplyAvailable = supplyContext.hasSupplyBoxesAvailable,
                    schedulingEnabled = schedulingEnabled
                )
            }

            config.modeType == 1 -> {
                LowCurrentTrafficMode.lowTrafficMode(
                    config,
                    kaoPanList,
                    kaoPanBoxList,
                    boxSupplyAvailable = supplyContext.hasSupplyBoxesAvailable,
                    schedulingEnabled = schedulingEnabled
                )
            }

            config.modeType == 2 -> {
                HighCurrentTrafficMode.highTrafficMode(
                    config,
                    list = kaoPanList,
                    listBox = kaoPanBoxList,
                    boxSupplyAvailable = supplyContext.hasSupplyBoxesAvailable,
                    schedulingEnabled = schedulingEnabled
                )
            }
        }
    }

    /**
     * 用户下单后，从可售烤盘中选择一根烤肠出餐。
     * 当前策略：
     * 1. 低并发及低转高过渡态仅从 1~9 号前区选取；
     * 2. 高并发从 1~21 号可售区选取；
     * 3. 按 holdingTime 最小值优先，即“最早进入保温的先卖”。
     */
    suspend fun purchase(tasteCode: String, productId: Int): KaoChangOperate.TakeSausageResult {
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val config = AppConfig.getAppConfig()
        val targetKaoPan: KaoPan? = if (config.modeType == 1 || config.transitionMode == 1) {
            TrafficModeHelper.selectSellCandidate(
                TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList),
                tasteCode,
                productId
            )
        } else {
            TrafficModeHelper.selectSellCandidate(
                TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList),
                tasteCode,
                productId
            )
        }

        if (targetKaoPan != null) {
            logD(
                LOG_ALGORITHM,
                "收到出货订单：口味=$tasteCode，商品=$productId，已调度托盘 ID=${targetKaoPan.id}，烤盘位=${targetKaoPan.positionSn}，holdingTime=${targetKaoPan.holdingTime}"
            )
            return KaoChangOperate.takeSausageResult(targetKaoPan)
        } else {
            logW(LOG_DEVICE, "收到出货订单失败：当前没有找到可售烤肠 [口味=$tasteCode, 商品=$productId]")
        }
        return KaoChangOperate.TakeSausageResult.ERROR
    }

    /**
     * 维护页批量重置烤盘。
     * 所有有肠烤盘都会按“管理员指定丢弃”处理。
     */
    suspend fun resetKaoPan(progressCallback: ((current: Int, total: Int, kaoPan: KaoPan) -> Unit)? = null) {
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val targetPans = kaoPanList.filter { it.isHasSausage }
        val targetPositions = targetPans.map { it.positionSn }
        LogUtils.i("【维护操作】管理员触发批量丢弃重置烤盘，待处理烤盘=$targetPositions")
        targetPans.forEachIndexed { index, kaoPan ->
            progressCallback?.invoke(index + 1, targetPans.size, kaoPan)
            KaoChangOperate.discardSausage(
                kaoPan,
                source = "管理员指定",
                detail = "维护页批量重置烤盘"
            )
        }
    }

    /**
     * 停止服务
     */
    fun stopServer() {
        isRunning = false
        modbusMonitorStarted = false
        logD(LOG_ALGORITHM, "智能烤肠算法服务停止")
    }

    private fun startModbusStateMonitor() {
        if (modbusMonitorStarted) {
            return
        }
        modbusMonitorStarted = true
        algorithmScope.launch {
            while (isRunning) {
                val now = System.currentTimeMillis()
                val connected = VMModbusHelper.connectStatus()
                val config = AppConfig.getAppConfig()

                if (shouldFreezeByInspection(config)) {
                    modbusDisconnectSinceMs = null
                    modbusReconnectSinceMs = null
                    lastReconnectAttemptMs = 0L
                    loggedManualRecoveryRequiredOnDisconnect = false
                    loggedManualRecoveryRequiredAfterReconnect = false
                    if (!connected && !inspectionDisconnectSuppressedLogged) {
                        inspectionDisconnectSuppressedLogged = true
                        logD(LOG_COMM, "检修模式下检测到 Modbus 断连，已抑制自动切维护和刷屏错误日志")
                    } else if (connected && inspectionDisconnectSuppressedLogged) {
                        inspectionDisconnectSuppressedLogged = false
                        logD(LOG_COMM, "检修模式下检测到 Modbus 已恢复连接，继续保持日志抑制")
                    }
                    delay(MODBUS_MONITOR_INTERVAL_MS)
                    continue
                }
                inspectionDisconnectSuppressedLogged = false

                if (connected) {
                    modbusDisconnectSinceMs = null
                    lastReconnectAttemptMs = 0L
                    loggedManualRecoveryRequiredOnDisconnect = false
                    rememberRecoverableOnlineStatus(config.onlineStatus)

                    if (modbusReportedUnavailable) {
                        if (modbusReconnectSinceMs == null) {
                            modbusReconnectSinceMs = now
                            logI(LOG_COMM, "【Modbus】检测到下位机重新连接，开始稳定性观察 | port=${AppConfig.getAppConfig().modbusAddress}")
                        } else if (now - modbusReconnectSinceMs!! >= MODBUS_RECONNECT_STABLE_DELAY_MS) {
                            val restoredOnlineStatus = resolveRecoveredOnlineStatus(config)
                            config.errorStatus = 0
                            config.onlineStatus = restoredOnlineStatus
                            AppConfig.saveAppConfig(config)
                            KaoChangOperate.restoreHeatingStateFromCurrentPans("modbus恢复稳定")
                            rememberRecoverableOnlineStatus(restoredOnlineStatus)
                            modbusReportedUnavailable = false
                            modbusReconnectSinceMs = null
                            loggedManualRecoveryRequiredAfterReconnect = false
                            logI(LOG_COMM, "【Modbus】下位机稳定连接确认，自动恢复正常状态 | port=${AppConfig.getAppConfig().modbusAddress}, onlineStatus=$restoredOnlineStatus, errorStatus=0")
                            uploadDeviceStatus(
                                "modbus串口恢复稳定，自动恢复原因=断连类错误，已恢复设备可用状态：onlineStatus=$restoredOnlineStatus"
                            )
                        }
                    } else {
                        modbusReconnectSinceMs = null
                        if (config.onlineStatus == 3 && config.errorStatus != 0 && !loggedManualRecoveryRequiredAfterReconnect) {
                            loggedManualRecoveryRequiredAfterReconnect = true
                            logW(
                                LOG_COMM,
                                "检测到modbus已恢复，但当前仍处于维护中(errorStatus=${config.errorStatus})；" +
                                    "不可自动恢复原因=当前错误并非本次断连监控触发，需人工恢复"
                            )
                        } else if (!(config.onlineStatus == 3 && config.errorStatus != 0)) {
                            loggedManualRecoveryRequiredAfterReconnect = false
                        }
                    }
                } else {
                    modbusReconnectSinceMs = null
                    loggedManualRecoveryRequiredAfterReconnect = false
                    if (modbusDisconnectSinceMs == null) {
                        modbusDisconnectSinceMs = now
                        logW(LOG_COMM, "检测到modbus串口断开，开始不可用状态观察")
                    }
                    if (lastReconnectAttemptMs == 0L || now - lastReconnectAttemptMs >= MODBUS_RECONNECT_ATTEMPT_INTERVAL_MS) {
                        lastReconnectAttemptMs = now
                        VMModbusHelper.connectModbus { status ->
                            logD(LOG_COMM, "modbus后台重连尝试完成：status=$status")
                        }
                    }

                    if (!modbusReportedUnavailable && now - modbusDisconnectSinceMs!! >= MODBUS_DISCONNECT_REPORT_DELAY_MS) {
                        if (config.onlineStatus in 0..2) {
                            rememberRecoverableOnlineStatus(config.onlineStatus)
                            config.errorStatus = 2
                            config.onlineStatus = 3
                            AppConfig.saveAppConfig(config)
                            modbusReportedUnavailable = true
                            loggedManualRecoveryRequiredOnDisconnect = false
                            uploadDeviceStatus("modbus串口断开超过${MODBUS_DISCONNECT_REPORT_DELAY_MS}ms，自动恢复原因=断连类错误，已上报维护中")
                        } else if (!loggedManualRecoveryRequiredOnDisconnect) {
                            loggedManualRecoveryRequiredOnDisconnect = true
                            logW(
                                LOG_COMM,
                                "modbus串口断开，但设备当前已处于维护中(errorStatus=${config.errorStatus})；" +
                                    "不可自动恢复原因=当前错误并非从运营/休息状态切入的断连故障，保留人工恢复"
                            )
                        }
                    }
                }

                delay(MODBUS_MONITOR_INTERVAL_MS)
            }
            modbusMonitorStarted = false
        }
    }

    private fun rememberRecoverableOnlineStatus(onlineStatus: Int) {
        if (onlineStatus in 0..2) {
            lastRecoverableOnlineStatus = onlineStatus
        }
    }

    private fun resolveRecoveredOnlineStatus(config: AppConfigBean): Int {
        if (config.status != 1) {
            return 0
        }
        if (lastRecoverableOnlineStatus == 0 || lastRecoverableOnlineStatus == 2) {
            return lastRecoverableOnlineStatus
        }
        return if (isWithinBusinessHours(getCurrentTime(), config.businessTime)) 1 else 2
    }

    private fun uploadDeviceStatus(logMessage: String) {
        logW(LOG_DEVICE, logMessage)
        SendServerHelper.publishServiceUpdateStatus()
    }
    /**
     * 判断当前时间是否在营业时间内
     * @param currentTimeStr 当前时间字符串，格式为"HH:mm"
     * @param businessTime 营业时间配置
     * @return 是否在营业时间内
     */
    private fun isWithinBusinessHours(currentTimeStr: String, businessTime: BusinessTime?): Boolean {
        try {
            if (businessTime == null) return false
            
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            val current = format.parse(currentTimeStr)
            
            // 获取当前是星期几（1-7，对应周一到周日）
            val calendar = Calendar.getInstance()
            // Calendar.DAY_OF_WEEK 返回 1-7，对应周日到周六，需要转换为 1-7 对应周一到周日
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1
            
            // 根据营业时间模式判断
            if ("everyday".equals(businessTime.mode, ignoreCase = true) && businessTime.defaultTime != null && businessTime.defaultTime.size >= 2) {
                // 默认模式：所有天使用相同的时间范围
                val start = format.parse(businessTime.defaultTime[0])
                val end = format.parse(businessTime.defaultTime[1])
                
                return isTimeInRange(current, start, end)
            } else if ("custom".equals(businessTime.mode, ignoreCase = true) && businessTime.customTimes != null) {
                // 自定义模式：每天可以有不同的时间范围
                for (timeItem in businessTime.customTimes) {
                    // 检查是否是当前日期且已启用
                    if (timeItem.day == dayOfWeek && timeItem.enabled && timeItem.timeRange != null && timeItem.timeRange.size >= 2) {
                        val start = format.parse(timeItem.timeRange[0])
                        val end = format.parse(timeItem.timeRange[1])
                        
                        return isTimeInRange(current, start, end)
                    }
                }
            }
            
            // 如果没有匹配的时间范围，则不在营业时间内
            return false
            
        } catch (e: Exception) {
            logW(LOG_ALGORITHM, "营业时间解析失败：$e")
            return false
        }
    }
    
    /**
     * 判断时间是否在范围内，处理跨天的情况
     */
    private fun isTimeInRange(current: Date, start: Date, end: Date): Boolean {
        // 判断是否跨天营业
        return if (start.before(end) || start == end) {
            // 不跨天：当前时间在开始和结束时间之间
            (current.after(start) || current == start) && (current.before(end) || current == end)
        } else {
            // 跨天：当前时间在开始时间之后或结束时间之前
            (current.after(start) || current == start) || (current.before(end) || current == end)
        }
    }
 

    /**
     * 获取当前时间
     * @return 当前时间字符串，格式为"HH:mm"
     */
    private fun getCurrentTime(): String {
        val currentTime = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return dateFormat.format(currentTime.time)
    }

    fun enableRoastAlgorithm(
        reason: String = "维护页手动开启算法",
        syncToServer: Boolean = true
    ) {
        val config = AppConfig.getAppConfig()
        if (!canEnableAlgorithm(config)) {
            logW(
                LOG_ALGORITHM,
                "拒绝开启烤肠算法：reason=$reason，" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                    "inspectionMode（检修模式）=${config.inspectionMode}，" +
                    "errorStatus（设备故障状态）=${errorStatusText(config.errorStatus)}，" +
                    "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}"
            )
            if (syncToServer) {
                SendServerHelper.publishServiceUpdateStatus()
            }
            return
        }
        KaoChangOperate.actionDebug()
        val wasRunning = isRunning
        isEnabled = true
        config.isEnable = 1
        AppConfig.saveAppConfig(config)
        if (!wasRunning) {
            logW(
                LOG_ALGORITHM,
                "手动开启烤肠算法时检测到轮询未运行，立即补启动算法服务：" +
                    "reason=$reason，onlineStatus=${onlineStatusText(config.onlineStatus)}，" +
                    "modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}"
            )
            runServer()
        }
        logD(
            LOG_ALGORITHM,
            "算法开关已开启：reason=$reason，" +
                "isRunning（算法轮询是否运行）=$wasRunning->${isRunning}，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                "modeType（并发模式）=${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}，" +
                "bakingTime（烤制时长，分钟）=${config.bakingTime}"
        )
        if (syncToServer) {
            SendServerHelper.publishServiceUpdateStatus()
        }
    }

    fun disableRoastAlgorithm(
        reason: String = "维护页手动关闭算法",
        syncToServer: Boolean = true
    ) {
        isEnabled = false
        val config = AppConfig.getAppConfig()
        config.isEnable = 0
        AppConfig.saveAppConfig(config)
        logD(
            LOG_ALGORITHM,
            "算法开关已关闭：reason=$reason，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                "modeType（并发模式）=${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}，" +
                "收口语义=停止自动补肠/停止开新批次/停止新的并发切换，但当前烤制/搬盘/出餐/转保温继续自然完成"
        )
        if (syncToServer) {
            SendServerHelper.publishServiceUpdateStatus()
        }
    }

}
