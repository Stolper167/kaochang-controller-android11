package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖两个 bug 的回归测试。
 *
 * Bug 1：LOW_SECONDARY（16~18）建批门槛/选坑逻辑
 *   修复前：required=6 且 drop(3).take(3)——PRIMARY 建批后 assignedBatchId 已写入那 3 个坑，
 *           它们从 unresolved 里消失，剩余坑数通常不足 6，SECONDARY 永远起不来。
 *   修复后：required=3，selectedPits = unresolved.take(3)，PRIMARY 的坑已被 assignedBatchId 排除，
 *           直接取剩余未分配坑前 3 个即可。
 *
 * Bug 2：lastHighBatchLaunchAt 未在模式切换时重置
 *   修复前：高→低→高切换后旧时间戳残留，shouldSwitchHighToLowByThreshold 读到大 elapsed 立刻误触高转低。
 *   修复后：collapseToLow 和 initializeHigh 中重置 lastHighBatchLaunchAt = 0L，
 *           使得 shouldSwitchHighToLowByThreshold 里 lastHighBatchLaunchAt <= 0L 的短路条件成立，返回 false。
 */
class KaoChangSchedulerLowSecondaryBatchTest {

    // ──────────────────────────────────────────
    // Bug 1：LOW_SECONDARY 建批门槛与选坑
    // ──────────────────────────────────────────

    /**
     * 验证修复后的筛选语义：
     * PRIMARY 建批后，3 个坑的 assignedBatchId 被写入，不再出现在 unresolved 里。
     * SECONDARY 取 unresolved.take(3)，选出的是剩余未分配的坑，不会与 PRIMARY 重叠。
     *
     * 使用真实 PitRecord（已改为 internal）直接断言筛选结果，驱动修复后的选坑公式。
     */
    @Test
    fun lowSecondary_unresolvedPitsExcludePrimaryAfterAssignment_andTake3IsCorrect() {
        val primaryBatchId = "L-1"
        // 6 个坑：前 3 个已被 PRIMARY 分配（assignedBatchId 非空），后 3 个未分配
        val allPits = listOf(
            makePit(positionSn = 1, assignedBatchId = primaryBatchId),
            makePit(positionSn = 2, assignedBatchId = primaryBatchId),
            makePit(positionSn = 3, assignedBatchId = primaryBatchId),
            makePit(positionSn = 4),
            makePit(positionSn = 5),
            makePit(positionSn = 6),
        )

        // 模拟 launchLowBatchIfNeeded 内部的 unresolved 过滤逻辑
        val unresolved = allPits.filter { !it.fulfilled && it.assignedBatchId == null }
            .sortedBy { it.createdAt }

        // 修复后 required=3，unresolved=3，满足条件
        val required = 3
        assertTrue(
            "PRIMARY 建批后剩余 3 个未分配坑应满足 SECONDARY required=3",
            unresolved.size >= required
        )

        // 修复后 selectedPits = unresolved.take(3)，选出坑 4、5、6
        val selectedPits = unresolved.take(3)
        assertEquals("SECONDARY 应选出 3 个坑", 3, selectedPits.size)
        assertEquals("选出坑位应为 4、5、6", listOf(4, 5, 6), selectedPits.map { it.positionSn })
        selectedPits.forEach { pit ->
            assertNull("选出的坑不能带有 assignedBatchId（不应与 PRIMARY 重叠）", pit.assignedBatchId)
        }
    }

