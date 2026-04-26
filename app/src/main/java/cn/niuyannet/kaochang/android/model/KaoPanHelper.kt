package cn.niuyannet.kaochang.android.model

import android.util.Log

import cn.niuyannet.kaochang.android.init.AppConfig.getAppConfig
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


/**
 * 烤盘与烤肠箱本地缓存辅助类。
 *
 * 负责烤盘、烤肠箱、口味列表的本地缓存与异步持久化。
 */
object KaoPanHelper {
    /**
     * 对象内部统一后台作用域。
     *
     * 烤盘和烤肠箱列表的保存不需要绑定页面生命周期，但也不应继续使用 GlobalScope。
     * 因此统一走对象级后台作用域，避免失控的全局协程。
     */
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val FILE_SP_URL = "file_kaopan_helper"
    private const val KEY_KAO_PAN_LIST = "key_kao_pan_list"
    private const val KEY_KAO_PAN_BOX_LIST = "key_kao_pan_box_list"
    private const val KEY_KAO_PAN_CONFIG = "key_kao_pan_config"
    private const val KEY_TASTE_LIST = "key_taste_list" // 添加口味列表的键
    /** 默认烤制时长，40 分钟。 */
    val bakingTime = 40 * 60 * 1000L

    /** 默认保温起始时间，实际进入保温时会重新赋值。 */
    val holdingTime = 0L

    /** 默认丢弃时长，6 小时后必须自动丢弃。 */
    val discardTime = 6 * 60 * 60 * 1000L

    /** 当前口味目录缓存。 */
    private var tasteList = mutableListOf<Taste>()

    /** 当前烤盘状态缓存。 */
    private var kaoPanList = mutableListOf<KaoPan>()

    /** 当前烤肠箱状态缓存。 */
    private var kaoPanBoxList = mutableListOf<KaoPanBox>()

    /**
     * 构建默认 33 个烤盘配置。
     *
     * 用于首次安装、清缓存后重启，或本地烤盘缓存损坏/缺失时的兜底自愈。
     */
    private fun buildDefaultKaoPanList(): MutableList<KaoPan> {
        return mutableListOf<KaoPan>().apply {
            val positions = mutableListOf<Int>()
            for (i in 1..31 step 3) {
                positions.add(i)
            }

            for (i in 2..32 step 3) {
                positions.add(i)
            }

            for (i in 3..33 step 3) {
                positions.add(i)
            }

            for (i in 0 until 33) {
                val positionSn = positions[i]
                val region = when (positionSn) {
                    in 1..9 -> 1
                    in 10..12 -> 4
                    in 13..21 -> 2
                    in 22..24 -> 4
                    else -> 3
                }
                val cmdValueMove = positionSn
                val cmdValueTake = positionSn + 100
                val cmdValueDiscard = positionSn + 300
                val config = getAppConfig()
                val bakingTimeMinutes = config.bakingTime
                val discardTimeHours = config.discardTime

                val kaoPan = KaoPan()
                kaoPan.id = i
                kaoPan.positionSn = positionSn
                kaoPan.positionRegion = region
                kaoPan.startTime = 0L
                kaoPan.bakingTime = bakingTimeMinutes * 60L * 1000L
                kaoPan.holdingTime = holdingTime
                kaoPan.closeTime = discardTimeHours * 60L * 60L * 1000L
                kaoPan.taste = null
                kaoPan.temperature = 0
                kaoPan.status = 0
                kaoPan.isHasSausage = false
                kaoPan.isHasGrilling = false
                kaoPan.cmdValueMove = cmdValueMove
                kaoPan.cmdValueTake = cmdValueTake
                kaoPan.cmdStatusMove = 0
                kaoPan.cmdStatusTake = 0
                kaoPan.cmdValueDiscard = cmdValueDiscard
                kaoPan.cmdStatusDiscard = 0
                add(kaoPan)
            }
        }
    }

