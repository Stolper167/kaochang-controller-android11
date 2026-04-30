package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.DeviceIdentityPolicy
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSONObject
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.StringCallback
import com.lzy.okgo.model.Response
import com.lzy.okgo.request.base.Request

/**
 * 上位机与云端的 HTTP 接口封装。
 */
object NetApi {
    // 正式环境接口地址
    var API_URL_START: String = "https://iotadmin.cheerluck.com/prod-api/api/app/device"

    // 测试环境示例
    // var API_URL_START: String = "https://iotadmintest.cheerluck.com/stage-api/api/app/device"

    // 设备初始化
    var init_url = "$API_URL_START/init"
    var identity_apply_url = "$API_URL_START/identity/apply"

    // 获取烤肠箱库存
    var stock_url = "$API_URL_START/stock"
    var getKcProductList = "$API_URL_START/getKcProductList"

    // 更新烤肠箱库存
    var updateStock_url = "$API_URL_START/updateStock"

    // 清空烤肠箱库存
    var clearStock_url = "$API_URL_START/clearStock"

    // 上传烤盘状态
    var updateKaoPanList_url = "$API_URL_START/updateKaoPanList"
    var updateDeviceInfo_url = "$API_URL_START/updateDeviceInfo"

    // 广告与设备信息
    var ads_url = "$API_URL_START/ads"
    var getDeviceInfo_url = "$API_URL_START/getDeviceInfo"

    private fun requireDeviceCodeForHttp(
        operation: String,
        deviceCode: String? = AppConfig.getDeviceId(),
        callback: ((code: Int, content: String?) -> Unit)? = null
    ): String? {
        val normalizedDeviceCode = DeviceIdentityPolicy.normalizeDeviceCode(deviceCode)
        if (normalizedDeviceCode.isNullOrBlank()) {
            LogUtils.w(
                "【设备身份】deviceCode（云端设备编号）未锁定，跳过 HTTP $operation，" +
                    "避免请求携带空设备编号或 systemAndroidId（系统 ANDROID_ID）"
            )
            AppConfig.logDeviceIdentity("HTTP $operation 前", force = true)
            callback?.invoke(1, "deviceCode_not_locked")
            return null
        }
        return normalizedDeviceCode
    }

