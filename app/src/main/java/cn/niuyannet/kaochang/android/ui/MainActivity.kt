package cn.niuyannet.kaochang.android.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.opengl.GLES11Ext
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.mqtt.KcOrderItem
import cn.niuyannet.kaochang.android.mqtt.MqttProtocol
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.CameraMonitor
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.utils.ConnectManageHelper
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.MaintenanceUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.OrderStateManager
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import com.bumptech.glide.Glide
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.usb.USBMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * 主界面
 */
class MainActivity : OrderActivity(), ICameraStateCallBack {
    companion object {
        private const val DEVICE_STATE_REFRESH_INTERVAL_MS = 15_000L

        init {
            System.loadLibrary("UVCCamera")
        }

        fun start(context: Context?) {
            context?.startActivity(Intent(context, MainActivity::class.java))
        }
    }

    private var runtimeStarted = false
    private val recentIncomingOrderLogAtMs = ConcurrentHashMap<String, Long>()
    private val recentBusyOrderLogAtMs = ConcurrentHashMap<String, Long>()
    private var deviceStateRefreshJob: Job? = null
    private var lastHomeDisplayLogSignature = ""
    private val homeUiRefreshCallback: (String) -> Unit = { _ ->
        if (!isFinishing && !isDestroyed) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    setDataToView()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrderStateManager.performFullSpCleanup() // 执行一次全量过期 SP 清理
        initView()
    }

    override fun initData() {
        initAdsData()
        KaoPanHelper.init()

        val hasValidCachedConfig = AppConfig.hasValidDeviceConfig()
        if (hasValidCachedConfig) {
            setDataToView()
            startRuntimeAfterConfigLoaded("cached", syncStatus = false)
        }

        loadDeviceInfo { success ->
            if (success) {
                if (!isFinishing && !isDestroyed) {
                    setDataToView()
                }
                startRuntimeAfterConfigLoaded("remote", syncStatus = true)
            } else if (!hasValidCachedConfig) {
                LogUtils.w("【启动流程】设备配置拉取失败且本地无有效配置，跳过算法启动与状态同步，避免默认配置误上报")
            }
        }
    }

    private fun startRuntimeAfterConfigLoaded(source: String, syncStatus: Boolean) {
        if (runtimeStarted && !syncStatus) {
            LogUtils.d("【启动流程】运行链路已启动，忽略重复启动请求：source=$source")
            return
        }
        if (!AppConfig.hasValidDeviceConfig()) {
            LogUtils.w("【启动流程】设备配置仍无效，拒绝启动算法和状态同步：source=$source")
            return
        }
        val correctedByBusinessStart = KaoChangAlgorithm.recoverBusinessStartIfNeeded("startRuntimeAfterConfigLoaded:$source")
        val correctedConfig = AppConfig.getAppConfig()
        if (correctedByBusinessStart) {
            LogUtils.d(
                "【启动流程】启动纠偏：检测到已进入营业时间，纠正本地旧状态 | " +
                    "source=$source，onlineStatus（设备营业状态）=${correctedConfig.onlineStatus}，" +
                    "isEnable（烤肠算法开关）=${correctedConfig.isEnable}，" +
                    "restStatusSource（休息中来源）=${AppConfig.restStatusSourceText(correctedConfig.restStatusSource)}"
            )
        }

        val shouldStartRuntime = !runtimeStarted
        if (shouldStartRuntime) {
            runtimeStarted = true
            LogUtils.d("【启动流程】设备配置有效，开始启动算法与状态同步：source=$source")
            KaoChangAlgorithm.restoreRoastAlgorithmState()
            KaoChangAlgorithm.runServer()
        }
        if (syncStatus) {
            val syncReason = if (correctedByBusinessStart) "营业开始纠偏后再同步" else "远端配置后正常同步"
            val currentConfig = AppConfig.getAppConfig()
            LogUtils.d(
                "【启动流程】准备同步设备营业状态：source=$source，syncReason=$syncReason，" +
                    "onlineStatus（设备营业状态）=${currentConfig.onlineStatus}，" +
                    "isEnable（烤肠算法开关）=${currentConfig.isEnable}，" +
                    "restStatusSource（休息中来源）=${AppConfig.restStatusSourceText(currentConfig.restStatusSource)}"
            )
            syncDeviceStatusToServer(retryDelaysMs = listOf(3000L))
        }
        if (shouldStartRuntime) ConnectManageHelper.lunStatus {
            if (!isFinishing && !isDestroyed) {
                setDataToView()
                mqttMessage()
            }
            DataManagementAPI.getDeviceInfo()
            DataManagementAPI.uploadDataToServer()
            startDeviceStateRefreshLoop()
        }
    }

