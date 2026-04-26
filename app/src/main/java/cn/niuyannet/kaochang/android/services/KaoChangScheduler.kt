package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import java.io.Serializable
import java.util.UUID

private enum class SchedulerMode : Serializable {
    LOW,
    HIGH
}

private enum class SchedulerTransition : Serializable {
    NONE,
    LOW_TO_HIGH,
    HIGH_TO_LOW
}

internal enum class BatchZone : Serializable {
    LOW_PRIMARY,
    LOW_SECONDARY,
    HIGH_MAIN
}

internal data class LowSecondaryEvacuationMove(
    val sourcePositionSn: Int,
    val targetPositionSn: Int
) : Serializable

internal data class HighToLowCompactionMove(
    val sourcePositionSn: Int,
    val targetPositionSn: Int
) : Serializable

private enum class BatchState : Serializable {
    HEATING,
    READY,
    HELD,
    COMPLETED
}

internal fun rankLowToHighFrontBackfillCandidate(
    positionSn: Int,
    slotTasteCode: String?,
    targetTasteCode: String?
): Int {
    val positionBucket = if (positionSn in 1..9) 0 else 2
    val tastePenalty = if (!targetTasteCode.isNullOrBlank() && slotTasteCode == targetTasteCode) 0 else 1
    return positionBucket + tastePenalty
}

internal fun selectHighMainBackfillTargets(
    frontPlanPositions: List<Int>,
    reservedPositions: Set<Int>
): List<Int> {
    val targets = frontPlanPositions
        .filter { it in 1..21 && it !in reservedPositions }
        .sortedWith(compareBy<Int>({ if (it in 1..9) 0 else 1 }, { it }))
        .take(9)
    return targets.takeIf { it.size == 9 } ?: emptyList()
}

internal fun planHighToLowCompaction(pans: List<KaoPan>): List<HighToLowCompactionMove>? {
    val frontPans = pans.filter { it.positionSn in 1..9 }
    val highFrontPans = pans.filter { it.positionSn in 1..21 }
    val remainingSausages = highFrontPans.filter { it.isHasSausage }
    if (remainingSausages.size > 6) {
        return null
    }

    val alreadyInLowFront = remainingSausages.count { it.positionSn in 1..9 }
    val sources = TrafficModeHelper.sortByHoldingAgeThenPosition(
        remainingSausages.filter { it.positionSn in 10..21 }
    )
    if (sources.isEmpty()) {
        return emptyList()
    }

    val targets = frontPans
        .filter { !it.isHasSausage && !it.isHasGrilling }
        .sortedBy { it.positionSn }
    if (alreadyInLowFront + sources.size > 9 || targets.size < sources.size) {
        return null
    }
    return sources.zip(targets).map { (source, target) ->
        HighToLowCompactionMove(
            sourcePositionSn = source.positionSn,
            targetPositionSn = target.positionSn
        )
    }
}

internal data class FrontOpeningBatchPlan(
    val name: String,
    val area: Int,
    val positions: IntRange
) : Serializable

internal data class FrontOpeningBatchEvaluation(
    val plan: FrontOpeningBatchPlan,
    val latestStartTime: Long,
    val batchBakeDurationMs: Long,
    val readyAt: Long,
    val remainingMs: Long
) : Serializable {
    val isReady: Boolean
        get() = remainingMs <= 0L
}

internal fun frontOpeningBatchPlans(modeType: Int, transitionMode: Int): List<FrontOpeningBatchPlan> {
    if (modeType == 1 && transitionMode == 0) {
        return listOf(
            FrontOpeningBatchPlan(name = "1~9开盘批次", area = 1, positions = 1..9)
        )
    }
    return listOf(
        FrontOpeningBatchPlan(name = "1~9开盘批次", area = 1, positions = 1..9),
        FrontOpeningBatchPlan(name = "10~21开盘批次", area = 2, positions = 10..21)
    )
}

internal fun evaluateFrontOpeningBatchPlan(
    plan: FrontOpeningBatchPlan,
    pans: List<KaoPan>,
    configBakeDurationMs: Long,
    now: Long
): FrontOpeningBatchEvaluation? {
    val batchPans = pans.filter { it.positionSn in plan.positions && it.isHasSausage }
    if (batchPans.isEmpty()) {
        return null
    }
    val heatingPans = batchPans.filter { it.startTime > 0L && it.holdingTime <= 0L }
    if (heatingPans.isEmpty()) {
        return null
    }
    val batchBakeDurationMs =
        if (configBakeDurationMs > 0L) {
            configBakeDurationMs
        } else {
            batchPans
                .map { it.bakingTime }
                .filter { it > 0L }
                .maxOrNull()
                ?: return null
        }
    val latestStartTime = heatingPans.maxOf { it.startTime }
    val readyAt = latestStartTime + batchBakeDurationMs
    return FrontOpeningBatchEvaluation(
        plan = plan,
        latestStartTime = latestStartTime,
        batchBakeDurationMs = batchBakeDurationMs,
        readyAt = readyAt,
        remainingMs = readyAt - now
    )
}

internal fun applyFrontOpeningBatchReady(
    plan: FrontOpeningBatchPlan,
    pans: List<KaoPan>,
    holdingTime: Long,
    keepWarmTemperature: Int
): Int {
    val batchPans = pans.filter { it.positionSn in plan.positions && it.isHasSausage }
    var updatedCount = 0
    batchPans.forEach { pan ->
        if (pan.startTime > 0L || pan.holdingTime <= 0L || pan.status != 2 || pan.temperature != keepWarmTemperature) {
            updatedCount += 1
        }
        pan.startTime = 0L
        if (pan.holdingTime <= 0L) {
            pan.holdingTime = holdingTime
        }
        pan.status = 2
        pan.temperature = keepWarmTemperature
    }
    return updatedCount
}

internal fun planLowSecondaryHoldingEvacuation(
    pans: List<KaoPan>,
    launchingZone: BatchZone
): List<LowSecondaryEvacuationMove>? {
    val holdingSourceRange = when (launchingZone) {
        BatchZone.LOW_PRIMARY -> 16..18
        BatchZone.LOW_SECONDARY -> 13..15
        BatchZone.HIGH_MAIN -> return emptyList()
    }
    val holdingSources = pans
        .filter {
            it.positionSn in holdingSourceRange &&
                it.isHasSausage &&
                (it.status == 2 || it.holdingTime > 0L)
        }
        .sortedBy { it.positionSn }
    if (holdingSources.isEmpty()) {
        return emptyList()
    }

    val frontPans = pans.filter { it.positionSn in 1..9 }
    val frontIsHeating = frontPans.any {
        it.isHasSausage && (it.status == 1 || it.startTime > 0L)
    }
    if (frontIsHeating) {
        return null
    }

    val availableTargets = frontPans
        .filter { !it.isHasSausage && !it.isHasGrilling }
        .sortedBy { it.positionSn }
        .toMutableList()
    if (availableTargets.size < holdingSources.size) {
        return null
    }

    return holdingSources.map { sourcePan ->
        val sourceTasteCode = sourcePan.taste?.tasteCode?.takeIf { it.isNotBlank() }
        val targetIndex = availableTargets.indexOfFirst { targetPan ->
            sourceTasteCode == null || targetPan.taste?.tasteCode == sourceTasteCode
        }
        if (targetIndex < 0) {
            return null
        }
        val targetPan = availableTargets.removeAt(targetIndex)
        LowSecondaryEvacuationMove(
            sourcePositionSn = sourcePan.positionSn,
            targetPositionSn = targetPan.positionSn
        )
    }
}

private data class FrontPlanSlot(
    val positionSn: Int,
    var taste: Taste? = null
) : Serializable

internal data class PitRecord(
    val pitId: String,
    val positionSn: Int,
    var taste: Taste? = null,
    val createdAt: Long,
    var assignedBatchId: String? = null,
    var fulfilled: Boolean = false
) : Serializable

