package cn.niuyannet.kaochang.android.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.databinding.FragmentTestBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.services.SelfCleanFeatureToggle
import cn.niuyannet.kaochang.android.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TestFragment : Fragment() {
    private data class RegisterSnapshot(
        val register: Int,
        val values: List<Int>
    )

    private var _binding: FragmentTestBinding? = null
    private val binding: FragmentTestBinding
        get() = _binding!!

    // 协程内 UI 更新前统一用此属性判断 Fragment 视图是否仍然存活，
    // 避免 onDestroyView 置空 _binding 后协程继续访问 binding 导致 NPE 崩溃。
    private val isViewAlive: Boolean
        get() = _binding != null && isAdded

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLegacyActionDebugSection()
        setupActionControlListeners()
        setupSelfCleanListeners()
        setupTemperatureControlListeners()
        setupSystemInfoListeners()
    }

    private fun setupLegacyActionDebugSection() {
        setLegacyActionDebugExpanded(false)
        binding.btnToggleLegacyActionDebug.setOnClickListener {
            setLegacyActionDebugExpanded(binding.layoutLegacyActionDebugContent.visibility != View.VISIBLE)
        }
    }

    private fun setLegacyActionDebugExpanded(expanded: Boolean) {
        binding.layoutLegacyActionDebugContent.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.btnToggleLegacyActionDebug.text = if (expanded) {
            "收起底层寄存器区"
        } else {
            "展开底层寄存器区"
        }
    }

    private fun setupActionControlListeners() {
        binding.btnRunFormalSellFromAction.setOnClickListener {
            runFormalSellFromActionInput()
        }

        binding.btnReadActionGroup.setOnClickListener {
            readRegister(200, 1, "动作寄存器(200)") { value ->
                binding.etActionGroup.setText(value[0].toString())
            }
        }
        binding.btnWriteActionGroup.setOnClickListener {
            val value = binding.etActionGroup.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                showToast("请输入动作寄存器值")
            } else {
                writeRegister(200, value.toInt(), "动作寄存器(200)")
            }
        }
        binding.btnReadActionGroupResult.setOnClickListener {
            readRegister(201, 1, "动作状态寄存器(201)") { value ->
                binding.tvActionGroupResult.text = formatRegisterValue(201, value[0])
            }
        }

        binding.btnReadMovePlate.setOnClickListener {
            readRegister(202, 1, "烤盘搬移寄存器(202)") { value ->
                val intValue = value[0]
                val startPosition = (intValue shr 8) and 0xFF
                val endPosition = intValue and 0xFF
                binding.etStartPosition.setText(startPosition.toString())
                binding.etEndPosition.setText(endPosition.toString())
            }
        }

        binding.btnWriteMovePlate.setOnClickListener {
            val startPos = binding.etStartPosition.text?.toString()?.trim().orEmpty()
            val endPos = binding.etEndPosition.text?.toString()?.trim().orEmpty()
            if (startPos.isEmpty() || endPos.isEmpty()) {
                showToast("请输入起始位和结束位")
                return@setOnClickListener
            }
            val value = (startPos.toInt() shl 8) or endPos.toInt()
            writeRegister(202, value, "烤盘搬移寄存器(202)")
        }

        binding.btnReadMovePlateResult.setOnClickListener {
            readRegister(203, 1, "烤盘搬移结果寄存器(203)") { value ->
                val resultText = when (value[0]) {
                    0 -> "0（空闲/完成）"
                    1 -> "1（执行中）"
                    2 -> "2（设备忙）"
                    else -> "${value[0]}（未知）"
                }
                binding.tvMovePlateResult.text = resultText
            }
        }

        binding.btnReadSwitchControl.setOnClickListener {
            readSwitchControl()
        }
        binding.btnReadSwitchControlcheck.setOnClickListener {
            readSwitchControl()
        }
        binding.btnWriteSwitchControlcheck.setOnClickListener {
            var value = 0
            if (binding.cbHeater0.isChecked) value = value or 0x01
            if (binding.cbHeater1.isChecked) value = value or 0x02
            if (binding.cbHeater2.isChecked) value = value or 0x04
            if (binding.cbFridge.isChecked) value = value or 0x08
            if (binding.cbLight.isChecked) value = value or 0x10
            writeRegister(204, value, "开关量控制寄存器(204)")
        }
        binding.btnWriteSwitchControl.setOnClickListener {
            val value = binding.etSwitchControl.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                showToast("请输入开关量控制值")
            } else {
                writeRegister(204, value.toInt(), "开关量控制寄存器(204)")
            }
        }

        binding.btnReadLimitSwitchStatus.setOnClickListener {
            readRegister(205, 1, "行程开关状态寄存器(205)") { value ->
                val intValue = value[0]
                val binaryString = Integer.toBinaryString(intValue).padStart(16, '0')
                binding.tvLimitSwitchStatus.text = "二进制: $binaryString (十进制: $intValue)"
            }
        }
    }

    private fun setupSelfCleanListeners() {
        binding.btnRunPanSelfClean.setOnClickListener {
            if (!SelfCleanFeatureToggle.isEnabled()) {
                val message = SelfCleanFeatureToggle.disabledReasonText()
                binding.tvSelfCleanResult.text = message
                showToast(message)
                return@setOnClickListener
            }
            val text = binding.etSelfCleanPosition.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                showToast("请输入 1~33 号烤盘位")
                return@setOnClickListener
            }
            val positionSn = text.toIntOrNull()
            if (positionSn == null || positionSn !in 1..33) {
                showToast("烤盘位必须在 1~33 之间")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                if (!isViewAlive) return@launch
                binding.tvSelfCleanResult.text = "正在执行烤盘 ${positionSn} 自清洁..."
                val report = withContext(Dispatchers.IO) {
                    KaoChangOperate.cleanPanPosition(
                        positionSn = positionSn,
                        source = "测试下位机页面手动触发",
                        busyDuringClean = true
                    )
                }
                if (!isViewAlive) return@launch
                binding.tvSelfCleanResult.text = buildSelfCleanReportText(report)
            }
        }

        binding.btnRunSellPlatformSelfClean.setOnClickListener {
            if (!SelfCleanFeatureToggle.isEnabled()) {
                val message = SelfCleanFeatureToggle.disabledReasonText()
                binding.tvSelfCleanResult.text = message
                showToast(message)
                return@setOnClickListener
            }
            lifecycleScope.launch {
                if (!isViewAlive) return@launch
                binding.tvSelfCleanResult.text = "正在执行出肠台自清洁..."
                val report = withContext(Dispatchers.IO) {
                    KaoChangOperate.cleanSellPlatform(
                        source = "测试下位机页面手动触发",
                        busyDuringClean = true
                    )
                }
                if (!isViewAlive) return@launch
                binding.tvSelfCleanResult.text = buildSelfCleanReportText(report)
            }
        }
    }

    private fun buildSelfCleanReportText(report: KaoChangOperate.SelfCleanExecutionReport): String {
        val status201Text = when (report.status201) {
            0 -> "0（空闲/完成）"
            1 -> "1（执行中超时）"
            2 -> "2（设备忙）"
            else -> "${report.status201}（通信失败/未知）"
        }
        return "目标=${report.targetLabel}\n" +
            "command（下位机自清洁指令）=${report.command}\n" +
            "status201（动作状态寄存器）=$status201Text\n" +
            "success=${report.success}\n" +
            "message=${report.message}"
    }

    private fun setupTemperatureControlListeners() {
        binding.btnReadTemps.setOnClickListener {
            readRegister(500, 3, "烤盘温度寄存器(500~502)") { value ->
                binding.tvPlateTemp0.text = "温度0: ${value[0]}°C"
                binding.tvPlateTemp1.text = "温度1: ${value[1]}°C"
                binding.tvPlateTemp2.text = "温度2: ${value[2]}°C"
            }
        }

        binding.btnWriteTemps.setOnClickListener {
            val temp0 = binding.etTempSet0.text?.toString()?.trim().orEmpty()
            val temp1 = binding.etTempSet1.text?.toString()?.trim().orEmpty()
            val temp2 = binding.etTempSet2.text?.toString()?.trim().orEmpty()
            if (temp0.isEmpty() || temp1.isEmpty() || temp2.isEmpty()) {
                showToast("请输入全部温度值")
                return@setOnClickListener
            }
            writeRegister(503, temp0.toInt(), "温度写寄存器(503)")
            writeRegister(504, temp1.toInt(), "温度写寄存器(504)")
            writeRegister(505, temp2.toInt(), "温度写寄存器(505)")
        }

        binding.btnReadTemps2.setOnClickListener {
            readRegister(503, 3, "温度写寄存器(503~505)") { value ->
                binding.etTempSet0.setText(value[0].toString())
                binding.etTempSet1.setText(value[1].toString())
                binding.etTempSet2.setText(value[2].toString())
            }
        }
    }

    private fun setupSystemInfoListeners() {
        binding.btnReadHardwareVersion.setOnClickListener {
            readRegister(900, 2, "硬件版本寄存器(900~901)") { value ->
                binding.tvHardwareVersion.text = decodeAsciiWords(value)
            }
        }

        binding.btnReadFirmwareVersion.setOnClickListener {
            readRegister(902, 2, "固件版本寄存器(902~903)") { value ->
                binding.tvFirmwareVersion.text = decodeAsciiWords(value)
            }
        }
    }

    private fun decodeAsciiWords(value: IntArray): String {
        return value.joinToString("") { word ->
            word.toString(16)
                .padStart(4, '0')
                .chunked(2)
                .map { it.toInt(16).toChar() }
                .joinToString("")
        }
    }

    private fun readSwitchControl() {
        readRegister(204, 1, "开关量控制寄存器(204)") { value ->
            val intValue = value[0]
            binding.etSwitchControl.setText(intValue.toString())
            binding.cbHeater0.isChecked = (intValue and 0x01) != 0
            binding.cbHeater1.isChecked = (intValue and 0x02) != 0
            binding.cbHeater2.isChecked = (intValue and 0x04) != 0
            binding.cbFridge.isChecked = (intValue and 0x08) != 0
            binding.cbLight.isChecked = (intValue and 0x10) != 0
        }
    }

    private fun readRegister(
        register: Int,
        quantity: Int,
        description: String,
        callback: (IntArray) -> Unit
    ) {
        lifecycleScope.launch {
            showToast("正在读取$description...")
            try {
                val values = withContext(Dispatchers.IO) {
                    KaoChangOperate.readHoldingRegisters(register, quantity)
                }
                if (!isViewAlive) return@launch
                if (values != null) {
                    callback.invoke(values)
                } else {
                    showToast("读取${description}失败：返回为空")
                }
            } catch (e: Exception) {
                if (!isViewAlive) return@launch
                showToast("读取${description}失败: ${e.message}")
            }
        }
    }

    private fun writeRegister(register: Int, value: Int, description: String) {
        lifecycleScope.launch {
            if (AppConfig.getAppConfig().moveStatus == 5) {
                showToast("当前正在自清洁，测试下位机写操作已禁用")
                return@launch
            }
            showToast("正在写入$description...")
            try {
                val snapshots = withContext(Dispatchers.IO) {
                    val beforeSnapshots = captureRegisterSnapshots(registersToObserveForWrite(register))
                    val result = KaoChangOperate.writeSingleRegister(
                        register,
                        value,
                        source = "TestFragment.writeRegister（测试下位机页面手动写寄存器）",
                        action = "测试下位机手动写入${VMModbusHelper.describeWriteRegister(register)}"
                    )
                    val afterSnapshots = if (result.isSuccess) {
                        observeRegisterSnapshotsAfterWrite(register, value)
                    } else {
                        emptyList()
                    }
                    Triple(beforeSnapshots, result, afterSnapshots)
                }
                val beforeSnapshots = snapshots.first
                val writeResult = snapshots.second
                val afterSnapshots = snapshots.third
                logManualWriteTrace(
                    register = register,
                    value = value,
                    description = description,
                    beforeSnapshots = beforeSnapshots,
                    writeResult = writeResult,
                    afterSnapshots = afterSnapshots
                )
                if (!isViewAlive) return@launch
                if (!writeResult.isSuccess) {
                    showToast("写入${description}失败：${writeResult.message ?: writeResult.status.name}")
                }
            } catch (e: Exception) {
                if (!isViewAlive) return@launch
                showToast("写入${description}失败: ${e.message}")
            }
        }
    }

    private fun runFormalSellFromActionInput() {
        val commandText = binding.etFormalSellAction.text?.toString()?.trim().orEmpty()
        if (commandText.isEmpty()) {
            showToast("请先输入 101~133 的正式链路动作号")
            return
        }
        val command = commandText.toIntOrNull()
        if (command == null || command !in 101..133) {
            showToast("正式链路调试仅支持 101~133（烤盘到售卖口）")
            return
        }
        val positionSn = command - 100
        val kaoPan = KaoPanHelper.getKaoPanByPosition(positionSn)
        if (kaoPan == null) {
            showToast("未找到烤盘$positionSn")
            return
        }
        lifecycleScope.launch {
            if (AppConfig.getAppConfig().moveStatus == 5) {
                showToast("当前正在自清洁，不能模拟正常售卖")
                return@launch
            }
            if (!isViewAlive) return@launch
            binding.tvFormalSellResult.text = "正式链路执行中：烤盘$positionSn"
            LogUtils.i(
                "【测试下位机】开始运维模拟正常售卖：" +
                    "command200=${formatRegisterValue(200, command)}，pan=$positionSn，" +
                    "hasSausage=${kaoPan.isHasSausage}，status=${kaoPan.status}，" +
                    "说明=运维联调用途，已跳过[有肠且可售]前置校验，直接调用正式出餐链路 takeSausageResult，并继续执行售卖口视觉识别与关门/丢弃收尾"
            )
            val takeResult = runCatching {
                withContext(Dispatchers.IO) {
                    KaoChangOperate.takeSausageResult(kaoPan)
                }
            }.getOrElse { error ->
                val message = "运维模拟正常售卖异常：pan=$positionSn, error=${error.message}"
                LogUtils.e("【测试下位机】$message", error)
                // 协程可能因导航离开而被 cancel，此时 binding 已为 null，不能访问 UI
                if (isViewAlive) {
                    binding.tvFormalSellResult.text = "正式链路结果：ERROR（执行异常）"
                    showToast("执行异常：${error.message}")
                }
                return@launch
            }
            val resultText = when (takeResult) {
                KaoChangOperate.TakeSausageResult.TAKEN_BY_USER -> "TAKEN_BY_USER（顾客已取走）"
                KaoChangOperate.TakeSausageResult.DISCARDED -> "DISCARDED（超时未取，已丢弃）"
                KaoChangOperate.TakeSausageResult.DELIVERY_NOT_CONFIRMED -> "DELIVERY_NOT_CONFIRMED（未确认到货或未确认顾客取走）"
                KaoChangOperate.TakeSausageResult.ERROR -> "ERROR（机械/视觉/通信异常）"
            }
            LogUtils.i(
                "【测试下位机】运维模拟正常售卖结束：" +
                    "pan=$positionSn，result=$resultText"
            )
            // IO 操作结束后 Fragment 可能已导航离开，务必再次检查
            if (!isViewAlive) return@launch
            binding.tvFormalSellResult.text = "正式链路结果：$resultText"
            showToast("正式链路结果：$resultText")
        }
    }

    private suspend fun captureRegisterSnapshots(registers: List<Int>): List<RegisterSnapshot> {
        return registers.map { register ->
            RegisterSnapshot(
                register = register,
                values = listOfNotNull(readSingleRegisterValue(register))
            )
        }
    }

    private suspend fun observeRegisterSnapshotsAfterWrite(
        register: Int,
        value: Int
    ): List<RegisterSnapshot> {
        val registers = registersToObserveForWrite(register)
        val pollDelays = when (register) {
            200, 202 -> listOf(0L, 300L, 800L, 1500L, 3000L)
            else -> listOf(0L, 200L)
        }
        val observedValues = linkedMapOf<Int, MutableList<Int>>()
        registers.forEach { observedValues[it] = mutableListOf() }

        pollDelays.forEachIndexed { index, delayMs ->
            if (index > 0) {
                delay(delayMs)
            }
            registers.forEach { targetRegister ->
                val currentValue = readSingleRegisterValue(targetRegister) ?: return@forEach
                val history = observedValues.getValue(targetRegister)
                if (history.isEmpty() || history.last() != currentValue) {
                    history += currentValue
                }
            }
        }

        if (register == 200 && observedValues[register].isNullOrEmpty()) {
            observedValues[register]?.add(value)
        }

        return observedValues.map { (targetRegister, values) ->
            RegisterSnapshot(targetRegister, values.toList())
        }
    }

    private suspend fun readSingleRegisterValue(register: Int): Int? {
        return KaoChangOperate.readHoldingRegisters(register, 1)?.firstOrNull()
    }

    private fun registersToObserveForWrite(register: Int): List<Int> {
        return when (register) {
            200 -> listOf(200, 201)
            202 -> listOf(202, 203)
            204, 503, 504, 505 -> listOf(register)
            else -> listOf(register)
        }
    }

    private fun logManualWriteTrace(
        register: Int,
        value: Int,
        description: String,
        beforeSnapshots: List<RegisterSnapshot>,
        writeResult: VMModbusHelper.ModbusOperationResult,
        afterSnapshots: List<RegisterSnapshot>
    ) {
        val builder = StringBuilder()
            .append("【测试下位机】手动写寄存器：")
            .append("description=").append(description)
            .append("，target=").append(VMModbusHelper.describeWriteRegister(register))
            .append("，request=").append(formatRegisterValue(register, value))
            .append("，writeResult=").append(VMModbusHelper.describeWriteResult(writeResult.status))

        beforeSnapshots.forEach { snapshot ->
            builder.append("\n  写前 ")
                .append(VMModbusHelper.describeWriteRegister(snapshot.register))
                .append(" = ")
                .append(formatSnapshotValues(snapshot.register, snapshot.values))
        }
        afterSnapshots.forEach { snapshot ->
            builder.append("\n  写后变化 ")
                .append(VMModbusHelper.describeWriteRegister(snapshot.register))
                .append(" = ")
                .append(formatSnapshotValues(snapshot.register, snapshot.values))
        }
        if (!writeResult.message.isNullOrBlank()) {
            builder.append("\n  message=").append(writeResult.message)
        }
        LogUtils.i(builder.toString())
    }

    private fun formatSnapshotValues(register: Int, values: List<Int>): String {
        if (values.isEmpty()) {
            return "读取失败"
        }
        return values.joinToString(separator = " -> ") { formatRegisterValue(register, it) }
    }

    private fun formatRegisterValue(register: Int, value: Int): String {
        return when (register) {
            200, 202, 204, 503, 504, 505 ->
                VMModbusHelper.describeWriteValue(register, value)
            201, 203 -> when (value) {
                0 -> "0（空闲/完成）"
                1 -> "1（执行中）"
                2 -> "2（设备忙）"
                else -> "$value（未知状态）"
            }
            205 -> {
                val binaryString = Integer.toBinaryString(value).padStart(16, '0')
                "$value（二进制=$binaryString）"
            }
            else -> value.toString()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
