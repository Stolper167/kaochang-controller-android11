package cn.niuyannet.kaochang.android.init

import android.provider.Settings
import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.model.KaoPanHelper
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
        return appConfigBean!!
    }

    fun saveAppConfig(appConfigBean: AppConfigBean) {
        alignRestStatusSource(appConfigBean)
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
        mergeString("deviceCode", "deviceCode", allowBlank = false) { config.deviceCode = it }
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

        saveAppConfig(config)

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
            append(config.deviceCode.ifBlank { "未知" })
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
        var androidId = PreferenceUtils.getStringPreference(Sp_File_URL, "sp_android_id", null)
        if (androidId == null) {
            androidId = Settings.Secure.getString(MyApp.instance().contentResolver, Settings.Secure.ANDROID_ID)
            PreferenceUtils.saveStringPreference(Sp_File_URL, "sp_android_id", androidId)
        }
        return androidId
    }

    fun saveDeviceId(androidId: String) {
        PreferenceUtils.saveStringPreference(Sp_File_URL, "sp_android_id", androidId)
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