    override fun onResume() {
        super.onResume()
        CameraMonitor.instance.startService(applicationContext)
        HomeUiRefreshBridge.register(homeUiRefreshCallback)
        if (runtimeStarted) {
            setDataToView()
            startDeviceStateRefreshLoop()
        }
    }

    override fun onStop() {
        super.onStop()
        HomeUiRefreshBridge.unregister(homeUiRefreshCallback)
        deviceStateRefreshJob?.cancel()
        deviceStateRefreshJob = null
        CameraMonitor.instance.close()
        clearCamera()
    }

    /**
     * 接收服务端下发的 MQTT 订单消息。
     * 接入了基于 OrderStateManager 的强幂等防重墙判定，绝不让同一个 OrderNo 执行出餐两次。
     */
    private fun mqttMessage() {
        VMMqttHelper.registerMessageListener(this::class.java.simpleName) { data ->
            // MQTT 回调运行在子线程，UI 操作（Dialog、View）必须切到主线程
            runOnUiThread {
                when (data.action) {
                    MqttProtocol.ACTION_ORDER_EVENT -> {
                        val orderMessage = data.content
                        val orderNo = extractOrderNo(orderMessage)
                        if (orderNo.isBlank()) {
                            LogUtils.e("【订单防重】非法订单消息，已拒绝执行 | deviceId=${AppConfig.getDeviceId()} | payload摘要=$orderMessage")
                            return@runOnUiThread
                        }
                        logIncomingOrder(orderMessage)
                        val moveStatus = AppConfig.getAppConfig().moveStatus
                        val onlineStatus = AppConfig.getAppConfig().onlineStatus
                        if (moveStatus == 2 || moveStatus == 5 || onlineStatus == 4) {
                            logBusyOrderRejected(orderNo, moveStatus, onlineStatus)
                            OrderStateManager.recordBusyIgnored(orderNo)
                        } else {
                            if (!OrderStateManager.checkAndAcceptOrder(orderNo)) {
                                return@runOnUiThread
                            }

                            LogUtils.d(
                                "【订单流转】收到新的顾客下单请求，准备进入出餐流程：" +
                                    "orderNo=${orderNo.ifBlank { "未知" }}, deviceId=${AppConfig.getDeviceId()}, " +
                                    "onlineStatus=$onlineStatus, moveStatus=$moveStatus"
                            )
                            AppConfig.getAppConfig().onlineStatus = 4
                            AppConfig.saveAppConfig(AppConfig.getAppConfig())
                            syncDeviceStatusToServer(retryDelaysMs = listOf(2000L))
                            showProcessingDialog()
                            setDataToView()
                            purchaseOrder(orderMessage)
                        }
                    }
                    MqttProtocol.ACTION_SERVICE_STATE -> handleServiceStateMessage(data.content)
                }
            }
        }
    }

    private fun handleServiceStateMessage(content: String) {
        val json = try {
            JSON.parseObject(content)
        } catch (e: Exception) {
            LogUtils.e("【MQTT业务】设备状态同步消息解析失败：error=${e.message}, raw=$content")
            return
        }
        when (json.getString("messageType").orEmpty()) {
            MqttProtocol.TYPE_RUNTIME_CONTROL -> handleRuntimeControlMessage(content)
            MqttProtocol.TYPE_CONCURRENCY_CONTROL -> handleConcurrencyControlMessage(content)
            else -> LogUtils.w(
                "【MQTT业务】收到未处理的 action=2 消息，已忽略：" +
                    "messageType=${json.getString("messageType").orEmpty().ifBlank { "unknown" }}"
            )
        }
    }