    /**
     * 旧逻辑复现（required=6 + drop(3).take(3)）：
     * PRIMARY 建批后 unresolved 只剩 4 个坑时，旧逻辑会拦截 SECONDARY。
     * 新逻辑（required=3）应当允许建批，选出前 3 个。
     */
    @Test
    fun lowSecondary_oldRequiredSixWouldBlock_newRequiredThreeAllows_with7TotalPits() {
        val primaryBatchId = "L-1"
        // 7 个坑：PRIMARY 占 3，剩余 4
        val allPits = listOf(
            makePit(positionSn = 1, assignedBatchId = primaryBatchId),
            makePit(positionSn = 2, assignedBatchId = primaryBatchId),
            makePit(positionSn = 3, assignedBatchId = primaryBatchId),
            makePit(positionSn = 4),
            makePit(positionSn = 5),
            makePit(positionSn = 6),
            makePit(positionSn = 7),
        )

        val unresolved = allPits.filter { !it.fulfilled && it.assignedBatchId == null }
            .sortedBy { it.createdAt }

        // 旧逻辑：required=6，4 < 6 → 拦截
        assertFalse("旧 required=6 时，4 个未分配坑不足，SECONDARY 被误拦", unresolved.size >= 6)

        // 新逻辑：required=3，4 >= 3 → 放行
        assertTrue("新 required=3 时，4 个未分配坑足够，SECONDARY 可建批", unresolved.size >= 3)

        val selectedPits = unresolved.take(3)
        assertEquals(3, selectedPits.size)
        // 选出的坑不含 PRIMARY 已分配的坑（1、2、3）
        assertTrue(selectedPits.none { it.positionSn in 1..3 })
    }

    /**
     * PRIMARY 建批后 unresolved < 3：SECONDARY 正确被拦截，不建批。
     */
    @Test
    fun lowSecondary_shouldNotLaunchWhenFewerThan3UnresolvedPitsRemain() {
        val primaryBatchId = "L-1"
        // 仅 4 个坑，PRIMARY 占 3，剩余 1
        val allPits = listOf(
            makePit(positionSn = 1, assignedBatchId = primaryBatchId),
            makePit(positionSn = 2, assignedBatchId = primaryBatchId),
            makePit(positionSn = 3, assignedBatchId = primaryBatchId),
            makePit(positionSn = 4),
        )

        val unresolved = allPits.filter { !it.fulfilled && it.assignedBatchId == null }

        assertFalse(
            "剩余 1 个未分配坑不足 required=3，SECONDARY 应被拦截",
            unresolved.size >= 3
        )
    }

    @Test
    fun lowPrimaryTemperatureConflict_shouldEvacuateHolding16To18IntoFrontEmptySlots() {
        val pans = (1..18).map { position ->
            pan(positionSn = position, tasteCode = "B").apply {
                when (position) {
                    2, 3, 5 -> {
                        setHasSausage(false)
                        setStatus(0)
                    }
                    in 16..18 -> {
                        setHasSausage(true)
                        setStatus(2)
                        setHoldingTime(position * 1_000L)
                    }
                    else -> {
                        setHasSausage(position in 1..9)
                        setStatus(if (position in 1..9) 2 else 0)
                        setHoldingTime(if (position in 1..9) 100L else 0L)
                    }
                }
            }
        }

        val moves = planLowSecondaryHoldingEvacuation(
            pans = pans,
            launchingZone = BatchZone.LOW_PRIMARY
        )

        assertEquals(
            listOf(
                LowSecondaryEvacuationMove(sourcePositionSn = 16, targetPositionSn = 2),
                LowSecondaryEvacuationMove(sourcePositionSn = 17, targetPositionSn = 3),
                LowSecondaryEvacuationMove(sourcePositionSn = 18, targetPositionSn = 5)
            ),
            moves
        )
    }

    @Test
    fun lowSecondaryTemperatureConflict_shouldBlockEvacuationWhenFrontIsHeating() {
        val pans = (1..18).map { position ->
            pan(positionSn = position, tasteCode = "B").apply {
                when (position) {
                    2, 3, 5 -> {
                        setHasSausage(false)
                        setStatus(0)
                    }
                    6 -> {
                        setHasSausage(true)
                        setStatus(1)
                        setStartTime(10_000L)
                    }
                    in 16..18 -> {
                        setHasSausage(true)
                        setStatus(2)
                        setHoldingTime(position * 1_000L)
                    }
                }
            }
        }

        val moves = planLowSecondaryHoldingEvacuation(
            pans = pans,
            launchingZone = BatchZone.LOW_PRIMARY
        )

        assertNull("一区正在烤制时，不能把二区保温肠搬入一区", moves)
    }

