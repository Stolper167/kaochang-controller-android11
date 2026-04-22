package cn.niuyannet.kaochang.android.modbus

enum class ModbusCommunicationState {
    NORMAL,
    AUTO_RECONNECTING,
    WAIT_MANUAL_RECOVERY
}

enum class ModbusConnectStrategy {
    FAST_RECOVERY,
    FULL_REDISCOVERY
}

data class ModbusRecoveryDecision(
    val state: ModbusCommunicationState,
    val stateChanged: Boolean,
    val shouldAttemptReconnect: Boolean = false,
    val connectStrategy: ModbusConnectStrategy? = null,
    val autoRecovered: Boolean = false,
    val enteredWaitManualRecovery: Boolean = false,
    val startedReconnectStableObservation: Boolean = false,
    val connectedButWaitingManualRecovery: Boolean = false
)

class ModbusRecoveryPolicy(
    private val autoReconnectTimeoutMs: Long,
    private val reconnectStableDelayMs: Long,
    private val reconnectAttemptIntervalMs: Long,
    private val backgroundRediscoveryIntervalMs: Long
) {
    private var state: ModbusCommunicationState = ModbusCommunicationState.NORMAL
    private var disconnectSinceMs: Long? = null
    private var reconnectSinceMs: Long? = null
    private var lastReconnectAttemptMs: Long = 0L
    private var lastBackgroundRediscoveryMs: Long = 0L

    fun currentState(): ModbusCommunicationState = state

    fun markDisconnectedAtStartup() {
        state = ModbusCommunicationState.WAIT_MANUAL_RECOVERY
        disconnectSinceMs = null
        reconnectSinceMs = null
        lastReconnectAttemptMs = 0L
        lastBackgroundRediscoveryMs = 0L
    }

    fun onDisconnected(nowMs: Long): ModbusRecoveryDecision {
        reconnectSinceMs = null
        return when (state) {
            ModbusCommunicationState.NORMAL -> {
                state = ModbusCommunicationState.AUTO_RECONNECTING
                disconnectSinceMs = nowMs
                lastReconnectAttemptMs = nowMs
                ModbusRecoveryDecision(
                    state = state,
                    stateChanged = true,
                    shouldAttemptReconnect = true,
                    connectStrategy = ModbusConnectStrategy.FAST_RECOVERY
                )
            }

            ModbusCommunicationState.AUTO_RECONNECTING -> {
                val disconnectSince = disconnectSinceMs ?: nowMs.also { disconnectSinceMs = it }
                if (nowMs - disconnectSince >= autoReconnectTimeoutMs) {
                    state = ModbusCommunicationState.WAIT_MANUAL_RECOVERY
                    lastReconnectAttemptMs = 0L
                    lastBackgroundRediscoveryMs = nowMs
                    ModbusRecoveryDecision(
                        state = state,
                        stateChanged = true,
                        enteredWaitManualRecovery = true
                    )
                } else {
                    val shouldAttempt =
                        lastReconnectAttemptMs == 0L || nowMs - lastReconnectAttemptMs >= reconnectAttemptIntervalMs
                    if (shouldAttempt) {
                        lastReconnectAttemptMs = nowMs
                    }
                    ModbusRecoveryDecision(
                        state = state,
                        stateChanged = false,
                        shouldAttemptReconnect = shouldAttempt,
                        connectStrategy = if (shouldAttempt) ModbusConnectStrategy.FAST_RECOVERY else null
                    )
                }
            }

            ModbusCommunicationState.WAIT_MANUAL_RECOVERY -> {
                val shouldAttempt =
                    lastBackgroundRediscoveryMs == 0L || nowMs - lastBackgroundRediscoveryMs >= backgroundRediscoveryIntervalMs
                if (shouldAttempt) {
                    lastBackgroundRediscoveryMs = nowMs
                }
                ModbusRecoveryDecision(
                    state = state,
                    stateChanged = false,
                    shouldAttemptReconnect = shouldAttempt,
                    connectStrategy = if (shouldAttempt) ModbusConnectStrategy.FULL_REDISCOVERY else null
                )
            }
        }
    }

    fun onConnected(nowMs: Long): ModbusRecoveryDecision {
        disconnectSinceMs = null
        lastReconnectAttemptMs = 0L
        return when (state) {
            ModbusCommunicationState.NORMAL -> {
                reconnectSinceMs = null
                ModbusRecoveryDecision(
                    state = state,
                    stateChanged = false
                )
            }

            ModbusCommunicationState.AUTO_RECONNECTING -> {
                val reconnectSince = reconnectSinceMs
                if (reconnectSince == null) {
                    reconnectSinceMs = nowMs
                    ModbusRecoveryDecision(
                        state = state,
                        stateChanged = false,
                        startedReconnectStableObservation = true
                    )
                } else if (nowMs - reconnectSince >= reconnectStableDelayMs) {
                    state = ModbusCommunicationState.NORMAL
                    reconnectSinceMs = null
                    ModbusRecoveryDecision(
                        state = state,
                        stateChanged = true,
                        autoRecovered = true
                    )
                } else {
                    ModbusRecoveryDecision(
                        state = state,
                        stateChanged = false
                    )
                }
            }

            ModbusCommunicationState.WAIT_MANUAL_RECOVERY -> {
                reconnectSinceMs = null
                ModbusRecoveryDecision(
                    state = state,
                    stateChanged = false,
                    connectedButWaitingManualRecovery = true
                )
            }
        }
    }

    fun resetToNormal() {
        state = ModbusCommunicationState.NORMAL
        disconnectSinceMs = null
        reconnectSinceMs = null
        lastReconnectAttemptMs = 0L
        lastBackgroundRediscoveryMs = 0L
    }
}
