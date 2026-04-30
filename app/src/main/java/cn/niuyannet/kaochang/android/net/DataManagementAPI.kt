package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.mqtt.MqttProtocol
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveKaoPanBoxList
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveTasteList
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object DataManagementAPI {
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val LOG_SYNC = "云端同步"
    @Volatile
    private var lastAppliedRuntimeControlSeq: Long = -1L
    @Volatile
    private var lastAppliedConcurrencyControlSeq: Long = -1L

    data class RemoteDeviceStateRefreshResult(
        val success: Boolean,
        val changed: Boolean,
        val onlineStatus: Int,
        val supplyStatus: Int,
        val scanBlockStatus: Int,
        val inspectionMode: Int,
        val isEnable: Int,
        val restStatusSource: String
    )

    data class RuntimeControlApplyResult(
        val controlSeq: Long,
        val applied: Boolean,
        val changed: Boolean,
        val reason: String,
        val onlineStatus: Int,
        val supplyStatus: Int,
        val scanBlockStatus: Int,
        val inspectionMode: Int,
        val isEnable: Int,
        val restStatusSource: String
    )

    data class ConcurrencyControlApplyResult(
        val controlSeq: Long,
        val applied: Boolean,
        val changed: Boolean,
        val reason: String,
        val modeType: Int,
        val transitionMode: Int,
        val overrodeConflictingTransition: Boolean
    )

    private fun logD(message: String) = LogUtils.d("[$LOG_SYNC] $message")
    private fun logW(message: String) = LogUtils.w("[$LOG_SYNC] $message")
    private fun logE(message: String) = LogUtils.e("[$LOG_SYNC] $message")

    private fun requireStableDeviceCode(operation: String): String? {
        val deviceCode = AppConfig.getDeviceId()
        if (deviceCode.isBlank()) {
            logW(
                "【设备身份】deviceCode（云端设备编号）未锁定，跳过$operation，" +
                    "避免使用 systemAndroidId（系统 ANDROID_ID）误同步或误创建设备"
            )
            AppConfig.logDeviceIdentity("$operation 前", force = true)
            return null
        }
        return deviceCode
    }

    private fun reconcileDiscardCloseTime(
        logPrefix: String,
        oldDiscardTime: Int,
        mergedConfig: AppConfigBean
    ) {
        val correctedCount = KaoPanHelper.refreshDiscardCloseTime(mergedConfig.discardTime)
        if (correctedCount <= 0) {
            return
        }

        val closeTimeMillis = mergedConfig.discardTime.coerceAtLeast(0) * 60L * 60L * 1000L
        if (oldDiscardTime != mergedConfig.discardTime) {
            LogUtils.d(
                "[$logPrefix] 远端配置已更新丢弃时间，并已回刷烤盘保温超时阈值：" +
                    "discardTime=${oldDiscardTime}小时->${mergedConfig.discardTime}小时, " +
                    "已修正烤盘数=$correctedCount, 新closeTime=${closeTimeMillis}ms"
            )
        } else {
            LogUtils.w(
                "[$logPrefix] 启动/同步时发现烤盘保温超时阈值与当前配置不一致，已自动回刷：" +
                    "discardTime=${mergedConfig.discardTime}小时, 已修正烤盘数=$correctedCount, " +
                    "目标closeTime=${closeTimeMillis}ms"
            )
        }
    }

    private fun formatDiffValue(label: String, value: Any?): String {
        if (value == null) {
            return "null"
        }
        return when (label) {
            "status" -> when ((value as? Number)?.toInt()) {
                0 -> "0(未启用/离线)"
                1 -> "1(启用/在线)"
                else -> value.toString()
            }

            "onlineStatus" -> when ((value as? Number)?.toInt()) {
                0 -> "0(未启用)"
                1 -> "1(运营中)"
                2 -> "2(休息中)"
                3 -> "3(维护中)"
                4 -> "4(工作中/正在出餐)"
                else -> value.toString()
            }

            "supplyStatus" -> when ((value as? Number)?.toInt()) {
                AppConfigBean.SUPPLY_STATUS_NORMAL -> "0(正常)"
                AppConfigBean.SUPPLY_STATUS_SOLD_OUT -> "1(售罄/待补货)"
                else -> value.toString()
            }

            "scanBlockStatus" -> when ((value as? Number)?.toInt()) {
                AppConfigBean.SCAN_BLOCK_STATUS_NORMAL -> "0(正常展示)"
                AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED -> "1(隐藏二维码并禁止下单/支付)"
                else -> value.toString()
            }

            "inspectionMode" -> when ((value as? Number)?.toInt()) {
                AppConfigBean.INSPECTION_MODE_NORMAL -> "0(正常)"
                AppConfigBean.INSPECTION_MODE_ACTIVE -> "1(检修中)"
                else -> value.toString()
            }

            "modeType" -> when ((value as? Number)?.toInt()) {
                1 -> "1(低并发)"
                2 -> "2(高并发)"
                else -> value.toString()
            }

            "transitionMode" -> when ((value as? Number)?.toInt()) {
                0 -> "0(无过渡态)"
                1 -> "1(低转高过渡)"
                2 -> "2(高转低过渡)"
                else -> value.toString()
            }

            "isEnable" -> when ((value as? Number)?.toInt()) {
                0 -> "0(已关闭)"
                1 -> "1(已开启)"
                else -> value.toString()
            }

            "keepWarmTemperature", "heatingTemperature" -> "${value}℃"
            "bakingTime" -> "${value}分钟"
            "discardTime" -> "${value}小时"
            "timeThresholdLow", "timeBeforeClose" -> "${value}分钟"
            "boxToPlatformTimeoutSeconds",
            "trayToSellPlatformTimeoutSeconds",
            "postSellSupplementGuardSeconds",
            "postSellIdleStableSeconds",
            "postSellIdleMaxWaitSeconds",
            "cameraPrewarmHoldSeconds" -> "${value}秒"

            else -> value.toString()
        }
    }

    private fun appendDiff(
        changes: MutableList<String>,
        label: String,
        oldValue: Any?,
        newValue: Any?
    ) {
        if (oldValue != newValue) {
            changes.add("$label: ${formatDiffValue(label, oldValue)}->${formatDiffValue(label, newValue)}")
        }
    }

    private fun onlineStatusText(value: Int): String = when (value) {
        0 -> "0（未启用）"
        1 -> "1（运营中）"
        2 -> "2（休息中）"
        3 -> "3（维护中）"
        4 -> "4（工作中/正在出餐）"
        else -> "$value（未知）"
    }

    private fun scanBlockStatusText(value: Int): String = when (value) {
        AppConfigBean.SCAN_BLOCK_STATUS_NORMAL -> "0（正常展示）"
        AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED -> "1（隐藏二维码并禁止下单/支付）"
        else -> "$value（未知）"
    }

    private fun inspectionModeText(value: Int): String = when (value) {
        AppConfigBean.INSPECTION_MODE_NORMAL -> "0（正常）"
        AppConfigBean.INSPECTION_MODE_ACTIVE -> "1（检修中）"
        else -> "$value（未知）"
    }

    private fun canEnableAlgorithm(config: AppConfigBean): Boolean {
        return config.onlineStatus in listOf(1, 4) &&
            config.inspectionMode == AppConfigBean.INSPECTION_MODE_NORMAL &&
            config.errorStatus == AppConfigBean.ERROR_STATUS_NORMAL
    }

    private fun hasHighConcurrencyAreaActive(): Boolean {
        return try {
            KaoPanHelper.getKaoPanList().any { pan ->
                val inHighConcurrencyArea = pan.positionSn in 10..21 || pan.positionSn in 25..33
                inHighConcurrencyArea &&
                    (pan.isHasSausage ||
                        pan.isHasGrilling ||
                        pan.startTime > 0L ||
                        pan.holdingTime > 0L ||
                        pan.status != 0)
            }
        } catch (e: Exception) {
            logW("判断高并发区域烤盘状态失败，保守保持高转低收口：error=${e.message}")
            true
        }
    }

    private fun restStatusSourceText(value: String?): String = AppConfig.restStatusSourceText(value)

    private fun appendBusinessTimeDiff(
        changes: MutableList<String>,
        oldValue: BusinessTime?,
        newValue: BusinessTime?
    ) {
        val oldJson = JSON.toJSONString(oldValue)
        val newJson = JSON.toJSONString(newValue)
        if (oldJson != newJson) {
            changes.add("businessTime: $oldJson->$newJson")
        }
    }

    /**
     * 处理远端配置同步后的营业状态跃迁副作用。
     *
     * 约束：
     * - 进入 0/2/3（未启用/休息中/维护中）：自动关闭算法，但当前硬件流程允许自然收尾
     * - 离开维护中：若算法仍处于开启态且当前允许开启，则按烤盘现状恢复加热/保温
     */
    fun handleRemoteOnlineStatusTransition(
        previousOnlineStatus: Int,
        previousIsEnable: Int,
        previousRestStatusSource: String,
        mergedConfig: AppConfigBean,
        source: String,
        syncAlgorithmChangeToServer: Boolean = true
    ) {
        if (mergedConfig.onlineStatus == 2) {
            val normalizedPreviousRestSource = AppConfig.normalizeRestStatusSource(previousRestStatusSource)
            val shouldMarkManualRemote =
                previousOnlineStatus != 2 ||
                    normalizedPreviousRestSource !in setOf(
                        AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS,
                        AppConfigBean.REST_SOURCE_MANUAL_REMOTE
                    )
            if (shouldMarkManualRemote) {
                mergedConfig.restStatusSource = AppConfigBean.REST_SOURCE_MANUAL_REMOTE
                AppConfig.saveAppConfig(mergedConfig)
                logD(
                    "远端状态同步：检测到后台手动设置 onlineStatus（设备营业状态）=2（休息中），" +
                        "已标记 restStatusSource（休息中来源）=${restStatusSourceText(mergedConfig.restStatusSource)}，" +
                        "source=$source，previousOnlineStatus=${formatDiffValue("onlineStatus", previousOnlineStatus)}"
                )
            }
        }

        if (previousOnlineStatus in listOf(1, 4) && mergedConfig.onlineStatus in listOf(0, 2, 3)) {
            logD(
                "远端配置切出可开算法营业状态，自动关闭算法：" +
                    "source=$source, " +
                    "previousOnlineStatus=${formatDiffValue("onlineStatus", previousOnlineStatus)}, " +
                    "newOnlineStatus=${formatDiffValue("onlineStatus", mergedConfig.onlineStatus)}, " +
                    "previousIsEnable=${formatDiffValue("isEnable", previousIsEnable)}, " +
                    "newIsEnable=${formatDiffValue("isEnable", 0)}"
            )
            KaoChangAlgorithm.disableRoastAlgorithm(
                reason = "远端配置切出运营/工作中(source=$source)",
                syncToServer = syncAlgorithmChangeToServer
            )
            return
        }

        if (previousOnlineStatus == 3 && mergedConfig.onlineStatus in listOf(1, 4)) {
            val currentIsEnable = AppConfig.getAlgorithmEnableFlag()
            if (!VMModbusHelper.connectStatus()) {
                logD(
                    "远端配置已离开维护中，但下位机未连接，保持算法关闭并跳过恢复加热：" +
                        "source=$source, " +
                        "newOnlineStatus=${formatDiffValue("onlineStatus", mergedConfig.onlineStatus)}, " +
                        "currentIsEnable=${formatDiffValue("isEnable", currentIsEnable)}"
                )
                return
            }
            if (!KaoChangAlgorithm.isEnabled || currentIsEnable != 1 || !canEnableAlgorithm(mergedConfig)) {
                logD(
                    "远端配置已离开维护中，但当前条件不满足自动恢复加热：" +
                        "source=$source, " +
                        "newOnlineStatus=${formatDiffValue("onlineStatus", mergedConfig.onlineStatus)}, " +
                        "inspectionMode（检修模式）=${inspectionModeText(mergedConfig.inspectionMode)}，" +
                        "errorStatus=${mergedConfig.errorStatus}，" +
                        "currentIsEnable=${formatDiffValue("isEnable", currentIsEnable)}"
                )
                return
            }
            helperScope.launch {
                KaoChangOperate.restoreHeatingStateFromCurrentPans("远端配置切回非维护状态且算法已开启")
            }
        }
    }

    fun applyRemoteRuntimeControl(
        content: String,
        source: String = MqttProtocol.SOURCE_MQTT_ACTION_2
    ): RuntimeControlApplyResult {
        val json = try {
            JSON.parseObject(content)
        } catch (e: Exception) {
            logE("远端运行时控制解析失败：source=$source, error=${e.message}, raw=$content")
            val config = AppConfig.getAppConfig()
            return RuntimeControlApplyResult(
                controlSeq = -1L,
                applied = false,
                changed = false,
                reason = "parse_failed",
                onlineStatus = config.onlineStatus,
                supplyStatus = config.supplyStatus,
                scanBlockStatus = config.scanBlockStatus,
                inspectionMode = config.inspectionMode,
                isEnable = config.isEnable,
                restStatusSource = config.restStatusSource
            )
        }

        val controlSeq = json.getLongValue("controlSeq").takeIf { it > 0 } ?: json.getLongValue("ts")
        val hasOnlineStatus = json.containsKey("onlineStatus")
        val hasIsEnable = json.containsKey("isEnable")
        val hasScanBlockStatus = json.containsKey("scanBlockStatus")
        if (!hasOnlineStatus && !hasIsEnable && !hasScanBlockStatus) {
            val config = AppConfig.getAppConfig()
            logD(
                "远端运行时控制已忽略：source=$source，controlSeq=$controlSeq，" +
                    "不包含 onlineStatus（设备营业状态）、scanBlockStatus（前台屏蔽状态）或 isEnable（烤肠算法开关）"
            )
            return RuntimeControlApplyResult(
                controlSeq = controlSeq,
                applied = false,
                changed = false,
                reason = "missing_runtime_fields",
                onlineStatus = config.onlineStatus,
                supplyStatus = config.supplyStatus,
                scanBlockStatus = config.scanBlockStatus,
                inspectionMode = config.inspectionMode,
                isEnable = config.isEnable,
                restStatusSource = config.restStatusSource
            )
        }

        val currentConfigBeforeApply = AppConfig.getAppConfig()
        if (controlSeq > 0 && lastAppliedRuntimeControlSeq >= 0 && controlSeq <= lastAppliedRuntimeControlSeq) {
            logW(
                "远端运行时控制已忽略：source=$source，controlSeq=$controlSeq，" +
                    "lastAppliedRuntimeControlSeq=$lastAppliedRuntimeControlSeq，reason=stale_control_seq，" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(currentConfigBeforeApply.onlineStatus)}，" +
                    "isEnable（烤肠算法开关）=${formatDiffValue("isEnable", currentConfigBeforeApply.isEnable)}"
            )
            return RuntimeControlApplyResult(
                controlSeq = controlSeq,
                applied = false,
                changed = false,
                reason = "stale_control_seq",
                onlineStatus = currentConfigBeforeApply.onlineStatus,
                supplyStatus = currentConfigBeforeApply.supplyStatus,
                scanBlockStatus = currentConfigBeforeApply.scanBlockStatus,
                inspectionMode = currentConfigBeforeApply.inspectionMode,
                isEnable = currentConfigBeforeApply.isEnable,
                restStatusSource = currentConfigBeforeApply.restStatusSource
            )
        }

        val config = currentConfigBeforeApply
        val previousOnlineStatus = config.onlineStatus
        val previousSupplyStatus = config.supplyStatus
        val previousScanBlockStatus = config.scanBlockStatus
        val previousInspectionMode = config.inspectionMode
        val previousIsEnable = config.isEnable
        val previousRestStatusSource = config.restStatusSource
        val operator = json.getString("operator").orEmpty()

        if (hasOnlineStatus) {
            config.onlineStatus = json.getIntValue("onlineStatus")
        }
        if (hasScanBlockStatus) {
            config.scanBlockStatus = if (json.getIntValue("scanBlockStatus") == AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED) {
                AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED
            } else {
                AppConfigBean.SCAN_BLOCK_STATUS_NORMAL
            }
        }
        AppConfig.alignRestStatusSource(config)
        AppConfig.saveAppConfig(config)

        if (hasOnlineStatus) {
            handleRemoteOnlineStatusTransition(
                previousOnlineStatus = previousOnlineStatus,
                previousIsEnable = previousIsEnable,
                previousRestStatusSource = previousRestStatusSource,
                mergedConfig = config,
                source = source,
                syncAlgorithmChangeToServer = false
            )
        }

        if (config.onlineStatus == 3) {
            KaoChangAlgorithm.disableRoastAlgorithm(
                reason = "远端MQTT运行时控制要求维护中(source=$source, operator=${operator.ifBlank { "unknown" }})",
                syncToServer = false
            )
        } else if (hasIsEnable) {
            when (json.getIntValue("isEnable")) {
                1 -> KaoChangAlgorithm.enableRoastAlgorithm(
                    reason = "远端MQTT运行时控制开启算法(source=$source, operator=${operator.ifBlank { "unknown" }})",
                    syncToServer = false
                )
                else -> KaoChangAlgorithm.disableRoastAlgorithm(
                    reason = "远端MQTT运行时控制关闭算法(source=$source, operator=${operator.ifBlank { "unknown" }})",
                    syncToServer = false
                )
            }
        }

        val currentConfig = AppConfig.getAppConfig()
        val changed =
            previousOnlineStatus != currentConfig.onlineStatus ||
                previousSupplyStatus != currentConfig.supplyStatus ||
                previousScanBlockStatus != currentConfig.scanBlockStatus ||
                previousInspectionMode != currentConfig.inspectionMode ||
                previousIsEnable != currentConfig.isEnable

        if (controlSeq > 0) {
            lastAppliedRuntimeControlSeq = maxOf(lastAppliedRuntimeControlSeq, controlSeq)
        }

        logD(
            "远端运行时控制已应用：" +
                "source=$source，controlSeq=$controlSeq，operator=${operator.ifBlank { "unknown" }}，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(previousOnlineStatus)}->${onlineStatusText(currentConfig.onlineStatus)}，" +
                "supplyStatus（补货状态）=${formatDiffValue("supplyStatus", previousSupplyStatus)}->${formatDiffValue("supplyStatus", currentConfig.supplyStatus)}，" +
                "scanBlockStatus（前台屏蔽状态）=${scanBlockStatusText(previousScanBlockStatus)}->${scanBlockStatusText(currentConfig.scanBlockStatus)}，" +
                "inspectionMode（检修模式）=${inspectionModeText(previousInspectionMode)}->${inspectionModeText(currentConfig.inspectionMode)}，" +
                "isEnable（烤肠算法开关）=${formatDiffValue("isEnable", previousIsEnable)}->${formatDiffValue("isEnable", currentConfig.isEnable)}，" +
                "restStatusSource（休息中来源）=${restStatusSourceText(previousRestStatusSource)}->${restStatusSourceText(currentConfig.restStatusSource)}"
        )
        return RuntimeControlApplyResult(
            controlSeq = controlSeq,
            applied = true,
            changed = changed,
            reason = "applied",
            onlineStatus = currentConfig.onlineStatus,
            supplyStatus = currentConfig.supplyStatus,
            scanBlockStatus = currentConfig.scanBlockStatus,
            inspectionMode = currentConfig.inspectionMode,
            isEnable = currentConfig.isEnable,
            restStatusSource = currentConfig.restStatusSource
        )
    }

    fun applyRemoteConcurrencyControl(
        content: String,
        source: String = MqttProtocol.SOURCE_MQTT_ACTION_2
    ): ConcurrencyControlApplyResult {
        val json = try {
            JSON.parseObject(content)
        } catch (e: Exception) {
            logE("远端并发控制解析失败：source=$source, error=${e.message}, raw=$content")
            val config = AppConfig.getAppConfig()
            return ConcurrencyControlApplyResult(
                controlSeq = -1L,
                applied = false,
                changed = false,
                reason = "parse_failed",
                modeType = config.modeType,
                transitionMode = config.transitionMode,
                overrodeConflictingTransition = false
            )
        }

        val controlSeq = json.getLongValue("controlSeq").takeIf { it > 0 } ?: json.getLongValue("ts")
        val hasModeType = json.containsKey("modeType")
        val hasTransitionMode = json.containsKey("transitionMode")
        if (!hasModeType && !hasTransitionMode) {
            val config = AppConfig.getAppConfig()
            logD(
                "远端并发控制已忽略：source=$source，controlSeq=$controlSeq，" +
                    "不包含 modeType（并发模式）或 transitionMode（并发切换过渡态）"
            )
            return ConcurrencyControlApplyResult(
                controlSeq = controlSeq,
                applied = false,
                changed = false,
                reason = "missing_concurrency_fields",
                modeType = config.modeType,
                transitionMode = config.transitionMode,
                overrodeConflictingTransition = false
            )
        }

        val currentConfigBeforeApply = AppConfig.getAppConfig()
        if (controlSeq > 0 && lastAppliedConcurrencyControlSeq >= 0 && controlSeq <= lastAppliedConcurrencyControlSeq) {
            logW(
                "远端并发控制已忽略：source=$source，controlSeq=$controlSeq，" +
                    "lastAppliedConcurrencyControlSeq=$lastAppliedConcurrencyControlSeq，reason=stale_control_seq，" +
                    "modeType（并发模式）=${formatDiffValue("modeType", currentConfigBeforeApply.modeType)}，" +
                    "transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", currentConfigBeforeApply.transitionMode)}"
            )
            return ConcurrencyControlApplyResult(
                controlSeq = controlSeq,
                applied = false,
                changed = false,
                reason = "stale_control_seq",
                modeType = currentConfigBeforeApply.modeType,
                transitionMode = currentConfigBeforeApply.transitionMode,
                overrodeConflictingTransition = false
            )
        }

        val previousModeType = currentConfigBeforeApply.modeType
        val previousTransitionMode = currentConfigBeforeApply.transitionMode
        val requestedModeType = json.getInteger("modeType") ?: previousModeType
        val requestedTransitionMode = json.getInteger("transitionMode")
        val operator = json.getString("operator").orEmpty()
        val highConcurrencyAreaActive = hasHighConcurrencyAreaActive()
        val resolvedTarget = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = previousModeType,
            currentTransitionMode = previousTransitionMode,
            requestedModeType = requestedModeType,
            requestedTransitionMode = requestedTransitionMode,
            highConcurrencyAreaActive = highConcurrencyAreaActive
        )

        currentConfigBeforeApply.modeType = resolvedTarget.modeType
        currentConfigBeforeApply.transitionMode = resolvedTarget.transitionMode
        AppConfig.saveAppConfig(currentConfigBeforeApply)

        val currentConfig = AppConfig.getAppConfig()
        val changed =
            previousModeType != currentConfig.modeType ||
                previousTransitionMode != currentConfig.transitionMode

        if (controlSeq > 0) {
            lastAppliedConcurrencyControlSeq = maxOf(lastAppliedConcurrencyControlSeq, controlSeq)
        }

        if (resolvedTarget.overrodeConflictingTransition) {
            logW(
                "远端并发控制覆盖了本地高转低过渡：source=$source，controlSeq=$controlSeq，operator=${operator.ifBlank { "unknown" }}，" +
                    "后台目标 modeType（并发模式）=${formatDiffValue("modeType", resolvedTarget.modeType)}，" +
                    "transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", resolvedTarget.transitionMode)}，" +
                    "策略=以后台最新目标为准"
            )
        }
        if (requestedModeType != resolvedTarget.modeType || (requestedTransitionMode ?: previousTransitionMode) != resolvedTarget.transitionMode) {
            logW(
                "远端并发控制已归一化并发运行态：source=$source，controlSeq=$controlSeq，operator=${operator.ifBlank { "unknown" }}，" +
                    "请求 modeType（并发模式）=${formatDiffValue("modeType", requestedModeType)}，" +
                    "请求 transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", requestedTransitionMode ?: previousTransitionMode)}，" +
                    "高并发区域是否仍有烤肠或动作=$highConcurrencyAreaActive，" +
                    "落地 modeType（并发模式）=${formatDiffValue("modeType", resolvedTarget.modeType)}，" +
                    "落地 transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", resolvedTarget.transitionMode)}"
            )
        }

        logD(
            "远端并发控制已应用：" +
                "source=$source，controlSeq=$controlSeq，operator=${operator.ifBlank { "unknown" }}，" +
                "modeType（并发模式）=${formatDiffValue("modeType", previousModeType)}->${formatDiffValue("modeType", currentConfig.modeType)}，" +
                "transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", previousTransitionMode)}->${formatDiffValue("transitionMode", currentConfig.transitionMode)}"
        )
        return ConcurrencyControlApplyResult(
            controlSeq = controlSeq,
            applied = true,
            changed = changed,
            reason = "applied",
            modeType = currentConfig.modeType,
            transitionMode = currentConfig.transitionMode,
            overrodeConflictingTransition = resolvedTarget.overrodeConflictingTransition
        )
    }

    fun dataSyncServer() {
        requireStableDeviceCode("启动云端同步") ?: return
        refreshStockData()
        getDeviceInfo()
    }

    fun refreshStockData(
        logPrefix: String = LOG_SYNC,
        onResult: ((success: Boolean, stockCount: Int) -> Unit)? = null
    ) {
        requireStableDeviceCode("同步烤肠箱库存") ?: run {
            onResult?.invoke(false, 0)
            return
        }
        NetApi.getStockData { code, content ->
            val kaoPanBoxList = mutableListOf<KaoPanBox>()
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json?.getIntValue("code") == 200) {
                    val jsonData = json.getJSONArray("data")
                    for (i in 0 until jsonData.size) {
                        val item = jsonData.getJSONObject(i)
                        val kaoPanBox = KaoPanBox().apply {
                            id = item.getIntValue("id")
                            deviceId = item.getString("deviceId")
                            productId = item.getIntValue("productId").toString()
                            positionSn = item.getIntValue("positionSn")
                            lastPositionSn = item.getIntValue("lastPositionSn")
                            num = maxOf(0, item.getIntValue("num"))
                            tasteCode = item.getString("tasteCode")
                            tasteName = item.getString("tasteName")
                            cmdValueTake = item.getIntValue("cmdValueTake")
                            cmdStatusTake = item.getIntValue("cmdStatusTake")
                        }
                        kaoPanBoxList.add(kaoPanBox)
                    }
                    saveKaoPanBoxList(kaoPanBoxList)
                    logD("[$logPrefix] 烤肠箱库存同步完成：箱位数=${kaoPanBoxList.size}")
                    onResult?.invoke(true, kaoPanBoxList.size)
                    return@getStockData
                }
                logE("[$logPrefix] 烤肠箱库存业务返回异常：content=$content")
            } else {
                ToastUtils.showShort("网络错误")
                logE("[$logPrefix] 同步烤肠箱库存失败：code=$code, content=$content")
            }
            onResult?.invoke(false, 0)
        }
    }

    fun uploadDataToServer() {
        requireStableDeviceCode("上传烤盘和库存状态") ?: return
        val metadataNormalized = KaoPanHelper.normalizeKaoPanTasteMetadata()
        if (metadataNormalized) {
            logD("上传前已统一烤盘口味元数据")
        }
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val kaoPanCount = kaoPanList.size
        val warmingCount = kaoPanList.count { it.isHasSausage && it.status == 2 }
        val bakingCount = kaoPanList.count { it.isHasSausage && it.status == 1 }
        NetApi.updateKaoPanList(JSON.toJSONString(kaoPanList)) { code, content ->
            if (code != 0) {
                logE("烤盘同步失败：code=$code, content=$content")
            } else {
                logD("烤盘状态已同步：烤盘总数=$kaoPanCount, 烤制中=$bakingCount, 保温中=$warmingCount")
            }
        }

        val kaoPanBoxList = KaoPanHelper.getKaoPanBoxList()
        val stockPayload = JSON.toJSONString(kaoPanBoxList)
        val stockItemCount = kaoPanBoxList.size
        val stockTotalCount = kaoPanBoxList.sumOf { box -> box.num }
        NetApi.updateStock(stockPayload) { code, content ->
            if (code != 0) {
                logE(
                    "库存同步失败：明细数=$stockItemCount, 总库存=$stockTotalCount, code=$code, content=$content"
                )
            } else {
                logD("库存已同步：箱位数=$stockItemCount, 总库存=$stockTotalCount")
            }
        }
    }

    fun getDeviceInfo() {
        val deviceCode = requireStableDeviceCode("拉取设备配置") ?: return
        NetApi.getKcProductList { code, content ->
            val tasteList = mutableListOf<Taste>()
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json?.getIntValue("code") == 200) {
                    val jsonData = json.getJSONArray("data")
                    for (i in 0 until jsonData.size) {
                        val item = jsonData.getJSONObject(i)
                        val taste = Taste(
                            item.getIntValue("tasteId"),
                            item.getString("tasteCode"),
                            item.getString("tasteName"),
                            item.getIntValue("id"),
                            item.getString("name")
                        )
                        tasteList.add(taste)
                    }
                    saveTasteList(tasteList)
                }
            } else {
                ToastUtils.showShort("网络错误")
                logE("同步口味列表失败：code=$code, content=$content")
            }
        }

        NetApi.getDeviceInfo(deviceCode) deviceInfoCallback@{ code, content ->
            if (code != 0) {
                logE("设备配置获取失败：code=$code, content=$content")
                return@deviceInfoCallback
            }

            val json = try {
                JSON.parseObject(content)
            } catch (e: Exception) {
                logE("设备配置响应解析失败：raw=$content")
                e.printStackTrace()
                return@deviceInfoCallback
            }

            if (json == null) {
                logE("设备配置响应为空：raw=$content")
                return@deviceInfoCallback
            }

            if (json.getIntValue("code") != 200) {
                logE("设备配置业务返回失败：code=${json.getIntValue("code")}, content=$content")
                return@deviceInfoCallback
            }

            val appConfigBean = AppConfig.getAppConfig()
            val previousOnlineStatus = appConfigBean.onlineStatus
            val previousIsEnable = AppConfig.getAlgorithmEnableFlag()
            val previousRestStatusSource = AppConfig.normalizeRestStatusSource(appConfigBean.restStatusSource)

            val oldBusinessTime = appConfigBean.businessTime
            val oldStatus = appConfigBean.status
            val oldOnlineStatus = appConfigBean.onlineStatus
            val oldSupplyStatus = appConfigBean.supplyStatus
            val oldScanBlockStatus = appConfigBean.scanBlockStatus
            val oldInspectionMode = appConfigBean.inspectionMode
            val oldModeType = appConfigBean.modeType
            val oldTransitionMode = appConfigBean.transitionMode
            val oldKeepWarmTemperature = appConfigBean.keepWarmTemperature
            val oldHeatingTemperature = appConfigBean.heatingTemperature
            val oldBakingTime = appConfigBean.bakingTime
            val oldDiscardTime = appConfigBean.discardTime
            val oldTimeThresholdLow = appConfigBean.timeThresholdLow
            val oldTimeBeforeClose = appConfigBean.timeBeforeClose
            val oldBoxToPlatformTimeoutSeconds = appConfigBean.boxToPlatformTimeoutSeconds
            val oldTrayToSellPlatformTimeoutSeconds = appConfigBean.trayToSellPlatformTimeoutSeconds
            val oldPostSellSupplementGuardSeconds = appConfigBean.postSellSupplementGuardSeconds
            val oldPostSellIdleStableSeconds = appConfigBean.postSellIdleStableSeconds
            val oldPostSellIdleMaxWaitSeconds = appConfigBean.postSellIdleMaxWaitSeconds
            val oldCameraPrewarmHoldSeconds = appConfigBean.cameraPrewarmHoldSeconds

            val mergeResult = AppConfig.mergeRemoteConfig(json.getJSONObject("data"), content, LOG_SYNC)
            if (!mergeResult.applied) {
                return@deviceInfoCallback
            }

            val mergedConfig = AppConfig.getAppConfig()
            val changes = mutableListOf<String>()
            appendBusinessTimeDiff(changes, oldBusinessTime, mergedConfig.businessTime)
            appendDiff(changes, "status", oldStatus, mergedConfig.status)
            appendDiff(changes, "onlineStatus", oldOnlineStatus, mergedConfig.onlineStatus)
            appendDiff(changes, "supplyStatus", oldSupplyStatus, mergedConfig.supplyStatus)
            appendDiff(changes, "scanBlockStatus", oldScanBlockStatus, mergedConfig.scanBlockStatus)
            appendDiff(changes, "inspectionMode", oldInspectionMode, mergedConfig.inspectionMode)
            appendDiff(changes, "modeType", oldModeType, mergedConfig.modeType)
            appendDiff(changes, "transitionMode", oldTransitionMode, mergedConfig.transitionMode)
            appendDiff(changes, "keepWarmTemperature", oldKeepWarmTemperature, mergedConfig.keepWarmTemperature)
            appendDiff(changes, "heatingTemperature", oldHeatingTemperature, mergedConfig.heatingTemperature)
            appendDiff(changes, "bakingTime", oldBakingTime, mergedConfig.bakingTime)
            appendDiff(changes, "discardTime", oldDiscardTime, mergedConfig.discardTime)
            appendDiff(changes, "timeThresholdLow", oldTimeThresholdLow, mergedConfig.timeThresholdLow)
            appendDiff(changes, "timeBeforeClose", oldTimeBeforeClose, mergedConfig.timeBeforeClose)
            appendDiff(
                changes,
                "boxToPlatformTimeoutSeconds",
                oldBoxToPlatformTimeoutSeconds,
                mergedConfig.boxToPlatformTimeoutSeconds
            )
            appendDiff(
                changes,
                "trayToSellPlatformTimeoutSeconds",
                oldTrayToSellPlatformTimeoutSeconds,
                mergedConfig.trayToSellPlatformTimeoutSeconds
            )
            appendDiff(
                changes,
                "postSellSupplementGuardSeconds",
                oldPostSellSupplementGuardSeconds,
                mergedConfig.postSellSupplementGuardSeconds
            )
            appendDiff(
                changes,
                "postSellIdleStableSeconds",
                oldPostSellIdleStableSeconds,
                mergedConfig.postSellIdleStableSeconds
            )
            appendDiff(
                changes,
                "postSellIdleMaxWaitSeconds",
                oldPostSellIdleMaxWaitSeconds,
                mergedConfig.postSellIdleMaxWaitSeconds
            )
            appendDiff(
                changes,
                "cameraPrewarmHoldSeconds",
                oldCameraPrewarmHoldSeconds,
                mergedConfig.cameraPrewarmHoldSeconds
            )

            if (changes.isNotEmpty()) {
                logD("远端配置已更新：${changes.joinToString(", ")}")
            }

            reconcileDiscardCloseTime(LOG_SYNC, oldDiscardTime, mergedConfig)

            handleRemoteOnlineStatusTransition(
                previousOnlineStatus,
                previousIsEnable,
                previousRestStatusSource,
                mergedConfig,
                "getDeviceInfo"
            )
            refreshStockData(logPrefix = "$LOG_SYNC-stock-after-config")
        }
    }

    fun refreshRemoteDeviceState(
        logPrefix: String = "$LOG_SYNC-home",
        onResult: ((result: RemoteDeviceStateRefreshResult) -> Unit)? = null
    ) {
        val appConfigBean = AppConfig.getAppConfig()
        val oldStatus = appConfigBean.status
        val oldOnlineStatus = appConfigBean.onlineStatus
        val oldSupplyStatus = appConfigBean.supplyStatus
        val oldScanBlockStatus = appConfigBean.scanBlockStatus
        val oldInspectionMode = appConfigBean.inspectionMode
        val oldQcodeUrl = appConfigBean.qcodeURL
        val oldAvailable = appConfigBean.available
        val oldAvailableCountdownLatched = appConfigBean.availableCountdownLatched
        val oldDiscardTime = appConfigBean.discardTime
        val previousIsEnable = AppConfig.getAlgorithmEnableFlag()
        val previousRestStatusSource = AppConfig.normalizeRestStatusSource(appConfigBean.restStatusSource)
        val deviceCode = requireStableDeviceCode("刷新远端设备状态") ?: run {
            onResult?.invoke(
                RemoteDeviceStateRefreshResult(
                    success = false,
                    changed = false,
                    onlineStatus = oldOnlineStatus,
                    supplyStatus = oldSupplyStatus,
                    scanBlockStatus = oldScanBlockStatus,
                    inspectionMode = oldInspectionMode,
                    isEnable = previousIsEnable,
                    restStatusSource = previousRestStatusSource
                )
            )
            return
        }

        NetApi.getDeviceInfo(deviceCode) refreshStateCallback@{ code, content ->
            if (code != 0) {
                logE("$logPrefix 失败：HTTP请求失败，code=$code, content=$content")
                onResult?.invoke(
                    RemoteDeviceStateRefreshResult(
                        success = false,
                        changed = false,
                        onlineStatus = oldOnlineStatus,
                        supplyStatus = oldSupplyStatus,
                        scanBlockStatus = oldScanBlockStatus,
                        inspectionMode = oldInspectionMode,
                        isEnable = previousIsEnable,
                        restStatusSource = previousRestStatusSource
                    )
                )
                return@refreshStateCallback
            }

            val json = try {
                JSON.parseObject(content)
            } catch (e: Exception) {
                logE("$logPrefix 失败：响应解析异常，raw=$content")
                onResult?.invoke(
                    RemoteDeviceStateRefreshResult(
                        success = false,
                        changed = false,
                        onlineStatus = oldOnlineStatus,
                        supplyStatus = oldSupplyStatus,
                        scanBlockStatus = oldScanBlockStatus,
                        inspectionMode = oldInspectionMode,
                        isEnable = previousIsEnable,
                        restStatusSource = previousRestStatusSource
                    )
                )
                return@refreshStateCallback
            }

            if (json == null || json.getIntValue("code") != 200) {
                logE("$logPrefix 失败：业务返回异常，raw=$content")
                onResult?.invoke(
                    RemoteDeviceStateRefreshResult(
                        success = false,
                        changed = false,
                        onlineStatus = oldOnlineStatus,
                        supplyStatus = oldSupplyStatus,
                        scanBlockStatus = oldScanBlockStatus,
                        inspectionMode = oldInspectionMode,
                        isEnable = previousIsEnable,
                        restStatusSource = previousRestStatusSource
                    )
                )
                return@refreshStateCallback
            }

            val mergeResult = AppConfig.mergeRemoteConfig(json.getJSONObject("data"), content, logPrefix)
            if (!mergeResult.applied) {
                onResult?.invoke(
                    RemoteDeviceStateRefreshResult(
                        success = false,
                        changed = false,
                        onlineStatus = oldOnlineStatus,
                        supplyStatus = oldSupplyStatus,
                        scanBlockStatus = oldScanBlockStatus,
                        inspectionMode = oldInspectionMode,
                        isEnable = previousIsEnable,
                        restStatusSource = previousRestStatusSource
                    )
                )
                return@refreshStateCallback
            }

            val mergedConfig = AppConfig.getAppConfig()
            reconcileDiscardCloseTime(logPrefix, oldDiscardTime, mergedConfig)
            handleRemoteOnlineStatusTransition(
                oldOnlineStatus,
                previousIsEnable,
                previousRestStatusSource,
                mergedConfig,
                "refreshHomeDeviceState"
            )
            val changed =
                oldStatus != mergedConfig.status ||
                    oldOnlineStatus != mergedConfig.onlineStatus ||
                    oldSupplyStatus != mergedConfig.supplyStatus ||
                    oldScanBlockStatus != mergedConfig.scanBlockStatus ||
                    oldInspectionMode != mergedConfig.inspectionMode ||
                    oldQcodeUrl != mergedConfig.qcodeURL ||
                    oldAvailable != mergedConfig.available ||
                    oldAvailableCountdownLatched != mergedConfig.availableCountdownLatched

            if (changed) {
                logD(
                    "$logPrefix 成功：首页相关配置发生变化：" +
                        "status（设备启用状态）=${formatDiffValue("status", oldStatus)}->${formatDiffValue("status", mergedConfig.status)}，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(oldOnlineStatus)}->${onlineStatusText(mergedConfig.onlineStatus)}，" +
                        "supplyStatus（补货状态）=${formatDiffValue("supplyStatus", oldSupplyStatus)}->${formatDiffValue("supplyStatus", mergedConfig.supplyStatus)}，" +
                        "scanBlockStatus（前台屏蔽状态）=${scanBlockStatusText(oldScanBlockStatus)}->${scanBlockStatusText(mergedConfig.scanBlockStatus)}，" +
                        "inspectionMode（检修模式）=${inspectionModeText(oldInspectionMode)}->${inspectionModeText(mergedConfig.inspectionMode)}，" +
                        "qrcodeChanged（二维码地址是否变化）=${oldQcodeUrl != mergedConfig.qcodeURL}，" +
                        "available（二维码页预计时间）=${oldAvailable}->${mergedConfig.available}，" +
                        "availableCountdownLatched（二维码页预计时间锁存标记）=${oldAvailableCountdownLatched}->${mergedConfig.availableCountdownLatched}"
                )
            }

            onResult?.invoke(
                RemoteDeviceStateRefreshResult(
                    success = true,
                    changed = changed,
                    onlineStatus = mergedConfig.onlineStatus,
                    supplyStatus = mergedConfig.supplyStatus,
                    scanBlockStatus = mergedConfig.scanBlockStatus,
                    inspectionMode = mergedConfig.inspectionMode,
                    isEnable = AppConfig.getAlgorithmEnableFlag(),
                    restStatusSource = AppConfig.normalizeRestStatusSource(mergedConfig.restStatusSource)
                )
            )
        }
    }

    fun refreshHomeDeviceState(onResult: ((changed: Boolean) -> Unit)? = null) {
        refreshRemoteDeviceState { result ->
            onResult?.invoke(result.changed)
        }
    }

    fun uploadDeviceInfo(callback: ((code: Int, content: String?) -> Unit)? = null) {
        requireStableDeviceCode("上传设备营业状态") ?: run {
            callback?.invoke(1, "deviceCode_not_locked")
            return
        }
        NetApi.updateDeviceInfo(callback)
    }
}
