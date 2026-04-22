package cn.niuyannet.kaochang.android.adapter

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.databinding.ItemKaopanBoxBinding
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import com.blankj.utilcode.util.ToastUtils
import java.util.Random

class KaoPanBoxAdapter(private val callBack: (() -> Unit)? = null) : 
    RecyclerView.Adapter<KaoPanBoxAdapter.ViewHolder>() {
    private companion object {
        const val BOX_FULL_CAPACITY = 16
    }

    private var dataList = mutableListOf<KaoPanBox>()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(list: List<KaoPanBox>) {
        dataList= list.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKaopanBoxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, callBack)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(dataList[position])
    }

    override fun getItemCount(): Int = dataList.size

    class ViewHolder(
        private val binding: ItemKaopanBoxBinding,
        private val callBack: (() -> Unit)? = null
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(kaoPanBox: KaoPanBox) {
            binding.apply {
                // 设置位置编号
                tvPosition.text = "${kaoPanBox.positionSn}"
                
                // 设置口味名称和背景
                tvTasteCode.text = kaoPanBox.tasteName ?: "暂无添加产品"
                // 根据口味设置对应的背景色和文字颜色
                // 根据口味名称生成随机但固定的颜色
                val bgColor = kaoPanBox.tasteName?.let {
                    // 使用口味名称的hashCode作为随机种子,保证同样的口味名称生成相同的颜色
                    val random = Random(it.hashCode().toLong())
                    val hue = random.nextFloat() * 360
                    val saturation = 0.7f + random.nextFloat() * 0.3f // 70-100% 饱和度
                    val brightness = 0.6f + random.nextFloat() * 0.4f // 60-100% 亮度
                    Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
                } ?: Color.GRAY
                
                // 计算颜色的亮度来决定文字颜色
                val textColor = if (bgColor is Int && bgColor > 0x1000000) {
                    // 如果是直接的颜色值
                    val brightness = (Color.red(bgColor) * 299 +
                                   Color.green(bgColor) * 587 +
                                   Color.blue(bgColor) * 114) / 1000
                    if (brightness > 128) Color.BLACK else Color.WHITE
                } else {
                    // 如果是资源ID,使用默认白色
                    Color.WHITE
                }
                linBg.setBackgroundColor(bgColor)
                tvTasteCode.setTextColor(textColor)
                
                // 设置烤肠数量和视觉效果
                tvNum.text = "${kaoPanBox.num}"
                
                // 根据加满状态设置不同样式
                if (kaoPanBox.num >= BOX_FULL_CAPACITY) {
                    // 已加满状态
                    tvNum.setTextColor(ContextCompat.getColor(root.context, R.color.primary))
                    tvNum.textSize = 26f
                    tvNum.setTypeface(null, Typeface.BOLD)
                } else if (kaoPanBox.num > 0) {
                    // 部分加满状态
                    tvNum.setTextColor(ContextCompat.getColor(root.context, R.color.black))
                    tvNum.textSize = 24f
                    tvNum.setTypeface(null, Typeface.NORMAL)
                } else {
                    // 空状态
                    tvNum.setTextColor(ContextCompat.getColor(root.context, R.color.red))
                    tvNum.textSize = 24f
                    tvNum.setTypeface(null, Typeface.NORMAL)
                }
                
                // 处理选中状态
                if (kaoPanBox.checkStatus == true) {
                    // 选中状态 - 设置高亮边框
                    linView.setBackgroundResource(R.drawable.shap_selected_box)
                    // 设置选中状态的位置号背景
                    tvPosition.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(root.context, R.color.primary)
                    )
                } else {
                    // 未选中状态
                    linView.setBackgroundResource(R.drawable.shap_normal_box)
                    // 恢复默认背景
                    tvPosition.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(root.context, R.color.blue_500)
                    )
                }
                
                // 点击事件处理
                linView.setOnClickListener {
//                    if (kaoPanBox.num == 14) {
//                        ToastUtils.showShort("烤肠箱已满，不需要加烤肠")
//                        return@setOnClickListener
//                    }
                    kaoPanBox.checkStatus = !(kaoPanBox.checkStatus ?: false)
                    callBack?.invoke()
                }
            }
        }
    }
}