    /**
     * 确保烤盘列表已加载。
     *
     * `PreferenceUtils.getListPreference()` 在本地没有值时会返回 `null`，
     * 启动阶段若直接 `.toMutableList()` 会导致 NPE。这里统一做空保护和默认自愈。
     */
    private fun ensureKaoPanListLoaded(): MutableList<KaoPan> {
        if (kaoPanList.isNotEmpty()) {
            return kaoPanList
        }

        val savedKaoPanList = PreferenceUtils.getListPreference(FILE_SP_URL, KEY_KAO_PAN_LIST, KaoPan::class.java)
        if (!savedKaoPanList.isNullOrEmpty()) {
            kaoPanList = savedKaoPanList.toMutableList()
            return kaoPanList
        }

        kaoPanList = buildDefaultKaoPanList()
        PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_KAO_PAN_LIST, kaoPanList)
        LogUtils.w("【烤盘缓存】本地烤盘列表为空，已自动重建默认 33 个烤盘配置")
        return kaoPanList
    }

    /**
     * 初始化烤盘配置。
     *
     * 优先读取本地缓存；若本地还没有保存过，则按默认工位顺序生成 33 个烤盘并立即落盘。
     */
    fun init() {
        ensureKaoPanListLoaded()
    }
    /**
     * 重置烤盘
     */
    fun resetKaoPan() {
        val config = getAppConfig()
        val bakingTimeMinutes = config.bakingTime
        val discardTimeHours = config.discardTime
        for (kaoPan in kaoPanList) {
            kaoPan.startTime = 0L
            kaoPan.bakingTime = bakingTimeMinutes * 60L * 1000L
            kaoPan.holdingTime = holdingTime
            kaoPan.closeTime = discardTimeHours * 60L * 60L * 1000L
            kaoPan.taste = null
            kaoPan.temperature = 0
            kaoPan.status = 0
            kaoPan.isHasSausage = false
            kaoPan.isHasGrilling = false
            kaoPan.cmdStatusMove = 0
            kaoPan.cmdStatusTake = 0
            kaoPan.cmdStatusDiscard = 0
        }
        // 保存更新后的烤盘列表
        saveKaoPanList(kaoPanList)
    }

    /**
     * 获取烤盘列表
     */
    fun getKaoPanList():List<KaoPan>{
        return ensureKaoPanListLoaded()
    }

    /**
     * 获取烤肠箱列表
     */
    fun getKaoPanBoxList():List<KaoPanBox>{
        if (this.kaoPanBoxList.size>0){
            return this.kaoPanBoxList
        }
        this.kaoPanBoxList =
            (PreferenceUtils.getListPreference(FILE_SP_URL, KEY_KAO_PAN_BOX_LIST, KaoPanBox::class.java)
                ?: emptyList()).toMutableList()
        return this.kaoPanBoxList
    }
    /**
     * 获取口味列表
     */
    fun getTasteList():List<Taste>{
        if (tasteList.size>0){
            return tasteList
        }
        tasteList =
            (PreferenceUtils.getListPreference(FILE_SP_URL, KEY_TASTE_LIST, Taste::class.java)
                ?: emptyList()).toMutableList()
        return tasteList;
    }
    /**
     * 保存烤盘列表
     */
    fun saveKaoPanList(list: List<KaoPan>) {
        helperScope.launch {
            kaoPanList = list.toMutableList()
            PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_KAO_PAN_LIST, kaoPanList)
        }
    }


    /**
     * 保存烤箱列表
     */
    fun saveKaoPanBoxList(list: List<KaoPanBox>) {
        helperScope.launch {
            kaoPanBoxList = list.toMutableList()
            PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_KAO_PAN_BOX_LIST, kaoPanBoxList)
        }
    }


    /**
     * 保存口味列表
     */
    fun saveTasteList(list: List<Taste>) {
        tasteList = list.toMutableList()
        PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_TASTE_LIST, tasteList)
    }

    /**
     * 按最新配置统一回刷所有烤盘的保温超时阈值。
     *
     * 这里只更新 closeTime，不改 holdingTime，避免把“已经保温了多久”重新计时。
     * 后台修改丢弃时间后，当前保温中的烤肠也应立即按新阈值执行自动丢弃。
     *
     * @param discardTimeHours 丢弃时间，单位小时
     * @return 实际回刷的烤盘数量
     */
    fun refreshDiscardCloseTime(discardTimeHours: Int): Int {
        val normalizedHours = discardTimeHours.coerceAtLeast(0).toLong()
        val closeTimeMillis = normalizedHours * 60L * 60L * 1000L
        val currentPans = getKaoPanList()
        var correctedCount = 0
        currentPans.forEach { kaoPan ->
            if (kaoPan.closeTime != closeTimeMillis) {
                kaoPan.closeTime = closeTimeMillis
                correctedCount += 1
            }
        }
        if (correctedCount > 0) {
            saveKaoPanList(currentPans)
        }
        return correctedCount
    }

    fun normalizeKaoPanTasteMetadata(): Boolean {
        val currentPans = getKaoPanList()
        var changed = false
        currentPans.forEach { kaoPan ->
            if (!kaoPan.isHasSausage || kaoPan.taste == null) {
                return@forEach
            }
            val normalizedTaste = normalizeTasteMetadata(kaoPan.taste)
            if (normalizedTaste == null) {
                return@forEach
            }
            if (!isSameTasteMetadata(kaoPan.taste, normalizedTaste)) {
                kaoPan.taste = normalizedTaste
                changed = true
            }
        }
        if (changed) {
            saveKaoPanList(currentPans)
        }
        return changed
    }

    fun buildTasteFromBox(kaoPanBox: KaoPanBox, fallbackTaste: Taste? = null): Taste {
        val normalizedByCatalog = findTasteFromCatalog(
            productId = kaoPanBox.productId?.toIntOrNull(),
            tasteCode = kaoPanBox.tasteCode,
            tasteName = kaoPanBox.tasteName,
            productName = fallbackTaste?.productName
        )
        return mergeTasteMetadata(
            baseTaste = fallbackTaste,
            resolvedTaste = normalizedByCatalog,
            fallbackProductId = kaoPanBox.productId?.toIntOrNull(),
            fallbackTasteCode = kaoPanBox.tasteCode,
            fallbackTasteName = kaoPanBox.tasteName,
            fallbackProductName = fallbackTaste?.productName
        )
    }

    fun copyResolvedTaste(sourceTaste: Taste?): Taste? {
        return normalizeTasteMetadata(sourceTaste)
    }

    private fun normalizeTasteMetadata(sourceTaste: Taste?): Taste? {
        if (sourceTaste == null) {
            return null
        }
        val resolvedTaste = findTasteFromCatalog(
            productId = sourceTaste.productId.takeIf { it > 0 },
            tasteId = sourceTaste.tasteId.takeIf { it > 0 },
            tasteCode = sourceTaste.tasteCode,
            tasteName = sourceTaste.tasteName,
            productName = sourceTaste.productName
        )
        return mergeTasteMetadata(
            baseTaste = sourceTaste,
            resolvedTaste = resolvedTaste,
            fallbackProductId = sourceTaste.productId.takeIf { it > 0 },
            fallbackTasteId = sourceTaste.tasteId.takeIf { it > 0 },
            fallbackTasteCode = sourceTaste.tasteCode,
            fallbackTasteName = sourceTaste.tasteName,
            fallbackProductName = sourceTaste.productName
        )
    }

    private fun mergeTasteMetadata(
        baseTaste: Taste?,
        resolvedTaste: Taste?,
        fallbackProductId: Int? = null,
        fallbackTasteId: Int? = null,
        fallbackTasteCode: String? = null,
        fallbackTasteName: String? = null,
        fallbackProductName: String? = null
    ): Taste {
        return Taste().apply {
            tasteId = resolvedTaste?.tasteId
                ?: fallbackTasteId
                ?: baseTaste?.tasteId
                ?: 0
            tasteCode = resolvedTaste?.tasteCode
                ?: fallbackTasteCode
                ?: baseTaste?.tasteCode
                ?: ""
            tasteName = resolvedTaste?.tasteName
                ?: fallbackTasteName
                ?: baseTaste?.tasteName
                ?: ""
            productId = resolvedTaste?.productId
                ?: fallbackProductId
                ?: baseTaste?.productId
                ?: 0
            productName = resolvedTaste?.productName
                ?: fallbackProductName
                ?: baseTaste?.productName
                ?: ""
        }
    }

    private fun findTasteFromCatalog(
        productId: Int? = null,
        tasteId: Int? = null,
        tasteCode: String? = null,
        tasteName: String? = null,
        productName: String? = null
    ): Taste? {
        val currentTasteList = getTasteList()
        if (currentTasteList.isEmpty()) {
            return null
        }

        val normalizedTasteCode = normalizeTasteCode(tasteCode)
        val normalizedTasteName = normalizeLabel(tasteName)
        val normalizedProductName = normalizeLabel(productName)

        return currentTasteList.firstOrNull {
            productId != null && productId > 0 && it.productId == productId &&
                tasteId != null && tasteId > 0 && it.tasteId == tasteId
        } ?: currentTasteList.firstOrNull {
            productId != null && productId > 0 && it.productId == productId &&
                normalizedTasteCode.isNotBlank() && normalizeTasteCode(it.tasteCode) == normalizedTasteCode
        } ?: currentTasteList.firstOrNull {
            tasteId != null && tasteId > 0 && it.tasteId == tasteId
        } ?: currentTasteList.firstOrNull {
            normalizedTasteCode.isNotBlank() && normalizeTasteCode(it.tasteCode) == normalizedTasteCode
        } ?: currentTasteList.firstOrNull {
            normalizedTasteName.isNotBlank() && normalizeLabel(it.tasteName) == normalizedTasteName &&
                (productId == null || productId <= 0 || it.productId == productId)
        } ?: currentTasteList.firstOrNull {
            normalizedProductName.isNotBlank() && normalizeLabel(it.productName) == normalizedProductName &&
                normalizedTasteName.isNotBlank() && normalizeLabel(it.tasteName) == normalizedTasteName
        }
    }

    private fun isSameTasteMetadata(left: Taste?, right: Taste?): Boolean {
        if (left == null || right == null) {
            return left == right
        }
        return left.tasteId == right.tasteId &&
            normalizeTasteCode(left.tasteCode) == normalizeTasteCode(right.tasteCode) &&
            normalizeLabel(left.tasteName) == normalizeLabel(right.tasteName) &&
            left.productId == right.productId &&
            normalizeLabel(left.productName) == normalizeLabel(right.productName)
    }

    private fun normalizeTasteCode(code: String?): String {
        return code?.trim()?.uppercase(Locale.getDefault()) ?: ""
    }

    private fun normalizeLabel(label: String?): String {
        return label
            ?.trim()
            ?.replace(Regex("\\s+"), "")
            ?.uppercase(Locale.getDefault())
            ?: ""
    }

    /**
     * 根据位置获取烤盘
     */
    fun getKaoPanByPosition(positionSn: Int): KaoPan? {
        return kaoPanList.find { it.positionSn == positionSn }
    }

    /**
     * 清除所有数据
     */
    fun clearAllData() {
        PreferenceUtils.deletePreference(FILE_SP_URL)
        kaoPanList.clear()
        kaoPanBoxList.clear()
        tasteList.clear()
    }

    /**
     * 清空烤盘
     */
    fun clearKaoPan() {
        PreferenceUtils.deletePreference(FILE_SP_URL,KEY_KAO_PAN_LIST)
        kaoPanList.clear()
    }

    /**
     * 低并发，高并发初烤肠初始化口味分配
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeKaoPamRatio(listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { it.num > 0 && it.tasteCode != null }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }

        // 2. 检查是否可分配
        if (flavorTypes2.size > list_size) {
            LogUtils.d("KaoChangAlgorithm", "口味类型数量大于工位数分配失败！")
        }
        //烤箱库存总数
        val totalCount = flavorTypes2.values.sum().toFloat()

        // 3. 计算比例并排序
        val sortedFlavors = flavorTypes2.map { (flavor, count) ->
            flavor to count / totalCount
        }.sortedByDescending { (_, ratio) -> ratio }

        // 4. 按比例计算“理论分配值”
        val rawAllocations = sortedFlavors.associate { (flavor, ratio) ->
            flavor to (list_size * ratio)
        }

        // 5. 取整，并保证每种至少 1 个
        val allocation = rawAllocations.mapValues { (_, value) ->
            maxOf(1, value.toInt())
        }.toMutableMap()

        // 6. 修正总数，使分配总和严格等于 list_size
        var currentSum = allocation.values.sum()

        if (currentSum < list_size) {
            // 6.1 不足：按小数部分从大到小补
            val sortedByRemainder = rawAllocations
                .map { (flavor, value) ->
                    flavor to (value - value.toInt())
                }
                .sortedByDescending { it.second }

            var idx = 0
            while (currentSum < list_size) {
                val flavor = sortedByRemainder[idx % sortedByRemainder.size].first
                allocation[flavor] = allocation[flavor]!! + 1
                currentSum++
                idx++
            }

        } else if (currentSum > list_size) {
            // 6.2 超出：从分配最多的口味开始减，但不能减到 < 1
            val sortedByCount = allocation.entries
                .sortedByDescending { it.value }
                .map { it.key }

            var idx = 0
            while (currentSum > list_size) {
                val flavor = sortedByCount[idx % sortedByCount.size]
                if (allocation[flavor]!! > 1) {
                    allocation[flavor] = allocation[flavor]!! - 1
                    currentSum--
                }
                idx++
            }
        }

        return allocation
    }


    /**
     * (比例计算有问题，已弃用)
     * 低并发，高并发初烤肠初始化口味分配
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeKaoPamRatio_old(listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { it.num > 0 && it.tasteCode != null }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }

        // 2. 检查是否可分配
        if (flavorTypes2.size > list_size) {
            LogUtils.d("KaoChangAlgorithm", "口味类型数量大于工位数分配失败！")
        }
        //烤箱库存总数
        val totalCount = flavorTypes2.values.sum().toFloat()

        // 3. 计算比例并排序
        val sortedFlavors = flavorTypes2.map { (flavor, count) ->
            flavor to count / totalCount
        }.sortedByDescending { (_, ratio) -> ratio }

        // 4. 初始化每个口味至少1个位置
        val allocation = sortedFlavors.associate { (flavor, _) ->
            flavor to 1
        }.toMutableMap()

        // 5. 按比例分配剩余位置
        var remainingPositions = list_size - allocation.size
        if (remainingPositions > 0) {
            // 计算每个口味的额外分配
            val extraAllocations = sortedFlavors.map { (flavor, ratio) ->
                flavor to (remainingPositions * ratio).toInt()
            }.toMap()

            // 应用额外分配
            extraAllocations.forEach { (flavor, extra) ->
                allocation[flavor] = allocation[flavor]!! + extra
            }

            // 处理剩余位置（由于取整可能未完全分配）
            if(totalCount>0){
                var unallocated = remainingPositions - extraAllocations.values.sum()
                var index = 0
                while (unallocated > 0) {
                    val flavor = sortedFlavors[index].first
                    allocation[flavor] = allocation[flavor]!! + 1
                    unallocated--
                    index = (index + 1) % sortedFlavors.size
                }
            }
        }

        return allocation
    }



    /**
     * 低转高并发打标签初始化
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeHighKaoPamRatio(listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { it.num > 0 && it.tasteCode != null }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }

        // 2. 检查是否可分配
        if (flavorTypes2.size > list_size) {
            LogUtils.d("KaoChangAlgorithm", "口味类型数量大于工位数分配失败！")
        }
        //烤箱库存总数
        val totalCount = flavorTypes2.values.sum().toFloat()

        // 3. 计算比例并排序
        val sortedFlavors = flavorTypes2.map { (flavor, count) ->
            flavor to count / totalCount
        }.sortedByDescending { (_, ratio) -> ratio }

        // 4. 初始化每个口味
        val allocation = mutableMapOf<String, Int>()

        // 5. 按比例分配剩余位置
        var remainingPositions = list_size
        if (remainingPositions > 0) {
            // 计算每个口味的额外分配
            val extraAllocations = sortedFlavors.map { (flavor, ratio) ->
                flavor to (remainingPositions * ratio).toInt()
            }.toMap()

            // 处理剩余位置（由于取整可能未完全分配）
            var unallocated = remainingPositions - extraAllocations.values.sum()
            var index = 0
            while (unallocated > 0) {
                // 循环遍历所有口味，按比例高低顺序分配
                val flavor = sortedFlavors[index % sortedFlavors.size].first
                // 增加该口味一个工位
                allocation[flavor] = (allocation[flavor] ?: 0) + 1
                unallocated--
                index++
            }
            // 将额外分配的结果合并到总分配中
            extraAllocations.forEach { (flavor, count) ->
                allocation[flavor] = (allocation[flavor] ?: 0) + count
            }
        }

        return allocation
    }



    /**
     * 是否距离烤制时间还要前半小时
     * @param currentTimeStr 当前时间字符串，格式为"HH:mm"
     * @param businessTime 营业时间配置
     * @return 是否距离烤制时间还要前半小时
     */

     fun isBeforeBusinessHours( businessTime: BusinessTime,minute : Int): Boolean {
        try {
            val currentTimeStr = getCurrentTime()

            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            val current = format.parse(currentTimeStr) ?: return false

            // 获取当前是星期几（1-7，对应周一到周日）
            val calendar = Calendar.getInstance()
            // Calendar.DAY_OF_WEEK 返回 1-7，对应周日到周六，需要转换为 1-7 对应周一到周日
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1

            // 根据营业时间模式判断
            if ("everyday".equals(businessTime.mode, ignoreCase = true) && businessTime.defaultTime != null && businessTime.defaultTime.size >= 2) {
                // 默认模式：所有天使用相同的时间范围
                val end = format.parse(businessTime.defaultTime[1]) ?: return false

                return isTimeInRange(current, end, minute)
            } else if ("custom".equals(businessTime.mode, ignoreCase = true) && businessTime.customTimes != null) {
                // 自定义模式：每天可以有不同的时间范围
                for (timeItem in businessTime.customTimes) {
                    // 检查是否是当前日期且已启用
                    if (timeItem.day == dayOfWeek && timeItem.enabled && timeItem.timeRange != null && timeItem.timeRange.size >= 2) {
                        val end = format.parse(timeItem.timeRange[1]) ?: return false
                        return isTimeInRange(current, end, minute)
                    }
                }
            }
            // 如果没有匹配的时间范围，则不在营业时间内
            return false

        } catch (e: Exception) {
            Log.e("KaoPanHeper", "时间格式解析错误: $e")
            return false
        }
    }

    private fun isTimeInRange(current: Date, end: Date, minute: Int): Boolean {

        if (current.after(end)) {
            // 当前时间已经超过结束时间，直接返回true
            return true
        }
        // 计算距离营业结束的时间差（毫秒）
        val diffInMillis = end.time - current.time
        val beforeCloseMinutesInMillis = minute.coerceAtLeast(0) * 60 * 1000L

        // 判断当前时间是否已经进入“距离营业结束 minute 分钟内”的区间
        return diffInMillis <= beforeCloseMinutesInMillis
    }

    /**
     * 获取当前时间
     * @return 当前时间字符串，格式为"HH:mm"
     */
    private fun getCurrentTime(): String {
        val currentTime = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return dateFormat.format(currentTime.time)
    }






}