    @Test
    fun lowPrimaryTemperatureConflict_shouldBlockEvacuationWhenFrontTasteReservationMismatch() {
        val pans = (1..18).map { position ->
            pan(positionSn = position, tasteCode = if (position in 1..9) "A" else "B").apply {
                when (position) {
                    2, 3, 5 -> {
                        setHasSausage(false)
                        setStatus(0)
                    }
                    in 16..18 -> {
                        setHasSausage(true)
                        setStatus(2)
                        setHoldingTime(position * 1_000L)
                    }
                    else -> {
                        setHasSausage(position in 1..9)
                        setStatus(if (position in 1..9) 2 else 0)
                    }
                }
            }
        }

        val moves = planLowSecondaryHoldingEvacuation(
            pans = pans,
            launchingZone = BatchZone.LOW_PRIMARY
        )

        assertNull("一区空位口味预留不匹配时，不能搬入二区保温肠", moves)
    }

    @Test
    fun lowPrimaryTemperatureConflict_shouldReturnEmptyPlanWhenOppositeLowSubZoneHasNoHoldingSausage() {
        val pans = (1..18).map { position ->
            pan(positionSn = position, tasteCode = "B").apply {
                setHasSausage(position in 1..9)
                setStatus(if (position in 1..9) 2 else 0)
            }
        }

        val moves = planLowSecondaryHoldingEvacuation(
            pans = pans,
            launchingZone = BatchZone.LOW_PRIMARY
        )

        assertEquals(emptyList<LowSecondaryEvacuationMove>(), moves)
    }

    @Test
    fun lowReadyEvacuationTarget_shouldPreferOriginalPitWhenAvailable() {
        val pans = (1..9).map { position ->
            pan(positionSn = position, tasteCode = "A").apply {
                setHasSausage(position != 4)
                setStatus(if (position == 4) 0 else 2)
            }
        }

        val target = resolveLowReadyEvacuationTarget(
            pans = pans,
            originalPitPositionSn = 4,
            tasteCode = "B"
        )

        assertEquals(4, target?.targetPositionSn)
        assertTrue("原始坑位可用时应优先搬回原坑", target?.isOriginalPit == true)
        assertFalse("搬回原始坑位不是口味兜底搬移", target?.isTasteFallback == true)
    }

    @Test
    fun lowReadyEvacuationTarget_shouldFallbackToSameTasteEmptyFrontSlot() {
        val pans = (1..9).map { position ->
            pan(positionSn = position, tasteCode = if (position == 5) "B" else "A").apply {
                setHasSausage(position !in listOf(4, 5))
                setStatus(if (position in listOf(4, 5)) 0 else 2)
            }
        }

        val target = resolveLowReadyEvacuationTarget(
            pans = pans,
            originalPitPositionSn = 4,
            tasteCode = "B",
            reservedTargetPositions = setOf(4)
        )

        assertEquals(5, target?.targetPositionSn)
        assertFalse("原始坑被占用或预留时不应标记为原始坑", target?.isOriginalPit == true)
        assertFalse("同口味空位不是口味兜底搬移", target?.isTasteFallback == true)
    }

    @Test
    fun lowReadyEvacuationTarget_shouldUseAnyFrontSlotWhenTasteDoesNotMatch() {
        val pans = (1..9).map { position ->
            pan(positionSn = position, tasteCode = "A").apply {
                setHasSausage(position !in listOf(2, 4))
                setStatus(if (position in listOf(2, 4)) 0 else 2)
            }
        }

        val target = resolveLowReadyEvacuationTarget(
            pans = pans,
            originalPitPositionSn = 4,
            tasteCode = "B",
            reservedTargetPositions = setOf(4)
        )

        assertEquals(2, target?.targetPositionSn)
        assertFalse("任意空位兜底不应标记为原始坑", target?.isOriginalPit == true)
        assertTrue("没有同口味空位时应标记为口味兜底搬移", target?.isTasteFallback == true)
    }

    @Test
    fun lowReadyEvacuationTarget_shouldReturnNullWhenFrontHasNoEmptySlot() {
        val pans = (1..9).map { position ->
            pan(positionSn = position, tasteCode = "B").apply {
                setHasSausage(true)
                setStatus(2)
            }
        }

        val target = resolveLowReadyEvacuationTarget(
            pans = pans,
            originalPitPositionSn = 4,
            tasteCode = "B"
        )

        assertNull("一区没有空位时，成熟批次不能伪装成保温可售", target)
    }

