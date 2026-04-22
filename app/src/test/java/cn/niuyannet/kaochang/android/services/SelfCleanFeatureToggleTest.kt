package cn.niuyannet.kaochang.android.services

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCleanFeatureToggleTest {

    @Test
    fun isEnabled_shouldReturnFalseInCurrentBuild() {
        assertFalse(SelfCleanFeatureToggle.isEnabled())
    }

    @Test
    fun cleanSellPlatform_shouldReturnDisabledReportWhenFeatureOff() = runBlocking {
        val report = KaoChangOperate.cleanSellPlatform(source = "unit_test")

        assertFalse(report.success)
        assertEquals(-1, report.command)
        assertEquals("出肠台", report.targetLabel)
        assertEquals(-1, report.status201)
        assertEquals("self_clean_disabled", report.message)
    }

    @Test
    fun runPanSelfCleanSequence_shouldShortCircuitWhenFeatureOff() = runBlocking {
        val result = KaoChangOperate.runPanSelfCleanSequence(
            positionSnList = listOf(25, 26, 27),
            includeSellPlatform = false,
            source = "unit_test"
        )

        assertFalse(result.success)
        assertEquals(1, result.reports.size)
        assertTrue(result.reports.all { it.message == "self_clean_disabled" })
    }
}
