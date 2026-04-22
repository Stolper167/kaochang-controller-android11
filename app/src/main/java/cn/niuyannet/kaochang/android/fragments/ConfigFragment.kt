package cn.niuyannet.kaochang.android.fragments

import android.R as AndroidR
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.databinding.FragmentConfigBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.kongqw.serialportlibrary.Device
import com.kongqw.serialportlibrary.SerialPortFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigFragment : Fragment() {
    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var configBean: AppConfigBean

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        statusInit()

        binding.btnSaveMqttConfig.setOnClickListener {
            val uiScope = viewLifecycleOwner.lifecycleScope
            configBean.mqttHost = binding.etMqttIp.text.toString()
            configBean.mqttPort = binding.etMqttPort.text.toString().toInt()
            configBean.mqttUsername = binding.etMqttUsername.text.toString()
            configBean.mqttPassword = binding.etMqttPassword.text.toString()
            AppConfig.saveAppConfig(configBean)

            ToastUtils.showShort("MQTT 配置已保存，正在连接")
            uiScope.launch(Dispatchers.IO) {
                VMMqttHelper.connect { connected ->
                    uiScope.launch mqttUi@{
                        if (_binding == null) {
                            return@mqttUi
                        }
                        updateMqttStatus(connected)
                        if (connected) {
                            ToastUtils.showShort("MQTT 连接成功")
                        } else {
                            ToastUtils.showShort("MQTT 连接失败，请检查网络或配置")
                        }
                    }
                    if (connected) {
                        LogUtils.d("【配置页】MQTT 连接成功")
                    } else {
                        LogUtils.w("【配置页】MQTT 连接失败")
                    }
                }
            }
        }

        binding.btnSaveModbusConfig.setOnClickListener {
            val uiScope = viewLifecycleOwner.lifecycleScope
            configBean.modbusBaudRate = binding.etModbusBaudRate.text.toString().toInt()
            AppConfig.saveAppConfig(configBean)

            ToastUtils.showShort("MODBUS 配置已保存，正在连接")
            uiScope.launch(Dispatchers.IO) {
                VMModbusHelper.connectModbus { connected ->
                    uiScope.launch modbusUi@{
                        if (_binding == null) {
                            return@modbusUi
                        }
                        updateModbusStatus(connected)
                        if (connected) {
                            ToastUtils.showShort("MODBUS 连接成功")
                        } else {
                            ToastUtils.showShort("MODBUS 未连通，请检查下位机、模拟器或接线")
                        }
                    }
                    if (connected) {
                        LogUtils.d("【配置页】MODBUS 连接成功")
                    } else {
                        LogUtils.w("【配置页】MODBUS 连接失败：未收到有效从站响应")
                    }
                }
            }
        }

        binding.swForceVisionSuccess.setOnCheckedChangeListener { _, isChecked ->
            configBean.forceVisionSuccess = if (isChecked) 1 else 0
            AppConfig.saveAppConfig(configBean)
            val msg = if (isChecked) {
                "已开启联调模式：视觉识别固定返回成功"
            } else {
                "已关闭联调模式"
            }
            ToastUtils.showShort(msg)
            LogUtils.w("【配置页】联调模式状态变更：$msg")
        }

        binding.btndisModbusConfig.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    VMModbusHelper.disconnectModbusMaster()
                }
                if (_binding == null) {
                    return@launch
                }
                updateModbusStatus(false)
                ToastUtils.showShort("MODBUS 已断开")
                LogUtils.d("【配置页】MODBUS 已手动断开")
            }
        }
    }

    private fun updateModbusStatus(connected: Boolean) {
        binding.tvModbusStatus.text = if (connected) "已连接" else "未连接"
    }

    private fun updateMqttStatus(connected: Boolean) {
        binding.tvMqttStatus.text = if (connected) "已连接" else "未连接"
    }

    /**
     * 初始化界面参数。
     */
    private fun initViews() {
        configBean = AppConfig.getAppConfig()
        binding.etMqttIp.setText(configBean.mqttHost)
        binding.etMqttPort.setText(configBean.mqttPort.toString())
        binding.etMqttUsername.setText(configBean.mqttUsername)
        binding.etMqttPassword.setText(configBean.mqttPassword)
        binding.etModbusBaudRate.setText(configBean.modbusBaudRate.toString())
        binding.swForceVisionSuccess.isChecked = configBean.forceVisionSuccess == 1
        initSpinner()
    }

    private fun statusInit() {
        viewLifecycleOwner.lifecycleScope.launch {
            val modbusStatus = withContext(Dispatchers.IO) {
                VMModbusHelper.connectStatus()
            }
            if (_binding == null) {
                return@launch
            }
            updateModbusStatus(modbusStatus)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val mqttStatus = withContext(Dispatchers.IO) {
                VMMqttHelper.connectStatus()
            }
            if (_binding == null) {
                return@launch
            }
            updateMqttStatus(mqttStatus)
        }
    }

    private fun initSpinner() {
        val serialPortFinder = SerialPortFinder()
        val devices: ArrayList<Device> = serialPortFinder.devices
        val items = devices.map { it.file.absolutePath }
        val adapter = ArrayAdapter(requireActivity(), AndroidR.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        binding.spinner.adapter = adapter

        val defaultPosition = items.indexOf(configBean.modbusAddress)
        if (defaultPosition >= 0) {
            binding.spinner.setSelection(defaultPosition)
            devices.first { it.file.absolutePath == configBean.modbusAddress }.apply {
                configBean.modbusAddress = file.absolutePath
            }
        }

        binding.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View,
                position: Int,
                id: Long
            ) {
                val selectedItem = parent.getItemAtPosition(position).toString()
                devices.first { it.file.absolutePath == selectedItem }.apply {
                    configBean.modbusAddress = file.absolutePath
                }
                LogUtils.d("【配置页】已选择串口：$selectedItem")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 未选择串口时无需额外处理。
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
