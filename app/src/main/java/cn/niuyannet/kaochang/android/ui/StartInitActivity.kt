package cn.niuyannet.kaochang.android.ui

import android.os.Bundle
import cn.niuyannet.kaochang.android.base.BaseActivity
import cn.niuyannet.kaochang.android.databinding.ActivityStartInitBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import cn.niuyannet.kaochang.android.detecition.SauceDetectionLauncher
import cn.niuyannet.kaochang.android.utils.dismissLoadingExt
import cn.niuyannet.kaochang.android.utils.showLoadingExt
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.LogUtils
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
        if (PreferenceUtils.getBooleanPreference(spFileKey, "sp_init_status", false)) {
            MainActivity.start(this)
            finish()
            return
        }

        binding = ActivityStartInitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.etDeviceId.setText(AppConfig.getDeviceId())

        binding.btnConfirm.setOnClickListener {
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
            NetApi.initDevice(binding.etDeviceId.text.toString()) { code, content ->
                dismissLoadingExt()
                if (code == 0) {
                    val json = JSON.parseObject(content)
                    if (json.getIntValue("code") == 200) {
                        PreferenceUtils.saveBooleanPreference(spFileKey, "sp_init_status", true)
                        AppConfig.saveDeviceId(binding.etDeviceId.text.toString())
                        MainActivity.start(it.context)
                        finish()
                    } else {
                        ToastUtils.showShort(json.getString("msg"))
                    }
                } else {
                    ToastUtils.showShort("网络服务错误")
                }
            }
        }

        binding.btnStartCheck.setOnClickListener {
            SauceDetectionLauncher.launch(this)
        }
    }
}
