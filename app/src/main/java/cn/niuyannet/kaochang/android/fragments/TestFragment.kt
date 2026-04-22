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
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.services.SelfCleanFeatureToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TestFragment : Fragment() {
    private var _binding: FragmentTestBinding? = null
    private val binding: FragmentTestBinding
        get() = _binding!!

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
        setupActionControlListeners()
        setupSelfCleanListeners()
        setupTemperatureControlListeners()
        setupSystemInfoListeners()
    }

    private fun setupActionControlListeners() {
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
                val resultText = when (value[0]) {
                    0 -> "0（空闲/完成）"
                    1 -> "1（执行中）"
                    2 -> "2（设备忙）"
                    else -> "${value[0]}（未知）"
                }
                binding.tvActionGroupResult.text = resultText
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
                binding.tvSelfCleanResult.text = "正在执行烤盘 ${positionSn} 自清洁..."
                val report = withContext(Dispatchers.IO) {
                    KaoChangOperate.cleanPanPosition(
                        positionSn = positionSn,
                        source = "测试下位机页面手动触发",
                        busyDuringClean = true
                    )
                }
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
                binding.tvSelfCleanResult.text = "正在执行出肠台自清洁..."
                val report = withContext(Dispatchers.IO) {
                    KaoChangOperate.cleanSellPlatform(
                        source = "测试下位机页面手动触发",
                        busyDuringClean = true
                    )
                }
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
                if (values != null) {
                    callback.invoke(values)
                } else {
                    showToast("读取${description}失败：返回为空")
                }
            } catch (e: Exception) {
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
                withContext(Dispatchers.IO) {
                    KaoChangOperate.writeSingleRegister(register, value)
                }
            } catch (e: Exception) {
                showToast("写入${description}失败: ${e.message}")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
