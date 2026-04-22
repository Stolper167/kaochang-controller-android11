package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowCurrentTrafficModeTest {

    @Test
    fun shouldKeepWarmFrontZoneDuringSupplementHeating_shouldReturnTrueWhenFrontHoldingAndSupplementHeating() {
        val frontPans = listOf(
            KaoPan().apply {
                setPositionSn(6)
                setHasSausage(true)
                setStatus(2)
                setHoldingTime(1L)
            }
        )
        val supplementPans = listOf(
            KaoPan().apply {
                setPositionSn(13)
                setHasSausage(true)
                setStatus(1)
                setStartTime(System.currentTimeMillis())
            }
        )

        assertTrue(LowCurrentTrafficMode.shouldKeepWarmFrontZoneDuringSupplementHeating(frontPans, supplementPans))
    }

    @Test
    fun shouldKeepWarmFrontZoneDuringSupplementHeating_shouldReturnFalseWhenNoSupplementHeating() {
        val frontPans = listOf(
            KaoPan().apply {
                setPositionSn(6)
                setHasSausage(true)
                setStatus(2)
                setHoldingTime(1L)
            }
        )
        val supplementPans = listOf(
            KaoPan().apply {
                setPositionSn(13)
                setHasSausage(false)
                setStatus(0)
                setStartTime(0L)
            }
        )

        assertFalse(LowCurrentTrafficMode.shouldKeepWarmFrontZoneDuringSupplementHeating(frontPans, supplementPans))
    }

    @Test
    fun reserveFrontSupplementCandidates_shouldOnlyReserveSelectedCandidates() {
        val frontPans = (1..5).map { position ->
            KaoPan().apply {
                setPositionSn(position)
                setHasSausage(false)
                setHasGrilling(false)
            }
        }

        val reservedPositions = LowCurrentTrafficMode.reserveFrontSupplementCandidates(frontPans, 3)

        assertEquals(listOf(1, 2, 3), reservedPositions)
        assertTrue(frontPans[0].isHasGrilling)
        assertTrue(frontPans[1].isHasGrilling)
        assertTrue(frontPans[2].isHasGrilling)
        assertFalse(frontPans[3].isHasGrilling)
        assertFalse(frontPans[4].isHasGrilling)
    }

    @Test
    fun clearStaleFrontReservations_shouldClearOnlyEmptyReservedPansWhenSupplementAreaEmpty() {
        val frontPans = listOf(
            KaoPan().apply {
                setPositionSn(1)
                setHasSausage(false)
                setHasGrilling(true)
            },
            KaoPan().apply {
                setPositionSn(2)
                setHasSausage(true)
                setHasGrilling(true)
            },
            KaoPan().apply {
                setPositionSn(3)
                setHasSausage(false)
                setHasGrilling(true)
            },
            KaoPan().apply {
                setPositionSn(4)
                setHasSausage(false)
                setHasGrilling(false)
            }
        )
        val supplementPans = listOf(
            KaoPan().apply {
                setPositionSn(13)
                setHasSausage(false)
            },
            KaoPan().apply {
                setPositionSn(14)
                setHasSausage(false)
            }
        )

        val clearedPositions = LowCurrentTrafficMode.clearStaleFrontReservations(frontPans, supplementPans)

        assertEquals(listOf(1, 3), clearedPositions)
        assertFalse(frontPans[0].isHasGrilling)
        assertTrue(frontPans[1].isHasGrilling)
        assertFalse(frontPans[2].isHasGrilling)
        assertFalse(frontPans[3].isHasGrilling)
    }
}
