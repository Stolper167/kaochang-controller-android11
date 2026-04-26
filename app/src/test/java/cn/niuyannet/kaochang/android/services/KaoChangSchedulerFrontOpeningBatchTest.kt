package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaoChangSchedulerFrontOpeningBatchTest {

    @Test
    fun currentSellPositions_shouldUseLowSaleAreaInLowMode() {
        val config = AppConfigBean().apply {
            modeType = 1
            transitionMode = 0
        }

        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 14, 15, 16, 17, 18),
            KaoChangScheduler.currentSellPositions(config)
        )
    }

    @Test
    fun currentSellPositions_shouldUseFullOneToTwentyOneInHighMode() {
        val config = AppConfigBean().apply {
            modeType = 2
            transitionMode = 0
        }

        assertEquals(
            (1..21).toList(),
            KaoChangScheduler.currentSellPositions(config)
        )
    }

    @Test
    fun currentSellPositions_shouldUseFullOneToTwentyOneDuringHighToLowTransition() {
        val config = AppConfigBean().apply {
            modeType = 2
            transitionMode = 2
        }

        assertEquals(
            (1..21).toList(),
            KaoChangScheduler.currentSellPositions(config)
        )
    }

    @Test
    fun frontOpeningBatchPlans_shouldUseOneToNineOnlyInLowMode() {
        val plans = frontOpeningBatchPlans(modeType = 1, transitionMode = 0)

        assertEquals(listOf("1~9开盘批次"), plans.map { it.name })
        assertEquals(listOf(1), plans.map { it.area })
        assertEquals(listOf(1..9), plans.map { it.positions })
    }

    @Test
    fun frontOpeningBatchPlans_shouldSplitOneToNineAndTenToTwentyOneInHighMode() {
        val plans = frontOpeningBatchPlans(modeType = 2, transitionMode = 2)

        assertEquals(listOf("1~9开盘批次", "10~21开盘批次"), plans.map { it.name })
        assertEquals(listOf(1, 2), plans.map { it.area })
        assertEquals(listOf(1..9, 10..21), plans.map { it.positions })
    }

    @Test
    fun evaluateFrontOpeningBatchPlan_shouldUseLatestStartTimeAndConfiguredBakeDuration() {
        val now = 4_000_000L
        val plan = FrontOpeningBatchPlan(name = "1~9开盘批次", area = 1, positions = 1..9)
        val pans = buildList {
            addAll((1..8).map { position ->
                heatingPan(positionSn = position, startTime = now - 50 * 60_000L, bakingTime = 55 * 60_000L)
            })
            add(heatingPan(positionSn = 9, startTime = now - 20 * 60_000L, bakingTime = 55 * 60_000L))
        }

        val evaluation = evaluateFrontOpeningBatchPlan(
            plan = plan,
            pans = pans,
            configBakeDurationMs = 40 * 60_000L,
            now = now
        )

        assertNotNull(evaluation)
        assertEquals(now - 20 * 60_000L, evaluation!!.latestStartTime)
        assertEquals(40 * 60_000L, evaluation.batchBakeDurationMs)
        assertEquals(20 * 60_000L, evaluation.remainingMs)
        assertFalse(evaluation.isReady)
    }

    @Test
    fun evaluateFrontOpeningBatchPlan_shouldSupportPartialFrontBatchDuringHighToLowTransition() {
        val now = 8_000_000L
        val plan = FrontOpeningBatchPlan(name = "10~21开盘批次", area = 2, positions = 10..21)
        val pans = listOf(
            heatingPan(positionSn = 10, startTime = now - 55 * 60_000L, bakingTime = 70 * 60_000L),
            heatingPan(positionSn = 11, startTime = now - 42 * 60_000L, bakingTime = 70 * 60_000L),
            heatingPan(positionSn = 13, startTime = now - 41 * 60_000L, bakingTime = 70 * 60_000L),
            heatingPan(positionSn = 14, startTime = now - 40 * 60_000L - 5_000L, bakingTime = 70 * 60_000L),
            heatingPan(positionSn = 16, startTime = now - 38 * 60_000L, bakingTime = 70 * 60_000L),
            heatingPan(positionSn = 17, startTime = now - 37 * 60_000L, bakingTime = 70 * 60_000L),
            heatingPan(positionSn = 19, startTime = now - 36 * 60_000L, bakingTime = 70 * 60_000L)
        )

        val evaluation = evaluateFrontOpeningBatchPlan(
            plan = plan,
            pans = pans,
            configBakeDurationMs = 40 * 60_000L,
            now = now
        )

        assertNotNull(evaluation)
        assertEquals(now - 36 * 60_000L, evaluation!!.latestStartTime)
        assertEquals(4 * 60_000L, evaluation.remainingMs)
        assertFalse(evaluation.isReady)
    }

    @Test
    fun evaluateFrontOpeningBatchPlan_shouldFallbackToLargestPanBakeDurationWhenConfigMissing() {
        val now = 6_000_000L
        val plan = FrontOpeningBatchPlan(name = "10~21开盘批次", area = 2, positions = 10..21)
        val pans = (10..21).mapIndexed { index, position ->
            val bakeDuration = if (index == 11) 45 * 60_000L else 35 * 60_000L
            heatingPan(positionSn = position, startTime = now - 10 * 60_000L, bakingTime = bakeDuration)
        }

        val evaluation = evaluateFrontOpeningBatchPlan(
            plan = plan,
            pans = pans,
            configBakeDurationMs = 0L,
            now = now
        )

        assertNotNull(evaluation)
        assertEquals(45 * 60_000L, evaluation!!.batchBakeDurationMs)
        assertEquals(35 * 60_000L, evaluation.remainingMs)
    }

    @Test
    fun applyFrontOpeningBatchReady_shouldConvertWholeBatchToHoldingAndSyncTemperature() {
        val plan = FrontOpeningBatchPlan(name = "10~21开盘批次", area = 2, positions = 10..21)
        val pans = (10..21).map { position ->
            heatingPan(positionSn = position, startTime = 1_000L, bakingTime = 2_000L)
        }
        val holdingTime = 9_999L
        val updatedCount = applyFrontOpeningBatchReady(
            plan = plan,
            pans = pans,
            holdingTime = holdingTime,
            keepWarmTemperature = 80
        )

        assertEquals(12, updatedCount)
        pans.forEach { pan ->
            assertEquals(0L, pan.startTime)
            assertEquals(holdingTime, pan.holdingTime)
            assertEquals(2, pan.status)
            assertEquals(80, pan.temperature)
        }
    }

    @Test
    fun rankLowToHighFrontBackfillCandidate_shouldPreferOneToNineBeforeTenToTwentyOne() {
        val frontCoreRank = rankLowToHighFrontBackfillCandidate(
            positionSn = 8,
            slotTasteCode = "B",
            targetTasteCode = "B"
        )
        val outerFrontRank = rankLowToHighFrontBackfillCandidate(
            positionSn = 10,
            slotTasteCode = "B",
            targetTasteCode = "B"
        )

        assertTrue(frontCoreRank < outerFrontRank)
    }

    @Test
    fun rankLowToHighFrontBackfillCandidate_shouldPreferSameTasteWithinSameAreaBucket() {
        val sameTasteRank = rankLowToHighFrontBackfillCandidate(
            positionSn = 10,
            slotTasteCode = "B",
            targetTasteCode = "B"
        )
        val mismatchTasteRank = rankLowToHighFrontBackfillCandidate(
            positionSn = 10,
            slotTasteCode = "A",
            targetTasteCode = "B"
        )

        assertTrue(sameTasteRank < mismatchTasteRank)
    }

    @Test
    fun selectHighMainBackfillTargets_shouldUseOneToTwentyOneAfterOneToNine() {
        val targets = selectHighMainBackfillTargets(
            frontPlanPositions = (1..21).toList(),
            reservedPositions = setOf(8, 9, 10, 11, 12, 13)
        )

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 14, 15), targets)
    }

    @Test
    fun selectHighMainBackfillTargets_shouldSkipWhenLessThanFullHighBatch() {
        val targets = selectHighMainBackfillTargets(
            frontPlanPositions = (1..21).toList(),
            reservedPositions = (1..13).toSet()
        )

        assertEquals(emptyList<Int>(), targets)
    }

    @Test
    fun planHighToLowCompaction_shouldMoveTenToTwentyOneBackToLowFrontSlots() {
        val pans = (1..21).map { position ->
            when (position) {
                3, 4 -> holdingPan(positionSn = position, holdingTime = 3_000L)
                10 -> holdingPan(positionSn = position, holdingTime = 1_000L)
                13 -> holdingPan(positionSn = position, holdingTime = 2_000L)
                else -> emptyPan(position)
            }
        }

        val moves = planHighToLowCompaction(pans)

        assertEquals(
            listOf(
                HighToLowCompactionMove(sourcePositionSn = 10, targetPositionSn = 1),
                HighToLowCompactionMove(sourcePositionSn = 13, targetPositionSn = 2)
            ),
            moves
        )
    }

    @Test
    fun planHighToLowCompaction_shouldWaitWhenHighFrontHasMoreThanSixSausages() {
        val pans = (1..21).map { position ->
            if (position in listOf(1, 2, 3, 10, 11, 12, 13)) {
                holdingPan(positionSn = position, holdingTime = position.toLong())
            } else {
                emptyPan(position)
            }
        }

        assertEquals(null, planHighToLowCompaction(pans))
    }

    private fun heatingPan(
        positionSn: Int,
        startTime: Long,
        bakingTime: Long
    ): KaoPan = KaoPan().apply {
        setPositionSn(positionSn)
        setHasSausage(true)
        setStartTime(startTime)
        setBakingTime(bakingTime)
        setHoldingTime(0L)
        setStatus(1)
        setTemperature(175)
    }

    private fun holdingPan(positionSn: Int, holdingTime: Long): KaoPan = KaoPan().apply {
        setPositionSn(positionSn)
        setHasSausage(true)
        setHoldingTime(holdingTime)
        setStatus(2)
    }

    private fun emptyPan(positionSn: Int): KaoPan = KaoPan().apply {
        setPositionSn(positionSn)
        setHasSausage(false)
        setHoldingTime(0L)
        setStatus(0)
    }
}
