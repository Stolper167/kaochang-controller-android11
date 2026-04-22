package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils

/**
 * 低并发模式控制器。
 *
 * 负责前区 1~9 的初始化、补肠、保温与过保丢弃，
 * 管理补热区 13~18 的加热与回补，并在满足条件时触发低并发转高并发。
 */
object LowCurrentTrafficMode {
    private const val TAG = "KaoChangAlgorithm"

    /**
     * 2 区加热开关：
     * - 0：允许加热
     * - 1：已经关闭，避免重复下发停加热指令
     */
    var TEMPERATURE_OFF = 0

    private var lastLowTrafficSnapshot: String? = null
    private var lastLowSupplementGateSnapshot: String? = null
    private var lastFrontKeepWarmAssistActive = false

    private fun modeText(value: Int): String = when (value) {
        1 -> "1(低并发)"
        2 -> "2(高并发)"
        else -> "$value(未知)"
    }

    private fun transitionText(value: Int): String = when (value) {
        0 -> "0(无过渡态)"
        1 -> "1(低转高过渡)"
        2 -> "2(高转低过渡)"
        else -> "$value(未知)"
    }

    private fun lowSupplementStateText(value: Int): String = when (value) {
        0 -> "0(无需补肠)"
        1 -> "1(补 13~15 补热区)"
        2 -> "2(补 16~18 补热区)"
        3 -> "3(补热区已空，可关闭 2 区加热)"
        else -> "$value(未知)"
    }

    private fun positionsText(list: List<KaoPan>, predicate: (KaoPan) -> Boolean): String {
        val positions = list.filter(predicate).map { it.positionSn }
        return if (positions.isEmpty()) "[]" else positions.joinToString(prefix = "[", postfix = "]")
    }

    internal fun reserveFrontSupplementCandidates(
        candidates: List<KaoPan>,
        count: Int
    ): List<Int> {
        val selectedCandidates = candidates.take(count)
        selectedCandidates.forEach { it.isHasGrilling = true }
        return selectedCandidates.map { it.positionSn }
    }

    internal fun clearStaleFrontReservations(
        frontPositions: List<KaoPan>,
        supplementPositions: List<KaoPan>
    ): List<Int> {
        val supplementAreaOccupied = supplementPositions.any { it.isHasSausage }
        if (supplementAreaOccupied) {
            return emptyList()
        }

        val staleReservedPositions = frontPositions.filter { !it.isHasSausage && it.isHasGrilling }
        staleReservedPositions.forEach { it.isHasGrilling = false }
        return staleReservedPositions.map { it.positionSn }
    }

    internal fun shouldKeepWarmFrontZoneDuringSupplementHeating(
        frontPositions: List<KaoPan>,
        supplementPositions: List<KaoPan>
    ): Boolean {
        val hasFrontKeepWarmSausage = frontPositions.any {
            it.isHasSausage && (it.status == 2 || it.holdingTime > 0L)
        }
        val hasSupplementHeatingSausage = supplementPositions.any {
            it.isHasSausage && (it.status == 1 || it.startTime > 0L)
        }
        return hasFrontKeepWarmSausage && hasSupplementHeatingSausage
    }

