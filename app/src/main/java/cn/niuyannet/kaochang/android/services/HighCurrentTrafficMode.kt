package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 高并发模式控制器。
 *
 * 负责高并发前区 1~21 与补热区 25~33 的初始化、补肠、回补、过保丢弃，
 * 同时根据营业结束时间和补肠节奏判断是否进入高转低过渡态。
 */
object HighCurrentTrafficMode {
    private const val TAG = "KaoChangAlgorithm"

    /** 已触发的高并发补肠坑位计数，仅用于状态观察。 */
    private var pitCount = 0

    /** 最近一次高并发补肠成功时间，用于判断高转低。 */
    private var lastFillPitTime = 0L

    private var lastHighTrafficSnapshot: String? = null

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

    private fun currentSupplementTrackingState(): HighTrafficSupplementTrackingState =
        HighTrafficSupplementTrackingState(
            pitCount = pitCount,
            lastFillPitTime = lastFillPitTime
        )

    private fun applySupplementTrackingState(
        nextState: HighTrafficSupplementTrackingState,
        reason: String
    ) {
        val previous = currentSupplementTrackingState()
        if (previous == nextState) {
            return
        }
        pitCount = nextState.pitCount
        lastFillPitTime = nextState.lastFillPitTime
        LogUtils.d(
            TAG,
            "【高并发补肠】更新补肠节奏跟踪：reason=$reason，pitCount=${previous.pitCount}->${nextState.pitCount}，lastFillPitTime=${previous.lastFillPitTime}->${nextState.lastFillPitTime}"
        )
    }

    private fun resetSupplementTracking(reason: String) {
        applySupplementTrackingState(
            nextState = resetHighTrafficSupplementTracking(),
            reason = reason
        )
    }

    private fun recordSupplementLaunch(
        filledCount: Int,
        now: Long,
        reason: String
    ) {
        applySupplementTrackingState(
            nextState = recordHighTrafficSupplementLaunch(
                previous = currentSupplementTrackingState(),
                filledCount = filledCount,
                now = now
            ),
            reason = reason
        )
    }

    private fun clearPitCountAfterBatch(reason: String) {
        applySupplementTrackingState(
            nextState = currentSupplementTrackingState().copy(pitCount = 0),
            reason = reason
        )
    }

    /**
     * 高并发主处理器。
     *
     * transitionMode 含义：
     * - 0：正常高并发
     * - 1：低转高过渡
     * - 2：高转低过渡
     */
    suspend fun highTrafficMode(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean = true
    ) {
        when (config.transitionMode) {
            0 -> {
                val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
                val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
                val p1_21 = list1_21.count { !it.isHasSausage }
                val p25_33 = list25_33.count { !it.isHasSausage }
                val totalEmpty = p1_21 + p25_33

                val stateSnapshot =
                    "front=$p1_21|25_33=$p25_33|total=$totalEmpty|pit=$pitCount|mode=${config.modeType}|transition=${config.transitionMode}|error=${config.errorStatus}"
                if (stateSnapshot != lastHighTrafficSnapshot) {
                    LogUtils.d(
                        TAG,
                        "【高并发状态】状态变化：1~21 空位=$p1_21，25~33 空位=$p25_33，总空位=$totalEmpty，pitCount=$pitCount，modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}，errorStatus=${config.errorStatus}"
                    )
                    lastHighTrafficSnapshot = stateSnapshot
                }

                val beforeCloseMinutes = config.timeBeforeClose.takeIf { it > 0 } ?: 30
                val isBeforeCloseWindow =
                    KaoPanHelper.isBeforeBusinessHours(config.businessTime, beforeCloseMinutes)

                if (totalEmpty == 30) {
                    if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                        initHighTrafficMode(list, listBox)
                        resetSupplementTracking("高并发初始化重新开盘")
                    } else {
                        LogUtils.w(
                            TAG,
                            "【高并发初始化】跳过初始化：boxSupplyAvailable=$boxSupplyAvailable，errorStatus=${config.errorStatus}，处于停业前收口窗口=$isBeforeCloseWindow"
                        )
                    }
                } else if (p1_21 >= 9) {
                    if (p25_33 == 9) {
                        if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                            val movedCount = addHighHeatSausage(list, listBox)
                            if (movedCount > 0) {
                                recordSupplementLaunch(
                                    filledCount = movedCount,
                                    now = System.currentTimeMillis(),
                                    reason = "高并发正常态向25~33批量补肠"
                                )
                                LogUtils.i(
                                    TAG,
                                    "【高并发补肠】已向 25~33 补热区批量补肠：movedCount=$movedCount，lastFillPitTime=$lastFillPitTime"
                                )
                            }
                        } else if (isBeforeCloseWindow) {
                            LogUtils.i(TAG, "【高并发补肠】接近营业结束，停止向 25~33 补热区继续补肠")
                        }
                    } else {
                        checkHighHeatEnd(list25_33, 3)
                        checkHighHeatSausage(list)
                    }
                }

                val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
                val list10_21 = TrafficModeHelper.getTrafficModePositions(10, 21, list)
                checkHighHeatEnd(list1_9, 1)
                checkHighHeatEnd(list10_21, 2)
                checkHighHeatDiscard(list1_21)
                if (schedulingEnabled && boxSupplyAvailable) {
                    checkSwitchToLow()
                }
                if (schedulingEnabled) {
                    SelfCleanManager.maybeRunZone3SelfClean(list)
                }

                // 烤盘保存由 KaoPanHelper 内部后台作用域统一处理，这里直接提交最新状态。
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            }

