package cn.niuyannet.kaochang.android.ui

import android.os.Bundle
import cn.niuyannet.kaochang.android.base.BaseActivity
import cn.niuyannet.kaochang.android.databinding.ActivityStartInitBinding
import cn.niuyannet.kaochang.android.detecition.SauceDetectionLauncher
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.DeviceIdentityPolicy
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.dismissLoadingExt
import cn.niuyannet.kaochang.android.utils.showLoadingExt
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils

/**
 * 设备初始化入口页。
 *
 * 当前联调阶段直接跳过 ARUCO 前置检测，点击确认后直接执行设备初始化。
 */
class StartInitActivity : BaseActivity() {
    private lateinit var binding: ActivityStartInitBinding
    private val spFileKey = "sp_file_init"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasInitFlag = PreferenceUtils.getBooleanPreference(spFileKey, "sp_init_status", false)
        val hasStableDeviceCode = AppConfig.hasStableDeviceId()
        if (hasInitFlag && hasStableDeviceCode) {
            AppConfig.logDeviceIdentity("设备初始化页跳过：已初始化且设备编号已锁定", force = true)
            MainActivity.start(this)
            finish()
            return
        }
        if (hasInitFlag && !hasStableDeviceCode) {
            PreferenceUtils.saveBooleanPreference(spFileKey, "sp_init_status", false)
            LogUtils.w(
                "【设备身份】初始化标记存在但 deviceCode（云端设备编号）未锁定，" +
                    "已停留在初始化页并要求重新输入后台预置设备编号"
            )
        }

        binding = ActivityStartInitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppConfig.logDeviceIdentity("设备初始化页打开", force = true)
        refreshDeviceCodeInput()

        binding.btnConfirm.setOnClickListener {
            val inputDeviceCode = binding.etDeviceId.text?.toString()?.trim().orEmpty()
            if (inputDeviceCode.isBlank()) {
                ToastUtils.showShort("请输入后台预置设备编号")
                LogUtils.w("【设备身份】设备初始化已拦截：输入的 deviceCode（云端设备编号）为空")
                return@setOnClickListener
            }
            val normalizedDeviceCode = DeviceIdentityPolicy.normalizeDeviceCode(inputDeviceCode)
            if (normalizedDeviceCode.isNullOrBlank()) {
                ToastUtils.showShort("设备编号格式无效")
                LogUtils.w("【设备身份】设备初始化已拦截：deviceCode（云端设备编号）格式无效")
                return@setOnClickListener
            }

            // 优化：仅在“联调模式（forceVisionSuccess=1）”下跳过 ARUCO 校准判定
            if (AppConfig.getAppConfig().forceVisionSuccess == 0) {
                val sharedPreferences = getSharedPreferences("aruco_helper_prefs", MODE_PRIVATE)
                val cornersJson = sharedPreferences.getString("corners_data", null)
                if (cornersJson == null) {
                    ToastUtils.showShort("未检测到前置 ARUCO 标识，禁止初始化设备")
                    return@setOnClickListener
                }
            } else {
                LogUtils.w("设备初始化：当前已开启联调模式，跳过 ARUCO 硬件校准检测，直接尝试登录！")
            }
            
            showLoadingExt("初始化中...")
            NetApi.initDevice(normalizedDeviceCode) { code, content ->
                dismissLoadingExt()
                if (code == 0) {
                    val json = try {
                        JSON.parseObject(content)
                    } catch (e: Exception) {
                        LogUtils.e("【设备身份】设备初始化响应解析失败：raw=$content", e)
                        null
                    }
                    if (json == null) {
                        ToastUtils.showShort("初始化响应解析失败")
                        return@initDevice
                    }
                    if (json.getIntValue("code") == 200) {
                        PreferenceUtils.saveBooleanPreference(spFileKey, "sp_init_status", true)
                        val remoteDeviceCode = json.getJSONObject("data")?.getString("deviceCode")
                            ?: json.getString("deviceCode")
                        AppConfig.saveDeviceId(remoteDeviceCode?.takeIf { it.isNotBlank() } ?: normalizedDeviceCode)
                        AppConfig.logDeviceIdentity("设备初始化成功后", force = true)
                        MainActivity.start(this@StartInitActivity)
                        finish()
                    } else {
                        val message = json.getString("msg") ?: "设备初始化失败"
                        LogUtils.w(
                            "【设备身份】设备初始化被云端拒绝：deviceCode（云端设备编号）=$normalizedDeviceCode，" +
                                "code=${json.getIntValue("code")}，msg=$message"
                        )
                        ToastUtils.showShort(message)
                    }
                } else {
                    LogUtils.e("【设备身份】设备初始化请求失败：deviceCode（云端设备编号）=$normalizedDeviceCode，content=$content")
                    ToastUtils.showShort("网络服务错误")
                }
            }
        }

        binding.btnStartCheck.setOnClickListener {
            SauceDetectionLauncher.launch(this)
        }
    }

    private fun refreshDeviceCodeInput() {
        val stableDeviceCode = AppConfig.getDeviceId()
        val locked = stableDeviceCode.isNotBlank()
        binding.etDeviceId.isEnabled = !locked
        binding.etDeviceId.setText(stableDeviceCode)
        binding.etDeviceId.hint = if (locked) {
            "设备编号已锁定"
        } else {
            "请输入后台预置设备编号"
        }
    }
}
