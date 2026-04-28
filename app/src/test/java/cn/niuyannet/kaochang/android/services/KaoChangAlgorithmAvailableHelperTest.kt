package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KaoChangAlgorithmAvailableHelperTest {

    @Test
    fun currentLegacySellPositions_shouldUseOnlyFrontNineInLowStable() {
        val config = AppConfigBean().apply {
            modeType = 1
            transitionMode = 0
        }

        assertEquals(1..9, KaoChangAlgorithm.currentLegacySellPositions(config))
    }

    @Test
    fun currentLegacySellPositions_shouldUseFrontTwentyOneInHighOrTransition() {
        val highConfig = AppConfigBean().apply {
            modeType = 2
            transitionMode = 0
        }
        val lowToHighConfig = AppConfigBean().apply {
            modeType = 2
            transitionMode = 1
        }

        assertEquals(1..21, KaoChangAlgorithm.currentLegacySellPositions(highConfig))
        assertEquals(1..21, KaoChangAlgorithm.currentLegacySellPositions(lowToHighConfig))
    }

    @Test
    fun currentLegacyHeatRange_shouldKeepSupplementZonesOutOfSellArea() {
        val lowStableConfig = AppConfigBean().apply {
            modeType = 1
            transitionMode = 0
        }
        val highStableConfig = AppConfigBean().apply {
            modeType = 2
            transitionMode = 0
        }
        val lowToHighConfig = AppConfigBean().apply {
            modeType = 2
            transitionMode = 1
        }
        val highToLowConfig = AppConfigBean().apply {
            modeType = 2
            transitionMode = 2
        }

        assertEquals(13..18, KaoChangAlgorithm.currentLegacyHeatRange(lowStableConfig))
        assertEquals(25..33, KaoChangAlgorithm.currentLegacyHeatRange(highStableConfig))
        assertEquals(25..33, KaoChangAlgorithm.currentLegacyHeatRange(lowToHighConfig))
        assertEquals(25..33, KaoChangAlgorithm.currentLegacyHeatRange(highToLowConfig))
    }

    @Test
    fun findNextOpeningSellBatch_shouldPreferFirstHighConcurrencyBatchWhenItWillBecomeSellableSooner() {
        val now = 1_000_000L
        val highOpeningPans = buildList {
            addAll((1..9).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 90_000L,
                    bakingTime = 120_000L
                )
            })
            addAll((10..21).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 30_000L,
                    bakingTime = 120_000L
                )
            })
        }

        val batch = findNextOpeningSellBatch(
            isLowOrTransition = false,
            sellArea = highOpeningPans,
            now = now
        )

        assertNotNull(batch)
        assertEquals("1~9开盘批次", batch!!.name)
        assertEquals(30_000L, batch.remainHeatTimeMs)
    }

    @Test
    fun findNextOpeningSellBatch_shouldUseWholeFrontAreaWhenLowConcurrency() {
        val now = 1_000_000L
        val lowOpeningPans = (1..9).map { position ->
            heatingPan(
                positionSn = position,
                startTime = now - 75_000L,
                bakingTime = 120_000L
            )
        }

        val batch = findNextOpeningSellBatch(
            isLowOrTransition = true,
            sellArea = lowOpeningPans,
            now = now
        )

        assertNotNull(batch)
        assertEquals("1~9开盘批次", batch!!.name)
        assertEquals(45_000L, batch.remainHeatTimeMs)
    }

    @Test
    fun findNextOpeningSellBatch_shouldUseLargestRemainingTimeInsideSelectedBatch() {
        val now = 2_000_000L
        val highOpeningPans = buildList {
            addAll((1..9).map { position ->
                val startOffset = if (position == 9) 10_000L else 90_000L
                heatingPan(
                    positionSn = position,
                    startTime = now - startOffset,
                    bakingTime = 120_000L
                )
            })
            addAll((10..21).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 30_000L,
                    bakingTime = 300_000L
                )
            })
        }

        val batch = findNextOpeningSellBatch(
            isLowOrTransition = false,
            sellArea = highOpeningPans,
            now = now
        )

        assertNotNull(batch)
        assertEquals("1~9开盘批次", batch!!.name)
        assertEquals(110_000L, batch.remainHeatTimeMs)
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
    }
}
