package cn.niuyannet.kaochang.android.init

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.net.RemoteConcurrencyControlHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject

object AppConfig {
    const val Sp_File_URL = "KAOCHANG_APP_CONFIG"
    private const val KEY_APP_CONFIG_OBJECT = "sp_app_config"
    private const val KEY_APP_CONFIG_JSON = "sp_app_config_json"
    private const val KEY_INSPECTION_SNAPSHOT_JSON = "sp_inspection_snapshot_json"
    private val BENIGN_PRESERVED_FIELDS = setOf("available", "availableCountdownLatched")
    private var appConfigBean: AppConfigBean? = null

    data class RemoteConfigMergeResult(
        val applied: Boolean,
        val usedLocalFallback: Boolean,
        val message: String
    )

    data class InspectionSnapshot(
        val savedAt: Long,
        val configJson: String,
        val kaoPanJson: String,
        val kaoPanBoxJson: String
    )

    fun normalizeRestStatusSource(restStatusSource: String?): String {
        return when (restStatusSource) {
            AppConfigBean.REST_SOURCE_NONE,
            AppConfigBean.REST_SOURCE_UNKNOWN,
            AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS,
            AppConfigBean.REST_SOURCE_MANUAL_REMOTE,
            AppConfigBean.REST_SOURCE_MANUAL_LOCAL -> restStatusSource
            else -> AppConfigBean.REST_SOURCE_UNKNOWN
        }
    }

    fun restStatusSourceText(restStatusSource: String?): String = when (normalizeRestStatusSource(restStatusSource)) {
        AppConfigBean.REST_SOURCE_NONE -> "NONE（当前不是休息中）"
        AppConfigBean.REST_SOURCE_UNKNOWN -> "UNKNOWN（来源未知/历史旧数据）"
        AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS -> "AUTO_END_BUSINESS（系统自动营业结束）"
        AppConfigBean.REST_SOURCE_MANUAL_REMOTE -> "MANUAL_REMOTE（后台手动设置休息中）"
        AppConfigBean.REST_SOURCE_MANUAL_LOCAL -> "MANUAL_LOCAL（本地人工设置休息中）"
        else -> "${restStatusSource ?: "null"}（未知）"
    }

    fun alignRestStatusSource(config: AppConfigBean) {
        config.restStatusSource = when (config.onlineStatus) {
            2 -> normalizeRestStatusSource(config.restStatusSource)
            else -> AppConfigBean.REST_SOURCE_NONE
        }
    }

    fun getTopicDeviceIdUrl(url: String): String {
        return url.replace("{device_id}", getDeviceId())
    }

