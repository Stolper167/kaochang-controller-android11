package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.SauceDetectionUitls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 上位机机械动作封装。
 *
 * 当前项目里和下位机交互最核心的寄存器语义是：
 * - 200：动作指令寄存器。上位机写入动作号，请求下位机执行动作。
 * - 201：动作状态寄存器。
 *   - 0 = 空闲 / 上一次动作已完成
 *   - 1 = 动作执行中
 *   - 2 = 设备忙，本次动作未被接收
 * - 202 / 203：烤盘搬移专用指令和结果寄存器，语义独立于 200 / 201。
 *
 * 因此：
 * - “是否断连”依赖 VMModbusHelper.connectStatus() 判断；
 * - “201=2”只表示设备忙，不等价于通信断开。
 */
object KaoChangOperate {
    private const val LOG_ORDER = "订单履约"
    private const val LOG_ACTION = "动作执行"
    private const val LOG_SELL_MONITOR = "售卖口监控"
    private const val LOG_WINDOW = "窗口收尾"
    private const val LOG_SUPPLEMENT = "补肠流程"
    private const val LOG_PAN_MOVE = "烤盘搬移"
    private const val LOG_HEAT = "加热控制"
    private const val LOG_DEVICE = "设备状态"
    private const val LOG_SELF_CLEAN = "【自清洁】"
    private const val DEFAULT_POST_SELL_SUPPLEMENT_GUARD_SECONDS = 5
    private const val DEFAULT_POST_SELL_IDLE_STABLE_SECONDS = 2
    private const val DEFAULT_POST_SELL_IDLE_MAX_WAIT_SECONDS = 12
    private const val MANUAL_TAKE_IDLE_TIMEOUT_MS = 15_000L
    private const val SELL_PLATFORM_RETRY_DELAY_MS = 300L
    private const val SELL_PLATFORM_CAPTURE_TIMEOUT_MS = 2500L
    private const val LEGACY_LIFT_PLATFORM_DEFAULT_WORLD_Y = 80
    private val SELL_PLATFORM_PRESENCE_RETRY_DELAYS_MS = listOf(300L, 500L, 700L)
    private const val SELL_PLATFORM_MONITOR_INTERVAL_MS = 1000L
    private const val SELL_PLATFORM_WAIT_TIMEOUT_MS = 15_000L
    private const val SELL_PLATFORM_CAPTURE_FAILURE_TOLERANCE = 2
    /**
     * 机械动作对象内部的主线程作用域。
     *
     * 这里只承接“保存配置、同步烤盘、上报设备状态”这类不属于页面生命周期、
     * 但又需要切回主线程执行的任务，避免继续使用 GlobalScope 造成悬挂协程。
     */
    private val operateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var supplementBlockedUntilMs: Long = 0L

    private fun logD(category: String, message: String) = LogUtils.d("[$category] $message")
    private fun logI(category: String, message: String) = LogUtils.i("[$category] $message")
    private fun logW(category: String, message: String) = LogUtils.w("[$category] $message")
    private fun logE(category: String, message: String) = LogUtils.e("[$category] $message")
    private fun logE(category: String, message: String, tr: Throwable) = LogUtils.e("[$category] $message", tr)

    private fun onlineStatusDesc(status: Int): String = when (status) {
        0 -> "0(未启用)"
        1 -> "1(运营中)"
        2 -> "2(休息中)"
        3 -> "3(维护中)"
        4 -> "4(工作中)"
        else -> "$status(未知)"
    }

    private fun restStatusSourceDesc(source: String?): String = AppConfig.restStatusSourceText(source)

    private fun moveStatusDesc(status: Int): String = when (status) {
        0 -> "0(空闲)"
        1 -> "1(补肠中)"
        2 -> "2(出餐中)"
        3 -> "3(烤盘搬移中)"
        4 -> "4(丢弃中)"
        5 -> "5(自清洁中)"
        else -> "$status(未知)"
    }

    private fun actionExecutionResultDesc(result: ActionExecutionResult): String = when (result) {
        ActionExecutionResult.SUCCESS -> "SUCCESS(成功)"
        ActionExecutionResult.COMMAND_REJECTED -> "COMMAND_REJECTED(指令被拒绝)"
        ActionExecutionResult.BUSY_TIMEOUT -> "BUSY_TIMEOUT(设备忙超时)"
        ActionExecutionResult.DISCONNECTED -> "DISCONNECTED(通信断开)"
        ActionExecutionResult.RUNNING_TIMEOUT -> "RUNNING_TIMEOUT(执行超时)"
    }

    enum class TakeSausageResult {
        TAKEN_BY_USER,
        DISCARDED,
        ERROR
    }

    data class SelfCleanExecutionReport(
        val success: Boolean,
        val command: Int,
        val targetLabel: String,
        val status201: Int,
        val message: String
    )