    private fun handleRuntimeControlMessage(content: String) {
        val result = DataManagementAPI.applyRemoteRuntimeControl(
            content = content,
            source = MqttProtocol.SOURCE_MQTT_ACTION_2
        )
        if (result.controlSeq > 0) {
            val ackOk = SendServerHelper.publishRuntimeControlAck(
                SendServerHelper.RuntimeControlAckPayload(
                    controlSeq = result.controlSeq,
                    applied = result.applied,
                    reason = result.reason,
                    onlineStatus = result.onlineStatus,
                    supplyStatus = result.supplyStatus,
                    scanBlockStatus = result.scanBlockStatus,
                    inspectionMode = result.inspectionMode,
                    isEnable = result.isEnable,
                    restStatusSource = result.restStatusSource
                )
            )
            if (ackOk) {
                LogUtils.d(
                    "【MQTT运行时控制】Ack 已发送：" +
                        "controlSeq=${result.controlSeq}，applied=${result.applied}，reason=${result.reason}"
                )
            } else {
                LogUtils.w(
                    "【MQTT运行时控制】Ack 发送失败：" +
                        "controlSeq=${result.controlSeq}，applied=${result.applied}，reason=${result.reason}"
                )
            }
        }
        LogUtils.d(
            "【MQTT运行时控制】处理完成：" +
                "controlSeq=${result.controlSeq}，applied=${result.applied}，changed=${result.changed}，reason=${result.reason}，" +
                "onlineStatus（设备营业状态）=${result.onlineStatus}，" +
                "supplyStatus（补货状态）=${result.supplyStatus}，" +
                "scanBlockStatus（前台屏蔽状态）=${result.scanBlockStatus}，" +
                "inspectionMode（检修模式）=${result.inspectionMode}，" +
                "isEnable（烤肠算法开关）=${result.isEnable}，" +
                "restStatusSource（休息中来源）=${AppConfig.restStatusSourceText(result.restStatusSource)}"
        )
        if (result.applied && result.changed) {
            SendServerHelper.publishServiceUpdateStatus()
            setDataToView()
        }
        if (result.applied) {
            MaintenanceUiRefreshBridge.requestRefresh(
                "runtime_control:${result.reason}:seq=${result.controlSeq}"
            )
        }
    }

    private fun handleConcurrencyControlMessage(content: String) {
        val result = DataManagementAPI.applyRemoteConcurrencyControl(
            content = content,
            source = MqttProtocol.SOURCE_MQTT_ACTION_2
        )
        if (result.controlSeq > 0) {
            val ackOk = SendServerHelper.publishConcurrencyControlAck(
                SendServerHelper.ConcurrencyControlAckPayload(
                    controlSeq = result.controlSeq,
                    applied = result.applied,
                    changed = result.changed,
                    reason = result.reason,
                    modeType = result.modeType,
                    transitionMode = result.transitionMode
                )
            )
            if (ackOk) {
                LogUtils.d(
                    "【MQTT并发控制】Ack 已发送：" +
                        "controlSeq=${result.controlSeq}，applied=${result.applied}，changed=${result.changed}，reason=${result.reason}"
                )
            } else {
                LogUtils.w(
                    "【MQTT并发控制】Ack 发送失败：" +
                        "controlSeq=${result.controlSeq}，applied=${result.applied}，changed=${result.changed}，reason=${result.reason}"
                )
            }
        }
        LogUtils.d(
            "【MQTT并发控制】处理完成：" +
                "controlSeq=${result.controlSeq}，applied=${result.applied}，changed=${result.changed}，reason=${result.reason}，" +
                "modeType（并发模式）=${result.modeType}，transitionMode（并发切换过渡态）=${result.transitionMode}，" +
                "overrodeConflictingTransition=${result.overrodeConflictingTransition}"
        )
        if (result.applied && result.changed) {
            SendServerHelper.publishServiceUpdateStatus("runtime_state:remote_concurrency_control")
            setDataToView()
        }
        if (result.applied) {
            MaintenanceUiRefreshBridge.requestRefresh(
                "concurrency_control:${result.reason}:seq=${result.controlSeq}"
            )
        }
    }