    fun getAppConfig(): AppConfigBean {
        if (appConfigBean != null) {
            return appConfigBean!!
        }

        val jsonConfig = PreferenceUtils.getStringPreference(Sp_File_URL, KEY_APP_CONFIG_JSON, null)
        if (!jsonConfig.isNullOrBlank()) {
            try {
                appConfigBean = JSON.parseObject(jsonConfig, AppConfigBean::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (appConfigBean == null) {
            val legacyConfig = PreferenceUtils.getObjectPreference<AppConfigBean>(Sp_File_URL, KEY_APP_CONFIG_OBJECT)
            if (legacyConfig != null) {
                appConfigBean = legacyConfig
                saveAppConfig(legacyConfig)
            }
        }

        if (appConfigBean == null) {
            appConfigBean = AppConfigBean()
        }
        val config = appConfigBean!!
        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        normalizeConcurrencyState(config, "配置加载")
        if (oldModeType != config.modeType || oldTransitionMode != config.transitionMode) {
            saveAppConfig(config)
        }
        return appConfigBean!!
    }

    fun saveAppConfig(appConfigBean: AppConfigBean) {
        alignRestStatusSource(appConfigBean)
        val normalizedDeviceCode = DeviceIdentityPolicy.normalizeDeviceCode(appConfigBean.deviceCode)
        when {
            !normalizedDeviceCode.isNullOrBlank() -> {
                appConfigBean.deviceCode = normalizedDeviceCode
                DeviceIdentityManager.saveDeviceCode(normalizedDeviceCode, "保存设备配置")
            }
            !appConfigBean.deviceCode.isNullOrBlank() -> {
                LogUtils.w(
                    "【设备身份】保存设备配置时发现非法 deviceCode（云端设备编号），已清空本地配置字段：" +
                        "rawDeviceCode=${appConfigBean.deviceCode}"
                )
                appConfigBean.deviceCode = ""
            }
        }
        this.appConfigBean = appConfigBean
        PreferenceUtils.saveStringPreference(Sp_File_URL, KEY_APP_CONFIG_JSON, JSON.toJSONString(appConfigBean))
    }

    fun hasValidDeviceConfig(): Boolean {
        val config = getAppConfig()
        return !config.deviceCode.isNullOrBlank() &&
            config.businessTime != null &&
            config.status in 0..1 &&
            config.onlineStatus in 0..4
    }

    fun getAlgorithmEnableFlag(): Int {
        val config = getAppConfig()
        return if (config.isEnable == 1) 1 else 0
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
            LogUtils.w("[配置迁移] 判断高并发区域烤盘状态失败，保守保持高转低收口", e)
            true
        }
    }

    private fun normalizeConcurrencyState(config: AppConfigBean, logPrefix: String) {
        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        val highConcurrencyAreaActive = hasHighConcurrencyAreaActive()
        val resolvedTarget = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = oldModeType,
            currentTransitionMode = oldTransitionMode,
            requestedModeType = oldModeType,
            requestedTransitionMode = oldTransitionMode,
            highConcurrencyAreaActive = highConcurrencyAreaActive
        )
        if (oldModeType == resolvedTarget.modeType && oldTransitionMode == resolvedTarget.transitionMode) {
            return
        }
        config.modeType = resolvedTarget.modeType
        config.transitionMode = resolvedTarget.transitionMode
        LogUtils.w(
            "[$logPrefix] 远端配置并发运行态已归一化：" +
                "原 modeType（并发模式）=$oldModeType，原 transitionMode（并发切换过渡态）=$oldTransitionMode，" +
                "高并发区域是否仍有烤肠或动作=$highConcurrencyAreaActive，" +
                "新 modeType（并发模式）=${config.modeType}，新 transitionMode（并发切换过渡态）=${config.transitionMode}"
        )
    }

    fun mergeRemoteConfig(
        jsonData: JSONObject?,
        rawContent: String?,
        logPrefix: String
    ): RemoteConfigMergeResult {
        val config = getAppConfig()
        if (jsonData == null) {
            val hasFallback = hasValidDeviceConfig()
            val message = "远端配置数据为空，responseLength=${rawContent?.length ?: 0}"
            if (hasFallback) {
                LogUtils.w("[$logPrefix] $message，继续使用本地有效配置")
            } else {
                LogUtils.e("[$logPrefix] $message，且本地也没有有效配置")
            }
            return RemoteConfigMergeResult(hasFallback, hasFallback, message)
        }

        val preservedFields = mutableListOf<String>()
        val parseIssues = mutableListOf<String>()

        fun mergeString(
            key: String,
            fieldLabel: String,
            allowBlank: Boolean = true,
            setter: (String) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                return
            }
            val value = jsonData.getString(key)
            if (!allowBlank && value.isNullOrBlank()) {
                preservedFields.add(fieldLabel)
                return
            }
            setter(value ?: "")
        }

        fun mergeInt(
            key: String,
            fieldLabel: String,
            setter: (Int) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                return
            }
            setter(jsonData.getIntValue(key))
        }

        mergeString("mqttUrl", "mqttUrl") { config.mqttHost = it }
        mergeString("mqttUsername", "mqttUsername") { config.mqttUsername = it }
        mergeString("mqttPassword", "mqttPassword") { config.mqttPassword = it }
        mergeString("modbusAddress", "modbusAddress") { config.modbusAddress = it }
        if (!jsonData.containsKey("deviceCode") || jsonData.get("deviceCode") == null) {
            preservedFields.add("deviceCode")
        } else {
            val rawDeviceCode = jsonData.getString("deviceCode")
            val normalizedDeviceCode = DeviceIdentityPolicy.normalizeDeviceCode(rawDeviceCode)
            if (normalizedDeviceCode.isNullOrBlank()) {
                preservedFields.add("deviceCode")
                parseIssues.add("deviceCode")
                LogUtils.w(
                    "[$logPrefix] 远端配置携带非法 deviceCode（云端设备编号），已忽略并保留本地身份：" +
                        "rawDeviceCode=$rawDeviceCode"
                )
            } else {
                config.deviceCode = normalizedDeviceCode
                DeviceIdentityManager.saveDeviceCode(normalizedDeviceCode, "远端配置合并")
            }
        }
        mergeString("name", "name", allowBlank = false) { config.deviceName = it }
        mergeString("address", "address") { config.deviceAddress = it }
        mergeString("qrcodeUrl", "qrcodeUrl") { config.qcodeURL = it }