private data class BatchItem(
    val supplyPositionSn: Int,
    var targetPositionSn: Int,
    val pitId: String,
    var taste: Taste? = null,
    var completed: Boolean = false
) : Serializable

private data class HeatBatch(
    val batchId: String,
    val zone: BatchZone,
    val createdAt: Long,
    var state: BatchState = BatchState.HEATING,
    var holdAtSupply: Boolean = false,
    var items: MutableList<BatchItem> = mutableListOf()
) : Serializable

private data class SchedulerSnapshot(
    var mode: SchedulerMode = SchedulerMode.LOW,
    var transition: SchedulerTransition = SchedulerTransition.NONE,
    var frontPlan: MutableList<FrontPlanSlot> = mutableListOf(),
    var pits: MutableList<PitRecord> = mutableListOf(),
    var batches: MutableList<HeatBatch> = mutableListOf(),
    var nextBatchSequence: Int = 1,
    var lastHighBatchLaunchAt: Long = 0L
) : Serializable

object KaoChangScheduler {
    private const val TAG = "KaoChangScheduler"
    private const val SP_FILE = "kaochang_scheduler"
    private const val KEY_SNAPSHOT = "sp_scheduler_snapshot"
    private const val ONE_MINUTE_MS = 60_000L
    private val LOW_SELL_POSITIONS = ((1..9).toList() + (13..18).toList()).toSet()
    private val HIGH_SELL_POSITIONS = (1..21).toSet()
    private val HIGH_BACKFILL_POSITIONS = (1..21).toSet()
    private const val HIGH_FRONT_PLAN_SIZE = 21
    private const val LOW_FRONT_PLAN_SIZE = 9
    private const val HIGH_BATCH_SIZE = 9

    @Volatile
    private var snapshotCache: SchedulerSnapshot? = null
    private val highBatchProgressLogBucket = mutableMapOf<String, Long>()

    fun currentSellPositions(config: AppConfigBean): List<Int> {
        return if (config.modeType == 1 && config.transitionMode == 0) {
            LOW_SELL_POSITIONS.sorted()
        } else {
            HIGH_SELL_POSITIONS.sorted()
        }
    }

    fun currentHeatRange(config: AppConfigBean): IntRange {
        val snapshot = getSnapshotOrNull()
        return when {
            snapshot == null -> {
                if (config.modeType == 1 && config.transitionMode == 0) {
                    13..18
                } else {
                    25..33
                }
            }
            snapshot.mode == SchedulerMode.LOW && snapshot.transition == SchedulerTransition.NONE -> 13..18
            else -> 25..33
        }
    }

    fun clearSnapshot(reason: String) {
        synchronized(this) {
            snapshotCache = null
            PreferenceUtils.saveStringPreference(SP_FILE, KEY_SNAPSHOT, "")
            highBatchProgressLogBucket.clear()
        }
        LogUtils.w(TAG, "已清理调度快照：reason=$reason")
    }

    fun registerFrontPitFromPan(kaoPan: KaoPan, reason: String) {
        val tasteCopy = copyTaste(kaoPan.taste)
        registerFrontPit(kaoPan.positionSn, tasteCopy, reason)
    }

    fun registerFrontPit(positionSn: Int, taste: Taste?, reason: String) {
        ensureSnapshot(AppConfig.getAppConfig(), KaoPanHelper.getKaoPanList(), KaoPanHelper.getKaoPanBoxList())
        val sellPositions = currentSellPositions(AppConfig.getAppConfig()).toSet()
        if (positionSn !in sellPositions) {
            return
        }
        synchronized(this) {
            val currentSnapshot = ensureSnapshot(AppConfig.getAppConfig(), KaoPanHelper.getKaoPanList(), KaoPanHelper.getKaoPanBoxList())
            val existing = currentSnapshot.pits.firstOrNull { !it.fulfilled && it.positionSn == positionSn }
            if (existing != null) {
                existing.taste = existing.taste ?: copyTaste(taste)
                saveSnapshot(currentSnapshot)
                return
            }
            val pit = PitRecord(
                pitId = UUID.randomUUID().toString(),
                positionSn = positionSn,
                taste = copyTaste(taste),
                createdAt = System.currentTimeMillis()
            )
            currentSnapshot.pits.add(pit)
            currentSnapshot.pits.sortBy { it.createdAt }
            saveSnapshot(currentSnapshot)
            LogUtils.i(
                TAG,
                "登记坑位：position=$positionSn，taste=${pit.taste?.tasteCode ?: "未知"}，reason=$reason，mode=${currentSnapshot.mode}"
            )
        }
    }

    suspend fun runCycle(
        config: AppConfigBean,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean
    ) {
        val snapshot = ensureSnapshot(config, pans, boxes)
        maybeAdoptExternalMode(snapshot, config, pans, boxes)
        applyFrontPlanProjection(snapshot, pans)
        completeFrontInitializationIfNeeded(snapshot, config, pans)
        updateBatchStates(snapshot, pans)

        when (snapshot.transition) {
            SchedulerTransition.NONE -> {
                when (snapshot.mode) {
                    SchedulerMode.LOW -> processLowStable(snapshot, config, pans, boxes, boxSupplyAvailable, schedulingEnabled)
                    SchedulerMode.HIGH -> processHighStable(snapshot, config, pans, boxes, boxSupplyAvailable, schedulingEnabled)
                }
            }
            SchedulerTransition.LOW_TO_HIGH -> {
                processLowToHigh(snapshot, config, pans, boxes, boxSupplyAvailable, schedulingEnabled)
            }
            SchedulerTransition.HIGH_TO_LOW -> {
                processHighToLow(snapshot, config, pans, boxes)
            }
        }

        discardExpiredFrontPans(snapshot, pans)
        applyFrontPlanProjection(snapshot, pans)
        saveSnapshot(snapshot)
    }

    private suspend fun processLowStable(
        snapshot: SchedulerSnapshot,
        config: AppConfigBean,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean
    ) {
        val beforeClose = isBeforeCloseWindow(config)
        val front = pans.filter { it.positionSn in 1..9 }
        val supplement = pans.filter { it.positionSn in 13..18 }
        if (front.none { it.isHasSausage } && supplement.none { it.isHasSausage }) {
            if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !beforeClose) {
                initializeLow(snapshot, pans, boxes)
            }
            return
        }

        handleReadyBatches(snapshot, pans)
        reconcileLowFrontPits(snapshot, pans)

