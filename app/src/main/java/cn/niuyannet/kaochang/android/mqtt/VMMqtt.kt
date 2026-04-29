package cn.niuyannet.kaochang.android.mqtt

import android.content.Context
import android.os.PowerManager
import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * MQTT 基础操作封装。
 *
 * 这里只负责连接、订阅、发布和断开，不承载上层业务状态机。
 */
open class VMMqtt {
    var mqttClient: MqttAsyncClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockGeneration: Long = 0L  // 当前 WakeLock 所属的连接代次

    // 状态标志位：并发同步锁定保护 (1=正在连接, 0=空闲)
    // 修改为 protected 以允许子类 VMMqttHelper 访问
    protected var isConnecting = false

    // 连接代次：隔离旧连接产生的乱序异步回调污染
    protected var connectionGeneration: Long = 0L

    // 受控后台协程作用域：用于旧客户端资源回收，避免裸 Thread 不可控膨胀
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val topicByServer = "/cl/devices/{device_id}/get"      // 服务端发给上位机的主题 (Topic)
    private val topicByDevice = "/cl/devices/{device_id}/service"  // 上位机上报给服务端的主题 (Topic)

    private fun requireMqttDeviceCode(operation: String): String? {
        val deviceCode = AppConfig.getDeviceId()
        if (deviceCode.isBlank()) {
            LogUtils.w(
                "【设备身份】deviceCode（云端设备编号）未锁定，拒绝 MQTT $operation，" +
                    "避免使用空 clientId 或空 topic"
            )
            AppConfig.logDeviceIdentity("MQTT $operation 前", force = true)
            return null
        }
        return deviceCode
    }

    private fun buildDeviceTopic(template: String, operation: String): String? {
        val deviceCode = requireMqttDeviceCode(operation) ?: return null
        return template.replace("{device_id}", deviceCode)
    }

    /**
     * 获取当前 MQTT 连接状态。
     * 返回：true=已连接, false=未连接
     */
    fun connectStatus(): Boolean {
        return mqttClient?.isConnected == true
    }

    /**
     * 重置连接执行状态标志位 (status = 0)
     * 主要用于在特殊情况下手动复位锁定状态。
     */
    fun resetConnectingState() {
        synchronized(this) {
            isConnecting = false
            LogUtils.d("【MQTT状态】已手动重置连接锁定标志位为 false (status=0)")
        }
    }

