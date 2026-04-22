package cn.niuyannet.kaochang.android.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.adapter.KaoPanBoxAdapter
import cn.niuyannet.kaochang.android.databinding.FragmentMaintenanceBoxBinding
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.dismissLoadingExt
import cn.niuyannet.kaochang.android.utils.showLoadingExt
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MaintenanceBoxFragment : Fragment() {
    private companion object {
        const val BOX_GRID_SPAN_COUNT = 11
        const val BOX_REFILL_TARGET_NUM = 16
    }
    private var _binding: FragmentMaintenanceBoxBinding? = null
    private val binding get() = _binding!!
    private var refreshJob: Job? = null
    private lateinit var kaoPanBoxAdapter: KaoPanBoxAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMaintenanceBoxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGrid()
        setupButtons()
        ensureBoxDataLoaded()
    }

    /**
     * 设置顶部按钮点击事件。
     */
    private fun setupButtons() {
        binding.btnMoveToKaopan.setOnClickListener {
            LogUtils.d("[维护操作] 点击烤肠箱搬运到烤盘")
            moveOvenToKaoPan()
        }
        binding.btnClearAll.setOnClickListener {
            LogUtils.d("[维护操作] 点击清空选中烤肠箱")
            clearOven()
        }
    }

    /**
     * 将选中的烤肠箱搬运到指定烤盘位置。
     */
    private fun moveOvenToKaoPan() {
        val selectedBoxes = KaoPanHelper.getKaoPanBoxList().filter { it.checkStatus == true }
        if (selectedBoxes.isEmpty()) {
            ToastUtils.showShort("请先选择烤肠箱")
            return
        }
        if (selectedBoxes.size > 1) {
            ToastUtils.showShort("只能选择一个烤肠箱")
            return
        }
        if (selectedBoxes[0].num <= 0) {
            ToastUtils.showShort("当前烤肠箱库存不足，无法继续补肠")
            return
        }

        MaterialDialog(requireContext()).show {
            title(text = "请选择目标烤盘位置")
            customView(R.layout.dialog_number_picker, noVerticalPadding = true)
            positiveButton(text = "确定") { dialog ->
                val numberPicker = dialog.getCustomView().findViewById<NumberPicker>(R.id.number_picker)
                val targetPosition = KaoPanHelper.getKaoPanList().first { it.positionSn == numberPicker.value }
                val boxPosition = selectedBoxes[0]
                LogUtils.d(
                    "[维护操作] 确认烤肠箱搬运：箱位=${boxPosition.positionSn}，口味=${
                        boxPosition.tasteName ?: "未知口味"
                    }，目标烤盘=${targetPosition.positionSn}"
                )
                showLoadingExt("移动中...")
                viewLifecycleOwner.lifecycleScope.launch {
                    targetPosition.taste = Taste().apply {
                        tasteCode = boxPosition.tasteCode
                    }

                    val result = withContext(Dispatchers.IO) {
                        KaoChangOperate.moveSausageToKaoPan(targetPosition, boxPosition)
                    }
                    dismissLoadingExt()
                    if (result) {
                            LogUtils.d("[维护操作] 烤肠箱搬运完成：箱位=${boxPosition.positionSn} -> 烤盘${targetPosition.positionSn}")
                            ToastUtils.showShort("移动成功")
                    } else {
                            LogUtils.e("[维护操作] 烤肠箱搬运失败：箱位=${boxPosition.positionSn} -> 烤盘${targetPosition.positionSn}")
                            ToastUtils.showShort("移动失败")
                    }
                }
            }
            negativeButton(text = "取消")
            lifecycleOwner(viewLifecycleOwner)
        }.apply {
            val numberPicker = getCustomView().findViewById<NumberPicker>(R.id.number_picker)
            numberPicker.minValue = 1
            numberPicker.maxValue = 33
            numberPicker.value = 1
            numberPicker.wrapSelectorWheel = false
        }
    }

    /**
     * 一键清空选中的烤肠箱。
     */
    private fun clearOven() {
        val selectedBoxes = KaoPanHelper.getKaoPanBoxList().filter { it.checkStatus == true }
        if (selectedBoxes.isEmpty()) {
            ToastUtils.showShort("请先选择需要清空的烤肠箱")
        } else {
            LogUtils.d("[维护操作] 确认清空烤肠箱：${selectedBoxes.joinToString { "箱位${it.positionSn}" }}")
            showClearConfirmDialog(selectedBoxes)
        }
    }

    /**
     * 显示清空确认对话框。
     */
    private fun showClearConfirmDialog(selectedBoxes: List<KaoPanBox>) {
        MaterialDialog(requireContext()).show {
            title(text = "确认清空选中的烤肠箱吗？")
            positiveButton(text = "确定") { dialog ->
                dialog.dismiss()
                for (box in selectedBoxes) {
                    box.productId = null
                    box.tasteCode = null
                    box.tasteName = null
                    box.checkStatus = false
                    box.num = 0
                }
                showLoadingExt("设置中...")
                NetApi.clearStock(JSON.toJSONString(KaoPanHelper.getKaoPanBoxList())) { code, content ->
                    dismissLoadingExt()
                    if (code == 0) {
                        val json = JSON.parseObject(content)
                        if (json.getIntValue("code") == 200) {
                            KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
                            kaoPanBoxAdapter.notifyDataSetChanged()
                            LogUtils.d("[维护操作] 已清空烤肠箱：${selectedBoxes.joinToString { "箱位${it.positionSn}" }}")
                            ToastUtils.showShort("已清空烤肠箱")
                        } else {
                            ToastUtils.showShort(json.getString("msg"))
                        }
                    } else {
                        ToastUtils.showShort("设置失败")
                    }
                }
            }
            negativeButton(text = "取消")
            lifecycleOwner(viewLifecycleOwner)
        }
    }

    /**
     * 启动定时刷新。
     */
    private fun startRefreshTimer() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            flow {
                while (true) {
                    emit(Unit)
                    delay(1000)
                }
            }.collect {
                kaoPanBoxAdapter.setData(KaoPanHelper.getKaoPanBoxList())
            }
        }
    }

    /**
     * 初始化烤肠箱列表。
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun setupGrid() {
        kaoPanBoxAdapter = KaoPanBoxAdapter {
            kaoPanBoxAdapter.notifyDataSetChanged()
        }
        binding.rvKaopanBox.apply {
            layoutManager = GridLayoutManager(context, BOX_GRID_SPAN_COUNT)
            adapter = kaoPanBoxAdapter
        }
        startRefreshTimer()

        binding.btnRefill.setOnClickListener {
            val selectedBoxes = KaoPanHelper.getKaoPanBoxList().filter { it.checkStatus == true }
            if (selectedBoxes.isEmpty()) {
                ToastUtils.showShort("请先选择需要加满的烤肠箱")
                return@setOnClickListener
            }
            LogUtils.d("[维护操作] 点击加满烤肠箱：${selectedBoxes.joinToString { "箱位${it.positionSn}" }}")
            showTasteSelectDialog(selectedBoxes)
        }
    }

    private fun ensureBoxDataLoaded() {
        if (KaoPanHelper.getKaoPanBoxList().isNotEmpty()) {
            return
        }
        LogUtils.w("[维护操作] 烤肠箱本地缓存为空，进入页面后主动补拉库存")
        showLoadingExt("同步烤肠箱中...")
        DataManagementAPI.refreshStockData(logPrefix = "维护页烤肠箱首屏补拉") { success, stockCount ->
            activity?.runOnUiThread {
                dismissLoadingExt()
                if (!isAdded || _binding == null) {
                    return@runOnUiThread
                }
                if (success) {
                    kaoPanBoxAdapter.setData(KaoPanHelper.getKaoPanBoxList())
                    LogUtils.i("[维护操作] 烤肠箱首屏补拉完成：箱位数=$stockCount")
                } else {
                    ToastUtils.showShort("烤肠箱数据同步失败，请稍后重试")
                }
            }
        }
    }

    /**
     * 显示口味选择对话框。
     */
    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun showTasteSelectDialog(selectedBoxes: List<KaoPanBox>) {
        val dialog = MaterialDialog(requireContext()).apply {
            customView(R.layout.dialog_taste_select, noVerticalPadding = true)
            cancelOnTouchOutside(true)
        }

        val customView = dialog.getCustomView()
        val radioGroup = customView.findViewById<RadioGroup>(R.id.radio_group)
        val btnCancel = customView.findViewById<MaterialButton>(R.id.btn_cancel)
        val btnConfirm = customView.findViewById<MaterialButton>(R.id.btn_confirm)

        val tastes = KaoPanHelper.getTasteList()
        if (tastes.isEmpty()) {
            LogUtils.e("[维护操作] 打开加满口味弹窗失败：口味列表为空，无法为${selectedBoxes.joinToString { "箱位${it.positionSn}" }}设置口味")
            ToastUtils.showShort("口味列表为空，请先检查商品同步或网络状态")
            dialog.dismiss()
            return
        }

        tastes.forEachIndexed { index, taste ->
            val radioButton = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = "${taste.productName}(${taste.tasteName})"
                textSize = 16f
                setTextColor(resources.getColorStateList(R.color.radio_text_selector, null))
                background = ContextCompat.getDrawable(requireContext(), R.drawable.radio_button_selector)
                buttonDrawable = null
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8.dpToPx(), 0, 8.dpToPx())
                }
                setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
                isChecked = index == 0
            }
            radioGroup.addView(radioButton)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val selectedId = radioGroup.checkedRadioButtonId
            val selectedView = radioGroup.findViewById<RadioButton>(selectedId)
            val selectedIndex = radioGroup.indexOfChild(selectedView)
            if (selectedIndex < 0 || selectedIndex >= tastes.size) {
                LogUtils.e("[维护操作] 加满烤肠箱失败：未选中有效口味，selectedId=$selectedId, selectedIndex=$selectedIndex, tasteCount=${tastes.size}")
                ToastUtils.showShort("请先选择有效口味")
                return@setOnClickListener
            }

            val selectedTaste = tastes[selectedIndex]
            LogUtils.d(
                "[维护操作] 准备加满烤肠箱：${selectedBoxes.joinToString { "箱位${it.positionSn}" }}，" +
                    "口味=${selectedTaste.productName}(${selectedTaste.tasteName})，目标库存=$BOX_REFILL_TARGET_NUM"
            )

            for (box in selectedBoxes) {
                box.productId = selectedTaste.productId.toString()
                box.tasteCode = selectedTaste.tasteCode
                box.tasteName = "${selectedTaste.productName}(${selectedTaste.tasteName})"
                box.checkStatus = false
                box.num = BOX_REFILL_TARGET_NUM
            }

            showLoadingExt("设置中...")
            NetApi.updateStock(JSON.toJSONString(KaoPanHelper.getKaoPanBoxList())) { code, content ->
                dismissLoadingExt()
                if (code == 0) {
                    val json = JSON.parseObject(content)
                    if (json.getIntValue("code") == 200) {
                        KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
                        kaoPanBoxAdapter.notifyDataSetChanged()
                        LogUtils.d(
                            "[维护操作] 已加满烤肠箱：${selectedBoxes.joinToString { "箱位${it.positionSn}" }}，" +
                                "口味=${selectedTaste.productName}(${selectedTaste.tasteName})，库存=$BOX_REFILL_TARGET_NUM"
                        )
                        ToastUtils.showShort("已成功加满选中的烤肠箱")
                        dialog.dismiss()
                    } else {
                        ToastUtils.showShort(json.getString("msg"))
                    }
                } else {
                    ToastUtils.showShort("添加失败")
                }
            }
        }

        dialog.show()
    }

    /**
     * dp 转 px。
     */
    private fun Int.dpToPx(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        _binding = null
    }
}