    /**
     * 设备初始化。
     */
    fun initDevice(deviceId: String, activationCode: String? = null, callback: (code: Int, content: String?) -> Unit) {
        val normalizedDeviceCode = DeviceIdentityPolicy.normalizeDeviceCode(deviceId).orEmpty()
        val normalizedActivationCode = activationCode?.trim().orEmpty()
        if (normalizedDeviceCode.isBlank() && normalizedActivationCode.isBlank()) {
            LogUtils.w("【设备身份】设备初始化已拦截：deviceCode（云端设备编号）和 activationCode（一次性激活码）均为空")
            callback(1, "deviceCode_or_activationCode_required")
            return
        }
        AppConfig.logDeviceIdentity("设备初始化请求前", force = true)
        val json = JSONObject()
        if (normalizedDeviceCode.isNotBlank()) {
            json["deviceCode"] = normalizedDeviceCode
        }
        if (normalizedActivationCode.isNotBlank()) {
            json["activationCode"] = normalizedActivationCode
        }
        LogUtils.d(
            "【网络请求】开始初始化设备：" +
                "deviceCode（云端设备编号）=${normalizedDeviceCode.ifBlank { "未填写" }}，" +
                "activationCode（一次性激活码）=${if (normalizedActivationCode.isBlank()) "未填写" else "已填写"}"
        )
        OkGo.post<String>(init_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onStart(request: Request<String, out Request<Any, Request<*, *>>>?) {
                    super.onStart(request)
                }

                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】设备初始化接口返回成功：deviceCode（云端设备编号）=${normalizedDeviceCode.ifBlank { "待云端返回" }}")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【网络请求】设备初始化失败：deviceCode（云端设备编号）=${normalizedDeviceCode.ifBlank { "未填写" }}, " +
                            "error=${response?.exception?.message}"
                    )
                    callback(1, null)
                }
            })
    }

    /**
     * 提交设备身份申请。
     */
    fun applyDeviceIdentity(
        candidateDeviceCode: String?,
        activationCode: String?,
        remark: String?,
        callback: (code: Int, content: String?) -> Unit
    ) {
        val json = AppConfig.buildDeviceIdentityApplyPayload(candidateDeviceCode, activationCode, remark)
        AppConfig.logDeviceIdentity("提交设备身份申请前", force = true)
        LogUtils.w(
            "【设备身份】提交身份申请：candidateDeviceCode（候选设备编号）=${json.getString("candidateDeviceCode")}，" +
                "identityInstallId（本机安装/身份申请指纹）=${json.getString("identityInstallId")}，" +
                "systemAndroidId（系统 ANDROID_ID）=${json.getString("systemAndroidId")}"
        )
        OkGo.post<String>(identity_apply_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.w("【设备身份】身份申请接口返回：content=${response?.body()}")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【设备身份】身份申请提交失败：error=${response?.exception?.message}，" +
                            "candidateDeviceCode=${json.getString("candidateDeviceCode")}"
                    )
                    callback(1, response?.body())
                }
            })
    }

    /**
     * 获取广告列表。
     */
    fun getAds(callback: (code: Int, content: String?) -> Unit) {
        LogUtils.d("【网络请求】开始获取广告列表")
        OkGo.get<String>(ads_url)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】获取广告列表成功")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】获取广告列表失败：error=${response?.exception?.message}")
                    callback(1, null)
                }
            })
    }

    /**
     * 获取设备详情。
     */
    fun getDeviceInfo(deviceId: String, callback: (code: Int, content: String?) -> Unit) {
        val normalizedDeviceCode = requireDeviceCodeForHttp("获取设备详情", deviceId, callback) ?: return
        LogUtils.d("【网络请求】开始获取设备详情：deviceCode（云端设备编号）=$normalizedDeviceCode")
        OkGo.get<String>(getDeviceInfo_url)
            .params("deviceCode", normalizedDeviceCode)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】获取设备详情成功：deviceCode（云端设备编号）=$normalizedDeviceCode")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【网络请求】获取设备详情失败：deviceCode（云端设备编号）=$normalizedDeviceCode, " +
                            "error=${response?.exception?.message}"
                    )
                    callback(1, null)
                }
            })
    }

    /**
     * 获取产品列表。
     */
    fun getKcProductList(callback: (code: Int, content: String?) -> Unit) {
        LogUtils.d("【网络请求】开始获取产品列表")
        OkGo.get<String>(getKcProductList)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】获取产品列表成功")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】获取产品列表失败：error=${response?.exception?.message}")
                    callback(1, null)
                }
            })
    }

    /**
     * 获取设备库存列表。
     */
    fun getStockData(callback: (code: Int, content: String?) -> Unit) {
        val deviceCode = requireDeviceCodeForHttp("获取库存", callback = callback) ?: return
        LogUtils.d("【网络请求】开始获取库存：deviceCode（云端设备编号）=$deviceCode")
        OkGo.get<String>(stock_url)
            .params("deviceCode", deviceCode)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】获取库存成功：deviceCode（云端设备编号）=$deviceCode")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【网络请求】获取库存失败：deviceCode（云端设备编号）=$deviceCode, " +
                            "error=${response?.exception?.message}"
                    )
                    callback(1, "网络错误")
                }
            })
    }

    /**
     * 更新烤肠箱库存。
     */
    fun updateStock(content: String, callback: (code: Int, content: String?) -> Unit) {
        val deviceCode = requireDeviceCodeForHttp("更新烤肠箱库存", callback = callback) ?: return
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["content"] = content
        LogUtils.d("【网络请求】开始更新烤肠箱库存：deviceCode（云端设备编号）=$deviceCode")
        OkGo.post<String>(updateStock_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】更新烤肠箱库存成功：deviceCode（云端设备编号）=$deviceCode")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【网络请求】更新烤肠箱库存失败：deviceCode（云端设备编号）=$deviceCode, " +
                            "error=${response?.exception?.message}"
                    )
                    callback(1, null)
                }
            })
    }

    /**
     * 清空烤盘库存。
     */
    fun clearStock(content: String, callback: (code: Int, content: String?) -> Unit) {
        val deviceCode = requireDeviceCodeForHttp("清空烤盘库存", callback = callback) ?: return
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["content"] = content
        LogUtils.d("【网络请求】开始清空烤盘：deviceCode（云端设备编号）=$deviceCode")
        OkGo.post<String>(clearStock_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】清空烤盘成功：deviceCode（云端设备编号）=$deviceCode")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【网络请求】清空烤盘失败：deviceCode（云端设备编号）=$deviceCode, " +
                            "error=${response?.exception?.message}"
                    )
                    callback(1, null)
                }
            })
    }

    /**
     * 上传烤盘状态。
     */
    fun updateKaoPanList(content: String?, callback: ((code: Int, content: String?) -> Unit)? = null) {
        val deviceCode = requireDeviceCodeForHttp("上传烤盘状态", callback = callback) ?: return
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["content"] = content
        LogUtils.d("【网络请求】开始上传烤盘状态：deviceCode（云端设备编号）=$deviceCode")
        OkGo.post<String>(updateKaoPanList_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】上传烤盘状态成功：deviceCode（云端设备编号）=$deviceCode")
                    callback?.invoke(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【网络请求】上传烤盘状态失败：deviceCode（云端设备编号）=$deviceCode, " +
                            "error=${response?.exception?.message}"
                    )
                    callback?.invoke(1, response?.body())
                }
            })
    }

    /**
     * 更新设备营业状态和基础控制参数。
     * 这是 HTTP 同步通道，和 MQTT 的 action=2 状态上报互为补充。
     */
    fun updateDeviceInfo(callback: ((code: Int, content: String?) -> Unit)? = null) {
        val deviceCode = requireDeviceCodeForHttp("更新设备信息", callback = callback) ?: return
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["errorStatus"] = AppConfig.getAppConfig().errorStatus
        json["onlineStatus"] = AppConfig.getAppConfig().onlineStatus
        json["supplyStatus"] = AppConfig.getAppConfig().supplyStatus
        json["scanBlockStatus"] = AppConfig.getAppConfig().scanBlockStatus
        json["inspectionMode"] = AppConfig.getAppConfig().inspectionMode
        json["modeType"] = AppConfig.getAppConfig().modeType
        json["transitionMode"] = AppConfig.getAppConfig().transitionMode
        json["available"] = AppConfig.getAppConfig().available
        json["availableCountdownLatched"] = AppConfig.getAppConfig().availableCountdownLatched
        json["isEnable"] = AppConfig.getAlgorithmEnableFlag()
        LogUtils.d("【网络请求】开始更新设备信息：deviceCode（云端设备编号）=$deviceCode")
        OkGo.post<String>(updateDeviceInfo_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】更新设备信息成功：deviceCode（云端设备编号）=$deviceCode")
                    callback?.invoke(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【网络请求】更新设备信息失败：deviceCode（云端设备编号）=$deviceCode, " +
                            "error=${response?.exception?.message}"
                    )
                    callback?.invoke(1, response?.body())
                }
            })
    }
}