    private fun tryRecoverIdleMoveStatus(reason: String): Boolean {
        val config = AppConfig.getAppConfig()
        if (config.moveStatus == 0) {
            return true
        }
        if (!VMModbusHelper.connectStatus()) {
            return false
        }
        return when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
            ActionExecution201State.IDLE -> {
                val oldMoveStatus = config.moveStatus
                config.moveStatus = 0
                AppConfig.saveAppConfig(config)
                logW(
                    LOG_ACTION,
                    "检测到本地机械状态残留，已按下位机空闲状态自动收口：" +
                        "reason=$reason，moveStatus（机械动作状态）=${moveStatusDesc(oldMoveStatus)}->${moveStatusDesc(config.moveStatus)}"
                )
                true
            }
            else -> false
        }
    }

    private suspend fun waitUntilMoveStatusIdleOrTimeout(
        actionName: String,
        timeoutMs: Long = MANUAL_TAKE_IDLE_TIMEOUT_MS
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var appState = AppConfig.getAppConfig().moveStatus
        while (appState != 0) {
            if (tryRecoverIdleMoveStatus(actionName)) {
                return true
            }
            if (System.currentTimeMillis() >= deadline) {
                logW(
                    LOG_ACTION,
                    "$actionName 等待机械动作归零超时：" +
                        "moveStatus（机械动作状态）=${moveStatusDesc(appState)}，timeoutMs=$timeoutMs"
                )
                return false
            }
            delay(1_000L)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_ACTION, "$actionName 等待机械动作收尾：moveStatus=${moveStatusDesc(appState)}")
        }
        return true
    }

    private enum class ActionExecutionResult {
        SUCCESS,
        COMMAND_REJECTED,
        BUSY_TIMEOUT,
        DISCONNECTED,
        RUNNING_TIMEOUT
    }

    private fun publishRuntimeStateWithHttpFallback(reason: String) {
        val mqttOk = SendServerHelper.publishServiceUpdateStatus()
        if (!mqttOk) {
            logW(LOG_DEVICE, "$reason：MQTT 主上报失败，回退 HTTP 补偿")
            DataManagementAPI.uploadDeviceInfo()
        }
    }

    private fun markDeviceErrorAndUpload(logMessage: String) {
        AppConfig.getAppConfig().errorStatus = 2
        AppConfig.getAppConfig().onlineStatus = 3
        operateScope.launch {
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            publishRuntimeStateWithHttpFallback("设备故障状态上报")
        }
        logE(LOG_DEVICE, logMessage)
    }

    private fun armPostSellSupplementGuard(
        reason: String,
        durationMs: Long = getPostSellSupplementGuardMs()
    ) {
        val blockUntil = System.currentTimeMillis() + durationMs
        if (blockUntil > supplementBlockedUntilMs) {
            supplementBlockedUntilMs = blockUntil
        }
        logD(LOG_WINDOW, "$reason，进入售卖收尾保护窗：${durationMs}ms")
    }

    private suspend fun waitForPostSellSupplementGuardIfNeeded() {
        val blockUntil = supplementBlockedUntilMs
        if (blockUntil <= 0L) {
            return
        }

        val remaining = blockUntil - System.currentTimeMillis()
        if (remaining > 0L) {
            logD(LOG_SUPPLEMENT, "售卖收尾后延迟补肠：remaining=${remaining}ms")
            delay(remaining)
        }

        supplementBlockedUntilMs = 0L

        if (!VMModbusHelper.connectStatus()) {
            logW(LOG_SUPPLEMENT, "售卖收尾保护结束时检测到下位机未连接，跳过空闲稳定检查")
            return
        }

        var idleSince = -1L
        val idleStableMs = getPostSellIdleStableMs()
        val checkDeadline = System.currentTimeMillis() + getPostSellIdleMaxWaitMs()
        while (System.currentTimeMillis() < checkDeadline) {
            when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
                ActionExecution201State.IDLE -> {
                    val now = System.currentTimeMillis()
                    if (idleSince < 0L) {
                        idleSince = now
                    }
                    if (now - idleSince >= idleStableMs) {
                        logD(LOG_SUPPLEMENT, "售卖收尾后下位机已连续空闲 ${now - idleSince}ms，允许补肠")
                        return
                    }
                }
                ActionExecution201State.RUNNING, ActionExecution201State.BUSY -> {
                    idleSince = -1L
                }
                ActionExecution201State.DISCONNECTED -> {
                    logW(LOG_SUPPLEMENT, "售卖收尾后读取 201 失败，跳过空闲稳定检查")
                    return
                }
            }
            delay(500L)
        }

        logW(LOG_SUPPLEMENT, "售卖收尾后等待下位机稳定空闲超时，继续按常规补肠流程处理")
    }

    private suspend fun captureSellPlatform(tag: String = "KaoChangAlgorithm") =
        SauceDetectionUitls.captureAndDetect(
            roiFlag = 1,
            retryDelayMs = SELL_PLATFORM_RETRY_DELAY_MS,
            captureTimeoutMs = SELL_PLATFORM_CAPTURE_TIMEOUT_MS,
            tag = tag
        )

    private suspend fun captureSellPlatformPresenceSequence(): List<SauceDetectionProcessor.SausageInfo?> {
        val results = mutableListOf<SauceDetectionProcessor.SausageInfo?>()
        results += captureSellPlatform()
        if (results.last()?.code == 2) {
            return results
        }
        SELL_PLATFORM_PRESENCE_RETRY_DELAYS_MS.forEach { delayMs ->
            delay(delayMs)
            results += captureSellPlatform()
            if (results.last()?.code == 2) {
                return results
            }
        }
        return results
    }

    private suspend fun resolveSellPlatformOutcomeAfterMechanicalDeliveryLegacy(): TakeSausageResult {
        val presenceResults = captureSellPlatformPresenceSequence()
        val detectDeadlineMs = System.currentTimeMillis() + SELL_PLATFORM_WAIT_TIMEOUT_MS
        return when (SellPlatformConfirmPolicy.evaluateInitialConfirmation(presenceResults)) {
            SellPlatformConfirmPolicy.InitialConfirmationDecision.VISION_UNAVAILABLE_ASSUME_DELIVERED -> {
                waitForUserTakeWhenVisionUnavailable(detectDeadlineMs)
            }

            SellPlatformConfirmPolicy.InitialConfirmationDecision.WAIT_FOR_DELIVERY_OR_FAST_TAKE -> {
                logW(LOG_SELL_MONITOR, "售卖台首次确认阶段未检测到烤肠，已完成 1.5 秒短频补拍，继续每秒轮询确认是否到货或已被顾客快速取走")
                monitorSellPlatformUntilTakenOrDiscarded(
                    detectDeadlineMs = detectDeadlineMs,
                    detectedSausageBeforeMonitor = false
                )
            }

            SellPlatformConfirmPolicy.InitialConfirmationDecision.WAIT_FOR_USER_TAKE -> {
                logD(LOG_SELL_MONITOR, "售卖台首次确认阶段已检测到烤肠，开始每秒轮询顾客是否已取走")
                monitorSellPlatformUntilTakenOrDiscarded(
                    detectDeadlineMs = detectDeadlineMs,
                    detectedSausageBeforeMonitor = true
                )
            }
        }
    }

    private fun finalizePanAfterMechanicalSell(kaoPan: KaoPan) {
        kaoPan.cmdStatusTake = 0
        kaoPan.isHasSausage = false
        kaoPan.holdingTime = 0L
        kaoPan.startTime = 0
        kaoPan.status = 0
        operateScope.launch {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }
    }

    private suspend fun closeSellPlatformAfterUserTake(detectedSausageBeforeEmpty: Boolean): TakeSausageResult {
        val closeResult = executeActionWithRetry(
            actionName = "售卖台关门"
        ) {
            writeSingleRegister2(action_address_shoumaitai, 1)
        }
        return if (closeResult != ActionExecutionResult.SUCCESS) {
            markActionFailure("售卖台关门", closeResult)
            TakeSausageResult.ERROR
        } else {
            armPostSellSupplementGuard("售卖台关门成功")
            if (detectedSausageBeforeEmpty) {
                logD(LOG_SELL_MONITOR, "售卖台曾检测到烤肠，复核已为空，判定客户已经把肠取走")
            } else {
                logW(LOG_SELL_MONITOR, "售卖台监控阶段未检测到烤肠，但窗口已为空，按兼容策略判定客户已经把肠取走")
            }
            TakeSausageResult.TAKEN_BY_USER
        }
    }

    private suspend fun discardSellPlatformAfterTimeout(): TakeSausageResult {
        val closeResult = executeActionWithRetry(
            actionName = "售卖台丢弃并关门"
        ) {
            writeSingleRegister2(action_address_shoumaitai, 2)
        }
        return if (closeResult != ActionExecutionResult.SUCCESS) {
            markActionFailure("售卖台丢弃并关门", closeResult)
            TakeSausageResult.ERROR
        } else {
            armPostSellSupplementGuard("售卖台丢弃并关门成功")
            logW(LOG_WINDOW, "顾客在等待时间内未取走烤肠，已执行丢弃流程并结束本次出餐")
            TakeSausageResult.DISCARDED
        }
    }

    private suspend fun monitorSellPlatformUntilTakenOrDiscarded(
        detectDeadlineMs: Long,
        detectedSausageBeforeMonitor: Boolean
    ): TakeSausageResult {
        var consecutiveCaptureFailures = 0
        var detectedSausage = detectedSausageBeforeMonitor
        while (System.currentTimeMillis() < detectDeadlineMs) {
            delay(SELL_PLATFORM_MONITOR_INTERVAL_MS)
            val detectResult = captureSellPlatform()
            when {
                detectResult == null -> {
                    consecutiveCaptureFailures++
                    if (consecutiveCaptureFailures >= SELL_PLATFORM_CAPTURE_FAILURE_TOLERANCE) {
                        tryCloseSellPlatformOnError("售卖台轮询阶段连续拍照失败")
                        markDeviceErrorAndUpload("售卖台轮询阶段连续拍照失败，无法确认顾客是否已取走烤肠，设备进入维护中")
                        return TakeSausageResult.ERROR
                    }
                    logW(LOG_SELL_MONITOR, "售卖台轮询阶段拍照失败，继续每秒重试确认顾客是否已取走烤肠")
                }

                detectResult.code == 2 -> {
                    consecutiveCaptureFailures = 0
                    detectedSausage = true
                    logD(LOG_SELL_MONITOR, "售卖台轮询检测到烤肠仍在，继续等待顾客取走")
                }

                else -> {
                    return closeSellPlatformAfterUserTake(detectedSausage)
                }
            }
        }

        return discardSellPlatformAfterTimeout()
    }

    private suspend fun waitForUserTakeWhenVisionUnavailable(
        detectDeadlineMs: Long
    ): TakeSausageResult {
        logW(
            LOG_SELL_MONITOR,
            "售卖台首次确认阶段连续拍照失败，进入兼容等待窗口；若视觉恢复则回到正常确认流程，若在等待时间内仍未恢复，则按已送达处理，不进入维护"
        )
        var loggedWaitingWithoutVision = false
        while (System.currentTimeMillis() < detectDeadlineMs) {
            delay(SELL_PLATFORM_MONITOR_INTERVAL_MS)
            val detectResult = captureSellPlatform()
            when {
                detectResult == null -> {
                    if (!loggedWaitingWithoutVision) {
                        loggedWaitingWithoutVision = true
                        logW(LOG_SELL_MONITOR, "售卖台兼容等待窗口内视觉仍不可用，继续等待顾客取走并尝试恢复识别")
                    }
                }

                detectResult.code == 2 -> {
                    logW(LOG_SELL_MONITOR, "售卖台视觉已恢复，重新检测到烤肠，切回正常轮询等待顾客取走")
                    return monitorSellPlatformUntilTakenOrDiscarded(
                        detectDeadlineMs = detectDeadlineMs,
                        detectedSausageBeforeMonitor = true
                    )
                }

                else -> {
                    logW(LOG_SELL_MONITOR, "售卖台视觉已恢复，检测到窗口为空，按顾客已取走处理")
                    return closeSellPlatformAfterUserTake(detectedSausageBeforeEmpty = false)
                }
            }
        }

        logW(
            LOG_SELL_MONITOR,
            "售卖台首次确认阶段连续拍照失败，在兼容等待窗口内视觉仍未恢复；按机械已送达处理并执行关门，不进入维护"
        )
        return closeSellPlatformAfterUserTake(detectedSausageBeforeEmpty = false)
    }

    private suspend fun tryCloseSellPlatformOnError(reason: String) {
        val closeResult = executeActionWithRetry(
            actionName = "售卖台故障兜底关门"
        ) {
            writeSingleRegister2(action_address_shoumaitai, 1)
        }
        if (closeResult == ActionExecutionResult.SUCCESS) {
            logW(LOG_WINDOW, "售卖台进入故障分支前已执行兜底关门：$reason")
        } else {
            logE(LOG_WINDOW, "售卖台故障兜底关门失败：$reason, result=$closeResult")
        }
    }

    private fun getPostSellSupplementGuardMs(): Long {
        val seconds = AppConfig.getAppConfig().postSellSupplementGuardSeconds
            .takeIf { it > 0 }
            ?: DEFAULT_POST_SELL_SUPPLEMENT_GUARD_SECONDS
        return seconds * 1000L
    }

    private fun getPostSellIdleStableMs(): Long {
        val seconds = AppConfig.getAppConfig().postSellIdleStableSeconds
            .takeIf { it > 0 }
            ?: DEFAULT_POST_SELL_IDLE_STABLE_SECONDS
        return seconds * 1000L
    }

    private fun getPostSellIdleMaxWaitMs(): Long {
        val seconds = AppConfig.getAppConfig().postSellIdleMaxWaitSeconds
            .takeIf { it > 0 }
            ?: DEFAULT_POST_SELL_IDLE_MAX_WAIT_SECONDS
        return seconds * 1000L
    }

    private fun getCameraPrewarmHoldMs(): Long {
        val seconds = AppConfig.getAppConfig().cameraPrewarmHoldSeconds
            .takeIf { it > 0 }
            ?: 8
        return seconds * 1000L
    }

    private fun getBoxTasteLabel(kaoPanBox: KaoPanBox): String {
        return kaoPanBox.tasteName?.takeIf { it.isNotBlank() } ?: "未知口味"
    }

    private fun getPanTasteLabel(kaoPan: KaoPan): String {
        val taste = kaoPan.taste
        return taste?.productName?.takeIf { it.isNotBlank() }
            ?: taste?.tasteName?.takeIf { it.isNotBlank() }
            ?: "未知口味"
    }

    private fun getBoxToPlatformTimeoutMs(): Long {
        val seconds = AppConfig.getAppConfig().boxToPlatformTimeoutSeconds
            .takeIf { it > 0 }
            ?: 90
        return seconds * 1000L
    }

    private fun getTrayToSellPlatformTimeoutMs(): Long {
        val seconds = AppConfig.getAppConfig().trayToSellPlatformTimeoutSeconds
            .takeIf { it > 0 }
            ?: 90
        return seconds * 1000L
    }

    // Modbus 设备地址与关键寄存器地址。
    val modbus_address = 1
    val action_address = 200
    val action_address_status = 201
    val action_address_move = 202
    val action_address_move_result = 203
    val action_address_shenjiatai = 206
    val action_address_shoumaitai = 207
    val action_address_1_temperature = 500
    val action_address_2_temperature = 501
    val action_address_3_temperature = 502
    val action_address_1_temperature_write = 503
    val action_address_2_temperature_write = 504
    val action_address_3_temperature_write = 505
    private const val self_clean_command_min = 501
    private const val self_clean_command_max = 533
    private const val self_clean_sell_platform_command = 534

    val keepWarmTemperature = 90
    val heatingTemperature = 150

    val action_debug = 255

    /**
     * 仅按当前和嵌入式确认的 201 协议语义解析动作状态。
     * 这里把读寄存器失败映射成 DISCONNECTED，避免和 201=2 的“设备忙”混淆。
     */
    private fun parseActionStatus201(value: Int): ActionExecution201State {
        return when (value) {
            0 -> ActionExecution201State.IDLE
            1 -> ActionExecution201State.RUNNING
            2 -> ActionExecution201State.BUSY
            else -> ActionExecution201State.DISCONNECTED
        }
    }

    private fun actionStatus201Desc(status: ActionExecution201State?): String = when (status) {
        null -> "INIT(未开始轮询)"
        ActionExecution201State.IDLE -> "0(IDLE/空闲)"
        ActionExecution201State.RUNNING -> "1(RUNNING/执行中)"
        ActionExecution201State.BUSY -> "2(BUSY/设备忙)"
        ActionExecution201State.DISCONNECTED -> "READ_FAIL(读取失败/通信断开)"
    }

    /**
     * 在真正下发动作前做一次通信层预检。
     * 这里只解决“上下位机通不通”的问题，不负责判断设备忙闲。
     */
    private suspend fun ensureModbusReady(actionName: String): Boolean {
        if (VMModbusHelper.connectStatus()) {
            return true
        }
        logW(LOG_ACTION, "$actionName 前检测到下位机通信断开，开始短时重连")
        repeat(3) { index ->
            val attempt = index + 1
            VMModbusHelper.connectModbus { status ->
                logD(LOG_ACTION, "$actionName 前重连下位机结果：attempt=$attempt/3, status=$status")
            }
            delay(1500L)
            if (VMModbusHelper.connectStatus()) {
                logD(LOG_ACTION, "$actionName 前下位机重连成功：attempt=$attempt/3")
                return true
            }
        }
        logE(LOG_ACTION, "$actionName 前下位机持续断开，放弃执行动作")
        return false
    }

    /**
     * 统一封装 200/201 协议下的动作执行逻辑。
     *
     * 约束：
     * - 下发动作前先做 connectStatus() 预检
     * - 201=2 按“设备忙”处理，有限次等待后重试
     * - 201=1 长时间不回到 0，按执行超时处理
     * - 通信层失败才走 DISCONNECTED
     */
    private suspend fun executeActionWithRetry(
        actionName: String,
        maxBusyRetry: Int = 3,
        busyRetryDelayMs: Long = 1000L,
        runningPollDelayMs: Long = 1000L,
        runningTimeoutMs: Long = 60_000L,
        requireObservedRunningBeforeSuccess: Boolean = false,
        requireRunningObservedTimeoutMs: Long = 3_000L,
        issueAction: suspend () -> VMModbusHelper.ModbusOperationResult
    ): ActionExecutionResult {
        var busyRetryCount = 0

        retryLoop@ while (busyRetryCount <= maxBusyRetry) {
            if (!ensureModbusReady(actionName)) {
                return ActionExecutionResult.DISCONNECTED
            }

            when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
                ActionExecution201State.IDLE -> Unit
                ActionExecution201State.RUNNING, ActionExecution201State.BUSY -> {
                    busyRetryCount++
                    if (busyRetryCount > maxBusyRetry) {
                        logE(LOG_ACTION, "$actionName 前连续检测到设备忙，超过重试阈值：retry=$busyRetryCount")
                        return ActionExecutionResult.BUSY_TIMEOUT
                    }
                    logW(LOG_ACTION, "$actionName 前检测到设备忙(201!=0)，等待后重试：attempt=$busyRetryCount/$maxBusyRetry")
                    delay(busyRetryDelayMs)
                    continue@retryLoop
                }
                ActionExecution201State.DISCONNECTED -> {
                    logE(LOG_ACTION, "$actionName 前读取动作状态失败")
                    return ActionExecutionResult.DISCONNECTED
                }
            }

            val issueResult = issueAction()
            when (issueResult.status) {
                VMModbusHelper.ModbusOperationStatus.SUCCESS -> Unit
                VMModbusHelper.ModbusOperationStatus.BUSY -> {
                    busyRetryCount++
                    if (busyRetryCount > maxBusyRetry) {
                        logE(LOG_ACTION, "$actionName 下发指令时下位机持续返回忙，超过重试阈值")
                        return ActionExecutionResult.BUSY_TIMEOUT
                    }
                    logW(LOG_ACTION, "$actionName 下发指令时下位机返回忙，等待后重试：attempt=$busyRetryCount/$maxBusyRetry")
                    delay(busyRetryDelayMs)
                    continue@retryLoop
                }
                VMModbusHelper.ModbusOperationStatus.DISCONNECTED -> {
                    logE(LOG_ACTION, "$actionName 下发指令失败：下位机通信断开")
                    return ActionExecutionResult.DISCONNECTED
                }
                VMModbusHelper.ModbusOperationStatus.PARAM_ERROR,
                VMModbusHelper.ModbusOperationStatus.DEVICE_FAILURE,
                VMModbusHelper.ModbusOperationStatus.PROTOCOL_ERROR,
                VMModbusHelper.ModbusOperationStatus.UNKNOWN_ERROR -> {
                    logE(LOG_ACTION, "$actionName 下发指令失败：${issueResult.status} ${issueResult.message ?: ""}".trim())
                    return ActionExecutionResult.COMMAND_REJECTED
                }
            }
            val start = System.currentTimeMillis()
            val progressTracker = ActionExecutionProgressTracker(requireObservedRunningBeforeSuccess)
            var lastPolledStatus: ActionExecution201State? = null

            while (true) {
                delay(runningPollDelayMs)
                if (!ensureModbusReady(actionName)) {
                    return ActionExecutionResult.DISCONNECTED
                }
                val currentStatus = parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))
                if (currentStatus != lastPolledStatus) {
                    logD(
                        LOG_ACTION,
                        "$actionName 轮询201状态变化：${actionStatus201Desc(lastPolledStatus)} -> ${actionStatus201Desc(currentStatus)}，requireObservedRunningBeforeSuccess=$requireObservedRunningBeforeSuccess"
                    )
                    lastPolledStatus = currentStatus
                }
                when (currentStatus) {
                    ActionExecution201State.RUNNING -> {
                        progressTracker.onStatus(currentStatus)
                        if (System.currentTimeMillis() - start > runningTimeoutMs) {
                            logE(LOG_ACTION, "$actionName 长时间处于执行中(201=1)，已超时")
                            return ActionExecutionResult.RUNNING_TIMEOUT
                        }
                    }
                    ActionExecution201State.IDLE -> {
                        when (progressTracker.onStatus(currentStatus)) {
                            ActionExecutionProgressTracker.ProgressDecision.SUCCESS -> return ActionExecutionResult.SUCCESS
                            ActionExecutionProgressTracker.ProgressDecision.WAIT -> Unit
                            ActionExecutionProgressTracker.ProgressDecision.WAIT_FOR_START -> {
                                if (System.currentTimeMillis() - start > requireRunningObservedTimeoutMs) {
                                    logE(LOG_ACTION, "$actionName 下发后未观察到201=1(执行中)，却持续回到201=0(空闲)，判定动作为未真正启动")
                                    return ActionExecutionResult.COMMAND_REJECTED
                                }
                            }
                        }
                    }
                    ActionExecution201State.BUSY -> {
                        busyRetryCount++
                        if (busyRetryCount > maxBusyRetry) {
                            logE(LOG_ACTION, "$actionName 下发后连续收到设备忙(201=2)，超过重试阈值")
                            return ActionExecutionResult.BUSY_TIMEOUT
                        }
                        logW(LOG_ACTION, "$actionName 下发后收到设备忙(201=2)，等待后重试：attempt=$busyRetryCount/$maxBusyRetry")
                        delay(busyRetryDelayMs)
                        continue@retryLoop
                    }
                    ActionExecution201State.DISCONNECTED -> {
                        logE(LOG_ACTION, "$actionName 执行过程中读取动作状态失败")
                        return ActionExecutionResult.DISCONNECTED
                    }
                }
            }
        }

        return ActionExecutionResult.BUSY_TIMEOUT
    }

    private fun markActionFailure(actionName: String, result: ActionExecutionResult) {
        val logMessage = when (result) {
            ActionExecutionResult.SUCCESS -> return
            ActionExecutionResult.COMMAND_REJECTED -> "$actionName 下发指令失败，设备进入维护中"
            ActionExecutionResult.BUSY_TIMEOUT -> "$actionName 连续收到设备忙(201=2)，超过重试阈值，设备进入维护中"
            ActionExecutionResult.DISCONNECTED -> "$actionName 过程中与下位机通信断开，设备进入维护中"
            ActionExecutionResult.RUNNING_TIMEOUT -> "$actionName 长时间处于执行中(201=1)，设备进入维护中"
        }
        markDeviceErrorAndUpload(logMessage)
    }

    private fun buildLegacyLiftPlatformRegisterValue(): Int {
        val worldY = LEGACY_LIFT_PLATFORM_DEFAULT_WORLD_Y.coerceAtLeast(60)
        return (1 shl 8) or worldY
    }

    private fun buildSelfCleanCommand(positionSn: Int): Int {
        require(positionSn in 1..33) { "positionSn out of range: $positionSn" }
        return 500 + positionSn
    }

    private fun buildSelfCleanFailureReport(
        command: Int,
        targetLabel: String,
        result: ActionExecutionResult
    ): SelfCleanExecutionReport {
        val status201 = when (result) {
            ActionExecutionResult.SUCCESS -> 0
            ActionExecutionResult.RUNNING_TIMEOUT -> 1
            ActionExecutionResult.BUSY_TIMEOUT -> 2
            ActionExecutionResult.COMMAND_REJECTED,
            ActionExecutionResult.DISCONNECTED -> -1
        }
        return SelfCleanExecutionReport(
            success = false,
            command = command,
            targetLabel = targetLabel,
            status201 = status201,
            message = "result=${actionExecutionResultDesc(result)}"
        )
    }

    private fun buildSelfCleanDisabledReport(targetLabel: String, source: String): SelfCleanExecutionReport {
        return SelfCleanFeatureToggle.buildDisabledExecutionReport(targetLabel)
    }

    private fun buildSelfCleanDisabledSequenceResult(source: String): SelfCleanSequenceResult {
        return SelfCleanFeatureToggle.buildDisabledSequenceResult()
    }

    private fun markSelfCleanFailure(targetLabel: String, result: ActionExecutionResult) {
        val config = AppConfig.getAppConfig()
        config.errorStatus = 2
        config.onlineStatus = 3
        config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
        config.zone3Dirty = 0
        config.zone3CleanPending = 0
        AppConfig.saveAppConfig(config)
        publishRuntimeStateWithHttpFallback("自清洁失败，进入维护中")
        HomeUiRefreshBridge.requestRefresh("self_clean:failed")
        logE(
            LOG_SELF_CLEAN,
            "自清洁失败：target=$targetLabel，result=${actionExecutionResultDesc(result)}，" +
                "onlineStatus（设备营业状态）=${onlineStatusDesc(config.onlineStatus)}，" +
                "errorStatus（设备故障状态）=${config.errorStatus}(2=设备故障)"
        )
    }

    private suspend fun executeSelfCleanCommand(
        command: Int,
        targetLabel: String,
        source: String
    ): SelfCleanExecutionReport {
        logI(
            LOG_SELF_CLEAN,
            "开始执行自清洁：source=$source，target=$targetLabel，command（下位机自清洁指令）=$command，" +
                "moveStatus（机械动作状态）=${moveStatusDesc(AppConfig.getAppConfig().moveStatus)}，" +
                "onlineStatus（设备营业状态）=${onlineStatusDesc(AppConfig.getAppConfig().onlineStatus)}"
        )
        val result = executeActionWithRetry(
            actionName = "$targetLabel 自清洁",
            runningPollDelayMs = 1000L,
            runningTimeoutMs = 180_000L
        ) {
            writeSingleRegister2(action_address, command)
        }
        if (result != ActionExecutionResult.SUCCESS) {
            logE(
                LOG_SELF_CLEAN,
                "自清洁失败：source=$source，target=$targetLabel，command=$command，result=${actionExecutionResultDesc(result)}"
            )
            markSelfCleanFailure(targetLabel, result)
            return buildSelfCleanFailureReport(command, targetLabel, result)
        }

        logI(
            LOG_SELF_CLEAN,
            "自清洁完成：source=$source，target=$targetLabel，command=$command，status201（动作状态寄存器）=0（空闲/完成）"
        )
        return SelfCleanExecutionReport(
            success = true,
            command = command,
            targetLabel = targetLabel,
            status201 = 0,
            message = "completed"
        )
    }

    suspend fun cleanPanPosition(positionSn: Int, source: String): SelfCleanExecutionReport {
        return cleanPanPosition(positionSn, source, busyDuringClean = false)
    }

    suspend fun cleanPanPosition(
        positionSn: Int,
        source: String,
        busyDuringClean: Boolean
    ): SelfCleanExecutionReport {
        if (!SelfCleanFeatureToggle.isEnabled()) {
            return buildSelfCleanDisabledReport("烤盘$positionSn", source)
        }
        val command = buildSelfCleanCommand(positionSn)
        return runPanSelfCleanSequence(
            listOf(positionSn),
            includeSellPlatform = false,
            source = source,
            busyDuringClean = busyDuringClean
        )
            .reports
            .firstOrNull()
            ?: SelfCleanExecutionReport(false, command, "烤盘$positionSn", -1, "missing_report")
    }

    suspend fun cleanSellPlatform(source: String): SelfCleanExecutionReport {
        return cleanSellPlatform(source, busyDuringClean = false)
    }

    suspend fun cleanSellPlatform(
        source: String,
        busyDuringClean: Boolean
    ): SelfCleanExecutionReport {
        if (!SelfCleanFeatureToggle.isEnabled()) {
            return buildSelfCleanDisabledReport("出肠台", source)
        }
        return runPanSelfCleanSequence(
            emptyList(),
            includeSellPlatform = true,
            source = source,
            busyDuringClean = busyDuringClean
        )
            .reports
            .firstOrNull()
            ?: SelfCleanExecutionReport(false, self_clean_sell_platform_command, "出肠台", -1, "missing_report")
    }

    data class SelfCleanSequenceResult(
        val success: Boolean,
        val reports: List<SelfCleanExecutionReport>
    )

    suspend fun runPanSelfCleanSequence(
        positionSnList: List<Int>,
        includeSellPlatform: Boolean,
        source: String,
        busyDuringClean: Boolean = false
    ): SelfCleanSequenceResult {
        if (!SelfCleanFeatureToggle.isEnabled()) {
            return buildSelfCleanDisabledSequenceResult(source)
        }
        val uniquePositions = positionSnList.distinct().sorted()
        val config = AppConfig.getAppConfig()
        if (config.errorStatus != 0) {
            val report = SelfCleanExecutionReport(false, -1, "自清洁前置检查", -1, "device_error")
            logW(LOG_SELF_CLEAN, "跳过自清洁：设备当前已处于故障态，source=$source")
            return SelfCleanSequenceResult(false, listOf(report))
        }

        var moveStatus = AppConfig.getAppConfig().moveStatus
        while (moveStatus != 0) {
            delay(2000L)
            moveStatus = AppConfig.getAppConfig().moveStatus
            logD(LOG_SELF_CLEAN, "等待其他机械动作结束后再执行自清洁：moveStatus=${moveStatusDesc(moveStatus)}，source=$source")
        }

        val reports = mutableListOf<SelfCleanExecutionReport>()
        val runtimeConfig = AppConfig.getAppConfig()
        val previousMoveStatus = runtimeConfig.moveStatus
        val previousOnlineStatus = runtimeConfig.onlineStatus
        val switchedToBusyOnlineStatus = busyDuringClean && previousOnlineStatus == 1
        runtimeConfig.moveStatus = 5
        if (switchedToBusyOnlineStatus) {
            runtimeConfig.onlineStatus = 4
        }
        AppConfig.saveAppConfig(runtimeConfig)
        publishRuntimeStateWithHttpFallback("自清洁开始")
        HomeUiRefreshBridge.requestRefresh("self_clean:start")

        try {
            uniquePositions.forEach { positionSn ->
                val report = executeSelfCleanCommand(
                    command = buildSelfCleanCommand(positionSn),
                    targetLabel = "烤盘$positionSn",
                    source = source
                )
                reports += report
                if (!report.success) {
                    return SelfCleanSequenceResult(false, reports)
                }
            }

            if (includeSellPlatform) {
                val report = executeSelfCleanCommand(
                    command = self_clean_sell_platform_command,
                    targetLabel = "出肠台",
                    source = source
                )
                reports += report
                if (!report.success) {
                    return SelfCleanSequenceResult(false, reports)
                }
            }
            return SelfCleanSequenceResult(true, reports)
        } finally {
            val latestConfig = AppConfig.getAppConfig()
            latestConfig.moveStatus = if (latestConfig.onlineStatus == 3) 0 else previousMoveStatus
            if (switchedToBusyOnlineStatus && latestConfig.onlineStatus == 4) {
                latestConfig.onlineStatus = previousOnlineStatus
            }
            AppConfig.saveAppConfig(latestConfig)
            if (latestConfig.onlineStatus != 3) {
                publishRuntimeStateWithHttpFallback("自清洁结束")
                HomeUiRefreshBridge.requestRefresh("self_clean:finished")
            }
        }
    }

    /**
     * 自动补肠：从烤肠箱取一根生肠，经平台/运输台搬到指定烤盘。
     *
     * 只有“烤肠箱 -> 平台”和“平台 -> 烤盘”两个动作都成功时，
     * 才允许扣减烤肠箱库存并写入目标烤盘状态。
     */
    suspend fun moveSausageToKaoPan(kaoPan: KaoPan, kaoPanBox: KaoPanBox):Boolean {
        if (AppConfig.getAppConfig().errorStatus>0){
            logD(LOG_SUPPLEMENT, "程序处于报错状态，不进行补肠操作")
            return false
        }
        if (kaoPanBox.num <= 0) {
            logE(LOG_SUPPLEMENT, "补肠取消：烤肠箱${kaoPanBox.positionSn}库存不足，当前库存=${kaoPanBox.num}")
            return false
        }

        var appState = AppConfig.getAppConfig().moveStatus
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_SUPPLEMENT, "补肠前等待其他机械动作结束：moveStatus=${moveStatusDesc(appState)}")
        }
        waitForPostSellSupplementGuardIfNeeded()
        AppConfig.getAppConfig().moveStatus = 1
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        val sausageName = getBoxTasteLabel(kaoPanBox)
        val boxStockBefore = kaoPanBox.num
        logD(
            LOG_SUPPLEMENT,
            "开始补肠：目标烤盘=${kaoPan.positionSn}，来源烤肠箱=${kaoPanBox.positionSn}，口味=$sausageName，" +
                "箱库存=$boxStockBefore，targetPanHasSausage=${kaoPan.isHasSausage}"
        )
        logD(LOG_ACTION, "机械臂将烤肠($sausageName)从烤肠箱${kaoPanBox.positionSn}搬运到升降台")
        val result = executeActionWithRetry(
            actionName = "烤肠箱${kaoPanBox.positionSn}到平台",
            runningTimeoutMs = getBoxToPlatformTimeoutMs(),
            requireObservedRunningBeforeSuccess = true
        ) {
            writeSingleRegister2(action_address, kaoPanBox.cmdValueTake)
        }
        if (result != ActionExecutionResult.SUCCESS) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            markActionFailure("烤肠箱${kaoPanBox.positionSn}到平台", result)
            return false
        }
        logD(LOG_SUPPLEMENT, "补肠中间态：烤肠($sausageName)已从烤肠箱${kaoPanBox.positionSn}搬运到升降台")
        kaoPanBox.cmdStatusTake = 0

        val liftPlatformRegisterValue = buildLegacyLiftPlatformRegisterValue()
        logD(
            LOG_SUPPLEMENT,
            "老版本已跳过升降台视觉识别：烤肠($sausageName)按固定安全坐标继续下发，寄存器206=$liftPlatformRegisterValue"
        )
        writeSingleRegister2(action_address_shenjiatai, liftPlatformRegisterValue)

        delay(200)
        logD(LOG_ACTION, "机械臂将烤肠($sausageName)从升降台搬运到烤盘${kaoPan.positionSn}")
        val result2 = executeActionWithRetry(
            actionName = "平台到烤盘${kaoPan.positionSn}",
            requireObservedRunningBeforeSuccess = true
        ) {
            writeSingleRegister2(action_address, kaoPan.cmdValueMove)
        }
        if (result2 != ActionExecutionResult.SUCCESS) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            markActionFailure("平台到烤盘${kaoPan.positionSn}", result2)
        }
        logD(
            LOG_SUPPLEMENT,
            "补肠完成态：烤肠($sausageName)已从升降台搬运到烤盘${kaoPan.positionSn}，" +
                "result=${actionExecutionResultDesc(result2)}"
        )

        kaoPan.cmdStatusMove = if (result2 == ActionExecutionResult.SUCCESS) 0 else 2
        val moveSuccess = result == ActionExecutionResult.SUCCESS && result2 == ActionExecutionResult.SUCCESS
        if (moveSuccess){
            kaoPan.isHasSausage=true
            // 减少烤肠箱中的数量，最低只允许到 0，防止库存被写成负数
            kaoPanBox.num = maxOf(0, kaoPanBox.num - 1)
            kaoPan.taste = KaoPanHelper.buildTasteFromBox(kaoPanBox, kaoPan.taste)
            //清空旧位置信息
            kaoPan.status=1//开始加热烤制
            // 记录开始时间
            kaoPan.startTime = System.currentTimeMillis()
            // 从当前 config 刷新烤制时长和丢弃阈值，防止初始化时 config 尚未从云端加载
            // 导致 bakingTime/closeTime 遗留为 0 而直接跳入保温状态
            val cfg = AppConfig.getAppConfig()
            kaoPan.bakingTime = cfg.bakingTime * 60L * 1000L
            kaoPan.closeTime  = cfg.discardTime * 60L * 60L * 1000L
            logD(LOG_SUPPLEMENT, "补肠后刷新烤制参数：bakingTime=${cfg.bakingTime}min, discardTime=${cfg.discardTime}h → panId=${kaoPan.id}")
            logD(
                LOG_SUPPLEMENT,
                "补肠完成：来源烤肠箱=${kaoPanBox.positionSn}，目标烤盘=${kaoPan.positionSn}，口味=$sausageName，" +
                    "箱库存=$boxStockBefore->${kaoPanBox.num}，panStatus=${kaoPan.status}，startTime=${kaoPan.startTime}"
            )
        } else {
            logE(
                LOG_SUPPLEMENT,
                "补肠失败，未更新烤盘状态和库存：targetPan=${kaoPan.positionSn}, sourceBox=${kaoPanBox.positionSn}, " +
                    "result=${actionExecutionResultDesc(result)}, result2=${actionExecutionResultDesc(result2)}"
            )
        }

        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        //更新烤肠箱的库存和烤盘的状态
        operateScope.launch {
            KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        logD(LOG_SUPPLEMENT, "补肠后烤盘状态更新完成")
        }
        logD(LOG_SUPPLEMENT, "补肠流程结束：来源烤肠箱=${kaoPanBox.positionSn}，目标烤盘=${kaoPan.positionSn}，success=$moveSuccess")

        while (AppConfig.getAppConfig().onlineStatus == 4) {
            delay(5000L)
            logD(LOG_SUPPLEMENT, "等待当前售卖结束后再补下一根烤肠")
        }
        return moveSuccess
    }

    /**
     * 烤盘间搬移。
     *
     * 这条链路走 202/203 寄存器，不复用 200/201 的忙态语义：
     * - 202：下发搬移动作，值为“起始烤盘 << 8 | 目标烤盘”
     * - 203：搬移结果，0=完成，1=执行中，其余=错误，-1=上位机读失败
     *
     * 因此这里除了写指令预检外，还要单独处理：
     * - 203 长时间停在 1 的超时
     * - 203 读失败
     * - 203 返回非 0/1 的明确错误
     */
    suspend fun moveKaoPanToKaoPan(
        kaoPan: KaoPan,
        newKaoPan: KaoPan,
        targetHoldingTime: Long = kaoPan.holdingTime
    ):Boolean  {
        val sourcePosition = kaoPan.positionSn
        val targetPosition = newKaoPan.positionSn
        if (sourcePosition==targetPosition){
            return true
        }

        var appState = AppConfig.getAppConfig().moveStatus
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_PAN_MOVE, "烤盘搬移前等待其他机械动作结束：moveStatus=${moveStatusDesc(appState)}")
        }
        AppConfig.getAppConfig().moveStatus = 3
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        logD(
            LOG_PAN_MOVE,
            "开始搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，口味=${getPanTasteLabel(kaoPan)}，" +
                "targetHoldingTime=$targetHoldingTime"
        )

        if (!ensureModbusReady("烤盘${sourcePosition}到烤盘${targetPosition}搬移")) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=下位机通信断开")
            markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移前下位机通信断开，设备进入维护中")
            return false
        }

        val value = (sourcePosition shl 8) or targetPosition
        val writeMoveResult = writeSingleRegister2(action_address_move,value)
        if (!writeMoveResult.isSuccess) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=203 指令下发失败，status=${writeMoveResult.status}")
            markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移指令下发失败：${writeMoveResult.status}")
            return false
        }
        val moveStart = System.currentTimeMillis()
        val moveTimeoutMs = 60_000L
        var result = 1
        while (result == 1) {
            delay(2000)
            result = VMModbusHelper.readHoldingRegisters(modbus_address,action_address_move_result)
            if (result == -1) {
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=读取 203 失败")
                markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移时读取203失败，设备进入维护中")
                return false
            }
            if (System.currentTimeMillis() - moveStart > moveTimeoutMs) {
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=203 长时间为 1（执行中）")
                markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移长时间处于执行中(203=1)，设备进入维护中")
                return false
            }
        }
        if (result != 0) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，203=$result")
            markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移失败，203=$result，设备进入维护中")
            return false
        }
        kaoPan.cmdStatusMove=result
        if (result==0){
            newKaoPan.isHasSausage=true
            newKaoPan.isHasGrilling=false
            newKaoPan.taste = KaoPanHelper.copyResolvedTaste(kaoPan.taste) ?: kaoPan.taste
            newKaoPan.holdingTime= targetHoldingTime
            newKaoPan.status= 2 //kaoPan.status
            kaoPan.isHasSausage=false
            kaoPan.startTime = 0
            kaoPan.status=0

        }
        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        operateScope.launch {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }
        logD(
            LOG_PAN_MOVE,
            "搬移完成：来源烤盘=$sourcePosition 已清空，目标烤盘=$targetPosition 已写入，" +
                "targetHoldingTime=${newKaoPan.holdingTime}，targetStatus=${newKaoPan.status}"
        )

        while (AppConfig.getAppConfig().onlineStatus == 4) {
            delay(5000L)
            logD(LOG_PAN_MOVE, "等待当前出餐结束后再继续烤盘搬移")
        }
        return result==0
    }   

    /**
     * 旧的手动取肠入口，主要给维护页手动操作使用。
     * 这条路径不参与订单履约状态机，但故障时也会进入维护中并补齐 errorStatus。
     */
    suspend fun takeSausage(kaoPan: KaoPan) :Boolean {
        if (!waitUntilMoveStatusIdleOrTimeout("手动取肠前等待机械动作归零")) {
            return false
        }
        AppConfig.getAppConfig().moveStatus = 2
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        val sausageName = getPanTasteLabel(kaoPan)
        CameraMonitor.instance.prewarm(getCameraPrewarmHoldMs())
        logD(LOG_ACTION, "维护操作：机械臂将烤肠($sausageName)从烤盘${kaoPan.positionSn}夹取、插签、搬运到售卖口")
        try {
            val result = executeActionWithRetry(
                actionName = "旧手动取肠-烤盘${kaoPan.positionSn}到售卖口",
                runningTimeoutMs = getTrayToSellPlatformTimeoutMs()
            ) {
                writeSingleRegister2(action_address,kaoPan.cmdValueTake)
            }
            var panStateCleared = false
            if(result!=ActionExecutionResult.SUCCESS){
                markActionFailure("旧手动取肠-烤盘${kaoPan.positionSn}到售卖口", result)
            } else {
                // 只要烤盘到售卖口成功，这根肠就已经离开了原烤盘。
                // 即使后续售卖口识别或关门收尾失败，也不应继续在烤盘上显示“有肠”。
                kaoPan.isHasSausage=false
                kaoPan.holdingTime=0L
                kaoPan.startTime = 0
                kaoPan.status=0
                panStateCleared = true
                operateScope.launch {
                    KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                }
                logD(LOG_ORDER, "旧手动取肠：烤盘${kaoPan.positionSn}已到售卖口，先清空烤盘状态，再执行售卖口收尾")
                // 搬移完成后 等待一定时间再进行视觉检测
                delay(3000)
                val sausageInfo = captureSellPlatform()
                if (sausageInfo==null){ // 拍照失败
                    tryCloseSellPlatformOnError("旧手动取肠首次拍照失败")
                    markDeviceErrorAndUpload("售卖台第一次拍照失败，旧手动取肠流程进入维护中")
                }else if (sausageInfo.code == 2){ // 识别到烤肠，再确认一次客户是否没取走
                    delay(3000)
                    val sausageInfo_2 = captureSellPlatform()
                    if (sausageInfo_2 == null) {
                        tryCloseSellPlatformOnError("旧手动取肠第二次拍照失败")
                        markDeviceErrorAndUpload("售卖台第二次拍照失败，旧手动取肠流程进入维护中")
                    }else if (sausageInfo_2.code == 2){
                        val closeResult = executeActionWithRetry(
                            actionName = "旧手动取肠收尾-丢弃并关门"
                        ) {
                            writeSingleRegister2(action_address_shoumaitai,2)
                        }
                        if (closeResult != ActionExecutionResult.SUCCESS) {
                            markActionFailure("旧手动取肠收尾-丢弃并关门", closeResult)
                            return false
                        }
                        logD(LOG_WINDOW, "客户未及时取肠，执行丢弃并关门完成")
                    }
                    else{
                        val closeResult = executeActionWithRetry(
                            actionName = "旧手动取肠收尾-关门"
                        ) {
                            writeSingleRegister2(action_address_shoumaitai,1)
                        }
                        if (closeResult != ActionExecutionResult.SUCCESS) {
                            markActionFailure("旧手动取肠收尾-关门", closeResult)
                            return false
                        }
                        logD(LOG_WINDOW, "客户已经把肠取走，关门完成")
                    }
                } else { // 未识别到 顾客取走了
                    val closeResult = executeActionWithRetry(
                        actionName = "旧手动取肠收尾-关门"
                    ) {
                        writeSingleRegister2(action_address_shoumaitai,1)
                    }
                    if (closeResult != ActionExecutionResult.SUCCESS) {
                        markActionFailure("旧手动取肠收尾-关门", closeResult)
                        return false
                    }
                    logD(LOG_WINDOW, "客户已经把肠取走，关门完成")
                }
            }

            kaoPan.cmdStatusTake=if (result == ActionExecutionResult.SUCCESS) 0 else 2

            if (result==ActionExecutionResult.SUCCESS && !panStateCleared){
                kaoPan.isHasSausage=false
                kaoPan.holdingTime=0L
                kaoPan.startTime = 0
                kaoPan.status=0
            }

            operateScope.launch {
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            }
            return result==ActionExecutionResult.SUCCESS
        } finally {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
        }
    }

    /**
     * 正式订单出餐入口。
     *
     * 当前真实业务语义：
     * - 顾客取走：返回 TAKEN_BY_USER
     * - 超时未取且系统已丢弃：返回 DISCARDED，当前订单逻辑按“本次已处理完成”推进
     * - 机械、识别或通信异常：返回 ERROR，并由上层上报 status=5
     */
    suspend fun takeSausageResult(kaoPan: KaoPan): TakeSausageResult {
        // 说明：
        // - 订单流程会在整单开始时把 moveStatus 置为 2，表示“订单出餐进行中”。
        // - 这里不能再要求 moveStatus==0，否则会和整单锁冲突，导致出餐卡住。
        // - 只需要等待与出餐冲突的动作（补肠/搬盘/丢弃）结束：1/3/4。
        // - 如果当前是 0（例如非整单场景直接调用），这里临时占用为 2，结束后再恢复。
        var appState = AppConfig.getAppConfig().moveStatus
        while (appState == 1 || appState == 3 || appState == 4 || appState == 5) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_ACTION, "正式出餐前等待机械动作收尾：moveStatus=$appState")
        }
        var acquiredByThisCall = false
        if (appState == 0) {
            AppConfig.getAppConfig().moveStatus = 2
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            acquiredByThisCall = true
        }

        try {
            CameraMonitor.instance.prewarm(getCameraPrewarmHoldMs())
            val sausageName = getPanTasteLabel(kaoPan)
            logD(LOG_ACTION, "机械臂将烤肠($sausageName)从烤盘${kaoPan.positionSn}夹取、插签、搬运到售卖口")
            val result = executeActionWithRetry(
                actionName = "烤盘${kaoPan.positionSn}到售卖口",
                runningTimeoutMs = getTrayToSellPlatformTimeoutMs()
            ) {
                writeSingleRegister2(action_address, kaoPan.cmdValueTake)
            }
            if (result != ActionExecutionResult.SUCCESS) {
                markActionFailure("烤盘${kaoPan.positionSn}到售卖口", result)
                return TakeSausageResult.ERROR
            }
            SelfCleanManager.recordUsedPanForCurrentOrder(kaoPan.positionSn)
            val takeResult = resolveSellPlatformOutcomeAfterMechanicalDeliveryLegacy()
            finalizePanAfterMechanicalSell(kaoPan)
            return takeResult
        } finally {
            if (acquiredByThisCall) {
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
            }
        }
    }

    /**
     * 过保丢弃：把烤盘中超过保温时长的烤肠丢弃。
     * 这条链路也按 201=忙时重试、通信失败/超时才判故障。
     */
    suspend fun discardSausage(
        kaoPan: KaoPan,
        source: String = "过保到期",
        detail: String? = null
    ) :Boolean {

        var appState = AppConfig.getAppConfig().moveStatus
        // 运动状态 为0是说明机械臂闲置
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_ACTION, "丢弃烤肠等待售卖完成：state=$appState")
        }
        // 机械臂状态改为搬运
        AppConfig.getAppConfig().moveStatus = 4
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        val tasteLabel = getPanTasteLabel(kaoPan)
        val sourceDetail = detail?.takeIf { it.isNotBlank() } ?: "未补充详细原因"
        LogUtils.i(
            "【丢弃流程】开始丢弃：来源=$source，烤盘=${kaoPan.positionSn}，口味=$tasteLabel，详情=$sourceDetail"
        )

        val result = executeActionWithRetry(
            actionName = "烤盘${kaoPan.positionSn}${source}丢弃",
            runningPollDelayMs = 2000L,
            runningTimeoutMs = 120_000L
        ) {
            writeSingleRegister2(action_address,kaoPan.cmdValueDiscard)
        }
        if(result != ActionExecutionResult.SUCCESS){
            LogUtils.e(
                "【丢弃流程】丢弃失败：来源=$source，烤盘=${kaoPan.positionSn}，口味=$tasteLabel，结果=$result，详情=$sourceDetail"
            )
            markActionFailure("烤盘${kaoPan.positionSn}${source}丢弃", result)
        }
        kaoPan.cmdStatusDiscard=if (result == ActionExecutionResult.SUCCESS) 0 else 2
        if (result==ActionExecutionResult.SUCCESS){
            kaoPan.isHasSausage=false
            kaoPan.holdingTime=0
            kaoPan.startTime=0
            kaoPan.status=0
            LogUtils.i(
                "【丢弃流程】丢弃完成：来源=$source，烤盘=${kaoPan.positionSn}，口味=$tasteLabel，详情=$sourceDetail，" +
                    "panHasSausage=${kaoPan.isHasSausage}，panStatus=${kaoPan.status}"
            )
        }
        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        operateScope.launch {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }
        while (AppConfig.getAppConfig().onlineStatus == 4) {
            delay(5000L)
            logD(LOG_ACTION, "等待当前售卖结束后再丢弃下一根烤肠")
        }
        return result==ActionExecutionResult.SUCCESS
    }
    /**
     * 读取烤盘温度
     * area 1，2，3区
     */
    fun readKaoPanTemperature(area: Int):Int{
        when (area) {   
            1 -> {
                return VMModbusHelper.readHoldingRegisters(modbus_address,action_address_1_temperature)
            }
            2 -> {
                return VMModbusHelper.readHoldingRegisters(modbus_address,action_address_2_temperature)
            }
            3 -> {
                return VMModbusHelper.readHoldingRegisters(modbus_address,action_address_3_temperature)
            }
        }
        return 0
    }
    /**
     *  1区500 2区501 3区502 读取状态
     *  503 504 505 写入温度
     * area 加热1，2，3区
     * 加热温度 160
     */
    fun heating(area: Int) {
        logD(LOG_HEAT, "开始加热：temperature=${AppConfig.getAppConfig().heatingTemperature}")
        //加热管开始工作
        when (area) {
            1 -> {
                 writeSingleRegister(action_address_1_temperature_write,AppConfig.getAppConfig().heatingTemperature)
            }
            2 -> {
                 writeSingleRegister(action_address_2_temperature_write,AppConfig.getAppConfig().heatingTemperature)
            }
            3 -> {
                 writeSingleRegister(action_address_3_temperature_write,AppConfig.getAppConfig().heatingTemperature)
            }
        }
    }
    /**
     *
     * area 1，2，3区
     * 保温 60
     */
    fun keepWarm(area: Int) {
        logD(LOG_HEAT, "开始保温：temperature=${AppConfig.getAppConfig().keepWarmTemperature}")
        logD(LOG_HEAT, "当前加热温度配置：temperature=${AppConfig.getAppConfig().heatingTemperature}")
        //开始保温工作
        when (area) {
            1 -> {
                 writeSingleRegister(action_address_1_temperature_write, AppConfig.getAppConfig().keepWarmTemperature)
            }
            2 -> {
                 writeSingleRegister(action_address_2_temperature_write,AppConfig.getAppConfig().keepWarmTemperature)
            }
            3 -> {
                 writeSingleRegister(action_address_3_temperature_write,AppConfig.getAppConfig().keepWarmTemperature)
            }
        }
    }

    /**
     *
     * area 加热1，2，3区
     * 停止加热 0
     */
    fun stopHeating(area: Int) {
        //停止加热
        when (area) {
            1 -> {
                 writeSingleRegister(action_address_1_temperature_write,0)
            }
            2 -> {
                 writeSingleRegister(action_address_2_temperature_write,0)
            }
            3 -> {
                 writeSingleRegister(action_address_3_temperature_write,0)
            }
        }
    }

    /**
     * 按当前烤盘实际状态重建 1/2/3 区温度。
     *
     * 规则：
     * - 该区存在 status=1 的烤肠：恢复加热
     * - 否则该区存在 status=2 或 holdingTime>0 的烤肠：恢复保温
     * - 否则：关闭该区加热
     */
    fun restoreHeatingStateFromCurrentPans(reason: String) {
        val list = KaoPanHelper.getKaoPanList()
        restoreAreaHeatingState(1, list.filter { it.positionSn in 1..9 }, reason)
        restoreAreaHeatingState(2, list.filter { it.positionSn in 10..21 }, reason)
        restoreAreaHeatingState(3, list.filter { it.positionSn in 25..33 }, reason)
    }

    private fun restoreAreaHeatingState(area: Int, pans: List<KaoPan>, reason: String) {
        val hasHeating = pans.any { it.isHasSausage && it.status == 1 }
        val hasKeepWarm = pans.any { it.isHasSausage && (it.status == 2 || it.holdingTime > 0L) }

        when {
            hasHeating -> {
                logD(LOG_HEAT, "按当前烤盘状态恢复${area}区加热：reason=$reason")
                heating(area)
            }
            hasKeepWarm -> {
                logD(LOG_HEAT, "按当前烤盘状态恢复${area}区保温：reason=$reason")
                keepWarm(area)
            }
            else -> {
                logD(LOG_HEAT, "按当前烤盘状态关闭${area}区加热：reason=$reason")
                stopHeating(area)
            }
        }
    }
    /**
     * 暂停营业时间
     * 停止加热和保温
     */
    fun pauseBusiness() {
        //1，2，3 区停止保温
        writeSingleRegister(action_address_1_temperature_write,0)
        writeSingleRegister(action_address_2_temperature_write,0)
        writeSingleRegister(action_address_3_temperature_write,0)

    }

    /**
     * 营业时间开始。
     * 若当前是休息中（2）且无故障，则自动恢复到运营中（1），并恢复算法与加热状态。
     * 若存在故障，则切到维护中（3），避免带病恢复营业。
     */
    fun startBusinessHours(config: AppConfigBean, source: String = "营业时间自动恢复") {
        if (config.errorStatus != 0) {
            val previousOnlineStatus = config.onlineStatus
            config.onlineStatus = 3
            config.isEnable = 0
            config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
            AppConfig.saveAppConfig(config)
            KaoChangAlgorithm.isEnabled = false
            logW(
                LOG_DEVICE,
                "跳过营业开始自动恢复：原因=检测到故障，source=$source，" +
                    "onlineStatus=${onlineStatusDesc(previousOnlineStatus)}->${onlineStatusDesc(config.onlineStatus)}，" +
                    "isEnable=${config.isEnable}(0=关闭)，" +
                    "restStatusSource（休息中来源）=${restStatusSourceDesc(config.restStatusSource)}，" +
                    "errorStatus=${config.errorStatus}(0=正常, 非0=故障)"
            )
            SendServerHelper.publishServiceUpdateStatus()
            return
        }

        if (config.onlineStatus != 2) {
            logD(
                LOG_DEVICE,
                "跳过营业开始自动恢复：原因=当前状态无需自动恢复，source=$source，" +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}，" +
                    "isEnable=${config.isEnable}，" +
                    "restStatusSource（休息中来源）=${restStatusSourceDesc(config.restStatusSource)}"
            )
            return
        }

        if (AppConfig.normalizeRestStatusSource(config.restStatusSource) != AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS) {
            logD(
                LOG_DEVICE,
                "跳过营业开始自动恢复：原因=休息中来源不允许自动恢复，source=$source，" +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}，" +
                    "restStatusSource（休息中来源）=${restStatusSourceDesc(config.restStatusSource)}"
            )
            return
        }

        val previousOnlineStatus = config.onlineStatus
        val previousIsEnable = config.isEnable
        val previousRestStatusSource = config.restStatusSource
        config.onlineStatus = 1
        config.isEnable = 1
        config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
        AppConfig.saveAppConfig(config)
        KaoChangAlgorithm.isEnabled = true
        logI(
            LOG_DEVICE,
            "营业开始自动恢复：source=$source，" +
                "onlineStatus=${onlineStatusDesc(previousOnlineStatus)}->${onlineStatusDesc(config.onlineStatus)}，" +
                "isEnable=$previousIsEnable(0=关闭, 1=开启)->${config.isEnable}(1=开启)，" +
                "restStatusSource（休息中来源）=${restStatusSourceDesc(previousRestStatusSource)}->${restStatusSourceDesc(config.restStatusSource)}"
        )
        restoreHeatingStateFromCurrentPans("营业时间开始自动恢复")
        SendServerHelper.publishServiceUpdateStatus()
    }

    /**
     * 营业时间结束。
     * 只有当设备当前是"运营中（1）"时才切换为"休息中（2）"；
     * 若后台已手动设为维护中/未启用，不覆盖。
     */
    suspend fun endBusinessHours(config: AppConfigBean){
        if (config.errorStatus != 0) {
            config.onlineStatus = 3
            config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
            AppConfig.saveAppConfig(config)
            logW(
                LOG_DEVICE,
                "营业时间结束：检测到设备故障标记，保持维护中 | errorStatus=${config.errorStatus}(0=正常, 非0=故障), " +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}"
            )
            SendServerHelper.publishServiceUpdateStatus()
            return
        }
        if (config.onlineStatus != 1) {
            logD(
                LOG_DEVICE,
                "营业时间结束：当前状态非运营中，跳过自动切换休息中（尊重后台设置） | " +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}"
            )
            return
        }
        // 当前是运营中，自动切换为休息中
        config.apply {
            onlineStatus = 2
            restStatusSource = AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
        }
        AppConfig.saveAppConfig(config)
        logD(
            LOG_DEVICE,
            "营业时间结束：已自动切换为休息中（2），" +
                "restStatusSource（休息中来源）=${restStatusSourceDesc(config.restStatusSource)}"
        )
        //同步更新云端状态
        SendServerHelper.publishServiceUpdateStatus()
        //执行丢弃操作
        try {
            KaoChangAlgorithm.resetKaoPan()
            if (!SelfCleanFeatureToggle.isEnabled()) {
                config.zone3Dirty = 0
                config.zone3CleanPending = 0
                AppConfig.saveAppConfig(config)
                logI(
                    LOG_SELF_CLEAN,
                    "营业结束：已跳过全量自清洁，reason=${SelfCleanFeatureToggle.disabledReasonText()}"
                )
                return
            }
            val selfCleanResult = runPanSelfCleanSequence(
                positionSnList = (1..33).toList(),
                includeSellPlatform = true,
                source = "营业结束全量自清洁"
            )
            if (!selfCleanResult.success) {
                val failedReport = selfCleanResult.reports.lastOrNull()
                logE(
                    LOG_SELF_CLEAN,
                    "营业结束全量自清洁失败：target=${failedReport?.targetLabel ?: "unknown"}，" +
                        "command=${failedReport?.command ?: -1}，message=${failedReport?.message ?: "unknown"}"
                )
                return
            }
            config.zone3Dirty = 0
            config.zone3CleanPending = 0
            AppConfig.saveAppConfig(config)
            logI(
                LOG_SELF_CLEAN,
                "营业结束全量自清洁完成：sequence=501..533,534，onlineStatus（设备营业状态）=${onlineStatusDesc(config.onlineStatus)}"
            )
        } catch (e: Exception) {
            // 营业结束时若重置烤盘失败，不应抛未实现异常导致崩溃；
            // 这里降级为维护中并上报，等待人工排查。
            config.errorStatus = 2
            config.onlineStatus = 3
            config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
            AppConfig.saveAppConfig(config)
            logE(
                LOG_DEVICE,
                "营业时间结束：重置烤盘失败，已降级为维护中并上报 | " +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}, " +
                    "errorStatus=${config.errorStatus}(2=设备故障), " +
                    "exception=${e.javaClass.simpleName}, message=${e.message}",
                e
            )
            SendServerHelper.publishServiceUpdateStatus()
        }
    }

    /**
     * 读取寄存器的值
     */
    fun readHoldingRegisters(address:Int,quantity:Int):IntArray?{
        return VMModbusHelper.readHoldingRegisters(modbus_address,address,quantity)
    }



    /**
     * 写入寄存器的值
     */
    fun writeSingleRegister(
        address: Int,
        value:Int,
        source: String = "KaoChangOperate.writeSingleRegister（立即写寄存器入口）",
        action: String? = null
    ): VMModbusHelper.ModbusOperationResult{
        logD(LOG_DEVICE, "写寄存器前错误状态检查：errorStatus=${AppConfig.getAppConfig().errorStatus}")
        return VMModbusHelper.writeSingleRegister(
            modbus_address,
            address,
            value,
            traceContext = VMModbusHelper.ModbusWriteTraceContext(
                source = source,
                action = action
            )
        )

    }

    /**
     * 由于下位机处理频率过低需要延迟150毫秒，读取都需要加延迟150发送，每个读取方法已经加了delay
     */
    suspend fun writeSingleRegister2(address: Int,value:Int): VMModbusHelper.ModbusOperationResult{
        delay(150)
        logD(LOG_DEVICE, "延迟写寄存器前错误状态检查：errorStatus=${AppConfig.getAppConfig().errorStatus}")
        return VMModbusHelper.writeSingleRegister(
            modbus_address,
            address,
            value,
            traceContext = VMModbusHelper.ModbusWriteTraceContext(
                source = "KaoChangOperate.writeSingleRegister2（延迟写寄存器入口）"
            )
        )

    }

    fun actionDebug() {
        writeSingleRegister(action_debug,5555)

    }

}