        val businessHoursRaw = jsonData.getString("businessHours")
        if (businessHoursRaw.isNullOrBlank()) {
            preservedFields.add("businessHours")
        } else {
            try {
                val businessTime = JSON.parseObject(businessHoursRaw, BusinessTime::class.java)
                if (businessTime != null) {
                    config.businessTime = businessTime
                } else {
                    parseIssues.add("businessHours")
                }
            } catch (e: Exception) {
                parseIssues.add("businessHours")
                LogUtils.w("[$logPrefix] 解析营业时间失败：businessHours=$businessHoursRaw", e)
            }
        }

        mergeInt("status", "status") { config.status = it }
        mergeInt("onlineStatus", "onlineStatus") { config.onlineStatus = it }
        mergeInt("supplyStatus", "supplyStatus") {
            config.supplyStatus = if (it == AppConfigBean.SUPPLY_STATUS_SOLD_OUT) {
                AppConfigBean.SUPPLY_STATUS_SOLD_OUT
            } else {
                AppConfigBean.SUPPLY_STATUS_NORMAL
            }
        }
        mergeInt("scanBlockStatus", "scanBlockStatus") {
            config.scanBlockStatus = if (it == AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED) {
                AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED
            } else {
                AppConfigBean.SCAN_BLOCK_STATUS_NORMAL
            }
        }
        mergeInt("inspectionMode", "inspectionMode") {
            config.inspectionMode = if (it == AppConfigBean.INSPECTION_MODE_ACTIVE) {
                AppConfigBean.INSPECTION_MODE_ACTIVE
            } else {
                AppConfigBean.INSPECTION_MODE_NORMAL
            }
        }
        mergeInt("concurrencyMode", "concurrencyMode") { config.modeType = it }
        mergeInt("transitionMode", "transitionMode") { config.transitionMode = it }
        mergeInt("errorStatus", "errorStatus") { config.errorStatus = it }
        mergeInt("available", "available") { config.available = it.toLong() }
        mergeInt("availableCountdownLatched", "availableCountdownLatched") { config.availableCountdownLatched = it }
        mergeInt("keepWarmTemperature", "keepWarmTemperature") { config.keepWarmTemperature = it }
        mergeInt("heatingTemperature", "heatingTemperature") { config.heatingTemperature = it }
        mergeInt("bakingTime", "bakingTime") { config.bakingTime = it }
        mergeInt("discardTime", "discardTime") { config.discardTime = it }
        mergeInt("timeThresholdLow", "timeThresholdLow") { config.timeThresholdLow = it }
        mergeInt("timeBeforeClose", "timeBeforeClose") { config.timeBeforeClose = it }

        if (jsonData.containsKey("boxToPlatformTimeoutSeconds")) {
            config.boxToPlatformTimeoutSeconds = jsonData.getIntValue("boxToPlatformTimeoutSeconds")
        }
        if (jsonData.containsKey("trayToSellPlatformTimeoutSeconds")) {
            config.trayToSellPlatformTimeoutSeconds = jsonData.getIntValue("trayToSellPlatformTimeoutSeconds")
        }
        if (jsonData.containsKey("postSellSupplementGuardSeconds")) {
            config.postSellSupplementGuardSeconds = jsonData.getIntValue("postSellSupplementGuardSeconds")
        }
        if (jsonData.containsKey("postSellIdleStableSeconds")) {
            config.postSellIdleStableSeconds = jsonData.getIntValue("postSellIdleStableSeconds")
        }
        if (jsonData.containsKey("postSellIdleMaxWaitSeconds")) {
            config.postSellIdleMaxWaitSeconds = jsonData.getIntValue("postSellIdleMaxWaitSeconds")
        }
        if (jsonData.containsKey("cameraPrewarmHoldSeconds")) {
            config.cameraPrewarmHoldSeconds = jsonData.getIntValue("cameraPrewarmHoldSeconds")
        }

        if (config.onlineStatus in 1..4 && config.status != 1) {
            preservedFields.add("status(normalized_from_onlineStatus)")
            config.status = 1
        }
        normalizeConcurrencyState(config, logPrefix)

        saveAppConfig(config)
        if (jsonData.containsKey("deviceCode")) {
            logDeviceIdentity("远端配置合并后")
        }

        val hasValidConfig = hasValidDeviceConfig()
        val preservedFieldSet = preservedFields.distinct()
        val significantPreservedFields = preservedFieldSet.filterNot { it in BENIGN_PRESERVED_FIELDS }
        val benignPreservedFields = preservedFieldSet.filter { it in BENIGN_PRESERVED_FIELDS }
        val details = mutableListOf<String>()
        if (significantPreservedFields.isNotEmpty()) {
            details.add("preserved=${significantPreservedFields.joinToString(",")}")
        }
        if (parseIssues.isNotEmpty()) {
            details.add("parseFailed=${parseIssues.distinct().joinToString(",")}")
        }

