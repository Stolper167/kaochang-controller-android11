package cn.niuyannet.kaochang.android.ui

import android.Manifest
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.mqtt.KcOrderItem
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.utils.DeviceStateText
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 订单履约流程。
 *
 * 这里负责：
 * - 接收已下发到上位机的订单内容
 * - 串行执行每一根烤肠的出餐
 * - 在动作开始/完成/失败时向后端上报订单事件
 * - 在订单结束时把设备营业状态从“工作中”收口回“运营中/维护中”
 */
abstract class OrderActivity: BaseSauceDetection() {

    private companion object {
        const val ORDER_EVENT_STATUS_PICKING = 1
        const val ORDER_EVENT_STATUS_TAKEN = 2
        const val ORDER_EVENT_STATUS_DISCARDED_BY_TIMEOUT = 3
        const val ORDER_EVENT_STATUS_FAILED = 5
    }

    private data class DeliveryResult(
        val success: Boolean,
        val completedCount: Int,
        val takenCount: Int,
        val discardedCount: Int
    )

    private var processingDialog: MaterialDialog? = null
    private var completedDialog: MaterialDialog? = null
    private var countDownTimer: CountDownTimer? = null

    private fun orderEventStatusText(status: Int): String = when (status) {
        ORDER_EVENT_STATUS_PICKING -> "1（开始夹取）"
        ORDER_EVENT_STATUS_TAKEN -> "2（单次履约完成：顾客已取走）"
        ORDER_EVENT_STATUS_DISCARDED_BY_TIMEOUT -> "3（已出餐但顾客未取，系统已丢弃）"
        ORDER_EVENT_STATUS_FAILED -> "5（单次履约失败）"
        else -> "${status}（未知）"
    }

    private fun onlineStatusText(status: Int): String = when (status) {
        0 -> "0（未启用）"
        1 -> "1（运营中）"
        2 -> "2（休息中）"
        3 -> "3（维护中）"
        4 -> "4（工作中/正在出餐）"
        else -> "${status}（未知）"
    }