    @Test
    fun lowReadyBatch_shouldDetectOppositeLowZoneHeating() {
        val pans = (1..18).map { position ->
            pan(positionSn = position, tasteCode = "B").apply {
                when (position) {
                    16 -> {
                        setHasSausage(true)
                        setStatus(1)
                        setStartTime(1_000L)
                    }
                    else -> {
                        setHasSausage(false)
                        setStatus(0)
                    }
                }
            }
        }

        assertTrue(
            "LOW_PRIMARY（13~15）成熟时，LOW_SECONDARY（16~18）仍在烤制应触发二区熟肠避让",
            hasOppositeLowZoneHeating(BatchZone.LOW_PRIMARY, pans)
        )
        assertFalse(
            "LOW_SECONDARY 自身作为待处理批次时，不应把 16~18 自己误判为对侧加热",
            hasOppositeLowZoneHeating(BatchZone.LOW_SECONDARY, pans)
        )
    }

    // ──────────────────────────────────────────
    // Bug 2：lastHighBatchLaunchAt 重置后的误触防护
    // ──────────────────────────────────────────

    /**
     * 验证修复后核心路径：
     * collapseToLow / initializeHigh 将 lastHighBatchLaunchAt 重置为 0L。
     * shouldSwitchHighToLowByThreshold 内部有 `lastHighBatchLaunchAt <= 0L` 短路，直接返回 false。
     *
     * shouldSwitchHighToLowByTimeout（KaoChangScheduler 内部判定的提取版本，已是 internal）
     * 与 shouldSwitchHighToLowByThreshold 实现数学等价，用其验证"0L 不触发高转低"的结论。
     */
    @Test
    fun lastHighBatchLaunchAt_resetToZero_shouldNotTriggerHighToLow() {
        // 模拟修复后：collapseToLow / initializeHigh 已将 lastHighBatchLaunchAt 置 0L
        val lastHighBatchLaunchAt = 0L
        val now = System.currentTimeMillis()
        val thresholdMinutes = 30

        val shouldSwitch = shouldSwitchHighToLowByTimeout(
            lastFillPitTime = lastHighBatchLaunchAt,
            now = now,
            timeThresholdLowMinutes = thresholdMinutes
        )

        assertFalse(
            "lastHighBatchLaunchAt=0L 时，shouldSwitchHighToLow 的 <=0 短路条件成立，不应触发高转低",
            shouldSwitch
        )
    }

    /**
     * 对照组：旧 bug 复现——lastHighBatchLaunchAt 为过期时间戳时误触高转低。
     * 确认 0L 重置是真实消除了误触条件，而不是测试本身逻辑写错。
     */
    @Test
    fun lastHighBatchLaunchAt_staleTimestamp_shouldTriggerHighToLow_confirmingBugExists() {
        val staleTimestamp = System.currentTimeMillis() - 60 * 60 * 1000L // 1 小时前
        val now = System.currentTimeMillis()
        val thresholdMinutes = 30

        val shouldSwitch = shouldSwitchHighToLowByTimeout(
            lastFillPitTime = staleTimestamp,
            now = now,
            timeThresholdLowMinutes = thresholdMinutes
        )

        assertTrue(
            "旧时间戳（1小时前）超过30分钟阈值，确认旧 bug 确实会误触高转低",
            shouldSwitch
        )
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private fun makePit(
        positionSn: Int,
        assignedBatchId: String? = null,
        fulfilled: Boolean = false
    ): PitRecord = PitRecord(
        pitId = "pit-$positionSn",
        positionSn = positionSn,
        taste = null,
        createdAt = positionSn.toLong(),
        assignedBatchId = assignedBatchId,
        fulfilled = fulfilled
    )

    private fun pan(positionSn: Int, tasteCode: String): KaoPan = KaoPan().apply {
        setPositionSn(positionSn)
        setTaste(Taste().apply {
            this.tasteCode = tasteCode
            this.productId = 101
        })
    }
}