            1 -> {
                lowTrafficModeTransition(config, list, listBox, boxSupplyAvailable, schedulingEnabled)
            }

            2 -> {
                highTrafficModeTransition(config, list, listBox)
            }
        }
    }

    /**
     * 低并发 -> 高并发过渡。
     * 13~15 原补热区需要先完成加热，再进入过渡保温；
     * 25~33 启动新一轮高并发补肠。
     *
     * 注意：
     * 13~15 在过渡期间不会提前写入 holdingTime，避免尚未完成交接就被当成可售。
     */
    private suspend fun lowTrafficModeTransition(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean
    ) {
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val list13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, list)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)

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

        val hasStartedHighHeatSupply = list25_33.any { it.isHasSausage }
        if (!hasStartedHighHeatSupply && boxSupplyAvailable && schedulingEnabled) {
            val frontEmptyCount = list1_21.count { !it.isHasSausage }
            val flavorAllocation = KaoPanHelper.computeHighKaoPamRatio(listBox, frontEmptyCount)
            var currentPosition = 0
            flavorAllocation.forEach { (flavor, count) ->
                var allocated = 0
                while (allocated < count && currentPosition < list1_21.size) {
                    while (currentPosition < list1_21.size && list1_21[currentPosition].isHasSausage) {
                        currentPosition++
                    }
                    if (currentPosition < list1_21.size) {
                        list1_21[currentPosition].taste = Taste().apply {
                            tasteCode = flavor
                        }
                        allocated++
                        currentPosition++
                    } else {
                        break
                    }
                }
            }

            val emptyPositions25_33 = list25_33.filter { !it.isHasSausage }
            val emptyPositions1_21 = list1_21.filter { !it.isHasSausage }
            val neededTastes = emptyPositions1_21.take(9).map { it.taste.tasteCode }

            KaoChangOperate.heating(3)
            var movedCount = 0
            neededTastes.forEachIndexed { index, tasteCode ->
                val matchingBox = TrafficModeHelper.findSupplyBoxByTaste(listBox, tasteCode)
                matchingBox?.let { box ->
                    if (box.num > 0 && index < emptyPositions25_33.size) {
                        val targetPosition = emptyPositions25_33[index]
                        targetPosition.taste = Taste().apply { this.tasteCode = tasteCode }
                        if (KaoChangOperate.moveSausageToKaoPan(targetPosition, box)) {
                            movedCount += 1
                        }
                    }
                }
            }
            if (movedCount > 0) {
                recordSupplementLaunch(
                    filledCount = movedCount,
                    now = System.currentTimeMillis(),
                    reason = "低转高过渡启动25~33高并发补肠"
                )
                LogUtils.i(
                    TAG,
                    "【并发切换】低转高过渡：已向 25~33 启动高并发补肠，movedCount=$movedCount，lastFillPitTime=$lastFillPitTime"
                )
            } else {
                LogUtils.w(TAG, "【并发切换】低转高过渡：尝试向 25~33 启动高并发补肠，但本次未成功移动烤肠")
            }
        }

        if (allFinished && heatingCount > 0) {
            KaoChangOperate.stopHeating(2)
            KaoChangOperate.keepWarm(2)
            list13_15.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    kaoPan.startTime = 0
                    kaoPan.holdingTime = 0L
                    kaoPan.status = 2
                }
            }
            LogUtils.i(TAG, "【并发切换】低转高过渡：13~15 旧补热区已烤熟，进入过渡保温")
        }

        val hasActiveHeatingIn13_15 = list13_15.any { it.isHasSausage && it.startTime > 0 }
        val hasStartedHighHeatSupplyNow = list25_33.any { it.isHasSausage }
        val idleResolvedState = resolveLowToHighTransitionIdleState(
            currentModeType = config.modeType,
            schedulingEnabled = schedulingEnabled,
            boxSupplyAvailable = boxSupplyAvailable,
            hasActiveHeatingIn13_15 = hasActiveHeatingIn13_15,
            hasStartedHighHeatSupplyNow = hasStartedHighHeatSupplyNow
        )
        if (idleResolvedState != null) {
            config.transitionMode = idleResolvedState.transitionMode
            config.modeType = idleResolvedState.modeType
            AppConfig.saveAppConfig(config)
            SendServerHelper.publishServiceUpdateStatus()
            resetSupplementTracking("低转高过渡收口并保留高并发目标")
            LogUtils.i(
                TAG,
                "【并发切换】低转高过渡暂不继续补肠：boxSupplyAvailable=$boxSupplyAvailable，schedulingEnabled=$schedulingEnabled，保留高并发目标等待后续调度启用"
            )
            return
        }
        if (hasActiveHeatingIn13_15 || !hasStartedHighHeatSupplyNow) {
            return
        }

        // 进入真正高并发正常态前，记录一次完成条件快照，便于排查“为什么此刻完成切换”。
        val frontOccupiedCount = list1_21.count { it.isHasSausage }
        val highSupplyOccupiedCount = list25_33.count { it.isHasSausage }
        LogUtils.i(
            TAG,
            "【并发切换】低转高完成条件满足：13~15 仍有在烤旧批次=$hasActiveHeatingIn13_15，25~33 已启动新补热=$hasStartedHighHeatSupplyNow，1~21 当前有肠数=$frontOccupiedCount，25~33 当前有肠数=$highSupplyOccupiedCount"
        )

        val now = System.currentTimeMillis()
        list13_15.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.startTime == 0L && kaoPan.holdingTime <= 0L) {
                kaoPan.holdingTime = now
                kaoPan.status = 2
            }
        }
        config.transitionMode = 0
        AppConfig.saveAppConfig(config)
        SendServerHelper.publishServiceUpdateStatus()
        LogUtils.i(
            TAG,
            "【并发切换】低转高过渡完成：modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}"
        )
    }

    /**
     * 高并发 -> 低并发过渡。
     * 不再继续执行 p>=9 的新补肠，只消化现有库存；
     * 当 1~21 只剩 6 根且 25~33 已清空时，切回低并发。
     */
    private suspend fun highTrafficModeTransition(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>
    ) {
        if (AppConfig.getAppConfig().onlineStatus == 4 || AppConfig.getAppConfig().moveStatus != 0) {
            LogUtils.i(
                TAG,
                "【并发切换】高转低过渡等待设备空闲：onlineStatus=${AppConfig.getAppConfig().onlineStatus}，moveStatus=${AppConfig.getAppConfig().moveStatus}"
            )
            return
        }

        checkHighHeatEnd(list, 3)
        checkHighHeatSausage(list)

        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val list10_21 = TrafficModeHelper.getTrafficModePositions(10, 21, list)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        val sausageCount = list1_21.count { it.isHasSausage }
        val sausageCount25_33 = list25_33.count { it.isHasSausage }

        if (sausageCount <= 6 && sausageCount25_33 == 0) {
            val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
            val alreadySausages1_9 = list1_9.filter { it.isHasSausage }
            if (alreadySausages1_9.size < sausageCount) {
                val remainingSausages = TrafficModeHelper.sortByHoldingAgeThenPosition(
                    list10_21.filter { it.isHasSausage }
                )
                val remainingPositions1_9 = list1_9.filter { !it.isHasSausage }
                remainingSausages.forEachIndexed { index, kaoPan ->
                    if (index < remainingPositions1_9.size) {
                        LogUtils.i(
                            TAG,
                            "【并发切换】高转低收拢：烤盘${kaoPan.positionSn} -> 烤盘${remainingPositions1_9[index].positionSn}，holdingTime=${kaoPan.holdingTime}"
                        )
                        KaoChangOperate.moveKaoPanToKaoPan(kaoPan, remainingPositions1_9[index])
                    }
                }
            }

            val existingCountMap = list1_9
                .filter { it.isHasSausage && it.taste?.tasteCode != null }
                .groupBy { it.taste!!.tasteCode }
                .mapValues { (_, pans) -> pans.size }
            val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox, list1_9.size)
            val needAllocation = mutableMapOf<String, Int>()
            flavorAllocation.forEach { (flavor, targetCount) ->
                val existing = existingCountMap.getOrDefault(flavor, 0)
                needAllocation[flavor] = (targetCount - existing).coerceAtLeast(0)
            }

            val emptyPans = list1_9.filter { !it.isHasSausage }
            var emptyIndex = 0
            needAllocation.forEach { (flavor, needCount) ->
                var allocated = 0
                while (allocated < needCount && emptyIndex < emptyPans.size) {
                    emptyPans[emptyIndex].taste = Taste().apply { tasteCode = flavor }
                    allocated++
                    emptyIndex++
                }
            }

            KaoChangOperate.stopHeating(2)
            config.transitionMode = 0
            config.modeType = 1
            AppConfig.saveAppConfig(config)
            SendServerHelper.publishServiceUpdateStatus()
            resetSupplementTracking("高转低过渡完成并回落低并发")
            LogUtils.i(
                TAG,
                "【并发切换】高转低过渡完成：modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}"
            )
        }

        checkHighHeatDiscard(list1_21)
    }

    /**
     * 初始化高并发模式。
     * 1 区、2 区同时加热，按比例向 1~21 工位上肠。
     */
    private suspend fun initHighTrafficMode(list: List<KaoPan>, listBox: List<KaoPanBox>) {
        LogUtils.i(TAG, "【高并发初始化】开始初始化高并发：目标工位=1~21")
        val allBoxEmpty = listBox.all { it.num <= 0 }
        if (allBoxEmpty) {
            LogUtils.w(TAG, "【高并发初始化】所有烤肠箱库存为空，停止 1 区和 2 区加热")
            KaoChangOperate.stopHeating(1)
            KaoChangOperate.stopHeating(2)
            return
        }

        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        KaoChangOperate.heating(1)
        KaoChangOperate.heating(2)

        val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox, list1_21.size)
        var currentPosition = 0
        flavorAllocation.forEach { (flavor, count) ->
            repeat(count) {
                if (currentPosition < list1_21.size) {
                    list1_21[currentPosition].taste = Taste().apply {
                        tasteCode = flavor
                    }
                    currentPosition++
                }
            }
        }

        list1_21.forEach { kaoPan ->
            val matchingBox = TrafficModeHelper.findSupplyBoxByTaste(listBox, kaoPan.taste?.tasteCode)
            matchingBox?.let { box ->
                if (box.num > 0) {
                    KaoChangOperate.moveSausageToKaoPan(kaoPan, box)
                }
            }
        }
    }

    /**
     * 向 25~33 补热区批量补肠。
     * 只有当前区 1~21 形成 9 个坑位时才触发。
     */
    private suspend fun addHighHeatSausage(list: List<KaoPan>, listBox: List<KaoPanBox>): Int {
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        val hasSausageIn25_33 = list25_33.any { it.isHasSausage }
        if (hasSausageIn25_33) {
            return 0
        }

        TrafficModeHelper.fillMissingTasteReservations(list1_21, listBox)
        val emptyPositions = list1_21.filter { !it.isHasSausage }
        val emptyPositions25_33 = list25_33.filter { !it.isHasSausage }
        if (emptyPositions.size >= 9 && emptyPositions25_33.size >= 9) {
            KaoChangOperate.heating(3)
            val neededTastes = emptyPositions.take(9).mapNotNull { it.taste?.tasteCode?.takeIf { code -> code.isNotBlank() } }
            if (neededTastes.size < 9) {
                LogUtils.w(
                    TAG,
                    "【高并发补肠】跳过 25~33 补热区补肠：前区空盘口味预留不足，empty=${emptyPositions.size}，reserved=${neededTastes.size}"
                )
                return 0
            }
            var movedCount = 0
            neededTastes.forEachIndexed { index, tasteCode ->
                val matchingBox = TrafficModeHelper.findSupplyBoxByTaste(listBox, tasteCode)
                matchingBox?.let { box ->
                    if (box.num > 0 && index < emptyPositions25_33.size) {
                        val targetPosition = emptyPositions25_33[index]
                        targetPosition.taste = Taste().apply { this.tasteCode = tasteCode }
                        val moved = KaoChangOperate.moveSausageToKaoPan(targetPosition, box)
                        if (moved) {
                            movedCount += 1
                            SelfCleanManager.markZone3Dirty()
                        }
                    }
                }
            }
            return movedCount
        }
        return 0
    }

    /**
     * 检查 25~33 补热区是否已经烤熟。
     * 烤熟后搬回 1~21 同口味空位。
     */
    private suspend fun checkHighHeatSausage(list: List<KaoPan>) {
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        var allFinished = true
        var heatingCount = 0

        list25_33.forEach { kaoPan ->
            if (kaoPan.isHasSausage) {
                heatingCount += 1
                if (kaoPan.status == 1) {
                    allFinished = false
                }
            }
        }

        if (allFinished && heatingCount > 0) {
            LogUtils.i(TAG, "【高并发补肠】25~33 补热区已烤熟，开始回补 1~21")
            KaoChangOperate.keepWarm(3)
            KaoChangOperate.keepWarm(2)
            KaoChangOperate.keepWarm(1)

            list25_33.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.taste != null) {
                    val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
                    val tasteCode = kaoPan.taste.tasteCode
                    val targetPan = TrafficModeHelper.selectFrontTargetPan(list1_21, tasteCode)
                    targetPan?.let {
                        KaoChangOperate.moveKaoPanToKaoPan(kaoPan, it)
                    }
                }
            }
            KaoChangOperate.stopHeating(3)
            clearPitCountAfterBatch("25~33 补热区回补完成")
            SelfCleanManager.markZone3CleanPendingIfNeeded(list)
        }
    }

    /**
     * 检查指定区域是否已经整体烤熟。
     * 烤熟后关闭对应加热区并进入保温状态。
     */
    private fun checkHighHeatEnd(list: List<KaoPan>, area: Int) {
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
            KaoChangOperate.stopHeating(area)
            KaoChangOperate.keepWarm(area)
            list.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    kaoPan.startTime = 0
                    kaoPan.holdingTime = System.currentTimeMillis()
                    kaoPan.status = 2
                }
            }
            LogUtils.i(TAG, "【加热控制】${area} 区烤肠已全部烤熟，切换为保温状态")
        }
    }

    /**
     * 检查高并发前区可售烤肠是否保温到期。
     */
    private suspend fun checkHighHeatDiscard(list: List<KaoPan>) {
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
     * 判断是否需要触发高转低。
     * 触发条件：
     * 1. 接近营业结束；
     * 2. 距离上次高并发补肠已超过高转低间隔阈值。
     */
    private fun checkSwitchToLow() {
        val businessTime = AppConfig.getAppConfig().businessTime
        if (businessTime != null) {
            val remainingTimeUntilClose = getRemainingTimeUntilClose(businessTime)
            val beforeCloseThreshold =
                (AppConfig.getAppConfig().timeBeforeClose.takeIf { it > 0 } ?: 30) * 60 * 1000L
            if (remainingTimeUntilClose > 0 && remainingTimeUntilClose < beforeCloseThreshold) {
                switchHighToLow("接近营业结束")
                return
            }
        }

        if (lastFillPitTime > 0) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastFill = currentTime - lastFillPitTime
            val timeThresholdLowMinutes = AppConfig.getAppConfig().timeThresholdLow
            if (shouldSwitchHighToLowByTimeout(lastFillPitTime, currentTime, timeThresholdLowMinutes)) {
                LogUtils.w(
                    TAG,
                    "【并发切换】高并发补肠间隔超时判定成立：lastFillPitTime=$lastFillPitTime，currentTime=$currentTime，elapsedMs=$timeSinceLastFill，timeThresholdLow=${timeThresholdLowMinutes}分钟"
                )
                switchHighToLow("高并发补肠间隔超时")
            }
        }
    }

    /**
     * 计算距离当日营业结束的剩余时间。
     * 返回值为毫秒；无法计算时返回 -1。
     */
    private fun getRemainingTimeUntilClose(businessTime: BusinessTime): Long {
        return try {
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            val calendar = Calendar.getInstance()
            val currentTimeMillis = calendar.timeInMillis
            val dayOfWeek =
                if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(
                    Calendar.DAY_OF_WEEK
                ) - 1

            val endTimeStr = when {
                "everyday".equals(businessTime.mode, ignoreCase = true) &&
                    businessTime.defaultTime != null &&
                    businessTime.defaultTime.size >= 2 -> {
                    businessTime.defaultTime[1]
                }

                "custom".equals(businessTime.mode, ignoreCase = true) &&
                    businessTime.customTimes != null -> {
                    val todayTime =
                        businessTime.customTimes.find { it.day == dayOfWeek && it.enabled }
                    if (todayTime != null && todayTime.timeRange != null && todayTime.timeRange.size >= 2) {
                        todayTime.timeRange[1]
                    } else {
                        return -1
                    }
                }

                else -> return -1
            }

            val endTime = format.parse(endTimeStr) ?: return -1
            val endCalendar = Calendar.getInstance()
            endCalendar.time = endTime
            val hour = endCalendar.get(Calendar.HOUR_OF_DAY)
            val minute = endCalendar.get(Calendar.MINUTE)
            endCalendar.timeInMillis = calendar.timeInMillis
            endCalendar.set(Calendar.HOUR_OF_DAY, hour)
            endCalendar.set(Calendar.MINUTE, minute)
            endCalendar.set(Calendar.SECOND, 0)
            endCalendar.set(Calendar.MILLISECOND, 0)

            if (endCalendar.timeInMillis < currentTimeMillis) {
                endCalendar.add(Calendar.DAY_OF_MONTH, 1)
            }
            endCalendar.timeInMillis - currentTimeMillis
        } catch (e: Exception) {
            LogUtils.e(TAG, "计算营业结束时间失败：${e.message}")
            -1
        }
    }

    /**
     * 高并发 -> 低并发。
     * 这里只写入过渡态，由高转低过渡处理器负责最终落回低并发。
     */
    private fun switchHighToLow(reason: String) {
        LogUtils.w(
            TAG,
            "【并发切换】触发高转低：原因=$reason，modeType=${modeText(AppConfig.getAppConfig().modeType)}，transitionMode=${transitionText(AppConfig.getAppConfig().transitionMode)}->2(高转低过渡)"
        )
        AppConfig.getAppConfig().apply {
            transitionMode = 2
        }
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        SendServerHelper.publishServiceUpdateStatus()
        resetSupplementTracking("进入高转低过渡")
    }
}
