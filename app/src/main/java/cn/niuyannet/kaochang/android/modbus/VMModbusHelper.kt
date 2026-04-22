package cn.niuyannet.kaochang.android.modbus

import android.os.Build
import android.util.Log
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.invertor.modbus.Modbus
import com.invertor.modbus.ModbusMaster
import com.invertor.modbus.ModbusMasterFactory
import com.invertor.modbus.exception.IllegalDataAddressException
import com.invertor.modbus.exception.IllegalDataValueException
import com.invertor.modbus.exception.ModbusIOException
import com.invertor.modbus.exception.ModbusNumberException
import com.invertor.modbus.exception.ModbusProtocolException
import com.invertor.modbus.exception.SlaveDeviceBusyException
import com.invertor.modbus.exception.SlaveDeviceFailureException
import com.invertor.modbus.serial.SerialParameters
import com.invertor.modbus.serial.SerialPort
import com.invertor.modbus.serial.SerialPortFactoryJSSC
import com.invertor.modbus.serial.SerialUtils
import com.invertor.modbus.utils.ModbusExceptionCode
import com.kongqw.serialportlibrary.SerialPortFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object VMModbusHelper {
    private const val PROBE_ATTEMPT_LOG_THROTTLE_MS = 30_000L
    private const val READ_FAILURE_LOG_THROTTLE_MS = 10_000L

    private data class BoardSerialProfile(
        val name: String,
        val keywords: List<String>,
        val preferredPorts: List<String>
    )

    private val boardSerialProfiles = listOf(
        BoardSerialProfile(
            name = "rk3568_s",
            keywords = listOf("rk3568_s"),
            preferredPorts = listOf("/dev/ttyS7", "/dev/ttyS2", "/dev/ttyUSB0", "/dev/ttyUSB1")
        ),
        BoardSerialProfile(
            name = "rk3568_r",
            keywords = listOf("rk3568_r"),
            preferredPorts = listOf("/dev/ttyS2", "/dev/ttyS7", "/dev/ttyUSB0", "/dev/ttyUSB1")
        ),
        BoardSerialProfile(
            name = "rk3568_generic",
            keywords = listOf("rk3568"),
            preferredPorts = listOf("/dev/ttyS2", "/dev/ttyS7", "/dev/ttyUSB0", "/dev/ttyUSB1")
        )
    )

    private data class ProbeRead(val startAddress: Int, val quantity: Int, val label: String)
    private data class ConnectivityProbeResult(
        val success: Boolean,
        val failureMessage: String? = null,
        val failureProbeLabel: String? = null
    )

    private val connectivityProbes = listOf(
        ProbeRead(0, 10, "bootstrap_0_9"),
        ProbeRead(201, 1, "device_status_201"),
        ProbeRead(200, 8, "command_window_200_207")
    )

    private val serialPortPrefixes = listOf(
        "/dev/ttyUSB",
        "/dev/ttyACM",
        "/dev/ttyCH343USB",
        "/dev/ttyS",
        "/dev/ttyFIQ",
        "/dev/ttyAMA",
        "/dev/ttyWK",
        "/dev/ttyXRUSB"
    )

    enum class ModbusOperationStatus {
        SUCCESS,
        DISCONNECTED,
        BUSY,
        PARAM_ERROR,
        DEVICE_FAILURE,
        PROTOCOL_ERROR,
        UNKNOWN_ERROR
    }

    data class ModbusOperationResult(
        val status: ModbusOperationStatus,
        val message: String? = null
    ) {
        val isSuccess: Boolean
            get() = status == ModbusOperationStatus.SUCCESS
    }

    data class ModbusWriteTraceContext(
        val source: String,
        val action: String? = null
    )

    var modbusMaster: ModbusMaster? = null
    var isLunXun = true
    var isDebug = false
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lunXunJob: Job? = null
    private var lastProbeAttemptSignature = ""
    private var lastProbeAttemptLogAtMs = 0L
    private var lastProbeSummarySignature = ""
    private var lastProbeSummaryLogAtMs = 0L
    private var lastReadFailureMessage = ""
    private var lastReadFailureLogAtMs = 0L
    @Volatile
    private var connectAttemptInFlight = false
    private val pendingConnectCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val writeTraceSeq = AtomicLong(0L)

    private fun shouldLogProbeAttempt(signature: String): Boolean {
        val now = System.currentTimeMillis()
        val shouldLog =
            signature != lastProbeAttemptSignature || now - lastProbeAttemptLogAtMs >= PROBE_ATTEMPT_LOG_THROTTLE_MS
        if (shouldLog) {
            lastProbeAttemptSignature = signature
            lastProbeAttemptLogAtMs = now
        }
        return shouldLog
    }

    private fun shouldLogProbeSummary(signature: String): Boolean {
        val now = System.currentTimeMillis()
        val shouldLog =
            signature != lastProbeSummarySignature || now - lastProbeSummaryLogAtMs >= PROBE_ATTEMPT_LOG_THROTTLE_MS
        if (shouldLog) {
            lastProbeSummarySignature = signature
            lastProbeSummaryLogAtMs = now
        }
        return shouldLog
    }

    private fun logReadFailureThrottled(message: String, level: String = "D") {
        val now = System.currentTimeMillis()
        val shouldLog =
            message != lastReadFailureMessage || now - lastReadFailureLogAtMs >= READ_FAILURE_LOG_THROTTLE_MS
        if (!shouldLog) {
            return
        }
        lastReadFailureMessage = message
        lastReadFailureLogAtMs = now
        when (level) {
            "W" -> LogUtils.w(message)
            "E" -> LogUtils.e(message)
            else -> LogUtils.d("KaoChangAlgorithm", message)
        }
    }

    private fun buildSerialParameters(devicePath: String, baudRate: Int): SerialParameters {
        return SerialParameters().apply {
            device = devicePath
            parity = SerialPort.Parity.NONE
            dataBits = 8
            stopBits = 1
            when (baudRate) {
                9600 -> setBaudRate(SerialPort.BaudRate.BAUD_RATE_9600)
                115200 -> setBaudRate(SerialPort.BaudRate.BAUD_RATE_115200)
            }
        }
    }

    private fun serialPortPriority(path: String): Int {
        val index = serialPortPrefixes.indexOfFirst { prefix -> path.startsWith(prefix) }
        return if (index >= 0) index else serialPortPrefixes.size
    }

    private fun serialPortTieBreaker(@Suppress("UNUSED_PARAMETER") path: String): Int {
        return 0
    }

    private fun listAvailableSerialPorts(): List<String> {
        val finderPorts = runCatching {
            SerialPortFinder().devices.mapNotNull { device -> device.file?.absolutePath }
        }.getOrElse {
            LogUtils.w("串口枚举失败（SerialPortFinder）：${it.message}")
            emptyList()
        }

        val devPorts = runCatching {
            File("/dev").listFiles()
                ?.map { file -> file.absolutePath }
                ?.filter { path -> serialPortPrefixes.any { prefix -> path.startsWith(prefix) } }
                .orEmpty()
        }.getOrElse {
            LogUtils.w("串口枚举失败（/dev）：${it.message}")
            emptyList()
        }

        return (finderPorts + devPorts)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy<String>({ serialPortPriority(it) }, { serialPortTieBreaker(it) }, { it }))
    }

    private fun buildBoardFingerprint(): String {
        return listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT,
            Build.BOARD,
            Build.HARDWARE
        )
            .filterNotNull()
            .joinToString(" | ")
            .lowercase()
    }

    private fun resolveBoardSerialProfile(): BoardSerialProfile? {
        val fingerprint = buildBoardFingerprint()
        return boardSerialProfiles.firstOrNull { profile ->
            profile.keywords.any { keyword -> fingerprint.contains(keyword) }
        }
    }

    private fun buildFullCandidatePorts(
        configuredAddress: String?,
        availablePorts: List<String>,
        boardProfile: BoardSerialProfile?
    ): List<String> {
        val preferred = configuredAddress?.takeIf { it.isNotBlank() }
        val profilePorts = boardProfile?.preferredPorts.orEmpty()
        return buildList {
            if (!preferred.isNullOrBlank()) {
                add(preferred)
            }
            profilePorts.forEach { port ->
                if (!contains(port) && availablePorts.contains(port)) {
                    add(port)
                }
            }
            availablePorts.forEach { port ->
                if (!contains(port)) {
                    add(port)
                }
            }
        }
    }

    private fun buildFastRecoveryPorts(
        configuredAddress: String?,
        availablePorts: List<String>,
        boardProfile: BoardSerialProfile?
    ): List<String> {
        val configured = configuredAddress?.takeIf { it.isNotBlank() }
        val profilePorts = boardProfile?.preferredPorts.orEmpty()
        return buildList {
            if (!configured.isNullOrBlank()) {
                add(configured)
            }
            profilePorts.forEach { port ->
                if (!contains(port) && availablePorts.contains(port)) {
                    add(port)
                }
            }
        }
    }

    private fun resolveCandidatePorts(
        configuredAddress: String?,
        strategy: ModbusConnectStrategy
    ): Pair<BoardSerialProfile?, List<String>> {
        val availablePorts = listAvailableSerialPorts()
        val boardProfile = resolveBoardSerialProfile()
        val fullCandidates = buildFullCandidatePorts(configuredAddress, availablePorts, boardProfile)
        val fastCandidates = buildFastRecoveryPorts(configuredAddress, availablePorts, boardProfile)
        val resolvedCandidates = when (strategy) {
            ModbusConnectStrategy.FAST_RECOVERY -> fastCandidates.ifEmpty { fullCandidates }
            ModbusConnectStrategy.FULL_REDISCOVERY -> fullCandidates
        }
        return boardProfile to resolvedCandidates
    }

    private fun tryConnectivityProbe(master: ModbusMaster): ConnectivityProbeResult {
        var lastError: Exception? = null
        var lastFailureProbeLabel: String? = null
        for (probe in connectivityProbes) {
            try {
                master.readHoldingRegisters(1, probe.startAddress, probe.quantity)
                return ConnectivityProbeResult(success = true)
            } catch (e: Exception) {
                lastError = e
                lastFailureProbeLabel = probe.label
            }
        }
        return ConnectivityProbeResult(
            success = false,
            failureMessage = lastError?.message ?: "未知错误",
            failureProbeLabel = lastFailureProbeLabel
        )
    }

    private fun hasModbusResponse(master: ModbusMaster): ConnectivityProbeResult {
        return tryConnectivityProbe(master)
    }

    private fun discoveryModeText(strategy: ModbusConnectStrategy): String = when (strategy) {
        ModbusConnectStrategy.FAST_RECOVERY -> "快速自恢复"
        ModbusConnectStrategy.FULL_REDISCOVERY -> "低频全量重发现"
    }

    fun connectModbus(
        strategy: ModbusConnectStrategy = ModbusConnectStrategy.FULL_REDISCOVERY,
        callback: (status: Boolean) -> Unit
    ) {
        if (isDebug) {
            callback.invoke(true)
            return
        }

        synchronized(this) {
            if (connectStatus()) {
                callback.invoke(true)
                return
            }
            pendingConnectCallbacks.add(callback)
            if (connectAttemptInFlight) {
                return
            }
            connectAttemptInFlight = true
        }
        helperScope.launch {
            val status = connectModbusInternal(strategy)
            val callbacks = synchronized(this@VMModbusHelper) {
                connectAttemptInFlight = false
                pendingConnectCallbacks.toList().also { pendingConnectCallbacks.clear() }
            }
            callbacks.forEach { pendingCallback ->
                runCatching { pendingCallback.invoke(status) }
            }
        }
    }

    private fun connectModbusInternal(strategy: ModbusConnectStrategy): Boolean {
        try {
            val appConfigBean = AppConfig.getAppConfig()
            Modbus.setLogLevel(Modbus.LogLevel.LEVEL_DEBUG)
            SerialUtils.setSerialPortFactory(SerialPortFactoryJSSC())
            val (boardProfile, candidatePorts) = resolveCandidatePorts(appConfigBean.modbusAddress, strategy)
            if (candidatePorts.isEmpty()) {
                LogUtils.e("没有找到可用的 Modbus 串口候选：mode=${discoveryModeText(strategy)}，当前配置=${appConfigBean.modbusAddress}")
                disconnectModbusMaster()
                return false
            }

            val probeAttemptSignature =
                "${strategy.name}|${appConfigBean.modbusAddress}|${boardProfile?.name ?: "none"}|${candidatePorts.joinToString()}"
            if (shouldLogProbeAttempt(probeAttemptSignature)) {
                LogUtils.d(
                    "开始探测 Modbus 串口：mode=${discoveryModeText(strategy)}，当前配置=${appConfigBean.modbusAddress}，" +
                        "板型=${boardProfile?.name ?: "未识别"}，" +
                        "候选串口=${candidatePorts.joinToString()}"
                )
            }
            var lastErrorMessage: String? = null
            val failureSummaries = mutableListOf<String>()

            for (devicePath in candidatePorts) {
                try {
                    disconnectModbusMaster()
                    val master = ModbusMasterFactory.createModbusMasterRTU(
                        buildSerialParameters(devicePath, appConfigBean.modbusBaudRate)
                    )
                    master.connect()
                    val probeResult = hasModbusResponse(master)
                    if (!probeResult.success) {
                        runCatching { master.disconnect() }
                        lastErrorMessage = "串口 $devicePath 没有收到 Modbus 响应"
                        failureSummaries.add(
                            "$devicePath: ${probeResult.failureProbeLabel ?: "probe"} -> ${probeResult.failureMessage ?: "未知错误"}"
                        )
                        continue
                    }
                    modbusMaster = master

                    if (appConfigBean.modbusAddress != devicePath) {
                        LogUtils.i("【Modbus】自动探测到可用串口：${appConfigBean.modbusAddress} -> $devicePath，已更新配置")
                        appConfigBean.modbusAddress = devicePath
                        AppConfig.saveAppConfig(appConfigBean)
                    } else {
                        LogUtils.i("【Modbus】串口连接成功：port=$devicePath")
                    }

                    return true
                } catch (e: Exception) {
                    lastErrorMessage = e.message
                    failureSummaries.add("$devicePath: ${e.message ?: e.javaClass.simpleName}")
                }
            }

            val probeSummarySignature =
                "${strategy.name}|${appConfigBean.modbusAddress}|${candidatePorts.joinToString()}|${failureSummaries.take(3).joinToString()}|${lastErrorMessage ?: ""}"
            if (shouldLogProbeSummary(probeSummarySignature)) {
                LogUtils.e(
                    "Modbus 串口探测失败：mode=${discoveryModeText(strategy)}，当前配置=${appConfigBean.modbusAddress}，" +
                        "板型=${boardProfile?.name ?: "未识别"}，探测端口数=${candidatePorts.size}，" +
                        "候选串口=${candidatePorts.joinToString()}，" +
                        "失败摘要=${failureSummaries.take(3).joinToString(" | ").ifBlank { lastErrorMessage ?: "未知" }}"
                )
            }
            disconnectModbusMaster()
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            disconnectModbusMaster()
            return false
        }
    }

    fun isConnectAttemptInFlight(): Boolean {
        return connectAttemptInFlight
    }

    @Synchronized
    fun connectStatus(): Boolean {
        if (isDebug) {
            return true
        }
        return try {
            if (modbusMaster == null) {
                false
            } else {
                tryConnectivityProbe(modbusMaster!!).success
            }
        } catch (e: Exception) {
            e.printStackTrace()
            disconnectModbusMaster()
            Log.i("@@@", "Modbus RTU 已断开")
            false
        }
    }

    private fun classifyException(actionName: String, e: Exception): ModbusOperationResult {
        return when (e) {
            is SlaveDeviceBusyException -> {
                LogUtils.w("$actionName 失败：下位机忙。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.BUSY, e.message)
            }

            is ModbusIOException -> {
                LogUtils.e("$actionName 失败：Modbus I/O 异常。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.DISCONNECTED, e.message)
            }

            is IllegalDataAddressException, is IllegalDataValueException, is ModbusNumberException -> {
                LogUtils.e("$actionName 失败：寄存器地址或数值非法。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.PARAM_ERROR, e.message)
            }

            is SlaveDeviceFailureException -> {
                LogUtils.e("$actionName 失败：下位机故障。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.DEVICE_FAILURE, e.message)
            }

            is ModbusProtocolException -> {
                when (e.getException()) {
                    ModbusExceptionCode.SLAVE_DEVICE_BUSY -> {
                        LogUtils.w("$actionName 失败：下位机忙。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.BUSY, e.message)
                    }

                    ModbusExceptionCode.SLAVE_DEVICE_FAILURE,
                    ModbusExceptionCode.GATEWAY_TARGET_DEVICE_FAILED_TO_RESPOND -> {
                        LogUtils.e("$actionName 失败：下位机故障。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.DEVICE_FAILURE, e.message)
                    }

                    ModbusExceptionCode.ILLEGAL_DATA_ADDRESS,
                    ModbusExceptionCode.ILLEGAL_DATA_VALUE,
                    ModbusExceptionCode.ILLEGAL_FUNCTION -> {
                        LogUtils.e("$actionName 失败：地址、功能码或数值非法。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.PARAM_ERROR, e.message)
                    }

                    else -> {
                        LogUtils.e("$actionName 失败：Modbus 协议异常。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.PROTOCOL_ERROR, e.message)
                    }
                }
            }

            else -> {
                LogUtils.e("$actionName 失败：未知异常。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.UNKNOWN_ERROR, e.message)
            }
        }
    }

    fun readCoils(serverAddress: Int, startAddress: Int): Boolean {
        return modbusMaster?.readCoils(serverAddress, startAddress, 1)?.get(0) ?: false
    }

    fun writeSingleCoil(serverAddress: Int, startAddress: Int, flag: Boolean): ModbusOperationResult {
        if (isDebug) {
            return ModbusOperationResult(ModbusOperationStatus.SUCCESS)
        }
        val currentMaster = modbusMaster ?: return ModbusOperationResult(
            ModbusOperationStatus.DISCONNECTED,
            "RTU 主站未初始化，跳过线圈写入 [$startAddress]"
        )
        return try {
            currentMaster.writeSingleCoil(serverAddress, startAddress, flag)
            ModbusOperationResult(ModbusOperationStatus.SUCCESS)
        } catch (e: Exception) {
            e.printStackTrace()
            classifyException("Write coil [$startAddress]", e)
        }
    }

    fun readHoldingRegisters(serverAddress: Int, startAddress: Int): Int {
        if (isDebug) {
            return 0
        }
        return try {
            modbusMaster?.readHoldingRegisters(serverAddress, startAddress, 1)?.get(0) ?: -1
        } catch (e: Exception) {
            e.printStackTrace()
            logReadFailureThrottled("读取寄存器失败：${e.message}")
            handleReadFailure(e)
            -1
        }
    }

    fun readHoldingRegisters(serverAddress: Int, startAddress: Int, quantity: Int): IntArray? {
        if (isDebug) {
            return IntArray(quantity)
        }
        return try {
            modbusMaster?.readHoldingRegisters(serverAddress, startAddress, quantity)
        } catch (e: Exception) {
            e.printStackTrace()
            logReadFailureThrottled("批量读取寄存器失败：${e.message}")
            handleReadFailure(e)
            null
        }
    }

    fun writeSingleRegister(serverAddress: Int, startAddress: Int, value: Int): ModbusOperationResult {
        return writeSingleRegister(serverAddress, startAddress, value, traceContext = null)
    }

    fun writeSingleRegister(
        serverAddress: Int,
        startAddress: Int,
        value: Int,
        traceContext: ModbusWriteTraceContext? = null
    ): ModbusOperationResult {
        val seq = writeTraceSeq.incrementAndGet()
        val source = traceContext?.source ?: "VMModbusHelper.writeSingleRegister（通用写寄存器入口）"
        val action = traceContext?.action
        if (isDebug) {
            val result = ModbusOperationResult(ModbusOperationStatus.SUCCESS)
            LogUtils.i(buildWriteTraceMessage(seq, serverAddress, startAddress, value, result.status, source, action, "debug_mode_skip"))
            return result
        }
        val currentMaster = modbusMaster
        if (currentMaster == null) {
            val result = ModbusOperationResult(
                ModbusOperationStatus.DISCONNECTED,
                "RTU 主站未初始化，跳过寄存器写入 [$startAddress]=$value"
            )
            LogUtils.i(buildWriteTraceMessage(seq, serverAddress, startAddress, value, result.status, source, action, result.message))
            return result
        }
        return try {
            currentMaster.writeSingleRegister(serverAddress, startAddress, value)
            ModbusOperationResult(ModbusOperationStatus.SUCCESS).also { result ->
                LogUtils.i(buildWriteTraceMessage(seq, serverAddress, startAddress, value, result.status, source, action, result.message))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            classifyException("Write register [$startAddress]=$value", e).also { result ->
                LogUtils.i(buildWriteTraceMessage(seq, serverAddress, startAddress, value, result.status, source, action, result.message))
            }
        }
    }

    @Synchronized
    fun disconnectModbusMaster() {
        runCatching { modbusMaster?.disconnect() }
        modbusMaster = null
    }

    internal fun shouldResetConnectionAfterReadFailure(status: ModbusOperationStatus): Boolean {
        return when (status) {
            ModbusOperationStatus.DISCONNECTED,
            ModbusOperationStatus.DEVICE_FAILURE,
            ModbusOperationStatus.PROTOCOL_ERROR,
            ModbusOperationStatus.UNKNOWN_ERROR -> true
            else -> false
        }
    }

    private fun handleReadFailure(exception: Exception) {
        val result = classifyException("Read holding registers", exception)
        if (!shouldResetConnectionAfterReadFailure(result.status)) {
            return
        }
        logReadFailureThrottled("【Modbus】读取失败后已清理失效连接，等待后续重连", level = "W")
        disconnectModbusMaster()
    }

    @Deprecated("Use disconnectModbusMaster() instead")
    fun disConnectTcpMaster() {
        disconnectModbusMaster()
    }

    fun setLunXunCallBack(address: Int, callback: (value: Int) -> Unit) {
        lunXunJob?.cancel()
        lunXunJob = helperScope.launch {
            while (isLunXun) {
                var value = -1
                try {
                    value = if (modbusMaster?.readCoils(1, address, 1)?.get(0) == true) {
                        1
                    } else {
                        0
                    }
                    delay(50)
                } catch (e: Exception) {
                    e.printStackTrace()
                    delay(2000)
                }
                callback.invoke(value)
            }
        }
    }

    internal fun describeWriteRegister(address: Int): String {
        val label = when (address) {
            200 -> "动作指令寄存器"
            202 -> "烤盘搬移指令寄存器"
            203 -> "烤盘搬移结果寄存器"
            206 -> "升降台目标寄存器"
            207 -> "售卖台动作寄存器"
            220 -> "卖肠分步协议命令寄存器"
            221 -> "卖肠分步协议参数寄存器"
            223 -> "卖肠分步协议摘要寄存器"
            224 -> "卖肠分步协议包1状态寄存器"
            225 -> "卖肠分步协议包2状态寄存器"
            226 -> "卖肠分步协议包3状态寄存器"
            227 -> "卖肠分步协议包4状态寄存器"
            230 -> "分步协议全局动作状态寄存器"
            231 -> "分步协议自清洁状态寄存器"
            232 -> "分步协议丢弃流程状态寄存器"
            233 -> "分步协议资源占用寄存器"
            234 -> "分步协议阻塞原因寄存器"
            235 -> "分步协议活动会话类型寄存器"
            236 -> "分步协议当前动作包寄存器"
            237 -> "分步协议动作包已耗时寄存器"
            238 -> "分步协议动作包超时寄存器"
            239 -> "分步协议 GPIO 输入寄存器"
            240 -> "分步协议光电原始位寄存器"
            241 -> "分步协议光电稳定位寄存器"
            242 -> "分步协议光电故障位寄存器"
            243 -> "分步协议光电变更序号寄存器"
            244 -> "分步协议光电消抖时间寄存器"
            250 -> "分步协议会话状态寄存器"
            251 -> "分步协议会话结果寄存器"
            252 -> "分步协议错误码寄存器"
            253 -> "分步协议错误子步骤寄存器"
            254 -> "分步协议可重试标记寄存器"
            255 -> "分步协议版本寄存器"
            256 -> "分步协议能力位寄存器"
            500 -> "温度读取寄存器1"
            501 -> "温度读取寄存器2"
            502 -> "温度读取寄存器3"
            503 -> "加热温度写入寄存器1"
            504 -> "加热温度写入寄存器2"
            505 -> "加热温度写入寄存器3"
            else -> "寄存器"
        }
        return "$address（$label）"
    }

    internal fun describeWriteValue(address: Int, value: Int): String {
        val label = when (address) {
            200 -> describeActionRegisterValue(value)
            202 -> describeMoveRegisterValue(value)
            206 -> describeLiftRegisterValue(value)
            207 -> when (value) {
                1 -> "关门"
                2 -> "丢弃并关门"
                else -> null
            }
            220 -> when (value) {
                1 -> "START/启动分步卖肠会话"
                2 -> "RETRY/重试当前动作包"
                3 -> "NEXT/推进下一动作包"
                4 -> "ABORT/中止分步卖肠会话"
                5 -> "RESET/重置分步卖肠会话"
                else -> null
            }
            221 -> {
                val targetPan = (value ushr 8) and 0xFF
                when {
                    targetPan > 0 -> "卖肠目标烤盘=$targetPan"
                    else -> "卖肠参数字"
                }
            }
            503, 504, 505 -> when {
                value > 0 -> "温度目标=$value℃"
                value == 0 -> "关闭加热/保温"
                else -> null
            }
            else -> null
        }
        return if (label.isNullOrBlank()) {
            value.toString()
        } else {
            "$value（$label）"
        }
    }

    internal fun describeWriteResult(status: ModbusOperationStatus): String {
        val label = when (status) {
            ModbusOperationStatus.SUCCESS -> "写入成功"
            ModbusOperationStatus.DISCONNECTED -> "通信断开"
            ModbusOperationStatus.BUSY -> "下位机忙"
            ModbusOperationStatus.PARAM_ERROR -> "参数错误"
            ModbusOperationStatus.DEVICE_FAILURE -> "下位机故障"
            ModbusOperationStatus.PROTOCOL_ERROR -> "协议异常"
            ModbusOperationStatus.UNKNOWN_ERROR -> "未知异常"
        }
        return "${status.name}（$label）"
    }

    internal fun buildWriteTraceMessage(
        seq: Long,
        serverAddress: Int,
        startAddress: Int,
        value: Int,
        result: ModbusOperationStatus,
        source: String,
        action: String?,
        message: String?
    ): String {
        val actionLabel = action ?: inferActionLabel(startAddress, value)
        val builder = StringBuilder()
            .append("【Modbus写指令】")
            .append("seq=").append(seq)
            .append("，source=").append(source)
            .append("，action=").append(actionLabel)
            .append("，server=").append(serverAddress).append("（下位机地址）")
            .append("，register=").append(describeWriteRegister(startAddress))
            .append("，value=").append(describeWriteValue(startAddress, value))
            .append("，hex=").append(String.format(Locale.US, "0x%04X", value and 0xFFFF))
            .append("，result=").append(describeWriteResult(result))
        if (!message.isNullOrBlank()) {
            builder.append("，message=").append(message)
        }
        return builder.toString()
    }

    private fun inferActionLabel(address: Int, value: Int): String {
        val inferred = when (address) {
            200, 202, 206, 207, 220, 221, 503, 504, 505 -> describeWriteValue(address, value)
            else -> value.toString()
        }
        return "auto_infer（$inferred）"
    }

    private fun describeActionRegisterValue(value: Int): String? {
        return when (value) {
            in 1..33 -> "平台到烤盘$value"
            in 101..133 -> "烤盘${value - 100}到售卖口"
            in 201..222 -> "烤肠箱${value - 200}到平台"
            in 301..333 -> "烤盘${value - 300}丢弃"
            in 501..533 -> "烤盘${value - 500}自清洁"
            else -> null
        }
    }

    private fun describeMoveRegisterValue(value: Int): String? {
        val sourcePan = (value ushr 8) and 0xFF
        val targetPan = value and 0xFF
        return if (sourcePan > 0 && targetPan > 0) {
            "烤盘搬移：$sourcePan->$targetPan"
        } else {
            null
        }
    }

    private fun describeLiftRegisterValue(value: Int): String? {
        val mode = (value ushr 8) and 0xFF
        val worldY = value and 0xFF
        return when {
            mode > 0 -> "升降台目标：模式=$mode，worldY=$worldY"
            worldY > 0 -> "升降台目标：worldY=$worldY"
            else -> null
        }
    }
}
