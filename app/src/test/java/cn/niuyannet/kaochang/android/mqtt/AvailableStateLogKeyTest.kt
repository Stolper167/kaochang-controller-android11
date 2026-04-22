package cn.niuyannet.kaochang.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AvailableStateLogKeyTest {

    @Test
    fun buildAvailableStateLogKey_shouldDeduplicateSameMinuteDisplayText() {
        val key1 = AvailableStateLogFormatter.buildStateLogKey(241_000L)
        val key2 = AvailableStateLogFormatter.buildStateLogKey(300_000L)

        assertEquals("预计烤制 5 分钟", key1)
        assertEquals(key1, key2)
    }

    @Test
    fun buildAvailableStateLogKey_shouldChangeWhenDisplayTextChanges() {
        val key1 = AvailableStateLogFormatter.buildStateLogKey(241_000L)
        val key2 = AvailableStateLogFormatter.buildStateLogKey(181_000L)

        assertNotEquals(key1, key2)
        assertEquals("预计烤制 4 分钟", key2)
    }

    @Test
    fun buildAvailableAckLogKey_shouldIncludeApplyResult() {
        val appliedKey = AvailableStateLogFormatter.buildAckLogKey(
            available = 241_000L,
            applied = true,
            reason = ""
        )
        val rejectedKey = AvailableStateLogFormatter.buildAckLogKey(
            available = 241_000L,
            applied = false,
            reason = "duplicate"
        )

        assertNotEquals(appliedKey, rejectedKey)
        assertEquals("applied|预计烤制 5 分钟", appliedKey)
        assertEquals("not_applied:duplicate|预计烤制 5 分钟", rejectedKey)
    }
}
