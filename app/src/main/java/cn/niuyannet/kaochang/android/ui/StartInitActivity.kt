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
                    "已停留在初始化页，可输入后台预置设备编号初始化或提交身份申请"
            )
        }

        binding = ActivityStartInitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppConfig.logDeviceIdentity("设备初始化页打开", force = true)
        refreshDeviceCodeInput()

        binding.btnConfirm.setOnClickListener {
            val inputDeviceCode = binding.etDeviceId.text?.toString()?.trim().orEmpty()
            val activationCode = binding.etActivationCode.text?.toString()?.trim().orEmpty()
            val normalizedDeviceCode = if (inputDeviceCode.isBlank()) {
                ""
            } else {
                DeviceIdentityPolicy.normalizeDeviceCode(inputDeviceCode).orEmpty()
            }
            if (inputDeviceCode.isNotBlank() && normalizedDeviceCode.isBlank()) {
                ToastUtils.showShort("设备编号格式无效")
                LogUtils.w("【设备身份】设备初始化已拦截：deviceCode（云端设备编号）格式无效")
                return@setOnClickListener
            }
            if (!DeviceIdentityPolicy.canInitializeWithDeviceCodeOrActivationCode(normalizedDeviceCode, activationCode)) {
                ToastUtils.showShort("请输入一次性激活码")
                LogUtils.w("【设备身份】设备初始化已拦截：未填写 activationCode（一次性激活码）")
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
            NetApi.initDevice(normalizedDeviceCode, activationCode) { code, content ->
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
                            ?: json.getJSONObject("data")?.getString("canonicalDeviceCode")
                            ?: json.getString("deviceCode")
                        val finalDeviceCode = remoteDeviceCode?.takeIf { it.isNotBlank() } ?: normalizedDeviceCode
                        AppConfig.saveDeviceId(finalDeviceCode)
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

        binding.btnApplyIdentity.setOnClickListener {
            val inputDeviceCode = binding.etDeviceId.text?.toString()?.trim().orEmpty()
            val normalizedDeviceCode = if (inputDeviceCode.isBlank()) {
                ""
            } else {
                DeviceIdentityPolicy.normalizeDeviceCode(inputDeviceCode).orEmpty()
            }
            if (inputDeviceCode.isNotBlank() && normalizedDeviceCode.isBlank()) {
                ToastUtils.showShort("候选设备编号格式无效")
                LogUtils.w("【设备身份】身份申请已拦截：candidateDeviceCode（候选设备编号）格式无效")
                return@setOnClickListener
            }

            showLoadingExt("提交身份申请中...")
            NetApi.applyDeviceIdentity(
                candidateDeviceCode = normalizedDeviceCode,
                activationCode = binding.etActivationCode.text?.toString()?.trim().orEmpty(),
                remark = "安卓11上位机初始化页提交"
            ) { code, content ->
                dismissLoadingExt()
                if (code != 0) {
                    binding.tvStatus.text = "身份申请提交失败，请检查网络"
                    ToastUtils.showShort("身份申请提交失败")
                    return@applyDeviceIdentity
                }
                handleIdentityApplyResponse(content)
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
        binding.etActivationCode.isEnabled = !locked
        binding.btnApplyIdentity.isEnabled = !locked
        binding.etDeviceId.setText(stableDeviceCode)
        binding.etDeviceId.hint = if (locked) {
            "设备编号已锁定"
        } else {
            "可选：后台预置设备编号"
        }
        binding.etActivationCode.hint = if (locked) {
            "设备已激活"
        } else {
            "请输入一次性激活码"
        }
    }

    private fun handleIdentityApplyResponse(content: String?) {
        val json = try {
            JSON.parseObject(content)
        } catch (e: Exception) {
            LogUtils.e("【设备身份】身份申请响应解析失败：raw=$content", e)
            null
        }
        if (json == null) {
            binding.tvStatus.text = "身份申请响应解析失败"
            ToastUtils.showShort("身份申请响应解析失败")
            return
        }
        if (json.getIntValue("code") != 200) {
            val message = json.getString("msg") ?: "身份申请失败"
            binding.tvStatus.text = message
            LogUtils.w("【设备身份】身份申请被云端拒绝：code=${json.getIntValue("code")}，msg=$message")
            ToastUtils.showShort(message)
            return
        }

        val data = json.getJSONObject("data")
        val identityStatus = data?.getString("identityStatus").orEmpty()
        val canonicalDeviceCode = data?.getString("canonicalDeviceCode").orEmpty()
        val requestCode = data?.getString("requestCode").orEmpty()
        when (identityStatus) {
            "APPROVED" -> {
                val normalizedDeviceCode = DeviceIdentityPolicy.normalizeDeviceCode(canonicalDeviceCode)
                if (normalizedDeviceCode.isNullOrBlank()) {
                    binding.tvStatus.text = "云端已通过，但正式设备编号为空"
                    LogUtils.e("【设备身份】身份申请已通过但 canonicalDeviceCode（正式云端设备编号）无效：content=$content")
                    return
                }
                PreferenceUtils.saveBooleanPreference(spFileKey, "sp_init_status", true)
                AppConfig.saveDeviceId(normalizedDeviceCode)
                AppConfig.logDeviceIdentity("身份申请通过后", force = true)
                binding.tvStatus.text = "身份已通过，正在进入系统"
                MainActivity.start(this@StartInitActivity)
                finish()
            }
            "REJECTED" -> {
                binding.tvStatus.text = "身份申请已驳回：$requestCode"
                ToastUtils.showShort("身份申请已驳回")
                LogUtils.w("【设备身份】身份申请已驳回：requestCode（申请编号）=$requestCode")
            }
            else -> {
                binding.tvStatus.text = "身份申请待审核：$requestCode"
                ToastUtils.showShort("身份申请已提交，等待后台审核")
                LogUtils.w("【设备身份】身份申请待审核：requestCode（申请编号）=$requestCode")
            }
        }
    }
}