    private val REQUEST_CODE_PERMISSIONS: Int = 10
    private val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.CAMERA
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化对话框
        initDialogs()
    }

    private fun initDialogs() {
        // 初始化处理中对话框
        processingDialog = MaterialDialog(this).apply {
            customView(view = LayoutInflater.from(context).inflate(R.layout.dialog_processing, null))
            cancelable(false)
            cancelOnTouchOutside(false)
        }
        
        // 初始化完成对话框
        completedDialog = MaterialDialog(this).apply {
            customView(view = LayoutInflater.from(context).inflate(R.layout.dialog_completed, null))
            cancelable(false)
            cancelOnTouchOutside(false)
        }
    }
    
    /**
     * 由子类实现的 UI 刷新钩子。
     * 订单流程结束、二维码需要重新显示时会回调这里。
     */
    open fun setDataToView() {}

    /**
     * 显示正在出烤肠对话框
     */
     fun showProcessingDialog() {
        processingDialog?.show()
    }

    private fun updateProcessingDialog(current: Int, total: Int) {
        processingDialog?.view?.findViewById<TextView>(R.id.tv_num)?.text = "$current/$total"
    }

    /**
     * 隐藏正在出烤肠对话框
     */
     fun hideProcessingDialog() {
        processingDialog?.dismiss()
    }
    
    /**
     * 显示烤肠已出对话框，5 秒后自动关闭
     */
     fun showCompletedDialog() {
        completedDialog?.show()
        
        // 开始倒计时
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                completedDialog?.view?.findViewById<TextView>(R.id.tv_countdown)?.text = 
                    "${seconds}秒后自动关闭"
            }
            
            override fun onFinish() {
                hideCompletedDialog()
            }
        }.start()
    }
    
    /**
     * 隐藏烤肠已出对话框
     */
     fun hideCompletedDialog() {
        countDownTimer?.cancel()
        completedDialog?.dismiss()
    }
    override fun onResume() {
        super.onResume()
        LogUtils.d("【订单流转】订单页重新进入前台")
        // 如果已经有权限，重新启动相机
//        if (allPermissionsGranted() &&SauceDetectionUitls.cameraProvider == null) {
//            LogUtils.d("order 重新启动相机")
//            startCamera()
//        }
    }
    override fun onPause() {
        super.onPause()
         // 释放相机资源
//        if (SauceDetectionUitls.cameraProvider != null) {
//            LogUtils.d("order 閲婃斁鐩告満")
//            SauceDetectionUitls.cameraProvider!!.unbindAll()
//            SauceDetectionUitls.cameraProvider=null
//        }
    }
    override fun onDestroy() {
        super.onDestroy()
        // 娓呯悊璧勬簮
        countDownTimer?.cancel()
        processingDialog?.dismiss()
        completedDialog?.dismiss()

//        if (SauceDetectionUitls.cameraProvider != null) {
//            SauceDetectionUitls.cameraProvider!!.unbindAll()
//            SauceDetectionUitls.cameraProvider=null
//            LogUtils.d("order 销毁相机资源")
//        }
    }

    /**
     * 执行整单履约。
     * 一个订单可能包含多个商品项，每个商品项又可能包含多根烤肠，这里按根串行处理。
     */
    fun purchaseOrder(orderMessage:String) {
        val safeOrderNo = try { com.alibaba.fastjson.JSON.parseObject(orderMessage).getString("order_no") ?: "" } catch (_: Exception) { "" }
        cn.niuyannet.kaochang.android.services.SelfCleanManager.beginOrderTracking(safeOrderNo)

            lifecycleScope.launch(Dispatchers.IO) {
                try {

                // 最多等待机械臂空闲 240 秒
                val maxWaitTime = 240_000L
                val checkInterval = 1000L
                var waited = 0L

                // 运动状态不为 0 时，说明机械臂仍在忙碌。
                // moveStatus 是本地机械动作状态，不是设备营业状态：
                // 0 = 空闲，2 = 正在执行取肠/出餐动作
                var appState = AppConfig.getAppConfig().moveStatus
                while (appState != 0 && waited < maxWaitTime) {
                    delay(checkInterval)
                    LogUtils.d("【订单流转】等待机械臂空闲中：currentMoveStatus=$appState, waitedMs=$waited")
                    appState = AppConfig.getAppConfig().moveStatus
                    waited += checkInterval
                }

                if (appState != 0) {
                    LogUtils.w("【订单流转】等待机械臂空闲超时，放弃本次取肠")
                }
                else {
                    // 机械臂开始执行当前订单，本地动作状态置为 2。
                    // 只有 moveStatus=2 才允许后续继续执行单根出餐。
                    appState = 2
                    AppConfig.getAppConfig().moveStatus = appState
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                }

                val json = JSON.parseObject(orderMessage)
                val orderNo = json.getString("order_no")
                val goodsList =
                    JSON.parseArray(json.getString("goods_items"), KcOrderItem::class.java)
                // 遍历商品列表
                val totalCount = goodsList.sumOf { it.quantity }
                val orderSummary = goodsList.joinToString(" | ") {
                    "itemId=${it.id}, productId=${it.productId}, tasteCode=${it.tasteCode}, quantity=${it.quantity}"
                }
                LogUtils.d(
                    "【订单流转】收到新的顾客下单请求：orderNo=$orderNo, itemCount=${goodsList.size}, totalQuantity=$totalCount, items=[$orderSummary]"
                )
                var completedTotal = 0
                var takenTotal = 0
                var discardedTotal = 0
                var orderCompleted = true
                for (item in goodsList) {
                    val result = handleOrderItemDelivery(
                        orderNo = orderNo,
                        item = item,
                        totalCount = totalCount,
                        completedBase = completedTotal
                    )
                    completedTotal += result.completedCount
                    takenTotal += result.takenCount
                    discardedTotal += result.discardedCount
                    if (!result.success) {
                        orderCompleted = false
                        break
                    }
                }
                orderCompleted = orderCompleted && completedTotal == totalCount
                LogUtils.d(
                    "【订单流转】整单处理结束：" +
                        "orderNo=$orderNo，completedTotal=$completedTotal/$totalCount，" +
                        "takenTotal=$takenTotal，discardedTotal=$discardedTotal，orderCompleted=$orderCompleted"
                )
                cn.niuyannet.kaochang.android.services.SelfCleanManager.finishOrderTracking(orderNo, orderCompleted)
                if (orderCompleted) { cn.niuyannet.kaochang.android.utils.OrderStateManager.markAsFinished(orderNo) }

                // 当前订单动作结束后，本地动作状态恢复为空闲。
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                withContext(Dispatchers.Main) {
                    val config = AppConfig.getAppConfig()
                    val status = config.onlineStatus
                    // 订单动作结束后，如果设备仍停留在 4（工作中），主动收口到 1（运营中）或 3（维护中）。
                    if (status == 4){
                        config.onlineStatus = if (orderCompleted && config.errorStatus == 0) 1 else 3
                    }
                    AppConfig.saveAppConfig(config)
                    // 这里上报的是设备营业状态（onlineStatus），不是订单事件 status。
                    // 订单结束后用 MQTT 轻量补发设备运行态，降低“假工作中”的概率。
                    syncDeviceStatusToServer(retryDelaysMs = listOf(2000L, 6000L))
                    // 订单结束后立即同步烤盘状态和库存到云端，保证扫码端看到实时数据。
                    DataManagementAPI.uploadDataToServer()
                    hideProcessingDialog()
                    if (orderCompleted) {
                        showCompletedDialog()
                        if (discardedTotal > 0) {
                            Toast.makeText(
                                this@OrderActivity,
                                "订单处理完成，其中${discardedTotal}根顾客超时未取，系统已丢弃",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Toast.makeText(this@OrderActivity, "订单未完成，请联系维护人员", Toast.LENGTH_LONG).show()
                    }
                    // 鏄剧ずQRCode
                    setDataToView()
                }
            } finally {
                cn.niuyannet.kaochang.android.services.SelfCleanManager.clearOrderTrackingIfMatches(safeOrderNo)
                cn.niuyannet.kaochang.android.utils.OrderStateManager.releaseProcessing(safeOrderNo)
            }
        }
    }

    /**
     * 执行单个商品项的逐根出餐。
     *
     * 注意：
     * - completedCount（已完成根数）用于控制当前商品项还要不要继续出下一根；
     * - takenCount（顾客已取走根数）只统计真正被顾客取走的数量；
     * - DISCARDED（已丢弃）会作为“已处理完成但顾客未取”的独立状态继续推进后续数量；
     * - DELIVERY_NOT_CONFIRMED / ERROR 才会终止当前商品项并上报失败。
     */
    private suspend fun handleOrderItemDelivery(
        orderNo: String,
        item: KcOrderItem,
        totalCount: Int,
        completedBase: Int
    ): DeliveryResult {
        var completedCount = 0
        var takenCount = 0
        var discardedCount = 0
        var attemptCount = 0
        val maxAttempts = item.quantity * 3

        while (completedCount < item.quantity && attemptCount < maxAttempts) {
            attemptCount++
            withContext(Dispatchers.Main) {
                showProcessingDialog()
                updateProcessingDialog(completedBase + completedCount + 1, totalCount)
            }
            val currentIndex = completedBase + completedCount + 1
            LogUtils.d(
                "【订单流转】开始处理单根烤肠：orderNo=$orderNo, itemId=${item.id}, productId=${item.productId}, " +
                    "tasteCode=${item.tasteCode}, current=$currentIndex/$totalCount, itemProgress=${completedCount + 1}/${item.quantity}, attempt=$attemptCount/$maxAttempts"
            )
            val currentMoveStatus = AppConfig.getAppConfig().moveStatus
            if (currentMoveStatus != 2) {
                LogUtils.w(
                    "【订单流转】终止当前出餐：机械动作状态不满足出餐条件，" +
                        "moveStatus（机械动作状态）=${DeviceStateText.moveStatus(currentMoveStatus)}，期望=2（夹取/出餐中），" +
                        "orderNo=$orderNo, itemId=${item.id}"
                )
                return DeliveryResult(
                    success = false,
                    completedCount = completedCount,
                    takenCount = takenCount,
                    discardedCount = discardedCount
                )
            }

            try {
                if (!ensureModbusReadyForDelivery(orderNo, item.id)) {
                    throw IllegalStateException("下位机通信未恢复，无法开始本次出餐：orderNo=$orderNo, itemId=${item.id}")
                }
                reportOrderEvent(orderNo, item.id, item.productId, item.tasteCode, ORDER_EVENT_STATUS_PICKING)

                when (KaoChangAlgorithm.purchase(item.tasteCode, item.productId)) {
                    KaoChangOperate.TakeSausageResult.TAKEN_BY_USER -> {
                        completedCount++
                        takenCount++
                        LogUtils.d(
                            "【履约完结】视觉判定顾客已取走，已上报 Java 订单事件：" +
                                "status（订单事件状态）=${orderEventStatusText(ORDER_EVENT_STATUS_TAKEN)}，orderNo=$orderNo, " +
                                "itemId=${item.id}, productId=${item.productId}, tasteCode=${item.tasteCode}, current=${completedBase + completedCount}/$totalCount"
                        )
                        reportOrderEvent(orderNo, item.id, item.productId, item.tasteCode, ORDER_EVENT_STATUS_TAKEN)
                    }

                    KaoChangOperate.TakeSausageResult.DISCARDED -> {
                        completedCount++
                        discardedCount++
                        LogUtils.w(
                            "【履约完结】烤肠已超时丢弃，已上报独立订单事件并继续后续数量：" +
                                "status（订单事件状态）=${orderEventStatusText(ORDER_EVENT_STATUS_DISCARDED_BY_TIMEOUT)}，orderNo=$orderNo, " +
                                "itemId=${item.id}, productId=${item.productId}, tasteCode=${item.tasteCode}, current=${completedBase + completedCount}/$totalCount"
                        )
                        reportOrderEvent(
                            orderNo,
                            item.id,
                            item.productId,
                            item.tasteCode,
                            ORDER_EVENT_STATUS_DISCARDED_BY_TIMEOUT
                        )
                        LogUtils.w(
                            "【订单流转】单根出餐超时被丢弃，但不视为设备履约失败：orderNo=$orderNo, itemId=${item.id}, " +
                                "completedCount=$completedCount/${item.quantity}, discardedCount=$discardedCount, attempt=$attemptCount/$maxAttempts"
                        )
                    }

                    KaoChangOperate.TakeSausageResult.DELIVERY_NOT_CONFIRMED -> {
                        reportOrderEvent(orderNo, item.id, item.productId, item.tasteCode, ORDER_EVENT_STATUS_FAILED)
                        LogUtils.e(
                            "【订单流转】售卖口未确认到货或未确认顾客取走，本次履约按失败处理并上报 Java 订单事件：" +
                                "status（订单事件状态）=${orderEventStatusText(ORDER_EVENT_STATUS_FAILED)}，" +
                                "orderNo=$orderNo, itemId=${item.id}, productId=${item.productId}, tasteCode=${item.tasteCode}, current=${completedBase + completedCount + 1}/$totalCount"
                        )
                        throw IllegalStateException("单根出餐未确认送达：orderNo=$orderNo, itemId=${item.id}")
                    }

                    KaoChangOperate.TakeSausageResult.ERROR -> {
                        reportOrderEvent(orderNo, item.id, item.productId, item.tasteCode, ORDER_EVENT_STATUS_FAILED)
                        LogUtils.e(
                            "【订单流转】单根出餐失败，已上报 Java 订单事件：" +
                                "status（订单事件状态）=${orderEventStatusText(ORDER_EVENT_STATUS_FAILED)}，orderNo=$orderNo, itemId=${item.id}"
                        )
                        throw IllegalStateException("单根出餐失败：orderNo=$orderNo, itemId=${item.id}")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("【订单流转】处理单根烤肠时发生异常：${e.message}")
                LogUtils.e("【设备状态】单根出餐异常，已切到维护中并准备上报：${e.message}")
                withContext(Dispatchers.Main) {
                    AppConfig.getAppConfig().errorStatus = 2
                    AppConfig.getAppConfig().onlineStatus = 3
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                    syncDeviceStatusToServer(retryDelaysMs = listOf(2000L, 6000L))
                }
                return DeliveryResult(
                    success = false,
                    completedCount = completedCount,
                    takenCount = takenCount,
                    discardedCount = discardedCount
                )
            }
        }

        if (completedCount < item.quantity) {
            LogUtils.e("【订单流转】商品项未全部履约完成：orderNo=$orderNo, itemId=${item.id}, completedCount=$completedCount/${item.quantity}, attemptCount=$attemptCount/$maxAttempts")
            LogUtils.e(
                "【订单流转】商品项履约失败，已上报订单事件：" +
                    "status（订单事件状态）=${orderEventStatusText(ORDER_EVENT_STATUS_FAILED)}，" +
                    "orderNo=$orderNo, itemId=${item.id}, completedCount=$completedCount/${item.quantity}, attemptCount=$attemptCount/$maxAttempts"
            )
            reportOrderEvent(orderNo, item.id, item.productId, item.tasteCode, ORDER_EVENT_STATUS_FAILED)
            return DeliveryResult(
                success = false,
                completedCount = completedCount,
                takenCount = takenCount,
                discardedCount = discardedCount
            )
        }

        LogUtils.d(
            "【订单流转】商品项处理完成：" +
                "orderNo=$orderNo, itemId=${item.id}, completedCount=$completedCount/${item.quantity}, " +
                "takenCount=$takenCount, discardedCount=$discardedCount"
        )
        return DeliveryResult(
            success = true,
            completedCount = completedCount,
            takenCount = takenCount,
            discardedCount = discardedCount
        )
    }

    /**
     * 出餐前的通信层防御性预检。
     * 这里只判断下位机是否可通信，不判断 201 的忙闲状态。
     */
    private suspend fun ensureModbusReadyForDelivery(
        orderNo: String,
        itemId: Int,
        maxRetryCount: Int = 3,
        retryDelayMs: Long = 1500L
    ): Boolean {
        if (VMModbusHelper.connectStatus()) {
            return true
        }

        LogUtils.w("【订单流转】检测到 Modbus 未连接，开始出餐前通信重试：orderNo=$orderNo, itemId=$itemId")
        repeat(maxRetryCount) { index ->
            val attempt = index + 1
            VMModbusHelper.connectModbus { status ->
                LogUtils.d("【订单流转】Modbus 重连结果：attempt=$attempt/$maxRetryCount, status=$status, orderNo=$orderNo, itemId=$itemId")
            }
            delay(retryDelayMs)
            if (VMModbusHelper.connectStatus()) {
                LogUtils.d("【订单流转】Modbus 重连成功：attempt=$attempt/$maxRetryCount, orderNo=$orderNo, itemId=$itemId")
                return true
            }
        }

        LogUtils.e("【订单流转】Modbus 重连失败，放弃本次出餐：orderNo=$orderNo, itemId=$itemId")
        return false
    }

    /**
     * 同步设备运行时状态。
     * MQTT 是主通道，retryDelaysMs 仅用于 MQTT 轻量补发；
     * HTTP 不再作为默认双写链路。
     */
    protected fun syncDeviceStatusToServer(retryDelaysMs: List<Long> = emptyList()) {
        val expectedStatus = AppConfig.getAppConfig().onlineStatus
        LogUtils.d(
            "【设备状态】开始同步设备营业状态：" +
                "expectedOnlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}，" +
                "retryCount=${retryDelaysMs.size}"
        )
        LogUtils.d("【设备状态】触发 MQTT 上报：attempt=0, onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}")
        val firstPublishOk = SendServerHelper.publishServiceUpdateStatus()
        if (firstPublishOk) {
            LogUtils.d("【设备状态】MQTT 上报成功：attempt=0, onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}")
        } else {
            LogUtils.e("【设备状态】MQTT 上报失败：attempt=0, onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}")
        }

        retryDelaysMs.forEachIndexed { index, delayMs ->
            lifecycleScope.launch(Dispatchers.IO) {
                delay(delayMs)
                val currentStatus = AppConfig.getAppConfig().onlineStatus
                if (currentStatus == expectedStatus) {
                    val attempt = index + 1
                    LogUtils.d("【设备状态】触发补发：attempt=$attempt, delayMs=$delayMs, onlineStatus（设备营业状态）=${onlineStatusText(currentStatus)}")
                    LogUtils.d("【设备状态】触发 MQTT 上报：attempt=$attempt, onlineStatus（设备营业状态）=${onlineStatusText(currentStatus)}")
                    val retryPublishOk = SendServerHelper.publishServiceUpdateStatus()
                    if (retryPublishOk) {
                        LogUtils.d("【设备状态】MQTT 上报成功：attempt=$attempt, onlineStatus（设备营业状态）=${onlineStatusText(currentStatus)}")
                    } else {
                        LogUtils.e("【设备状态】MQTT 上报失败：attempt=$attempt, onlineStatus（设备营业状态）=${onlineStatusText(currentStatus)}")
                    }
                } else {
                    LogUtils.d(
                        "【设备状态】取消补发：" +
                            "attempt=${index + 1}, " +
                            "expectedOnlineStatus（期望设备营业状态）=${onlineStatusText(expectedStatus)}, " +
                            "currentOnlineStatus（当前设备营业状态）=${onlineStatusText(currentStatus)}"
                    )
                }
            }
        }
    }

    /**
     * 上报订单事件到服务端（MQTT），并在发送失败时记录错误日志。
     * MQTT 发送失败不阻断出餐流程，但必须记录以便排查订单状态不同步问题。
     */
    protected fun reportOrderEvent(orderNo: String, itemId: Int, productId: Int, tasteCode: String, status: Int) {
        val eventDesc = orderEventStatusText(status)
        val payload = com.alibaba.fastjson.JSONObject().apply {
            put("order_no", orderNo)
            put("item_id", itemId)
            put("product_id", productId)
            put("taste_code", tasteCode)
            put("status", status)
        }
        val ok = SendServerHelper.publishServiceUpdateOrder(payload)
        if (!ok) {
            LogUtils.e(
                "【订单流转】MQTT 上报订单事件失败（MQTT 可能断连）：" +
                    "orderNo=$orderNo, itemId=$itemId, productId=$productId, tasteCode=$tasteCode, " +
                    "status（订单事件状态）=$eventDesc，订单状态可能与服务端不同步"
            )
        } else {
            LogUtils.d(
                "【订单流转】MQTT 上报订单事件成功：" +
                    "orderNo=$orderNo, itemId=$itemId, productId=$productId, tasteCode=$tasteCode, " +
                    "status（订单事件状态）=$eventDesc"
            )
        }
    }

    /**
     * 写入寄存器。
     */
    private fun writeRegister(register: Int, value: Int, description: String) {
        lifecycleScope.launch {
            try {
                LogUtils.d("【寄存器写入】开始写入：description=$description, register=$register, value=$value")
                withContext(Dispatchers.IO) {
                    KaoChangOperate.writeSingleRegister(register, value)
                }
                LogUtils.d("【寄存器写入】写入完成：description=$description, register=$register, value=$value")
            } catch (e: Exception) {
                LogUtils.e("【寄存器写入】写入失败：description=$description, register=$register, value=$value, error=${e.message}", e)
            }
        }
    }
}
