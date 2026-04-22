package cn.niuyannet.kaochang.android.modbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModbusRecoveryPolicyTest {

    @Test
    fun onDisconnected_shouldEnterAutoReconnectingAndAllowImmediateRetry() {
        val policy = ModbusRecoveryPolicy(
            autoReconnectTimeoutMs = 15_000L,
            reconnectStableDelayMs = 5_000L,
            reconnectAttemptIntervalMs = 3_000L,
            backgroundRediscoveryIntervalMs = 30_000L
        )

        val result = policy.onDisconnected(nowMs = 1_000L)

        assertEquals(ModbusCommunicationState.AUTO_RECONNECTING, result.state)
        assertTrue(result.stateChanged)
        assertTrue(result.shouldAttemptReconnect)
        assertEquals(ModbusConnectStrategy.FAST_RECOVERY, result.connectStrategy)
        assertFalse(result.enteredWaitManualRecovery)
    }

    @Test
    fun onDisconnected_shouldEnterWaitManualRecoveryAfterTimeout() {
        val policy = ModbusRecoveryPolicy(
            autoReconnectTimeoutMs = 15_000L,
            reconnectStableDelayMs = 5_000L,
            reconnectAttemptIntervalMs = 3_000L,
            backgroundRediscoveryIntervalMs = 30_000L
        )

        policy.onDisconnected(nowMs = 1_000L)
        val result = policy.onDisconnected(nowMs = 16_100L)

        assertEquals(ModbusCommunicationState.WAIT_MANUAL_RECOVERY, result.state)
        assertTrue(result.enteredWaitManualRecovery)
        assertFalse(result.shouldAttemptReconnect)
    }

    @Test
    fun onConnected_shouldAutoRecoverOnlyAfterStableWindowWhenStillInAutoReconnect() {
        val policy = ModbusRecoveryPolicy(
            autoReconnectTimeoutMs = 15_000L,
            reconnectStableDelayMs = 5_000L,
            reconnectAttemptIntervalMs = 3_000L,
            backgroundRediscoveryIntervalMs = 30_000L
        )

        policy.onDisconnected(nowMs = 1_000L)

        val firstConnected = policy.onConnected(nowMs = 2_000L)
        val stableConnected = policy.onConnected(nowMs = 7_100L)

        assertEquals(ModbusCommunicationState.AUTO_RECONNECTING, firstConnected.state)
        assertFalse(firstConnected.autoRecovered)
        assertEquals(ModbusCommunicationState.NORMAL, stableConnected.state)
        assertTrue(stableConnected.autoRecovered)
    }

    @Test
    fun onConnected_shouldStayWaitingManualRecoveryUntilManualReset() {
        val policy = ModbusRecoveryPolicy(
            autoReconnectTimeoutMs = 15_000L,
            reconnectStableDelayMs = 5_000L,
            reconnectAttemptIntervalMs = 3_000L,
            backgroundRediscoveryIntervalMs = 30_000L
        )

        policy.onDisconnected(nowMs = 1_000L)
        policy.onDisconnected(nowMs = 16_100L)

        val connected = policy.onConnected(nowMs = 17_000L)

        assertEquals(ModbusCommunicationState.WAIT_MANUAL_RECOVERY, connected.state)
        assertTrue(connected.connectedButWaitingManualRecovery)

        policy.resetToNormal()

        assertEquals(ModbusCommunicationState.NORMAL, policy.currentState())
    }

    @Test
    fun onDisconnected_shouldUseBackgroundRediscoveryIntervalAfterEnteringWaitManualRecovery() {
        val policy = ModbusRecoveryPolicy(
            autoReconnectTimeoutMs = 15_000L,
            reconnectStableDelayMs = 5_000L,
            reconnectAttemptIntervalMs = 3_000L,
            backgroundRediscoveryIntervalMs = 30_000L
        )

        policy.onDisconnected(nowMs = 1_000L)
        policy.onDisconnected(nowMs = 16_100L)

        val tooEarly = policy.onDisconnected(nowMs = 40_000L)
        val due = policy.onDisconnected(nowMs = 46_200L)

        assertEquals(ModbusCommunicationState.WAIT_MANUAL_RECOVERY, tooEarly.state)
        assertFalse(tooEarly.shouldAttemptReconnect)
        assertEquals(ModbusCommunicationState.WAIT_MANUAL_RECOVERY, due.state)
        assertTrue(due.shouldAttemptReconnect)
        assertEquals(ModbusConnectStrategy.FULL_REDISCOVERY, due.connectStrategy)
    }
}