    private fun logLowSupplementGateIfNeeded(
        config: AppConfigBean,
        list1_9: List<KaoPan>,
        list13_15: List<KaoPan>,
        list16_18: List<KaoPan>,
        p1_9: Int,
        p13_15: Int,
        p16_18: Int,
        supplementDecision: Int,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean,
        isBeforeCloseWindow: Boolean
    ) {
        val frontEmptyPositions = positionsText(list1_9) { !it.isHasSausage }
        val frontGrillingReservedPositions = positionsText(list1_9) { !it.isHasSausage && it.isHasGrilling }
        val frontSupplementCandidates = positionsText(list1_9) { !it.isHasSausage && !it.isHasGrilling }
        val emptyPositions13_15 = positionsText(list13_15) { !it.isHasSausage }
        val emptyPositions16_18 = positionsText(list16_18) { !it.isHasSausage }
        val stateSnapshot =
            "front=$p1_9|13_15=$p13_15|16_18=$p16_18|decision=$supplementDecision|box=$boxSupplyAvailable|schedule=$schedulingEnabled|beforeClose=$isBeforeCloseWindow|error=${config.errorStatus}|empty1_9=$frontEmptyPositions|grilling1_9=$frontGrillingReservedPositions|candidate1_9=$frontSupplementCandidates|empty13_15=$emptyPositions13_15|empty16_18=$emptyPositions16_18"
        if (stateSnapshot == lastLowSupplementGateSnapshot) {
            return
        }
        lastLowSupplementGateSnapshot = stateSnapshot
        LogUtils.d(
            TAG,
            "【低并发补肠门槛】判定快照：" +
                "1~9空位=$p1_9，13~15空位=$p13_15，16~18空位=$p16_18，" +
                "补肠决策=${lowSupplementStateText(supplementDecision)}，" +
                "boxSupplyAvailable（本地烤肠箱有库存）=$boxSupplyAvailable，" +
                "schedulingEnabled（算法调度开启）=$schedulingEnabled，" +
                "isBeforeCloseWindow（停业前收口窗口）=$isBeforeCloseWindow，" +
                "errorStatus=${config.errorStatus}，" +
                "frontEmptyPositions（1~9空盘）=$frontEmptyPositions，" +
                "frontGrillingReservedPositions（1~9已预留补肠）=$frontGrillingReservedPositions，" +
                "frontSupplementCandidates（1~9可作为补肠目标空盘）=$frontSupplementCandidates，" +
                "emptyPositions13_15=$emptyPositions13_15，" +
                "emptyPositions16_18=$emptyPositions16_18"
        )
    }

    /**
     * 低并发主处理器。
     *
     * 关键状态说明：
     * - p1_9：前区 1~9 空位数
     * - p13_15：13~15 空位数
     * - p16_18：16~18 空位数
     * - supplementDecision：
     *   0=无需补肠，1=补 13~15，2=补 16~18，3=补热区为空可关 2 区加热
     */
    suspend fun lowTrafficMode(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>,
        listBox: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean = true
    ): Int {
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        val list13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val list13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, kaoPanList)
        val list16_18 = TrafficModeHelper.getTrafficModePositions(16, 18, kaoPanList)
        val clearedReservedPositions = clearStaleFrontReservations(list1_9, list13_18)
        if (clearedReservedPositions.isNotEmpty()) {
            LogUtils.w(
                TAG,
                "【低并发补肠】清理前区残留预留标记：" +
                    "positions=$clearedReservedPositions，原因=13~18 补热区已空，但前区空盘仍存在 isHasGrilling=true"
            )
        }

        val p1_9 = list1_9.count { !it.isHasSausage }
        val p13_18 = list13_18.count { !it.isHasSausage }
        val p13_15 = list13_15.count { !it.isHasSausage }
        val p16_18 = list16_18.count { !it.isHasSausage }
        val totalEmpty = p1_9 + p13_18

        val supplementDecision = lowConcurrencyIntestinalSupplementation(p1_9, p13_15, p16_18)
        val stateSnapshot =
            "total=$totalEmpty|front=$p1_9|13_15=$p13_15|16_18=$p16_18|decision=$supplementDecision|mode=${config.modeType}|transition=${config.transitionMode}|error=${config.errorStatus}"
        if (stateSnapshot != lastLowTrafficSnapshot) {
            LogUtils.d(
                TAG,
                "【低并发状态】状态变化：总空位=$totalEmpty，1~9空位=$p1_9，13~15空位=$p13_15，16~18空位=$p16_18，补肠决策=${lowSupplementStateText(supplementDecision)}，modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}，errorStatus=${config.errorStatus}"
            )
            lastLowTrafficSnapshot = stateSnapshot
        }

        val beforeCloseMinutes = config.timeBeforeClose.takeIf { it > 0 } ?: 30
        val isBeforeCloseWindow =
            KaoPanHelper.isBeforeBusinessHours(config.businessTime, beforeCloseMinutes)

        if (p1_9 >= 3 || supplementDecision != 0 || p13_15 < list13_15.size || p16_18 < list16_18.size) {
            logLowSupplementGateIfNeeded(
                config = config,
                list1_9 = list1_9,
                list13_15 = list13_15,
                list16_18 = list16_18,
                p1_9 = p1_9,
                p13_15 = p13_15,
                p16_18 = p16_18,
                supplementDecision = supplementDecision,
                boxSupplyAvailable = boxSupplyAvailable,
                schedulingEnabled = schedulingEnabled,
                isBeforeCloseWindow = isBeforeCloseWindow
            )
        }