    /**
     * 连接 MQTT 服务。
     * 使用 synchronized(this) 确保状态检查和修改的原子性。
     */
    fun connect(callback: (status: Boolean) -> Unit) {
        AppConfig.logDeviceIdentity("MQTT连接前", force = true)
        val deviceCode = requireMqttDeviceCode("连接") ?: run {
            callback(false)
            return
        }
        synchronized(this) {
            // 检查是否正在连接中
            if (isConnecting) {
                LogUtils.w("【MQTT连接】检测到已有连接任务正在进行中 (status=1)，跳过本次重复请求 | clientId=client_$deviceCode")
                return
            }

            // 检查是否已经是连接成功的状态
            if (connectStatus()) {
                LogUtils.d("【MQTT连接】当前已处于连接态，无需重新连接")
                callback(true)
                return
            }

            // 设置连接中标志位
            isConnecting = true
            LogUtils.d("【MQTT连接】准备发起连接，已加锁 (isConnecting=true)")
        }

        try {
            val appConfigBean = AppConfig.getAppConfig()
            val serverURI = "tcp://${appConfigBean.mqttHost}:${appConfigBean.mqttPort}"
            val username = appConfigBean.mqttUsername
            val passwordText = appConfigBean.mqttPassword ?: ""
            val clientId = "client_$deviceCode"

            LogUtils.d("【MQTT连接】执行连接逻辑 | server=$serverURI | clientId=$clientId")

            // 立刻拨动状态机代次，切断所有已在内存游离的老客户端异步回调
            val currentGen = System.currentTimeMillis()
            connectionGeneration = currentGen
            LogUtils.d("【MQTT连接】分配新一代 Client Generation: $currentGen")

            // 在 new 客户端前，先尝试清理之前的旧实例和后台资源（由于 generation 已经阻断，可以放权清理）
            cleanupOldClient()

            // 获取 WakeLock，确保连接和后续心跳过程中 CPU 不休眠
            acquireWakeLock(currentGen)

            mqttClient = MqttAsyncClient(
                serverURI,
                clientId,
                MemoryPersistence()
            )

            val options = MqttConnectOptions().apply {
                userName = username
                password = passwordText.toCharArray()
                keepAliveInterval = 60       // 心跳间隔 60s
                isCleanSession = false        // 保留会话
                isAutomaticReconnect = false  // 禁用 Paho 自动重连，由 VMMqttHelper 持久化托管
                connectionTimeout = 30        // 设置 30s 连接超时，防止异步任务无限挂起
            }

            LogUtils.d("【MQTT配置】心跳间隔: ${options.keepAliveInterval}s, 连接超时: ${options.connectionTimeout}s")

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    if (currentGen != connectionGeneration) {
                        LogUtils.w("【MQTT连接】丢弃陈旧的连接成功回调 | hookGen=$currentGen, currentGen=$connectionGeneration")
                        releaseWakeLock(currentGen)
                        return
                    }
                    synchronized(this@VMMqtt) {
                        isConnecting = false // 连接成功，释放锁 (status=0)
                        LogUtils.d("【MQTT连接】TCP连接成功回调，已释放锁 | generation=$currentGen")
                    }

                    // 业务订阅完全生效后才对外宣告 MQTT 可用
                    LogUtils.d("【MQTT连接】开始绑定 Callback 和执行全量关键业务订阅 | generation=$currentGen")
                    mqttClient?.let { bindCallbackAndSubscribe(it, currentGen, callback) } ?: run {
                        releaseWakeLock(currentGen)
                        callback(false)
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (currentGen != connectionGeneration) {
                        LogUtils.w("【MQTT连接】丢弃陈旧的连接失败回调 | hookGen=$currentGen, currentGen=$connectionGeneration")
                        releaseWakeLock(currentGen)
                        return
                    }
                    synchronized(this@VMMqtt) {
                        isConnecting = false // 连接失败，释放锁 (status=0)
                        LogUtils.e("【MQTT连接】底栈连接失败回调：${exception?.message}，已释放锁 | generation=$currentGen")
                    }
                    releaseWakeLock(currentGen)
                    callback(false)
                }
            })
        } catch (e: Exception) {
            val gen = connectionGeneration
            synchronized(this) {
                isConnecting = false // 捕获代码异常，强制释放锁 (status=0)
                LogUtils.e("【MQTT连接】连接逻辑触发异常：${e.message}，已强制解锁 | generation=$gen")
            }
            releaseWakeLock(gen)
            callback(false)
        }
    }

    /**
     * 订阅主题。
     *
     * @param client     发起此次订阅的客户端实例（不使用全局 mqttClient，防止误操作新连接）
     * @param generation 发起此次订阅的连接代次，用于校验回调时效性
     * @param topic      订阅的 MQTT 主题
     * @param qos        服务质量等级，默认 1
     * @param callback   订阅结果回调
     */
    fun subscribe(
        client: MqttAsyncClient,
        generation: Long,
        topic: String,
        qos: Int = 1,
        callback: ((Boolean) -> Unit)? = null
    ) {
        try {
            LogUtils.d("【MQTT订阅】开始订阅：topic=$topic, qos=$qos, generation=$generation")
            client.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    if (generation != connectionGeneration) {
                        LogUtils.w("【MQTT订阅】丢弃陈旧的订阅成功回调：topic=$topic, hookGen=$generation, curGen=$connectionGeneration")
                        return
                    }
                    LogUtils.d("【MQTT订阅】关键订阅成功：topic=$topic, qos=$qos, generation=$generation")
                    // WakeLock 不释放 —— 工控机常供电，保持 CPU 唤醒确保 TimerPingSender 心跳正常发送
                    callback?.invoke(true)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (generation != connectionGeneration) {
                        LogUtils.w("【MQTT订阅】丢弃陈旧的订阅失败回调：topic=$topic, hookGen=$generation, curGen=$connectionGeneration")
                        return
                    }
                    LogUtils.e("【MQTT订阅】关键订阅失败，报废本轮连接：topic=$topic, generation=$generation, error=${exception?.message}")
                    silentClose(client)        // 只关传入的 client，不碰全局引用
                    releaseWakeLock(generation) // 带代次校验的释放
                    callback?.invoke(false)
                }
            })
        } catch (e: Exception) {
            LogUtils.e("【MQTT订阅】订阅抛出异常：topic=$topic, generation=$generation, error=${e.message}", e)
            if (generation == connectionGeneration) {
                silentClose(client)
                releaseWakeLock(generation)
                callback?.invoke(false)
            }
        }
    }

    /**
     * 取消订阅主题。
     */
    fun unsubscribe(topic: String) {
        try {
            LogUtils.d("【MQTT订阅】开始取消订阅：topic=$topic")
            mqttClient?.unsubscribe(topic, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.d("【MQTT订阅】取消订阅成功：topic=$topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.e("【MQTT订阅】取消订阅失败：topic=$topic, error=${exception?.message}")
                }
            })
        } catch (e: MqttException) {
            LogUtils.e("【MQTT订阅】取消订阅异常：topic=$topic, error=${e.message}", e)
        }
    }

    /**
     * 发布消息到指定主题。
     */
    fun publish(topic: String, msg: String, qos: Int = 1, retained: Boolean = false): Boolean {
        val client = mqttClient
        if (client == null) {
            LogUtils.w("【MQTT发布】跳过发布：客户端未初始化，topic=$topic")
            return false
        }
        if (!client.isConnected) {
            LogUtils.w("【MQTT发布】跳过发布：客户端未连接，topic=$topic")
            return false
        }
        return try {
            LogUtils.d("【MQTT发布】开始发布：topic=$topic, qos=$qos, retained=$retained")
            val message = MqttMessage().apply {
                payload = msg.toByteArray()
                this.qos = qos
                isRetained = retained
            }
            client.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.d("【MQTT发布】发布成功：topic=$topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.e("【MQTT发布】发布失败：topic=$topic, error=${exception?.message}")
                }
            })
            true
        } catch (e: Throwable) {
            LogUtils.e("【MQTT发布】发布异常：topic=$topic, error=${e.message}", e)
            false
        }
    }

    /**
     * 手动断开 MQTT 连接。
     */
    fun disconnect() {
        synchronized(this) {
            isConnecting = false
            connectionGeneration = 0L  // 使所有挂起的异步回调全部失效
            LogUtils.d("【MQTT连接】主动调用断开流程，已重置状态标志位和代次")
        }

        try {
            LogUtils.d("【MQTT连接】开始断开连接请求")

            // 无条件释放 WakeLock
            releaseWakeLockInternal()

            val currentClient = mqttClient
            currentClient?.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.d("【MQTT连接】异步断开成功")
                    silentClose(currentClient)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.e("【MQTT连接】异步断开失败：${exception?.message}，将尝试强制注销资源")
                    silentClose(currentClient)
                }
            })
        } catch (e: Exception) {
            LogUtils.e("【MQTT连接】触发断开异常：${e.message}", e)
            silentClose(mqttClient)
        }
    }

    /**
     * 留给子类在连接成功后挂载 Callback 和挂载业务订阅的切入点。
     * 传入分配的 generation 确立 Callback 身份隔离。
     */
    protected open fun bindCallbackAndSubscribe(client: MqttAsyncClient, generation: Long, callback: (Boolean) -> Unit) {
        allSubScribe(client, generation, callback)
    }

    protected fun silentClose(client: MqttAsyncClient?) {
        try {
            client?.disconnectForcibly(100L, 500L, false)
        } catch (_: Exception) {}
        try {
            client?.close()
        } catch (_: Exception) {}
    }

    /**
     * 核心资源清理方法：基于 generation 安全解绑，彻底切断物理残留。
     */
    private fun cleanupOldClient() {
        val oldClient = mqttClient
        if (oldClient == null) {
            return
        }

        try {
            if (isConnecting) {
                LogUtils.d(
                    "【MQTT清理】发现旧 MQTT 客户端实例，当前正在创建新连接：" +
                        "isConnecting（MQTT连接握手中标记）=1（正在连接），处理=释放旧实例后继续新连接"
                )
            } else {
                LogUtils.d("【MQTT清理】发现旧 MQTT 客户端实例，准备释放后创建新连接")
            }

            // 1. 先将当前活跃代次挂空，使得旧对象接下来的所有异步回调全部失效丢弃
            mqttClient = null

            // 2. 然后强行断连和关闭。利用协程后台作用域避免卡死主调度
            cleanupScope.launch {
                silentClose(oldClient)
                LogUtils.d("【MQTT清理】旧 MQTT 客户端实例已释放")
            }

        } catch (e: Exception) {
            LogUtils.w("【MQTT清理】回收流程触发异常：${e.message}")
        }
    }

    /**
     * 向服务端主题发布消息。
     */
    fun publishService(it: TopicMessage): Boolean {
        val topic = buildDeviceTopic(topicByDevice, "发布消息") ?: return false
        return publish(topic, JSON.toJSONString(it))
    }

    /**
     * 连接成功后订阅当前设备对应的服务端主题。
     *
     * @param client     发起订阅的客户端实例
     * @param generation 连接代次
     * @param callback   订阅结果回调
     */
    fun allSubScribe(client: MqttAsyncClient, generation: Long, callback: (Boolean) -> Unit) {
        val topic = buildDeviceTopic(topicByServer, "订阅服务端主题") ?: run {
            callback(false)
            return
        }
        subscribe(client, generation, topic, callback = callback)
    }

    /**
     * 取消当前设备的全部订阅。
     */
    fun unAllSubscribe() {
        val topic = buildDeviceTopic(topicByServer, "取消订阅服务端主题") ?: return
        unsubscribe(topic)
    }

    /**
     * 获取 WakeLock，防止 CPU 进入深度睡眠导致 TimerPingSender 心跳线程挂起。
     * 工控机持续供电，无需担心耗电问题，WakeLock 将持续到连接断开或主动释放。
     *
     * @param generation 本次连接尝试的代次，用于标记 WakeLock 归属
     */
    protected fun acquireWakeLock(generation: Long) {
        try {
            releaseWakeLockInternal()  // 先释放可能残留的旧锁
            val powerManager = MyApp.instance().getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KaoChang::MqttWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()  // 无超时：工控机常供电，WakeLock 跟随连接生命周期
            }
            wakeLockGeneration = generation
            LogUtils.d("【MQTT连接】WakeLock已获取 | generation=$generation | 无超时(工控机常供电)")
        } catch (e: Exception) {
            LogUtils.e("【MQTT连接】获取WakeLock失败：${e.message}", e)
        }
    }

    /**
     * 带代次校验的 WakeLock 释放 —— 异步回调使用此方法，防止陈旧代次释放当前连接的锁。
     *
     * @param generation 请求释放的代次，必须与当前 WakeLock 所属代次匹配才会执行释放
     */
    protected fun releaseWakeLock(generation: Long) {
        if (generation != wakeLockGeneration) {
            LogUtils.d("【MQTT连接】跳过陈旧代次的WakeLock释放请求 | requestGen=$generation, ownerGen=$wakeLockGeneration")
            return
        }
        releaseWakeLockInternal()
    }

    /**
     * 无条件释放 WakeLock —— 仅在 disconnect() 主动断连和 acquireWakeLock() 内部清理时使用。
     */
    private fun releaseWakeLockInternal() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    LogUtils.d("【MQTT连接】WakeLock已释放 | ownerGen=$wakeLockGeneration")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            LogUtils.e("【MQTT连接】释放WakeLock失败：${e.message}", e)
        }
    }
}