        val message = buildString {
            append("远端配置合并完成")
            if (benignPreservedFields.isNotEmpty()) {
                append("，首页预计时间字段继续沿用本地值：")
                append(benignPreservedFields.joinToString(","))
            }
            if (details.isNotEmpty()) {
                append("（")
                append(details.joinToString("; "))
                append("）")
            }
            append("，deviceCode=")
            append(config.deviceCode?.ifBlank { "未知" } ?: "未知")
            jsonData.getString("updateTime")?.takeIf { it.isNotBlank() }?.let {
                append("，serverUpdateTime=")
                append(it)
            }
        }

        if (details.isNotEmpty()) {
            if (hasValidConfig) {
                LogUtils.w("[$logPrefix] $message")
            } else {
                LogUtils.e("[$logPrefix] $message，合并后配置仍然无效")
            }
        }

        val userVisibleFallback = parseIssues.isNotEmpty() ||
            preservedFields.any {
                it == "deviceCode" ||
                    it == "name" ||
                    it == "businessHours" ||
                    it == "qrcodeUrl" ||
                    it == "status" ||
                    it == "onlineStatus"
            }

        return RemoteConfigMergeResult(
            applied = hasValidConfig,
            usedLocalFallback = userVisibleFallback,
            message = message
        )
    }

    fun getDeviceId(): String {
        val deviceCode = DeviceIdentityManager.getDeviceCode()
        syncCachedConfigDeviceCode(deviceCode)
        return deviceCode
    }

    fun saveDeviceId(deviceCode: String) {
        val normalizedDeviceCode = DeviceIdentityManager.saveDeviceCode(deviceCode, "保存设备编号")
        if (!normalizedDeviceCode.isNullOrBlank()) {
            val config = getAppConfig()
            config.deviceCode = normalizedDeviceCode
            saveAppConfig(config)
        }
    }

    fun hasStableDeviceId(): Boolean {
        return DeviceIdentityManager.hasStableDeviceCode()
    }

    fun logDeviceIdentity(reason: String, force: Boolean = false) {
        DeviceIdentityManager.logIdentity(reason, force)
    }

    fun buildDeviceIdentityApplyPayload(
        candidateDeviceCode: String?,
        activationCode: String? = null,
        remark: String? = null
    ): JSONObject {
        return DeviceIdentityManager.buildIdentityApplyPayload(candidateDeviceCode, activationCode, remark)
    }

    private fun syncCachedConfigDeviceCode(deviceCode: String) {
        if (deviceCode.isBlank()) {
            return
        }
        val config = getAppConfig()
        if (config.deviceCode == deviceCode) {
            return
        }
        config.deviceCode = deviceCode
        appConfigBean = config
        PreferenceUtils.saveStringPreference(Sp_File_URL, KEY_APP_CONFIG_JSON, JSON.toJSONString(config))
    }

    fun saveInspectionSnapshot(reason: String) {
        val payload = InspectionSnapshot(
            savedAt = System.currentTimeMillis(),
            configJson = JSON.toJSONString(getAppConfig()),
            kaoPanJson = JSON.toJSONString(KaoPanHelper.getKaoPanList()),
            kaoPanBoxJson = JSON.toJSONString(KaoPanHelper.getKaoPanBoxList())
        )
        PreferenceUtils.saveStringPreference(Sp_File_URL, KEY_INSPECTION_SNAPSHOT_JSON, JSON.toJSONString(payload))
        LogUtils.i(
            "[检修模式] 已保存进入前快照：" +
                "reason=$reason，savedAt=${payload.savedAt}，" +
                "kaoPanCount=${KaoPanHelper.getKaoPanList().size}，kaoPanBoxCount=${KaoPanHelper.getKaoPanBoxList().size}"
        )
    }

    fun getInspectionSnapshot(): InspectionSnapshot? {
        val raw = PreferenceUtils.getStringPreference(Sp_File_URL, KEY_INSPECTION_SNAPSHOT_JSON, null)
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            JSON.parseObject(raw, InspectionSnapshot::class.java)
        } catch (e: Exception) {
            LogUtils.w("[检修模式] 读取检修快照失败，已忽略损坏数据", e)
            null
        }
    }

    fun clearInspectionSnapshot() {
        PreferenceUtils.saveStringPreference(Sp_File_URL, KEY_INSPECTION_SNAPSHOT_JSON, "")
    }
}