        val keepWarmAssistActive = shouldKeepWarmFrontZoneDuringSupplementHeating(list1_9, list13_18)
        if (keepWarmAssistActive && !lastFrontKeepWarmAssistActive) {
            LogUtils.i(
                TAG,
                "【加热控制】检测到 1 区存在保温烤肠且 13~18 补热区正在加热，补写 1 区保温温度"
            )
            KaoChangOperate.keepWarm(1)
        }
        lastFrontKeepWarmAssistActive = keepWarmAssistActive

        if (totalEmpty == list1_9.size + list13_18.size) {
            if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                initLowTrafficMode(kaoPanList, listBox)
            } else {
                LogUtils.w(
                    TAG,
                    "【低并发初始化】跳过初始化：" +
                        "schedulingEnabled=$schedulingEnabled，boxSupplyAvailable=$boxSupplyAvailable，" +
                        "errorStatus=${config.errorStatus}，isBeforeCloseWindow=$isBeforeCloseWindow"
                )
            }
        } else if (p1_9 >= list13_15.size || p13_18 < list13_18.size) {
            if (p16_18 < list16_18.size) {
                checkLowHeatSausage16_18(kaoPanList)
            }

            if (p13_15 < list13_15.size) {
                checkLowHeatSausage(kaoPanList)
            }

            if (schedulingEnabled && supplementDecision == 1 && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                TEMPERATURE_OFF = 0
                addLowHeatSausage(kaoPanList, listBox, list13_15.size)
                LogUtils.i(TAG, "【低并发补肠】已向 13~15 补热区补肠")
            } else if (schedulingEnabled && supplementDecision == 2 && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                TEMPERATURE_OFF = 0
                addLowHeatSausage16_18(kaoPanList, listBox, list16_18.size)
                LogUtils.i(TAG, "【低并发补肠】已向 16~18 补热区补肠")
            } else if (supplementDecision == 1 || supplementDecision == 2) {
                LogUtils.w(
                    TAG,
                    "【低并发补肠】本轮未执行补肠：" +
                        "补肠决策=${lowSupplementStateText(supplementDecision)}，" +
                        "schedulingEnabled=$schedulingEnabled，" +
                        "boxSupplyAvailable=$boxSupplyAvailable，" +
                        "errorStatus=${config.errorStatus}，" +
                        "isBeforeCloseWindow=$isBeforeCloseWindow"
                )
            }
        }

        if (supplementDecision == 3 && TEMPERATURE_OFF != 1) {
            TEMPERATURE_OFF = 1
            LogUtils.i(TAG, "【加热控制】2 区补热区已空，关闭 2 区加热")
            KaoChangOperate.stopHeating(2)
        }

        checkLowHeatEnd(list1_9)
        checkLowHeatDiscard(list1_9)

        if (schedulingEnabled && boxSupplyAvailable && config.modeType == 1 && config.transitionMode == 0 && p1_9 >= 7 && p13_18 == 0) {
            LogUtils.w(
                TAG,
                "【并发切换】触发低转高：1~9 空位=$p1_9，13~18 全满，modeType=${modeText(config.modeType)}->2(高并发)，transitionMode=${transitionText(config.transitionMode)}->1(低转高过渡)"
            )
            switchLowToHigh()
        }

        if (schedulingEnabled) {
            SelfCleanManager.maybeRunZone3SelfClean(kaoPanList)
        }

        // 烤盘保存由 KaoPanHelper 内部后台作用域统一处理，这里直接提交最新状态。
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        return totalEmpty
    }

    /**
     * 根据前区空位情况决定补肠动作。
     *
     * 返回值：
     * - 0：无需补肠
     * - 1：补 13~15
     * - 2：补 16~18
     * - 3：补热区为空，可关 2 区加热
     */
    private fun lowConcurrencyIntestinalSupplementation(
        p1_9: Int,
        p13_15: Int,
        p16_18: Int
    ): Int {
        val p13_18 = p13_15 + p16_18
        if (p1_9 >= 3) {
            if (p13_15 == 3 && p16_18 == 3) {
                return 1
            } else if (p13_15 == 3 && p1_9 >= 6 && p16_18 == 0) {
                return 1
            } else if (p16_18 == 3 && p1_9 >= 6 && p13_15 == 0) {
                return 2
            }
        } else if (p13_18 == 6) {
            return 3
        }
        return 0
    }

    /**
     * 初始化低并发模式。
     * 从烤肠箱按口味比例向 1~9 工位上肠，并启动 1 区加热。
     */
    private suspend fun initLowTrafficMode(list: List<KaoPan>, listBox: List<KaoPanBox>) {
        LogUtils.i(TAG, "【低并发初始化】开始初始化低并发：前区目标工位=1~9")
        val allBoxEmpty = listBox.all { it.num <= 0 }
        if (allBoxEmpty) {
            LogUtils.w(TAG, "【低并发初始化】所有烤肠箱库存为空，停止 1 区加热")
            KaoChangOperate.stopHeating(1)
            return
        }

        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        KaoChangOperate.heating(1)

        val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox, list1_9.size)
        var currentPosition = 0
        flavorAllocation.forEach { (flavor, count) ->
            repeat(count) {
                if (currentPosition < list1_9.size) {
                    list1_9[currentPosition].taste = Taste().apply {
                        tasteCode = flavor
                    }
                    currentPosition++
                }
            }
        }

        list1_9.forEach { kaoPan ->
            val matchingBox = TrafficModeHelper.findSupplyBoxByTaste(listBox, kaoPan.taste?.tasteCode)
            matchingBox?.let { box ->
                if (box.num > 0) {
                    KaoChangOperate.moveSausageToKaoPan(kaoPan, box)
                }
            }
        }
    }

    /**
     * 检查 13~15 补热区是否已经烤熟。
     * 烤熟后搬回前区同口味的待补位，并在搬运成功后才将目标位标记为可售。
     */
    private suspend fun checkLowHeatSausage(list: List<KaoPan>) {
        val list13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, list)
        val list13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, list)
        var allFinished = true
        var heatingCount = 0

        list13_15.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                heatingCount += 1
                val heatTime = System.currentTimeMillis() - kaoPan.startTime
                if (heatTime < kaoPan.bakingTime) {
                    allFinished = false
                }
            }
        }

        if (allFinished && heatingCount > 0) {
            KaoChangOperate.keepWarm(1)
            LogUtils.i(TAG, "【低并发补肠】13~15 补热区已烤熟，开始回补前区")
            list13_15.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
                    val targetPan = TrafficModeHelper.selectFrontTargetPan(
                        list1_9,
                        kaoPan.taste.tasteCode,
                        requireReserved = true
                    )
                    targetPan?.let {
                        KaoChangOperate.moveKaoPanToKaoPan(
                            kaoPan,
                            it,
                            targetHoldingTime = System.currentTimeMillis()
                        )
                    } ?: LogUtils.w(
                        TAG,
                        "【低并发补肠】13~15 补热区烤熟回补失败：未找到同口味前区待补位，烤盘=${kaoPan.positionSn}，口味=${kaoPan.taste.tasteCode}"
                    )
                }
            }

            val remainingSupplement = list13_18.count { it.isHasSausage }
            if (remainingSupplement == 0) {
                LogUtils.i(TAG, "【加热控制】13~18 补热区已无烤肠，关闭 2 区加热")
                KaoChangOperate.stopHeating(2)
            }
        }
    }

    /**
     * 检查 16~18 补热区是否已经烤熟。
     * 烤熟后搬回前区同口味的待补位，并在搬运成功后才将目标位标记为可售。
     */
    private suspend fun checkLowHeatSausage16_18(list: List<KaoPan>) {
        val list13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, list)
        val list16_18 = TrafficModeHelper.getTrafficModePositions(16, 18, list)
        var allFinished = true
        var heatingCount = 0

        list16_18.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                heatingCount += 1
                val heatTime = System.currentTimeMillis() - kaoPan.startTime
                if (heatTime < kaoPan.bakingTime) {
                    allFinished = false
                }
            }
        }

        if (allFinished && heatingCount > 0) {
            KaoChangOperate.keepWarm(1)
            LogUtils.i(TAG, "【低并发补肠】16~18 补热区已烤熟，开始回补前区")
            list16_18.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
                    val targetPan = TrafficModeHelper.selectFrontTargetPan(
                        list1_9,
                        kaoPan.taste.tasteCode,
                        requireReserved = true
                    )
                    targetPan?.let {
                        KaoChangOperate.moveKaoPanToKaoPan(
                            kaoPan,
                            it,
                            targetHoldingTime = System.currentTimeMillis()
                        )
                    } ?: LogUtils.w(
                        TAG,
                        "【低并发补肠】16~18 补热区烤熟回补失败：未找到同口味前区待补位，烤盘=${kaoPan.positionSn}，口味=${kaoPan.taste.tasteCode}"
                    )
                }
            }

            val remainingSupplement = list13_18.count { it.isHasSausage }
            if (remainingSupplement == 0) {
                LogUtils.i(TAG, "【加热控制】13~18 补热区已无烤肠，关闭 2 区加热")
                KaoChangOperate.stopHeating(2)
            }
        }
    }

    /**
     * 向 13~15 补热区补肠。
     * 前区空位的口味决定补热区本轮上肠口味。
     */
    private suspend fun addLowHeatSausage(
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        pNum: Int
    ) {
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        val list13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, list)
        val hasSausageIn13_15 = list13_15.any { it.isHasSausage }
        if (hasSausageIn13_15) {
            LogUtils.w(
                TAG,
                "【低并发补肠】跳过 13~15 补热区补肠：13~15 已有烤肠，occupied=${positionsText(list13_15) { it.isHasSausage }}"
            )
            return
        }

        TrafficModeHelper.fillMissingTasteReservations(list1_9, listBox)
        val emptyPositions = list1_9.filter { !it.isHasSausage && !it.isHasGrilling }
        val emptyPositions13_15 = list13_15.filter { !it.isHasSausage }
        if (emptyPositions.size >= pNum && emptyPositions13_15.size >= pNum) {
            KaoChangOperate.heating(2)
            val neededTastes = emptyPositions.take(pNum).mapNotNull { it.taste?.tasteCode?.takeIf { code -> code.isNotBlank() } }
            if (neededTastes.size < pNum) {
                LogUtils.w(TAG, "【低并发补肠】跳过 13~15 补热区补肠：前区空盘口味预留不足，empty=${emptyPositions.size}，reserved=${neededTastes.size}")
                return
            }
            LogUtils.d(
                TAG,
                "【低并发补肠】准备向 13~15 补热区补肠：" +
                    "frontCandidates=${emptyPositions.take(pNum).map { it.positionSn }}，" +
                    "targetPositions=${emptyPositions13_15.take(pNum).map { it.positionSn }}，" +
                    "neededTastes=$neededTastes"
            )
            neededTastes.forEachIndexed { index, tasteCode ->
                val matchingBox = TrafficModeHelper.findSupplyBoxByTaste(listBox, tasteCode)
                if (matchingBox == null) {
                    LogUtils.w(
                        TAG,
                        "【低并发补肠】13~15 补热区补肠缺少对应库存箱：tasteCode=$tasteCode，index=$index"
                    )
                }
                matchingBox?.let { box ->
                    if (box.num > 0) {
                        val targetPosition = emptyPositions13_15[index]
                        targetPosition.taste = Taste().apply { this.tasteCode = tasteCode }
                        KaoChangOperate.moveSausageToKaoPan(targetPosition, box)
                    } else {
                        LogUtils.w(
                            TAG,
                            "【低并发补肠】13~15 补热区补肠跳过空库存箱：boxPosition=${box.positionSn}，tasteCode=$tasteCode，num=${box.num}"
                        )
                    }
                }
            }
            reserveFrontSupplementCandidates(emptyPositions, pNum)
        } else {
            LogUtils.w(
                TAG,
                "【低并发补肠】跳过 13~15 补热区补肠：可补前区空盘不足或补热区空位不足，" +
                    "required=$pNum，frontCandidates=${emptyPositions.map { it.positionSn }}，" +
                    "targetEmpty=${emptyPositions13_15.map { it.positionSn }}"
            )
        }
    }

    /**
     * 向 16~18 补热区补肠。
     */
    private suspend fun addLowHeatSausage16_18(
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        pNum: Int
    ) {
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        val list16_18 = TrafficModeHelper.getTrafficModePositions(16, 18, list)
        val hasSausageIn16_18 = list16_18.any { it.isHasSausage }
        if (hasSausageIn16_18) {
            LogUtils.w(
                TAG,
                "【低并发补肠】跳过 16~18 补热区补肠：16~18 已有烤肠，occupied=${positionsText(list16_18) { it.isHasSausage }}"
            )
            return
        }

        TrafficModeHelper.fillMissingTasteReservations(list1_9, listBox)
        val emptyPositions = list1_9.filter { !it.isHasSausage && !it.isHasGrilling }
        val emptyPositions16_18 = list16_18.filter { !it.isHasSausage }
        if (emptyPositions.size >= pNum && emptyPositions16_18.size >= pNum) {
            KaoChangOperate.heating(2)
            val neededTastes = emptyPositions.take(pNum).mapNotNull { it.taste?.tasteCode?.takeIf { code -> code.isNotBlank() } }
            if (neededTastes.size < pNum) {
                LogUtils.w(TAG, "【低并发补肠】跳过 16~18 补热区补肠：前区空盘口味预留不足，empty=${emptyPositions.size}，reserved=${neededTastes.size}")
                return
            }
            LogUtils.d(
                TAG,
                "【低并发补肠】准备向 16~18 补热区补肠：" +
                    "frontCandidates=${emptyPositions.take(pNum).map { it.positionSn }}，" +
                    "targetPositions=${emptyPositions16_18.take(pNum).map { it.positionSn }}，" +
                    "neededTastes=$neededTastes"
            )
            neededTastes.forEachIndexed { index, tasteCode ->
                val matchingBox = TrafficModeHelper.findSupplyBoxByTaste(listBox, tasteCode)
                if (matchingBox == null) {
                    LogUtils.w(
                        TAG,
                        "【低并发补肠】16~18 补热区补肠缺少对应库存箱：tasteCode=$tasteCode，index=$index"
                    )
                }
                matchingBox?.let { box ->
                    if (box.num > 0) {
                        val targetPosition = emptyPositions16_18[index]
                        targetPosition.taste = Taste().apply { this.tasteCode = tasteCode }
                        KaoChangOperate.moveSausageToKaoPan(targetPosition, box)
                    } else {
                        LogUtils.w(
                            TAG,
                            "【低并发补肠】16~18 补热区补肠跳过空库存箱：boxPosition=${box.positionSn}，tasteCode=$tasteCode，num=${box.num}"
                        )
                    }
                }
            }
            reserveFrontSupplementCandidates(emptyPositions, pNum)
        } else {
            LogUtils.w(
                TAG,
                "【低并发补肠】跳过 16~18 补热区补肠：可补前区空盘不足或补热区空位不足，" +
                    "required=$pNum，frontCandidates=${emptyPositions.map { it.positionSn }}，" +
                    "targetEmpty=${emptyPositions16_18.map { it.positionSn }}"
            )
        }
    }

    /**
     * 检查前区 1~9 是否整体加热完成。
     * 完成后关闭 1 区加热并进入保温状态。
     */
    private fun checkLowHeatEnd(list: List<KaoPan>) {
        var allFinished = true
        var heatingCount = 0
        list.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                heatingCount += 1
                val heatTime = System.currentTimeMillis() - kaoPan.startTime
                if (heatTime < kaoPan.bakingTime) {
                    allFinished = false
                }
            }
        }
        if (allFinished && heatingCount > 0) {
            KaoChangOperate.stopHeating(1)
            KaoChangOperate.keepWarm(1)
            list.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    kaoPan.startTime = 0
                    kaoPan.holdingTime = System.currentTimeMillis()
                    kaoPan.status = 2
                }
            }
            LogUtils.i(TAG, "【加热控制】前区 1~9 已全部烤熟，切换为保温状态")
        }
    }

    /**
     * 检查前区可售烤肠是否保温到期。
     * 仅当 holdingTime 超过 closeTime 时才触发自动丢弃。
     */
    private suspend fun checkLowHeatDiscard(list: List<KaoPan>) {
        list.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.holdingTime > 0) {
                val holdTime = System.currentTimeMillis() - kaoPan.holdingTime
                if (holdTime > kaoPan.closeTime) {
                    LogUtils.i(
                        TAG,
                        "【丢弃流程】检测到过保烤肠：来源=过保到期，烤盘=${kaoPan.positionSn}，已保温=${holdTime}ms，阈值=${kaoPan.closeTime}ms"
                    )
                    KaoChangOperate.discardSausage(
                        kaoPan,
                        source = "过保到期",
                        detail = "已保温=${holdTime}ms，阈值=${kaoPan.closeTime}ms"
                    )
                }
            }
        }
    }

    /**
     * 低并发 -> 高并发。
     * modeType 切到 2，transitionMode 切到 1，表示进入低转高过渡态。
     */
    private fun switchLowToHigh() {
        AppConfig.getAppConfig().apply {
            modeType = 2
            transitionMode = 1
        }
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        LogUtils.i(
            TAG,
            "【并发切换】低转高状态已写入本地：modeType=${modeText(AppConfig.getAppConfig().modeType)}，transitionMode=${transitionText(AppConfig.getAppConfig().transitionMode)}"
        )
        SendServerHelper.publishServiceUpdateStatus()
    }
}
