package cn.niuyannet.kaochang.android.ui

import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import cn.niuyannet.kaochang.android.base.BaseActivity
import cn.niuyannet.kaochang.android.databinding.MainActivityBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils

abstract class LoadNetActivity : BaseActivity() {
    lateinit var binding: MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        binding = MainActivityBinding.inflate(layoutInflater)
    }

    fun loadDeviceInfo(onLoaded: ((Boolean) -> Unit)? = null) {
        binding.mainContent.visibility = View.GONE
        binding.loadingLayout.visibility = View.VISIBLE
        binding.emptyLayout.visibility = View.GONE

        NetApi.getDeviceInfo(AppConfig.getDeviceId()) { code, content ->
            if (code != 0) {
                showLoadFailure("网络异常", onLoaded)
                return@getDeviceInfo
            }

            val json = try {
                JSON.parseObject(content)
            } catch (e: Exception) {
                LogUtils.e("[启动配置] 解析 getDeviceInfo 响应失败，raw=$content", e)
                null
            }

            if (json == null) {
                showLoadFailure("配置解析失败", onLoaded)
                return@getDeviceInfo
            }

            if (json.getIntValue("code") != 200) {
                val message = json.getString("msg") ?: "获取配置失败"
                LogUtils.w("[启动配置] getDeviceInfo 业务失败：code=${json.getIntValue("code")}, raw=$content")
                showLoadFailure(message, onLoaded)
                return@getDeviceInfo
            }

            val previousOnlineStatus = AppConfig.getAppConfig().onlineStatus
            val previousIsEnable = AppConfig.getAlgorithmEnableFlag()
            val previousRestStatusSource = AppConfig.normalizeRestStatusSource(AppConfig.getAppConfig().restStatusSource)
            val mergeResult = AppConfig.mergeRemoteConfig(json.getJSONObject("data"), content, "启动配置")
            if (mergeResult.applied) {
                DataManagementAPI.handleRemoteOnlineStatusTransition(
                    previousOnlineStatus = previousOnlineStatus,
                    previousIsEnable = previousIsEnable,
                    previousRestStatusSource = previousRestStatusSource,
                    mergedConfig = AppConfig.getAppConfig(),
                    source = "loadDeviceInfo"
                )
                binding.mainContent.visibility = View.VISIBLE
                binding.loadingLayout.visibility = View.GONE
                binding.emptyLayout.visibility = View.GONE
                if (mergeResult.usedLocalFallback) {
                    ToastUtils.showShort("远端配置不完整，已继续使用本地配置")
                }
                onLoaded?.invoke(true)
            } else {
                showLoadFailure("配置无效", onLoaded)
            }
        }
    }

    private fun showLoadFailure(message: String, onLoaded: ((Boolean) -> Unit)?) {
        val hasFallback = AppConfig.hasValidDeviceConfig()
        binding.loadingLayout.visibility = View.GONE

        if (hasFallback) {
            binding.mainContent.visibility = View.VISIBLE
            binding.emptyLayout.visibility = View.GONE
            LogUtils.w("[启动配置] $message，已回退到本地有效配置")
            ToastUtils.showShort("$message，已继续使用本地配置")
            onLoaded?.invoke(true)
            return
        }

        binding.mainContent.visibility = View.GONE
        binding.emptyLayout.visibility = View.VISIBLE
        binding.tvEmptyText.text = message
        ToastUtils.showShort(message)
        onLoaded?.invoke(false)
    }
}