        if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !beforeClose) {
            launchLowBatchIfNeeded(snapshot, BatchZone.LOW_PRIMARY, pans, boxes)
            launchLowBatchIfNeeded(snapshot, BatchZone.LOW_SECONDARY, pans, boxes)
        }

        val unresolvedPitCount = snapshot.pits.count { !it.fulfilled }
        if (
            schedulingEnabled &&
            boxSupplyAvailable &&
            config.errorStatus == 0 &&
            !beforeClose &&
            unresolvedPitCount >= 7 &&
            lowCapacityAlreadyMaxed(snapshot, pans)
        ) {
            enterLowToHighTransition(snapshot, config, pans, boxes)
        }
    }

    /**
     * 低并发稳态自愈：
     * - App 重启/覆盖安装后，若 1~9 已出现空位，但历史 pits 未恢复，则按当前 frontPlan 补登记 pits
     * - 若某位置已重新有肠，而旧 pit 仍未完成且也不再被活动批次占用，则自动收口，避免误判一直缺肠
     *
     * 只在低并发稳态运行，不影响高并发/过渡态。
     */
    private fun reconcileLowFrontPits(snapshot: SchedulerSnapshot, pans: List<KaoPan>) {
        if (snapshot.mode != SchedulerMode.LOW || snapshot.transition != SchedulerTransition.NONE) {
            return
        }
        val frontPans = pans.filter { it.positionSn in 1..9 }
        if (frontPans.none { it.isHasSausage }) {
            return
        }

        val protectedPitIds = snapshot.batches
            .filter { it.state != BatchState.COMPLETED && it.state != BatchState.HELD }
            .flatMap { batch -> batch.items.map { it.pitId } }
            .toSet()
        val activePitPositions = snapshot.batches
            .filter { it.state != BatchState.COMPLETED && it.state != BatchState.HELD }
            .flatMap { batch -> batch.items.map { it.targetPositionSn } }
            .toSet()

        val staleResolvedPits = mutableListOf<Int>()
        snapshot.pits
            .filter { !it.fulfilled && it.positionSn in 1..9 && it.pitId !in protectedPitIds }
            .forEach { pit ->
                val currentPan = frontPans.firstOrNull { it.positionSn == pit.positionSn } ?: return@forEach
                if (currentPan.isHasSausage) {
                    pit.fulfilled = true
                    pit.assignedBatchId = null
                    staleResolvedPits += pit.positionSn
                }
            }

        val recoveredPitPositions = mutableListOf<Int>()
        snapshot.frontPlan
            .filter { it.positionSn in 1..9 }
            .forEach { slot ->
                val currentPan = frontPans.firstOrNull { it.positionSn == slot.positionSn } ?: return@forEach
                if (currentPan.isHasSausage) {
                    return@forEach
                }
                if (slot.positionSn in activePitPositions) {
                    return@forEach
                }
                val existingPit = snapshot.pits.firstOrNull { !it.fulfilled && it.positionSn == slot.positionSn }
                if (existingPit == null) {
                    snapshot.pits.add(
                        PitRecord(
                            pitId = UUID.randomUUID().toString(),
                            positionSn = slot.positionSn,
                            taste = copyTaste(slot.taste),
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    recoveredPitPositions += slot.positionSn
                } else if (existingPit.taste == null && slot.taste != null) {
                    existingPit.taste = copyTaste(slot.taste)
                }
            }

        if (recoveredPitPositions.isEmpty() && staleResolvedPits.isEmpty()) {
            return
        }
        snapshot.pits.sortBy { it.createdAt }
        LogUtils.i(
            TAG,
            "低并发坑位自愈完成：recoveredPositions=$recoveredPitPositions，resolvedPositions=$staleResolvedPits，" +
                "frontOccupied=${frontPans.filter { it.isHasSausage }.map { it.positionSn }}，" +
                "frontEmpty=${frontPans.filter { !it.isHasSausage }.map { it.positionSn }}"
        )
    }

    private suspend fun processLowToHigh(
        snapshot: SchedulerSnapshot,
        config: AppConfigBean,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean
    ) {
        handleReadyBatches(snapshot, pans)

        val beforeClose = isBeforeCloseWindow(config)
        val hasHeatingLowBatch = snapshot.batches.any {
            it.zone != BatchZone.HIGH_MAIN && (it.state == BatchState.HEATING || it.state == BatchState.READY)
        }
        val highBatch = snapshot.batches.firstOrNull { it.zone == BatchZone.HIGH_MAIN && it.state != BatchState.COMPLETED }
        if (
            highBatch == null &&
            schedulingEnabled &&
            boxSupplyAvailable &&
            config.errorStatus == 0 &&
            !beforeClose
        ) {
            launchHighTransitionBatch(snapshot, pans, boxes)
        }

        val activeHighBatch = snapshot.batches.firstOrNull { it.zone == BatchZone.HIGH_MAIN && it.state != BatchState.COMPLETED }
        val canFinalize =
            !hasHeatingLowBatch &&
                activeHighBatch == null &&
                pans.none { it.positionSn in 25..33 && it.isHasSausage }
        if (canFinalize || (!schedulingEnabled || !boxSupplyAvailable || beforeClose)) {
            snapshot.transition = SchedulerTransition.NONE
            snapshot.mode = SchedulerMode.HIGH
            snapshot.pits.removeAll { it.fulfilled }
            snapshot.pits.sortBy { it.createdAt }
            rebuildFrontPlan(snapshot, HIGH_FRONT_PLAN_SIZE, boxes, pans)
            applyRuntimeMode(snapshot, config, "低转高过渡完成")
            LogUtils.i(TAG, "低转高过渡完成：frontStock=${pans.count { it.positionSn in HIGH_BACKFILL_POSITIONS && it.isHasSausage }}")
        }
    }

    private suspend fun processHighStable(
        snapshot: SchedulerSnapshot,
        config: AppConfigBean,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean
    ) {
        val beforeClose = isBeforeCloseWindow(config)
        val front = pans.filter { it.positionSn in HIGH_BACKFILL_POSITIONS }
        val supplement = pans.filter { it.positionSn in 25..33 }
        if (front.none { it.isHasSausage } && supplement.none { it.isHasSausage }) {
            if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !beforeClose) {
                initializeHigh(snapshot, pans, boxes)
            }
            return
        }

        handleReadyBatches(snapshot, pans)

        if (beforeClose || shouldSwitchHighToLowByThreshold(snapshot, config)) {
            snapshot.transition = SchedulerTransition.HIGH_TO_LOW
            applyRuntimeMode(snapshot, config, if (beforeClose) "进入收口窗口触发高转低" else "高并发补肠节奏放缓触发高转低")
            return
        }

        if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !beforeClose) {
            launchHighBatchIfNeeded(snapshot, pans, boxes)
        }
    }

    private suspend fun processHighToLow(
        snapshot: SchedulerSnapshot,
        config: AppConfigBean,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ) {
        handleReadyBatches(snapshot, pans)
        val highSupplementCount = pans.count { it.positionSn in 25..33 && it.isHasSausage }
        val activeHighBatch = snapshot.batches.any { it.zone == BatchZone.HIGH_MAIN && it.state != BatchState.COMPLETED }
        if (highSupplementCount == 0 && !activeHighBatch) {
            val moves = planHighToLowCompaction(pans)
            if (moves == null) {
                LogUtils.i(
                    TAG,
                    "高转低过渡继续等待：frontStock=${pans.count { it.positionSn in 1..21 && it.isHasSausage }}，" +
                        "zone3Stock=$highSupplementCount"
                )
                return
            }
            if (!compactHighFrontToLow(moves, pans)) {
                return
            }
            collapseToLow(snapshot, config, pans, boxes)
        }
    }

    private suspend fun compactHighFrontToLow(moves: List<HighToLowCompactionMove>, pans: List<KaoPan>): Boolean {
        if (moves.isEmpty()) {
            return true
        }
        KaoChangOperate.keepWarm(1)
        LogUtils.i(TAG, "高转低收拢开始：moves=$moves")
        moves.forEach { move ->
            val sourcePan = pans.firstOrNull { it.positionSn == move.sourcePositionSn }
            val targetPan = pans.firstOrNull { it.positionSn == move.targetPositionSn }
            if (sourcePan == null || targetPan == null) {
                LogUtils.w(TAG, "高转低收拢失败：source=${move.sourcePositionSn} 或 target=${move.targetPositionSn} 不存在")
                return false
            }
            val targetHoldingTime = sourcePan.holdingTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            if (!KaoChangOperate.moveKaoPanToKaoPan(sourcePan, targetPan, targetHoldingTime)) {
                LogUtils.w(
                    TAG,
                    "高转低收拢中止：source=${move.sourcePositionSn}，target=${move.targetPositionSn}，原因=搬盘失败"
                )
                return false
            }
            LogUtils.i(
                TAG,
                "高转低收拢完成：source=${move.sourcePositionSn} -> target=${move.targetPositionSn}，holdingTime=$targetHoldingTime"
            )
        }
        return true
    }

    private suspend fun handleReadyBatches(snapshot: SchedulerSnapshot, pans: List<KaoPan>) {
        val readyBatches = snapshot.batches.filter { it.state == BatchState.READY }
        readyBatches.forEach { batch ->
            if (batch.holdAtSupply) {
                holdBatchInPlace(snapshot, batch, pans)
            } else {
                backfillBatch(snapshot, batch, pans)
            }
        }
    }

    private suspend fun initializeLow(
        snapshot: SchedulerSnapshot,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ) {
        val allBoxEmpty = boxes.none { it.num > 0 }
        if (allBoxEmpty) {
            KaoChangOperate.stopHeating(1)
            return
        }
        snapshot.mode = SchedulerMode.LOW
        snapshot.transition = SchedulerTransition.NONE
        snapshot.pits.clear()
        snapshot.batches.clear()
        rebuildFrontPlan(snapshot, LOW_FRONT_PLAN_SIZE, boxes, pans)
        KaoChangOperate.heating(1)
        snapshot.frontPlan.forEach { slot ->
            val targetPan = pans.firstOrNull { it.positionSn == slot.positionSn } ?: return@forEach
            if (targetPan.isHasSausage) {
                return@forEach
            }
            targetPan.taste = copyTaste(slot.taste)
            val box = findSupplyBox(boxes, slot.taste?.tasteCode) ?: return@forEach
            KaoChangOperate.moveSausageToKaoPan(targetPan, box)
        }
        LogUtils.i(TAG, "低并发初始化完成：positions=1~9")
    }

    private suspend fun initializeHigh(
        snapshot: SchedulerSnapshot,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ) {
        val allBoxEmpty = boxes.none { it.num > 0 }
        if (allBoxEmpty) {
            KaoChangOperate.stopHeating(1)
            KaoChangOperate.stopHeating(2)
            return
        }
        snapshot.mode = SchedulerMode.HIGH
        snapshot.transition = SchedulerTransition.NONE
        snapshot.pits.clear()
        snapshot.batches.clear()
        // 重置高并发补肠时间戳，防止旧时间戳被 shouldSwitchHighToLowByThreshold 误读，
        // 导致重新进入高并发后立刻又触发高转低过渡。
        snapshot.lastHighBatchLaunchAt = 0L
        rebuildFrontPlan(snapshot, HIGH_FRONT_PLAN_SIZE, boxes, pans)
        KaoChangOperate.heating(1)
        KaoChangOperate.heating(2)
        snapshot.frontPlan.forEach { slot ->
            val targetPan = pans.firstOrNull { it.positionSn == slot.positionSn } ?: return@forEach
            if (targetPan.isHasSausage) {
                return@forEach
            }
            targetPan.taste = copyTaste(slot.taste)
            val box = findSupplyBox(boxes, slot.taste?.tasteCode) ?: return@forEach
            KaoChangOperate.moveSausageToKaoPan(targetPan, box)
        }
        LogUtils.i(TAG, "高并发初始化完成：positions=1~21，frontPlan=1~21")
    }

    private suspend fun launchLowBatchIfNeeded(
        snapshot: SchedulerSnapshot,
        zone: BatchZone,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ) {
        val zonePositions = zone.positions()
        val zoneLabel = when (zone) {
            BatchZone.LOW_PRIMARY -> "低并发主补热区13~15"
            BatchZone.LOW_SECONDARY -> "低并发次补热区16~18"
            BatchZone.HIGH_MAIN -> "高并发补热区25~33"
        }
        if (snapshot.batches.any { it.zone == zone && it.state != BatchState.COMPLETED && it.state != BatchState.HELD }) {
            LogUtils.d(TAG, "跳过创建${zoneLabel}：已有未完成批次在执行")
            return
        }
        if (zonePositions.any { position -> pans.any { it.positionSn == position && it.isHasSausage } }) {
            LogUtils.d(
                TAG,
                "跳过创建${zoneLabel}：补热区已有烤肠，occupied=${zonePositions.filter { position -> pans.any { it.positionSn == position && it.isHasSausage } }}"
            )
            return
        }
        val unresolved = snapshot.pits.filter { !it.fulfilled && it.assignedBatchId == null }.sortedBy { it.createdAt }
        // LOW_SECONDARY 的 required 是 3 而非 6：
        // PRIMARY 建批时会给它的 3 个坑写入 assignedBatchId，这 3 个坑已从 unresolved 里排除。
        // 所以 SECONDARY 只需检查剩余未分配坑位是否还有 3 个，取前 3 即可，
        // 不能再 drop(3) 也不能要求 unresolved >= 6，否则 16~18 在 7~8 缺口场景下永远起不来。
        val required = when (zone) {
            BatchZone.LOW_PRIMARY -> 3
            BatchZone.LOW_SECONDARY -> 3
            BatchZone.HIGH_MAIN -> HIGH_BATCH_SIZE
        }
        if (unresolved.size < required) {
            LogUtils.d(
                TAG,
                "跳过创建${zoneLabel}：未完成坑位不足，required=$required，available=${unresolved.size}，pitPositions=${unresolved.map { it.positionSn }}"
            )
            return
        }
        val selectedPits = unresolved.take(3)
        if (selectedPits.size < 3) {
            LogUtils.d(
                TAG,
                "跳过创建${zoneLabel}：可分配坑位不足3个，selected=${selectedPits.size}，pitPositions=${selectedPits.map { it.positionSn }}"
            )
            return
        }
        if (!evacuateLowSecondaryHoldingIfNeeded(snapshot, zone, pans)) {
            LogUtils.w(TAG, "跳过创建${zoneLabel}：二区保温肠避让未完成，为避免同区烤制温度影响保温肠，本轮不启动二区加热")
            return
        }
        KaoChangOperate.heating(2)
        val batch = HeatBatch(
            batchId = nextBatchId(snapshot, if (zone == BatchZone.LOW_PRIMARY) "L1" else "L2"),
            zone = zone,
            createdAt = System.currentTimeMillis()
        )
        batch.holdAtSupply = true
        zonePositions.forEachIndexed { index, supplyPosition ->
            val pit = selectedPits.getOrNull(index) ?: return@forEachIndexed
            val targetPan = pans.firstOrNull { it.positionSn == supplyPosition } ?: return@forEachIndexed
            val box = findSupplyBox(boxes, pit.taste?.tasteCode) ?: return@forEachIndexed
            targetPan.taste = copyTaste(pit.taste)
            if (KaoChangOperate.moveSausageToKaoPan(targetPan, box)) {
                pit.assignedBatchId = batch.batchId
                batch.items.add(
                    BatchItem(
                        supplyPositionSn = supplyPosition,
                        targetPositionSn = supplyPosition,
                        pitId = pit.pitId,
                        taste = copyTaste(pit.taste)
                    )
                )
            }
        }
        if (batch.items.isEmpty()) {
            LogUtils.w(
                TAG,
                "创建${zoneLabel}失败：本轮没有任何烤肠成功补入，selectedPitPositions=${selectedPits.map { it.positionSn }}"
            )
            if (pans.none { it.positionSn in 13..18 && it.isHasSausage }) {
                KaoChangOperate.stopHeating(2)
            }
            return
        }
        snapshot.batches.add(batch)
        LogUtils.i(
            TAG,
            "创建低并发批次：batch=${batch.batchId}，zone=$zone，targets=${batch.items.map { it.targetPositionSn }}"
        )
    }

    private suspend fun evacuateLowSecondaryHoldingIfNeeded(
        snapshot: SchedulerSnapshot,
        launchingZone: BatchZone,
        pans: List<KaoPan>
    ): Boolean {
        val moves = planLowSecondaryHoldingEvacuation(pans, launchingZone)
        if (moves == null) {
            LogUtils.w(
                TAG,
                "二区温控冲突避让失败：launchingZone=$launchingZone，原因=一区空位不足、口味不匹配或一区正在烤制"
            )
            return false
        }
        if (moves.isEmpty()) {
            return true
        }

        KaoChangOperate.keepWarm(1)
        LogUtils.i(TAG, "二区温控冲突避让开始：launchingZone=$launchingZone，moves=$moves")
        moves.forEach { move ->
            val sourcePan = pans.firstOrNull { it.positionSn == move.sourcePositionSn }
            val targetPan = pans.firstOrNull { it.positionSn == move.targetPositionSn }
            if (sourcePan == null || targetPan == null) {
                LogUtils.w(TAG, "二区温控冲突避让失败：source=${move.sourcePositionSn} 或 target=${move.targetPositionSn} 不存在")
                return false
            }
            val targetHoldingTime = if (sourcePan.holdingTime > 0L) {
                sourcePan.holdingTime
            } else {
                System.currentTimeMillis()
            }
            val moved = KaoChangOperate.moveKaoPanToKaoPan(sourcePan, targetPan, targetHoldingTime)
            if (!moved) {
                LogUtils.w(TAG, "二区温控冲突避让失败：source=${move.sourcePositionSn}，target=${move.targetPositionSn}，原因=搬盘失败")
                return false
            }
            snapshot.pits
                .firstOrNull { !it.fulfilled && it.positionSn == move.targetPositionSn }
                ?.apply {
                    fulfilled = true
                    assignedBatchId = null
                }
            LogUtils.i(
                TAG,
                "二区温控冲突避让完成：source=${move.sourcePositionSn} -> target=${move.targetPositionSn}，" +
                    "holdingTime=$targetHoldingTime"
            )
        }
        return true
    }

    private suspend fun launchHighBatchIfNeeded(
        snapshot: SchedulerSnapshot,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ) {
        if (snapshot.batches.any { it.zone == BatchZone.HIGH_MAIN && it.state != BatchState.COMPLETED }) {
            return
        }
        if (pans.any { it.positionSn in 25..33 && it.isHasSausage }) {
            return
        }
        val selectedPits = snapshot.pits
            .filter { !it.fulfilled && it.assignedBatchId == null && it.positionSn in HIGH_BACKFILL_POSITIONS }
            .sortedBy { it.createdAt }
            .take(HIGH_BATCH_SIZE)
        if (selectedPits.size < HIGH_BATCH_SIZE) {
            return
        }
        val batch = createHighBatch(snapshot, selectedPits, pans, boxes, "H")
        if (batch != null) {
            snapshot.lastHighBatchLaunchAt = System.currentTimeMillis()
        }
    }

    private suspend fun launchHighTransitionBatch(
        snapshot: SchedulerSnapshot,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ) {
        val selectedPits = snapshot.pits
            .filter { !it.fulfilled && it.assignedBatchId == null && it.positionSn in HIGH_BACKFILL_POSITIONS }
            .sortedBy { it.createdAt }
            .take(HIGH_BATCH_SIZE)
        if (selectedPits.size < HIGH_BATCH_SIZE) {
            LogUtils.i(
                TAG,
                "跳过创建高并发过渡批次：前区目标坑位不足整批，required=$HIGH_BATCH_SIZE，" +
                    "available=${selectedPits.size}，pitPositions=${selectedPits.map { it.positionSn }}"
            )
            return
        }
        val batch = createHighBatch(snapshot, selectedPits, pans, boxes, "HT")
        if (batch == null) return
        snapshot.lastHighBatchLaunchAt = System.currentTimeMillis()
    }

    private suspend fun createHighBatch(
        snapshot: SchedulerSnapshot,
        selectedPits: List<PitRecord>,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>,
        prefix: String
    ): HeatBatch? {
        if (selectedPits.size < HIGH_BATCH_SIZE) {
            LogUtils.i(
                TAG,
                "跳过创建高并发批次：目标坑位不足整批，required=$HIGH_BATCH_SIZE，available=${selectedPits.size}，" +
                    "pitPositions=${selectedPits.map { it.positionSn }}"
            )
            return null
        }
        if (!hasEnoughHighBatchSupply(selectedPits.take(HIGH_BATCH_SIZE), boxes)) {
            LogUtils.i(
                TAG,
                "跳过创建高并发批次：源肠箱库存不足整批，required=$HIGH_BATCH_SIZE，" +
                    "pitPositions=${selectedPits.take(HIGH_BATCH_SIZE).map { it.positionSn }}"
            )
            return null
        }
        val supplyPositions = (25..33).toList()
        KaoChangOperate.heating(3)
        val batch = HeatBatch(
            batchId = nextBatchId(snapshot, prefix),
            zone = BatchZone.HIGH_MAIN,
            createdAt = System.currentTimeMillis()
        )
        supplyPositions.forEachIndexed { index, supplyPosition ->
            val pit = selectedPits.getOrNull(index) ?: return@forEachIndexed
            val targetPan = pans.firstOrNull { it.positionSn == supplyPosition } ?: return@forEachIndexed
            val box = findSupplyBox(boxes, pit.taste?.tasteCode) ?: return@forEachIndexed
            targetPan.taste = copyTaste(pit.taste)
            if (KaoChangOperate.moveSausageToKaoPan(targetPan, box)) {
                pit.assignedBatchId = batch.batchId
                batch.items.add(
                    BatchItem(
                        supplyPositionSn = supplyPosition,
                        targetPositionSn = pit.positionSn,
                        pitId = pit.pitId,
                        taste = copyTaste(pit.taste)
                    )
                )
            }
        }
        if (batch.items.isEmpty()) {
            if (pans.none { it.positionSn in 25..33 && it.isHasSausage }) {
                KaoChangOperate.stopHeating(3)
            }
            return null
        }
        snapshot.batches.add(batch)
        LogUtils.i(
            TAG,
            "创建高并发批次：batch=${batch.batchId}，targets=${batch.items.map { it.targetPositionSn }}"
        )
        return batch
    }

    private fun updateBatchStates(snapshot: SchedulerSnapshot, pans: List<KaoPan>) {
        val now = System.currentTimeMillis()
        snapshot.batches.forEach { batch ->
            if (batch.state != BatchState.HEATING) {
                return@forEach
            }
            val highMainReadyLogContext = if (batch.zone == BatchZone.HIGH_MAIN) {
                val highBatchPans = batch.items.mapNotNull { item ->
                    pans.firstOrNull { it.positionSn == item.supplyPositionSn }
                }
                Triple(
                    highBatchPans.map { it.positionSn },
                    resolveHighMainBatchBakeDurationMs(highBatchPans),
                    highBatchPans.maxOfOrNull { it.startTime }
                )
            } else {
                null
            }
            val allCooked = when (batch.zone) {
                BatchZone.HIGH_MAIN -> isHighMainBatchCooked(batch, pans, now)
                else -> batch.items
                    .filter { !it.completed }
                    .all batchItems@{ item ->
                        val pan = pans.firstOrNull { it.positionSn == item.supplyPositionSn } ?: return@batchItems false
                        if (!pan.isHasSausage) {
                            false
                        } else if (pan.startTime > 0L) {
                            now - pan.startTime >= pan.bakingTime
                        } else {
                            pan.status == 2 || pan.holdingTime > 0L
                        }
                    }
            }
            if (!allCooked) {
                return@forEach
            }
            val holdingTime = System.currentTimeMillis()
            batch.items.forEach batchItems@{ item ->
                val pan = pans.firstOrNull { it.positionSn == item.supplyPositionSn } ?: return@batchItems
                if (pan.isHasSausage) {
                    markPanHolding(pan, holdingTime)
                }
            }
            when (batch.zone) {
                BatchZone.LOW_PRIMARY,
                BatchZone.LOW_SECONDARY -> {
                    if (snapshot.batches.none {
                            it.batchId != batch.batchId &&
                                it.zone != BatchZone.HIGH_MAIN &&
                                it.state == BatchState.HEATING
                        }) {
                        KaoChangOperate.keepWarm(2)
                    }
                }
                BatchZone.HIGH_MAIN -> {
                    KaoChangOperate.keepWarm(3)
                }
            }
            batch.state = BatchState.READY
            highBatchProgressLogBucket.remove(batch.batchId)
            if (batch.zone == BatchZone.HIGH_MAIN) {
                val positions = highMainReadyLogContext?.first ?: emptyList()
                val batchBakeDurationMs = highMainReadyLogContext?.second ?: 0L
                val latestStartTime = highMainReadyLogContext?.third
                LogUtils.i(
                    TAG,
                    "高并发批次已达到整批转保温条件：batch=${batch.batchId}，positions=$positions，" +
                        "batchBakingTimeMs=$batchBakeDurationMs，latestStartTime=$latestStartTime，holdAtSupply=${batch.holdAtSupply}"
                )
            } else {
                LogUtils.i(TAG, "批次烤熟待处理：batch=${batch.batchId}，holdAtSupply=${batch.holdAtSupply}")
            }
        }
    }

    /**
     * 高并发 25~33 补热区按“批次最后一根开始计时 + 当前整批烤制时长”统一收口。
     *
     * 这样可以避免：
     * 1. 同批烤肠因机械臂逐根补入造成 startTime 有先后，状态页上出现 42~56 分钟时仍难以判断何时整批转保温；
     * 2. 单盘 bakingTime 遗留旧值，导致后台当前配置已是 40 分钟，但该批仍按旧值迟迟不收口。
     */
    private fun isHighMainBatchCooked(batch: HeatBatch, pans: List<KaoPan>, now: Long): Boolean {
        val sourcePans = batch.items
            .filter { !it.completed }
            .mapNotNull { item -> pans.firstOrNull { it.positionSn == item.supplyPositionSn } }
        if (sourcePans.isEmpty()) {
            return false
        }
        if (sourcePans.any { !it.isHasSausage }) {
            return false
        }

        val batchBakeDurationMs = resolveHighMainBatchBakeDurationMs(sourcePans)
        val heatingPans = sourcePans.filter { it.startTime > 0L }
        if (heatingPans.isEmpty()) {
            return sourcePans.all { it.status == 2 || it.holdingTime > 0L }
        }

        val latestStartTime = heatingPans.maxOf { it.startTime }
        val readyAt = latestStartTime + batchBakeDurationMs
        val remainingMs = readyAt - now
        if (remainingMs > 0L) {
            maybeLogHighMainBatchProgress(
                batch = batch,
                sourcePans = sourcePans,
                batchBakeDurationMs = batchBakeDurationMs,
                latestStartTime = latestStartTime,
                readyAt = readyAt,
                remainingMs = remainingMs
            )
            return false
        }
        return true
    }

    private fun resolveHighMainBatchBakeDurationMs(sourcePans: List<KaoPan>): Long {
        val configBakeDurationMs = AppConfig.getAppConfig().bakingTime.coerceAtLeast(0) * ONE_MINUTE_MS
        if (configBakeDurationMs > 0L) {
            return configBakeDurationMs
        }
        return sourcePans
            .map { it.bakingTime }
            .filter { it > 0L }
            .maxOrNull()
            ?: 0L
    }

    private fun maybeLogHighMainBatchProgress(
        batch: HeatBatch,
        sourcePans: List<KaoPan>,
        batchBakeDurationMs: Long,
        latestStartTime: Long,
        readyAt: Long,
        remainingMs: Long
    ) {
        val bucket = remainingMs / ONE_MINUTE_MS
        val lastBucket = highBatchProgressLogBucket[batch.batchId]
        if (lastBucket == bucket) {
            return
        }
        highBatchProgressLogBucket[batch.batchId] = bucket
        LogUtils.i(
            TAG,
            "高并发批次继续烤制中：batch=${batch.batchId}，positions=${sourcePans.map { it.positionSn }}，" +
                "batchBakingTimeMs=$batchBakeDurationMs，latestStartTime=$latestStartTime，readyAt=$readyAt，remainingMs=$remainingMs"
        )
    }

    private fun markPanHolding(pan: KaoPan, holdingTime: Long) {
        pan.startTime = 0L
        if (pan.holdingTime <= 0L) {
            pan.holdingTime = holdingTime
        }
        pan.status = 2
        pan.temperature = AppConfig.getAppConfig().keepWarmTemperature
    }

    private fun resolveLowToHighFrontBackfillTarget(
        snapshot: SchedulerSnapshot,
        pans: List<KaoPan>,
        tasteCode: String?,
        reservedTargetPositions: Set<Int>
    ): KaoPan? {
        val frontSlots = snapshot.frontPlan.filter { it.positionSn in HIGH_BACKFILL_POSITIONS }
        if (frontSlots.isEmpty()) {
            return null
        }

        return frontSlots
            .asSequence()
            .filter { it.positionSn !in reservedTargetPositions }
            .sortedWith(
                compareBy<FrontPlanSlot>(
                    { rankLowToHighFrontBackfillCandidate(it.positionSn, it.taste?.tasteCode, tasteCode) },
                    { it.positionSn }
                )
            )
            .mapNotNull { slot ->
                pans.firstOrNull { it.positionSn == slot.positionSn }
            }
            .firstOrNull { !it.isHasSausage }
    }

    private suspend fun backfillBatch(snapshot: SchedulerSnapshot, batch: HeatBatch, pans: List<KaoPan>) {
        val now = System.currentTimeMillis()
        val reservedTargetPositions = mutableSetOf<Int>()
        batch.items.forEach { item ->
            if (item.completed) {
                return@forEach
            }
            val sourcePan = pans.firstOrNull { it.positionSn == item.supplyPositionSn } ?: return@forEach
            val plannedTargetPan = pans.firstOrNull { it.positionSn == item.targetPositionSn }
            val targetPan =
                if (batch.zone == BatchZone.HIGH_MAIN) {
                    resolveLowToHighFrontBackfillTarget(
                        snapshot = snapshot,
                        pans = pans,
                        tasteCode = item.taste?.tasteCode,
                        reservedTargetPositions = reservedTargetPositions
                    ) ?: plannedTargetPan
                } else {
                    plannedTargetPan
                } ?: return@forEach
            if (!sourcePan.isHasSausage) {
                item.completed = true
                markPitFulfilled(snapshot, item.pitId)
                return@forEach
            }
            if (targetPan.isHasSausage) {
                return@forEach
            }
            if (item.targetPositionSn != targetPan.positionSn) {
                LogUtils.i(
                    TAG,
                    "低转高回填目标重排：batch=${batch.batchId}，source=${sourcePan.positionSn}，" +
                        "oldTarget=${item.targetPositionSn}，newTarget=${targetPan.positionSn}，" +
                        "tasteCode=${item.taste?.tasteCode ?: "未知"}"
                )
                item.targetPositionSn = targetPan.positionSn
            }
            reservedTargetPositions += targetPan.positionSn
            if (batch.zone == BatchZone.HIGH_MAIN) {
                KaoChangOperate.keepWarm(1)
                KaoChangOperate.keepWarm(2)
            } else {
                KaoChangOperate.keepWarm(1)
            }
            if (KaoChangOperate.moveKaoPanToKaoPan(sourcePan, targetPan, now)) {
                item.completed = true
                markPitFulfilled(snapshot, item.pitId)
            } else {
                LogUtils.w(
                    TAG,
                    "批次回填中止：batch=${batch.batchId}，烤盘${sourcePan.positionSn} -> 烤盘${targetPan.positionSn} 搬移失败，本批次停止继续回填"
                )
                return
            }
        }
        if (batch.items.all { it.completed }) {
            batch.state = BatchState.COMPLETED
            cleanupZoneHeating(batch.zone, pans, snapshot)
            LogUtils.i(TAG, "批次回填完成：batch=${batch.batchId}")
        }
    }

    private fun holdBatchInPlace(snapshot: SchedulerSnapshot, batch: HeatBatch, pans: List<KaoPan>) {
        batch.items.forEach { item ->
            if (item.completed) {
                return@forEach
            }
            val sourcePan = pans.firstOrNull { it.positionSn == item.supplyPositionSn } ?: return@forEach
            if (sourcePan.isHasSausage) {
                markPanHolding(sourcePan, System.currentTimeMillis())
            }
            item.completed = true
            markPitFulfilled(snapshot, item.pitId)
        }
        batch.state = BatchState.HELD
        cleanupZoneHeating(batch.zone, pans, snapshot)
        LogUtils.i(TAG, "批次原位保留完成：batch=${batch.batchId}")
    }

    private suspend fun collapseToLow(
        snapshot: SchedulerSnapshot,
        config: AppConfigBean,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ) {
        snapshot.mode = SchedulerMode.LOW
        snapshot.transition = SchedulerTransition.NONE
        snapshot.pits.clear()
        snapshot.batches.clear()
        // 重置高并发补肠时间戳，防止下次重新进入高并发时被旧时间戳误触发高转低。
        snapshot.lastHighBatchLaunchAt = 0L
        rebuildFrontPlan(snapshot, LOW_FRONT_PLAN_SIZE, boxes, pans)
        KaoChangOperate.stopHeating(2)
        applyRuntimeMode(snapshot, config, "高转低过渡完成")
        LogUtils.i(TAG, "高转低过渡完成：frontStock=${pans.count { it.positionSn in 1..9 && it.isHasSausage }}")
    }

    private suspend fun discardExpiredFrontPans(pansSnapshot: SchedulerSnapshot, pans: List<KaoPan>) {
        if (pansSnapshot.frontPlan.isEmpty()) {
            return
        }
        val expiryPositions = (LOW_SELL_POSITIONS + HIGH_SELL_POSITIONS)
        pans.filter { it.positionSn in expiryPositions && it.isHasSausage && it.holdingTime > 0L }
            .forEach { pan ->
                val holding = System.currentTimeMillis() - pan.holdingTime
                if (holding > pan.closeTime) {
                    LogUtils.i(
                        TAG,
                        "检测到前区过保烤肠：position=${pan.positionSn}，holding=$holding，closeTime=${pan.closeTime}"
                    )
                    KaoChangOperate.discardSausage(
                        pan,
                        source = "过保到期",
                        detail = "已保温=${holding}ms，阈值=${pan.closeTime}ms"
                    )
                }
            }
    }

    private fun maybeAdoptExternalMode(
        snapshot: SchedulerSnapshot,
        config: AppConfigBean,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ) {
        if (snapshot.transition != SchedulerTransition.NONE) {
            return
        }
        if (snapshot.pits.any { !it.fulfilled } || snapshot.batches.any { it.state != BatchState.COMPLETED && it.state != BatchState.HELD }) {
            return
        }
        val configMode = config.modeType.toSchedulerMode()
        if (snapshot.mode == configMode) {
            return
        }
        snapshot.mode = configMode
        rebuildFrontPlan(snapshot, if (configMode == SchedulerMode.HIGH) HIGH_FRONT_PLAN_SIZE else LOW_FRONT_PLAN_SIZE, boxes, pans)
        LogUtils.i(TAG, "采纳外部并发模式切换：mode=${snapshot.mode}")
    }

    private fun completeFrontInitializationIfNeeded(
        snapshot: SchedulerSnapshot,
        config: AppConfigBean,
        pans: List<KaoPan>
    ) {
        val now = System.currentTimeMillis()
        val configBakeDurationMs = config.bakingTime.coerceAtLeast(0) * ONE_MINUTE_MS
        val plans = frontOpeningBatchPlans(
            modeType = if (snapshot.mode == SchedulerMode.HIGH) 2 else 1,
            transitionMode = when (snapshot.transition) {
                SchedulerTransition.NONE -> 0
                SchedulerTransition.LOW_TO_HIGH -> 1
                SchedulerTransition.HIGH_TO_LOW -> 2
            }
        )
        plans.forEach { plan ->
            val evaluation = evaluateFrontOpeningBatchPlan(
                plan = plan,
                pans = pans,
                configBakeDurationMs = configBakeDurationMs,
                now = now
            ) ?: return@forEach
            if (!evaluation.isReady) {
                return@forEach
            }
            val updatedCount = applyFrontOpeningBatchReady(
                plan = plan,
                pans = pans,
                holdingTime = now,
                keepWarmTemperature = config.keepWarmTemperature
            )
            if (updatedCount <= 0) {
                return@forEach
            }
            KaoChangOperate.stopHeating(plan.area)
            KaoChangOperate.keepWarm(plan.area)
            LogUtils.i(
                TAG,
                "前区开盘批次已达到整批转保温条件：batch=${plan.name}，positions=${plan.positions.toList()}，" +
                    "latestStartTime=${evaluation.latestStartTime}，batchBakingTimeMs=${evaluation.batchBakeDurationMs}，" +
                    "readyAt=${evaluation.readyAt}，transition=${snapshot.transition}"
            )
        }
    }

    private fun enterLowToHighTransition(
        snapshot: SchedulerSnapshot,
        config: AppConfigBean,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ) {
        snapshot.mode = SchedulerMode.HIGH
        snapshot.transition = SchedulerTransition.LOW_TO_HIGH
        snapshot.pits.clear()
        snapshot.batches.forEach { batch ->
            if (batch.zone != BatchZone.HIGH_MAIN) {
                batch.holdAtSupply = true
            }
        }
        rebuildFrontPlan(snapshot, HIGH_FRONT_PLAN_SIZE, boxes, pans)
        registerLowToHighFrontPits(snapshot, pans)
        applyRuntimeMode(snapshot, config, "触发低转高过渡")
        LogUtils.w(
            TAG,
            "触发低转高过渡：frontEmpty=${HIGH_BACKFILL_POSITIONS.count { position -> pans.any { it.positionSn == position && !it.isHasSausage } }}，" +
                "frontRange=1~21"
        )
    }

    private fun registerLowToHighFrontPits(snapshot: SchedulerSnapshot, pans: List<KaoPan>) {
        val now = System.currentTimeMillis()
        val occupiedPositions = pans
            .filter { it.positionSn in HIGH_BACKFILL_POSITIONS && it.isHasSausage }
            .mapTo(mutableSetOf()) { it.positionSn }
        val reservedByActiveLowBatches = snapshot.batches
            .filter { it.zone != BatchZone.HIGH_MAIN && it.state != BatchState.COMPLETED }
            .flatMapTo(mutableSetOf()) { batch -> batch.items.map { it.supplyPositionSn } }
        val unavailablePositions = occupiedPositions + reservedByActiveLowBatches
        snapshot.frontPlan
            .filter { it.positionSn in HIGH_BACKFILL_POSITIONS && it.positionSn !in unavailablePositions }
            .forEach { slot ->
                snapshot.pits.add(
                    PitRecord(
                        pitId = UUID.randomUUID().toString(),
                        positionSn = slot.positionSn,
                        taste = copyTaste(slot.taste),
                        createdAt = now + slot.positionSn
                    )
                )
            }
        snapshot.pits.sortBy { it.createdAt }
        LogUtils.i(
            TAG,
            "低转高扩容坑位登记完成：pitPositions=${snapshot.pits.map { it.positionSn }}，" +
                "occupied=$occupiedPositions，reservedByLowBatch=$reservedByActiveLowBatches"
        )
    }

    private fun rebuildFrontPlan(
        snapshot: SchedulerSnapshot,
        size: Int,
        boxes: List<KaoPanBox>,
        pans: List<KaoPan>
    ) {
        val occupied = pans.filter { it.positionSn in (1..size) && it.isHasSausage }
        val allocation = KaoPanHelper.computeKaoPamRatio(boxes, size)
        val existingCount = occupied
            .mapNotNull { it.taste?.tasteCode?.takeIf { code -> code.isNotBlank() } }
            .groupingBy { it }
            .eachCount()

        val remainingByTaste = allocation.mapValues { (tasteCode, targetCount) ->
            (targetCount - existingCount.getOrDefault(tasteCode, 0)).coerceAtLeast(0)
        }.toMutableMap()

        val slots = mutableListOf<FrontPlanSlot>()
        (1..size).forEach { position ->
            val occupiedPan = occupied.firstOrNull { it.positionSn == position }
            if (occupiedPan != null) {
                slots.add(FrontPlanSlot(position, copyTaste(occupiedPan.taste)))
            } else {
                val nextTasteCode = remainingByTaste.entries.firstOrNull { it.value > 0 }?.key
                    ?: allocation.keys.firstOrNull()
                val taste = resolveTasteForCode(nextTasteCode, boxes)
                if (nextTasteCode != null) {
                    remainingByTaste[nextTasteCode] = (remainingByTaste[nextTasteCode] ?: 1) - 1
                }
                slots.add(FrontPlanSlot(position, taste))
            }
        }
        snapshot.frontPlan = slots
    }

    private fun applyFrontPlanProjection(snapshot: SchedulerSnapshot, pans: List<KaoPan>) {
        snapshot.frontPlan.forEach { slot ->
            val pan = pans.firstOrNull { it.positionSn == slot.positionSn } ?: return@forEach
            if (!pan.isHasSausage && slot.taste != null) {
                pan.taste = copyTaste(slot.taste)
            }
        }
    }

    private fun lowCapacityAlreadyMaxed(snapshot: SchedulerSnapshot, pans: List<KaoPan>): Boolean {
        val activeLowBatches = snapshot.batches.count {
            it.zone != BatchZone.HIGH_MAIN && it.state != BatchState.COMPLETED && it.state != BatchState.HELD
        }
        val supplementOccupied = pans.count { it.positionSn in 13..18 && it.isHasSausage }
        return activeLowBatches >= 2 || supplementOccupied >= 6
    }

    private fun shouldSwitchHighToLowByThreshold(snapshot: SchedulerSnapshot, config: AppConfigBean): Boolean {
        val thresholdMinutes = config.timeThresholdLow
        if (snapshot.lastHighBatchLaunchAt <= 0L || thresholdMinutes <= 0) {
            return false
        }
        val elapsed = System.currentTimeMillis() - snapshot.lastHighBatchLaunchAt
        return elapsed > thresholdMinutes * 60 * 1000L
    }

    private fun isBeforeCloseWindow(config: AppConfigBean): Boolean {
        val businessTime = config.businessTime ?: return false
        val beforeCloseMinutes = config.timeBeforeClose.takeIf { it > 0 } ?: 30
        return KaoPanHelper.isBeforeBusinessHours(businessTime, beforeCloseMinutes)
    }

    private fun cleanupZoneHeating(zone: BatchZone, pans: List<KaoPan>, snapshot: SchedulerSnapshot) {
        when (zone) {
            BatchZone.LOW_PRIMARY,
            BatchZone.LOW_SECONDARY -> {
                val areaStillUsed = pans.any { it.positionSn in 13..18 && it.isHasSausage } ||
                    snapshot.batches.any {
                        it.zone != BatchZone.HIGH_MAIN &&
                            it.state != BatchState.COMPLETED &&
                            it.state != BatchState.HELD
                    }
                if (!areaStillUsed) {
                    KaoChangOperate.stopHeating(2)
                }
            }
            BatchZone.HIGH_MAIN -> {
                val areaStillUsed = pans.any { it.positionSn in 25..33 && it.isHasSausage } ||
                    snapshot.batches.any { it.zone == BatchZone.HIGH_MAIN && it.state != BatchState.COMPLETED }
                if (!areaStillUsed) {
                    KaoChangOperate.stopHeating(3)
                }
            }
        }
    }

    private fun markPitFulfilled(snapshot: SchedulerSnapshot, pitId: String) {
        snapshot.pits.firstOrNull { it.pitId == pitId }?.apply {
            fulfilled = true
            assignedBatchId = null
        }
    }

    private fun ensureSnapshot(
        config: AppConfigBean,
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ): SchedulerSnapshot {
        val cached = synchronized(this) {
            snapshotCache ?: loadSnapshotLocked()
        }
        if (cached != null) {
            return cached
        }
        val mode = config.modeType.toSchedulerMode()
        val snapshot = SchedulerSnapshot(
            mode = mode,
            transition = config.transitionMode.toSchedulerTransition()
        )
        rebuildFrontPlan(snapshot, if (mode == SchedulerMode.HIGH) HIGH_FRONT_PLAN_SIZE else LOW_FRONT_PLAN_SIZE, boxes, pans)
        saveSnapshot(snapshot)
        return snapshot
    }

    private fun getSnapshotOrNull(): SchedulerSnapshot? {
        synchronized(this) {
            return snapshotCache ?: loadSnapshotLocked()
        }
    }

    private fun saveSnapshot(snapshot: SchedulerSnapshot) {
        synchronized(this) {
            snapshotCache = snapshot
            PreferenceUtils.saveObjectBase64Preference(SP_FILE, KEY_SNAPSHOT, snapshot)
        }
    }

    private fun loadSnapshotLocked(): SchedulerSnapshot? {
        val snapshot = PreferenceUtils.getObjectBase64Preference(SP_FILE, KEY_SNAPSHOT) as? SchedulerSnapshot
        if (snapshot != null) {
            snapshotCache = snapshot
        }
        return snapshot
    }

    private fun nextBatchId(snapshot: SchedulerSnapshot, prefix: String): String {
        val id = "$prefix-${snapshot.nextBatchSequence}"
        snapshot.nextBatchSequence += 1
        return id
    }

    private fun resolveTasteForCode(tasteCode: String?, boxes: List<KaoPanBox>): Taste? {
        if (tasteCode.isNullOrBlank()) {
            return null
        }
        val matchingBox = boxes.firstOrNull { it.tasteCode == tasteCode }
        return if (matchingBox != null) {
            KaoPanHelper.buildTasteFromBox(matchingBox, Taste(tasteCode))
        } else {
            Taste(tasteCode)
        }
    }

    private fun copyTaste(taste: Taste?): Taste? {
        return KaoPanHelper.copyResolvedTaste(taste) ?: taste?.let {
            Taste().apply {
                tasteCode = it.tasteCode
                tasteId = it.tasteId
                tasteName = it.tasteName
                productId = it.productId
                productName = it.productName
            }
        }
    }

    private fun findSupplyBox(boxes: List<KaoPanBox>, tasteCode: String?): KaoPanBox? {
        return boxes
            .filter { it.tasteCode == tasteCode && it.num > 0 }
            .maxByOrNull { it.positionSn }
    }

    private fun hasEnoughHighBatchSupply(selectedPits: List<PitRecord>, boxes: List<KaoPanBox>): Boolean {
        val requiredByTaste = selectedPits
            .map { it.taste?.tasteCode }
            .groupingBy { it }
            .eachCount()
        return requiredByTaste.all { (tasteCode, requiredCount) ->
            boxes
                .filter { it.tasteCode == tasteCode }
                .sumOf { it.num }
                .let { availableCount -> availableCount >= requiredCount }
        }
    }

    private fun applyRuntimeMode(snapshot: SchedulerSnapshot, config: AppConfigBean, reason: String) {
        val nextMode = if (snapshot.mode == SchedulerMode.LOW) 1 else 2
        val nextTransition = when (snapshot.transition) {
            SchedulerTransition.NONE -> 0
            SchedulerTransition.LOW_TO_HIGH -> 1
            SchedulerTransition.HIGH_TO_LOW -> 2
        }
        if (config.modeType == nextMode && config.transitionMode == nextTransition) {
            return
        }
        config.modeType = nextMode
        config.transitionMode = nextTransition
        AppConfig.saveAppConfig(config)
        SendServerHelper.publishServiceUpdateStatus("scheduler:$reason")
    }

    private fun Int.toSchedulerMode(): SchedulerMode {
        return if (this == 2) SchedulerMode.HIGH else SchedulerMode.LOW
    }

    private fun Int.toSchedulerTransition(): SchedulerTransition {
        return when (this) {
            1 -> SchedulerTransition.LOW_TO_HIGH
            2 -> SchedulerTransition.HIGH_TO_LOW
            else -> SchedulerTransition.NONE
        }
    }

    private fun BatchZone.positions(): List<Int> {
        return when (this) {
            BatchZone.LOW_PRIMARY -> listOf(13, 14, 15)
            BatchZone.LOW_SECONDARY -> listOf(16, 17, 18)
            BatchZone.HIGH_MAIN -> (25..33).toList()
        }
    }
}
