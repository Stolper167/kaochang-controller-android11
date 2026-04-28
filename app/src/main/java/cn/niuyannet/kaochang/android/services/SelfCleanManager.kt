package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.utils.DeviceStateText
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.LogUtils

object SelfCleanManager {
    private const val LOG_SELF_CLEAN = "【自清洁】"
    private const val ENABLE_PER_ORDER_SELF_CLEAN = false
    private val ZONE3_POSITIONS = (25..33).toList()

    private var currentOrderNo: String? = null
    private val currentOrderUsedPanPositions = linkedSetOf<Int>()

    private fun logD(message: String) = LogUtils.d("[$LOG_SELF_CLEAN] $message")
    private fun logI(message: String) = LogUtils.i("[$LOG_SELF_CLEAN] $message")
    private fun logW(message: String) = LogUtils.w("[$LOG_SELF_CLEAN] $message")
    private fun logE(message: String) = LogUtils.e("[$LOG_SELF_CLEAN] $message")
    fun isFeatureEnabled(): Boolean = SelfCleanFeatureToggle.isEnabled()

    private fun clearZone3FlagsIfNeeded(reason: String) {
        val config = AppConfig.getAppConfig()
        if (config.zone3Dirty == 0 && config.zone3CleanPending == 0) {
            return
        }
        config.zone3Dirty = 0
        config.zone3CleanPending = 0
        AppConfig.saveAppConfig(config)
        logI(
            "自清洁总开关关闭，已清理三区自清洁标记 | reason=$reason，" +
                "zone3Dirty=false，zone3CleanPending=false"
        )
    }

    fun beginOrderTracking(orderNo: String?) {
        if (orderNo.isNullOrBlank()) {
            currentOrderNo = null
            currentOrderUsedPanPositions.clear()
            return
        }
        currentOrderNo = orderNo
        currentOrderUsedPanPositions.clear()
        logD("预留每单自清洁：开始跟踪订单使用烤盘 | orderNo=$orderNo，enabled=$ENABLE_PER_ORDER_SELF_CLEAN")
    }

    fun recordUsedPanForCurrentOrder(positionSn: Int) {
        val orderNo = currentOrderNo ?: return
        if (currentOrderUsedPanPositions.add(positionSn)) {
            logD("预留每单自清洁：记录本单实际出过肠的烤盘 | orderNo=$orderNo，positionSn（烤盘位编号）=$positionSn")
        }
    }

    fun finishOrderTracking(orderNo: String?, orderCompleted: Boolean) {
        if (orderNo.isNullOrBlank() || currentOrderNo != orderNo) {
            return
        }
        val usedPositions = currentOrderUsedPanPositions.toList().sorted()
        logI(
            "预留每单自清洁：订单结束，已收集本单实际出过肠的烤盘位集合 | " +
                "orderNo=$orderNo，orderCompleted=$orderCompleted，usedPanPositions=$usedPositions，" +
                "enabled=$ENABLE_PER_ORDER_SELF_CLEAN（false=当前版本仅预留不启用）"
        )
        currentOrderNo = null
        currentOrderUsedPanPositions.clear()
    }

    fun clearOrderTrackingIfMatches(orderNo: String?) {
        if (!orderNo.isNullOrBlank() && currentOrderNo == orderNo) {
            currentOrderNo = null
            currentOrderUsedPanPositions.clear()
        }
    }

    fun markZone3Dirty() {
        if (!isFeatureEnabled()) {
            clearZone3FlagsIfNeeded("忽略三区置脏")
            return
        }
        val config = AppConfig.getAppConfig()
        if (config.zone3Dirty == 1 && config.zone3CleanPending == 0) {
            return
        }
        config.zone3Dirty = 1
        config.zone3CleanPending = 0
        AppConfig.saveAppConfig(config)
        logI("三区自清洁标记已置脏 | zone3Dirty=true，zone3CleanPending=false，range=25~33")
    }

    fun markZone3CleanPendingIfNeeded(list: List<KaoPan>) {
        if (!isFeatureEnabled()) {
            clearZone3FlagsIfNeeded("忽略三区待执行标记")
            return
        }
        val config = AppConfig.getAppConfig()
        if (config.zone3Dirty != 1 || config.zone3CleanPending == 1) {
            return
        }
        val zone3Pans = list.filter { it.positionSn in 25..33 }
        if (zone3Pans.any { it.isHasSausage }) {
            return
        }
        config.zone3CleanPending = 1
        AppConfig.saveAppConfig(config)
        logI("三区自清洁待执行标记已设置 | zone3Dirty=true，zone3CleanPending=true，range=25~33")
    }

    suspend fun maybeRunZone3SelfClean(list: List<KaoPan>): Boolean {
        if (!isFeatureEnabled()) {
            clearZone3FlagsIfNeeded("跳过三区自动自清洁")
            return false
        }
        val config = AppConfig.getAppConfig()
        if (config.zone3CleanPending != 1 || config.zone3Dirty != 1) {
            return false
        }
        if (config.errorStatus != 0 || config.onlineStatus != 1 || config.moveStatus != 0) {
            return false
        }
        if (list.any { it.positionSn in 25..33 && it.isHasSausage }) {
            return false
        }

        val previousOnlineStatus = config.onlineStatus
        config.onlineStatus = 4
        AppConfig.saveAppConfig(config)
        publishRuntimeState("zone3_self_clean:start")
        HomeUiRefreshBridge.requestRefresh("zone3_self_clean:start")
        logI(
            "开始执行三区批量自清洁 | range=25~33，" +
                "onlineStatus（设备营业状态）=${DeviceStateText.onlineStatus(previousOnlineStatus)}->${DeviceStateText.onlineStatus(config.onlineStatus)}，" +
                "moveStatus（机械动作状态）=${DeviceStateText.moveStatus(config.moveStatus)}，" +
                "zone3Dirty=${config.zone3Dirty}，zone3CleanPending=${config.zone3CleanPending}"
        )

        val result = KaoChangOperate.runPanSelfCleanSequence(
            positionSnList = ZONE3_POSITIONS,
            includeSellPlatform = false,
            source = "三区自动清洁"
        )
        val latestConfig = AppConfig.getAppConfig()
        if (!result.success) {
            latestConfig.zone3Dirty = 0
            latestConfig.zone3CleanPending = 0
            AppConfig.saveAppConfig(latestConfig)
            HomeUiRefreshBridge.requestRefresh("zone3_self_clean:failed")
            logE("三区批量自清洁失败，已停止后续步骤并清除待执行标记")
            return false
        }

        latestConfig.zone3Dirty = 0
        latestConfig.zone3CleanPending = 0
        latestConfig.onlineStatus = previousOnlineStatus
        AppConfig.saveAppConfig(latestConfig)
        publishRuntimeState("zone3_self_clean:finished")
        HomeUiRefreshBridge.requestRefresh("zone3_self_clean:finished")
        logI(
            "三区批量自清洁完成 | range=25~33，onlineStatus（设备营业状态）=${DeviceStateText.onlineStatus(latestConfig.onlineStatus)}，" +
                "zone3Dirty=false，zone3CleanPending=false"
        )
        return true
    }

    private fun publishRuntimeState(source: String) {
        val mqttOk = SendServerHelper.publishServiceUpdateStatus("runtime_state:$source")
        if (!mqttOk) {
            logW("运行态上报失败，回退 HTTP 补偿 | source=$source")
            DataManagementAPI.uploadDeviceInfo()
        }
    }
}