    private fun extractOrderNo(orderMessage: String): String {
        return try {
            JSON.parseObject(orderMessage).getString("order_no").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 设备忙时可能短时间重复收到相同订单或多个订单，这里按订单号做短时间节流，
     * 避免主日志被同类“忙拒单”提示刷满，同时保留足够的定位参数。
     */
    private fun logBusyOrderRejected(orderNo: String, moveStatus: Int, onlineStatus: Int) {
        val logKey = if (orderNo.isBlank()) "unknown" else orderNo
        val now = System.currentTimeMillis()
        recentBusyOrderLogAtMs.entries.removeIf { now - it.value >= 5 * 60_000L }
        val lastLogAt = recentBusyOrderLogAtMs[logKey]
        if (lastLogAt != null && now - lastLogAt < 10_000L) {
            return
        }
        recentBusyOrderLogAtMs[logKey] = now
        LogUtils.w(
            "【订单流转】设备当前正忙，拒绝接入新的下单请求：" +
                "orderNo=${orderNo.ifBlank { "未知" }}, deviceId=${AppConfig.getDeviceId()}, " +
                "onlineStatus=$onlineStatus, moveStatus=$moveStatus，后续应走退款或重试"
        )
    }

    private fun logIncomingOrder(orderMessage: String) {
        try {
            val json = JSON.parseObject(orderMessage)
            val orderNo = json.getString("order_no").orEmpty()
            if (orderNo.isNotBlank()) {
                val now = System.currentTimeMillis()
                recentIncomingOrderLogAtMs.entries.removeIf { now - it.value >= 5 * 60_000L }
                val lastLogAt = recentIncomingOrderLogAtMs[orderNo]
                if (lastLogAt != null && now - lastLogAt < 30_000L) {
                    return
                }
                recentIncomingOrderLogAtMs[orderNo] = now
            }
            val goodsList = JSON.parseArray(json.getString("goods_items"), KcOrderItem::class.java)
            val totalQuantity = goodsList.sumOf { it.quantity }
            val itemSummary = goodsList.joinToString(" | ") {
                "itemId=${it.id}, productId=${it.productId}, tasteCode=${it.tasteCode}, quantity=${it.quantity}"
            }
            LogUtils.d(
                "【订单防漏跟踪】接单成功：orderNo=$orderNo, deviceId=${AppConfig.getDeviceId()}, " +
                    "itemCount=${goodsList.size}, totalQuantity=$totalQuantity, items=[$itemSummary]"
            )
        } catch (e: Exception) {
            LogUtils.w("【订单防漏跟踪】接单日志解析失败：raw=$orderMessage, error=${e.message}")
        }
    }

    private fun initView() {
        binding.refreshButton.setOnClickListener {
            loadDeviceInfo()
        }
        binding.configButton.setOnClickListener {
            goToSetting()
        }
        binding.tvGly.setOnClickListener {
            goToSetting()
        }
    }

    private fun goToSetting() {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            hint = "请输入密码"
        }
        AlertDialog.Builder(this)
            .setTitle("设备管理密码")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val password = editText.text.toString()
                if (password == "123") {
                    SettingActivity.start(this)
                } else {
                    ToastUtils.showShort("密码错误")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    override fun setDataToView() {
        val appConfigBean = AppConfig.getAppConfig()
        val isInspectionMode = appConfigBean.inspectionMode == 1
        val isScanBlocked = appConfigBean.scanBlockStatus == 1
        val isSupplySoldOut =
            appConfigBean.status == 1 &&
                appConfigBean.onlineStatus == 1 &&
                appConfigBean.supplyStatus == 1
        if (appConfigBean.status == 1) {
            when {
                isInspectionMode -> {
                    binding.deviceStatusText.text = "检修中"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_maintaining))
                }
                isScanBlocked -> {
                    binding.deviceStatusText.text = "前台已屏蔽"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_working))
                }
                else -> when (appConfigBean.onlineStatus) {
                0 -> {
                    binding.deviceStatusText.text = "未启用"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_offline))
                }
                1 -> {
                    if (isSupplySoldOut) {
                        binding.deviceStatusText.text = "售罄/待补货"
                        binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_resting))
                    } else {
                        binding.deviceStatusText.text = "运营中"
                        binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_operating))
                    }
                }
                2 -> {
                    binding.deviceStatusText.text = "休息中"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_resting))
                }
                3 -> {
                    binding.deviceStatusText.text = "维护中"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_maintaining))
                }
                4 -> {
                    binding.deviceStatusText.text = "正在出烤肠"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_working))
                }
            }
            }
        } else {
            binding.deviceStatusText.text = "离线"
            binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_offline))
        }

        if (isInspectionMode) {
            binding.tvScanTip.text = "设备检修中"
        } else if (isScanBlocked) {
            binding.tvScanTip.text = "当前暂停扫码下单"
        } else if (appConfigBean.onlineStatus == 3) {
            binding.tvScanTip.text = "设备维护中"
        } else if (isSupplySoldOut) {
            binding.tvScanTip.text = "售罄/补货中"
        } else {
            if (appConfigBean.available > 0) {
                val leftTime = appConfigBean.available
                if (leftTime >= 60_000) {
                    val minutes = ceil(appConfigBean.available / 60000.0).toInt()
                    binding.tvScanTip.text = "预计烤制 ${minutes} 分钟"
                } else {
                    val seconds = ceil(appConfigBean.available / 1000.0).toInt()
                    binding.tvScanTip.text = "预计烤制 ${seconds} 秒"
                }
            } else {
                binding.tvScanTip.text = "扫码下单 纯肉烤肠"
            }
        }

        binding.deviceNameText.text = appConfigBean.deviceName + "(" + appConfigBean.deviceCode + ")"
        if (appConfigBean.status == 1 &&
            AppConfig.getAppConfig().onlineStatus == 1 &&
            !isSupplySoldOut &&
            !isScanBlocked &&
            !isInspectionMode
        ) {
            binding.qrCodeImage.visibility = View.VISIBLE
            Glide.with(this).load(AppConfig.getAppConfig().qcodeURL).into(binding.qrCodeImage)
        } else {
            binding.qrCodeImage.visibility = View.GONE
        }

        val statusText = when (appConfigBean.status) {
            0 -> "0（未启用/离线）"
            1 -> "1（启用/在线）"
            else -> "${appConfigBean.status}（未知）"
        }
        val onlineStatusText = when (appConfigBean.onlineStatus) {
            0 -> "0（未启用）"
            1 -> "1（运营中）"
            2 -> "2（休息中）"
            3 -> "3（维护中）"
            4 -> "4（工作中/正在出餐）"
            else -> "${appConfigBean.onlineStatus}（未知）"
        }
        val supplyStatusText = when (appConfigBean.supplyStatus) {
            0 -> "0（正常）"
            1 -> "1（售罄/待补货）"
            else -> "${appConfigBean.supplyStatus}（未知）"
        }
        val scanBlockStatusText = when (appConfigBean.scanBlockStatus) {
            0 -> "0（正常展示）"
            1 -> "1（隐藏二维码并禁止下单/支付）"
            else -> "${appConfigBean.scanBlockStatus}（未知）"
        }
        val inspectionModeText = when (appConfigBean.inspectionMode) {
            0 -> "0（正常）"
            1 -> "1（检修中）"
            else -> "${appConfigBean.inspectionMode}（未知）"
        }
        val qrVisible = binding.qrCodeImage.visibility == View.VISIBLE
        val displaySignature = listOf(
            appConfigBean.status,
            appConfigBean.onlineStatus,
            appConfigBean.supplyStatus,
            appConfigBean.scanBlockStatus,
            appConfigBean.inspectionMode,
            binding.tvScanTip.text.toString(),
            qrVisible
        ).joinToString("|")
        if (lastHomeDisplayLogSignature != displaySignature) {
            lastHomeDisplayLogSignature = displaySignature
            LogUtils.d(
                "【首页展示】刷新首页文案：" +
                    "status（设备启用状态）=$statusText，" +
                    "onlineStatus（设备营业状态）=$onlineStatusText，" +
                    "supplyStatus（补货状态）=$supplyStatusText，" +
                    "scanBlockStatus（前台屏蔽状态）=$scanBlockStatusText，" +
                    "inspectionMode（检修模式）=$inspectionModeText，" +
                    "available（二维码页预计时间）=${appConfigBean.available}，" +
                    "availableCountdownLatched（二维码页预计时间锁存标记）=${appConfigBean.availableCountdownLatched}，" +
                    "tvScanTip（首页提示文案）=${binding.tvScanTip.text}，" +
                    "qrVisible（二维码是否展示）=$qrVisible"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        HomeUiRefreshBridge.unregister(homeUiRefreshCallback)
        VMMqttHelper.unregisterMessageListener(this::class.java.simpleName) // 组件解绑
        CameraMonitor.instance.close()
        clearCamera()
        ConnectManageHelper.disConnect()
        KaoChangAlgorithm.stopServer()
    }

    private var mCameraClient: MultiCameraClient? = null
    private val mCameraMap = ConcurrentHashMap<Int, MultiCameraClient.ICamera>()

    private fun initCameraData() {
        mCameraClient = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                generateCamera(this@MainActivity, device).apply {
                    mCameraMap[device.deviceId] = this
                    onCameraAttached(this)
                }
                mCameraClient?.requestPermission(device)
            }

            override fun onDetachDec(device: UsbDevice?) {
                mCameraMap.remove(device?.deviceId)?.apply {
                    setUsbControlBlock(null)
                    onCameraDetached(this)
                }
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                ctrlBlock ?: return
                mCameraMap[device.deviceId]?.apply {
                    setUsbControlBlock(ctrlBlock)
                    onCameraConnected(this)
                }
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                mCameraMap[device?.deviceId]?.apply {
                    onCameraDisConnected(this)
                }
            }

            override fun onCancelDev(device: UsbDevice?) {
                mCameraMap[device?.deviceId]?.apply {
                    onCameraDisConnected(this)
                }
            }
        })
        mCameraClient?.register()
    }

    private fun ensureUvcCameraClient() {
        if (mCameraClient == null) {
            initCameraData()
        }
    }

    private fun findFirstCamera(callback: ICameraStateCallBack?): Pair<Boolean, MultiCameraClient.ICamera?> {
        ensureUvcCameraClient()
        try {
            mCameraMap.values.first().let { camera ->
                return if (!camera.isCameraOpened()) {
                    camera.setCameraStateCallBack(callback)
                    camera.openCamera(SurfaceTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES), getCameraRequest())
                    Pair(true, camera)
                } else {
                    Pair(false, camera)
                }
            }
        } catch (_: Exception) {
        }
        return Pair(false, null)
    }

    private fun generateCamera(ctx: Context, device: UsbDevice): MultiCameraClient.ICamera {
        return CameraUVC(ctx, device)
    }

    private fun onCameraAttached(camera: MultiCameraClient.ICamera) {
        LogUtils.d("【UVC相机】检测到摄像头接入：deviceName=${camera.device.deviceName}")
    }

    private fun onCameraConnected(camera: MultiCameraClient.ICamera) {
        LogUtils.d("【UVC相机】摄像头连接成功：deviceName=${camera.device.deviceName}")
    }

    private fun onCameraDetached(camera: MultiCameraClient.ICamera) {
        camera.closeCamera()
        LogUtils.d("【UVC相机】摄像头已断开：deviceName=${camera.device.deviceName}")
    }

    private fun onCameraDisConnected(camera: MultiCameraClient.ICamera) {
        camera.closeCamera()
    }

    private fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .create()
    }

    private fun clearCamera() {
        mCameraMap.values.forEach {
            LogUtils.d("【UVC相机】关闭摄像头实例：deviceId=${it.device.deviceId}")
            it.closeCamera()
        }
        mCameraMap.clear()
        mCameraClient?.unRegister()
        mCameraClient?.destroy()
        mCameraClient = null
        LogUtils.d("【UVC相机】已清空本地摄像头缓存并销毁客户端")
    }

    private fun startDeviceStateRefreshLoop() {
        if (deviceStateRefreshJob?.isActive == true) {
            return
        }
        deviceStateRefreshJob = lifecycleScope.launch {
            while (isActive && !isFinishing && !isDestroyed) {
                delay(DEVICE_STATE_REFRESH_INTERVAL_MS)
                if (isFinishing || isDestroyed) {
                    break
                }
                DataManagementAPI.refreshHomeDeviceState { changed ->
                    if (changed && !isFinishing && !isDestroyed) {
                        runOnUiThread {
                            setDataToView()
                        }
                    }
                }
            }
        }
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        LogUtils.d("【UVC相机】摄像头状态变更：state=${code.name}, message=$msg")
    }
}
